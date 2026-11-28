// Stored secrets are encrypted, wrong passwords are limited, and nightly backups work.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'lumen-sec-'));
const PORT = 19000 + Math.floor(Math.random() * 1000);
const BASE = `http://127.0.0.1:${PORT}`;
process.env.DATA_DIR = DATA;
let server, cookie;

const req = async (method, url, body, withCookie = true) => {
  const res = await fetch(BASE + url, { method, headers: { 'content-type': 'application/json', ...(withCookie && cookie ? { cookie } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  let json = null; try { json = await res.json(); } catch { /* not JSON */ }
  return { status: res.status, json, res };
};

before(async () => {
  server = spawn(process.execPath, ['--no-warnings', 'src/index.js'], {
    cwd: path.resolve(import.meta.dirname, '..'),
    env: { ...process.env, DATA_DIR: DATA, PORT: String(PORT), LUMEN_USERNAME: 'admin', LUMEN_PASSWORD: 'secret123' }, stdio: 'ignore',
  });
  for (let i = 0; i < 100; i++) { try { if ((await fetch(BASE + '/api/health')).ok) break; } catch { /* starting */ } await new Promise(r => setTimeout(r, 100)); }
  const login = await req('POST', '/api/auth/login', { username: 'admin', password: 'secret123' }, false);
  cookie = login.res.headers.get('set-cookie').split(';')[0];
});
after(() => { server?.kill(); fs.rmSync(DATA, { recursive: true, force: true }); });

test('Plex and Overseerr settings are stored encrypted', async () => {
  const { db, getSetting, setSetting } = await import('../src/db.js');
  setSetting('plex', { url: 'http://plex:32400', token: 'plex-token-123' });
  const raw = db.prepare("SELECT value FROM settings WHERE key = 'plex'").get().value;
  assert.ok(raw.startsWith('enc1:') && !raw.includes('plex-token-123'));
  assert.equal(getSetting('plex').token, 'plex-token-123');
});

test('IPTV passwords are stored encrypted and still work', async () => {
  const { db } = await import('../src/db.js');
  db.prepare("INSERT INTO providers(id, name, type, url, username, password) VALUES (7, 'X', 'xtream', 'http://127.0.0.1:1', 'u', 'old')").run();
  assert.equal((await req('PUT', '/api/iptv/providers/7', { password: 'hunter2' })).status, 200);
  const raw = db.prepare('SELECT password FROM providers WHERE id = 7').get().password;
  assert.ok(raw.startsWith('enc1:') && !raw.includes('hunter2'));
  const { getProvider } = await import('../src/services/iptv.js');
  assert.equal(getProvider(7).password, 'hunter2');
});

test('the nightly backup is written without the TV guide', async () => {
  const { nightlyBackup } = await import('../src/services/backup.js');
  const file = nightlyBackup();
  assert.ok(file && fs.existsSync(file));
  const { DatabaseSync } = await import('node:sqlite');
  const b = new DatabaseSync(file);
  assert.equal(b.prepare('SELECT COUNT(*) AS n FROM programmes').get().n, 0);
  assert.ok(b.prepare('SELECT COUNT(*) AS n FROM providers').get().n >= 1);
  b.close();
  assert.equal(nightlyBackup(), null); // once a day
});

test('repeated wrong passwords are paused', async () => {
  for (let i = 0; i < 5; i++) assert.equal((await req('POST', '/api/auth/login', { username: 'admin', password: 'nope' }, false)).status, 401);
  assert.equal((await req('POST', '/api/auth/login', { username: 'admin', password: 'secret123' }, false)).status, 429);
});

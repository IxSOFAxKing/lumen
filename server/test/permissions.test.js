// Sign-in and permission rules: who can use what (owner login, code-paired devices, Plex Home members, friends).
// Starts a real Lumen server on a temporary data folder and checks the answers it gives.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'lumen-test-'));
const PORT = 18000 + Math.floor(Math.random() * 1000);
const BASE = `http://127.0.0.1:${PORT}`;
process.env.DATA_DIR = DATA;

let server, cookie, tokens = {};

async function req(method, url, { token, body, withCookie } = {}) {
  const headers = { 'content-type': 'application/json' };
  if (token) headers.authorization = `Bearer ${token}`;
  if (withCookie) headers.cookie = cookie;
  const res = await fetch(BASE + url, { method, headers, body: body === undefined ? (method === 'GET' ? undefined : '{}') : JSON.stringify(body) });
  let json = null; try { json = await res.json(); } catch { /* not JSON */ }
  return { status: res.status, json, res };
}

before(async () => {
  server = spawn(process.execPath, ['--no-warnings', 'src/index.js'], {
    cwd: path.resolve(import.meta.dirname, '..'),
    env: { ...process.env, DATA_DIR: DATA, PORT: String(PORT), LUMEN_USERNAME: 'admin', LUMEN_PASSWORD: 'secret123' },
    stdio: 'ignore',
  });
  for (let i = 0; i < 100; i++) {
    try { if ((await fetch(BASE + '/api/health')).ok) break; } catch { /* starting */ }
    await new Promise(r => setTimeout(r, 100));
  }
  const login = await req('POST', '/api/auth/login', { body: { username: 'admin', password: 'secret123' } });
  assert.equal(login.status, 200);
  cookie = login.res.headers.get('set-cookie').split(';')[0];

  // Two Plex accounts as if they had signed in with Plex: a friend and a Plex Home member.
  const { db } = await import('../src/db.js');
  const devices = await import('../src/services/devices.js');
  await import('../src/services/accounts.js');
  db.prepare("INSERT INTO accounts(plex_id, uuid, username, title, role, server_token, verified_at) VALUES ('42','u42','pal','Pal','friend','t',unixepoch())").run();
  db.prepare("INSERT INTO accounts(plex_id, uuid, username, title, role, server_token, verified_at) VALUES ('43','u43','sis','Sis','home','t',unixepoch())").run();
  tokens.friend = devices.issueAccountToken({ name: 'Friend TV', platform: 'android-tv' }, '42').token;
  tokens.home = devices.issueAccountToken({ name: 'Home TV', platform: 'android-tv' }, '43').token;
});

after(() => { server?.kill(); fs.rmSync(DATA, { recursive: true, force: true }); });

test('signed-out requests are refused', async () => {
  assert.equal((await req('GET', '/api/settings')).status, 401);
  assert.equal((await req('GET', '/api/settings', { token: 'not-a-token' })).status, 401);
});

test('a wrong password is refused', async () => {
  assert.equal((await req('POST', '/api/auth/login', { body: { username: 'admin', password: 'nope' } })).status, 401);
});

test('the owner (Lumen login) can use and change everything', async () => {
  const st = await req('GET', '/api/auth/status', { withCookie: true });
  assert.equal(st.json.perms.admin, true);
  assert.equal((await req('GET', '/api/devices', { withCookie: true })).status, 200);
  assert.equal((await req('GET', '/api/accounts', { withCookie: true })).status, 200);
  assert.equal((await req('GET', '/api/system/health', { withCookie: true })).status, 200);
});

test('Plex tokens are never sent to apps', async () => {
  const s = await req('GET', '/api/settings', { withCookie: true });
  assert.equal(s.json.plex.token, undefined);
  assert.equal(s.json.plex.accountToken, undefined);
});

test('a friend gets Plex only by default', async () => {
  const t = tokens.friend;
  const st = await req('GET', '/api/auth/status', { token: t });
  assert.deepEqual({ ...st.json.perms }, { admin: false, manage: false, live: false, discover: false });
  assert.equal((await req('GET', '/api/iptv/providers', { token: t })).status, 403);
  assert.equal((await req('GET', '/api/stream/live/1/2', { token: t })).status, 403);
  assert.deepEqual((await req('GET', '/api/discover/config', { token: t })).json, { url: '', configured: false, blocked: true });
  assert.equal((await req('GET', '/api/discover/alerts', { token: t })).status, 403);
});

test('nobody but the owner can change server settings', async () => {
  for (const t of [tokens.friend, tokens.home]) {
    assert.equal((await req('PUT', '/api/settings/iptv', { token: t, body: { epgRefreshHours: 1 } })).status, 403);
    assert.equal((await req('POST', '/api/iptv/providers', { token: t })).status, 403);
    assert.equal((await req('GET', '/api/devices', { token: t })).status, 403);
    assert.equal((await req('GET', '/api/accounts', { token: t })).status, 403);
    assert.equal((await req('POST', '/api/plex/connect', { token: t })).status, 403);
    assert.equal((await req('PUT', '/api/discover/config', { token: t })).status, 403);
    assert.equal((await req('GET', '/api/settings/export', { token: t })).status, 403);
    assert.equal((await req('GET', '/api/system/health', { token: t })).status, 403);
  }
});

test('a Plex Home member gets Live TV, Discover and favourites', async () => {
  const t = tokens.home;
  const st = await req('GET', '/api/auth/status', { token: t });
  assert.equal(st.json.perms.live, true);
  assert.equal(st.json.perms.discover, true);
  assert.equal((await req('GET', '/api/iptv/providers', { token: t })).status, 200);
  assert.equal((await req('POST', '/api/iptv/favorites', { token: t, body: {} })).status !== 403, true);
});

test('each person keeps their own app settings', async () => {
  await req('PUT', '/api/settings/ui', { token: tokens.home, body: { accent: '#123456' } });
  assert.equal((await req('GET', '/api/settings', { token: tokens.home })).json.ui.accent, '#123456');
  assert.notEqual((await req('GET', '/api/settings', { withCookie: true })).json.ui.accent, '#123456');
});

test('the owner can turn on Live TV for a friend, and block them', async () => {
  await req('PUT', '/api/accounts/42', { withCookie: true, body: { live: true } });
  assert.equal((await req('GET', '/api/iptv/providers', { token: tokens.friend })).status, 200);
  await req('PUT', '/api/accounts/42', { withCookie: true, body: { blocked: true } });
  assert.equal((await req('GET', '/api/auth/status', { token: tokens.friend })).json.authed, false);
  assert.equal((await req('GET', '/api/settings', { token: tokens.friend })).status, 401);
});

test('the server owner account can\'t be blocked', async () => {
  const { db } = await import('../src/db.js');
  db.prepare("INSERT INTO accounts(plex_id, title, role, verified_at) VALUES ('1','Owner','owner',unixepoch())").run();
  assert.equal((await req('PUT', '/api/accounts/1', { withCookie: true, body: { blocked: true } })).status, 400);
});

test('each Plex user has their own Live TV favourites', async () => {
  const { db } = await import('../src/db.js');
  db.prepare("INSERT INTO providers(id, name, type, url) VALUES (9, 'Test IPTV', 'm3u', 'http://127.0.0.1:1/list.m3u')").run();
  db.prepare("INSERT INTO categories(provider_id, kind, id, name) VALUES (9, 'live', 'news', 'News'), (9, 'live', 'adult', 'Adult')").run();
  db.prepare("INSERT INTO channels(provider_id, id, num, name, category_id, url) VALUES (9, 'n1', 1, 'News One', 'news', 'http://127.0.0.1:1/n1'), (9, 'n2', 2, 'News Two', 'news', 'http://127.0.0.1:1/n2'), (9, 'a1', 3, 'Late Night', 'adult', 'http://127.0.0.1:1/a1')").run();
  await req('POST', '/api/iptv/favorites', { withCookie: true, body: { providerId: 9, channelId: 'n1' } });
  await req('POST', '/api/iptv/favorites', { token: tokens.home, body: { providerId: 9, channelId: 'n2' } });
  const mine = (await req('GET', '/api/iptv/favorites', { withCookie: true })).json.map(c => c.id);
  const hers = (await req('GET', '/api/iptv/favorites', { token: tokens.home })).json.map(c => c.id);
  assert.deepEqual(mine, ['n1']);
  assert.deepEqual(hers, ['n2']);
  // Her lists are hers: the owner's list ids don't work for her.
  const ownerList = (await req('GET', '/api/iptv/fav-lists', { withCookie: true })).json[0].id;
  assert.equal((await req('DELETE', `/api/iptv/fav-lists/${ownerList}`, { token: tokens.home })).status, 404);
});

test('the owner chooses which Live TV groups a Plex user can see', async () => {
  assert.equal((await req('PUT', '/api/iptv/access/u43', { token: tokens.home, body: { blocked: { 9: [] } } })).status, 403);
  assert.equal((await req('PUT', '/api/iptv/access/u43', { withCookie: true, body: { blocked: { 9: ['adult'] } } })).status, 200);
  const cats = (await req('GET', '/api/iptv/providers/9/categories', { token: tokens.home })).json.map(c => c.id);
  assert.deepEqual(cats.sort(), ['news']);
  const all = (await req('GET', '/api/iptv/providers/9/channels?category=__all', { token: tokens.home })).json.map(c => c.id);
  assert.ok(!all.includes('a1') && all.includes('n1'));
  assert.equal((await req('POST', '/api/iptv/play/live', { token: tokens.home, body: { providerId: 9, channelId: 'a1' } })).status, 403);
  // The owner still sees everything.
  const ownerCats = (await req('GET', '/api/iptv/providers/9/categories', { withCookie: true })).json.map(c => c.id);
  assert.deepEqual(ownerCats.sort(), ['adult', 'news']);
});

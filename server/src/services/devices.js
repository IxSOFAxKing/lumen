// Paired devices (Android TV app, future phone apps): pairing codes + long-lived per-device tokens.
// Every app talks to this server, so settings, Plex/IPTV logins and favourites are shared automatically.
import crypto from 'node:crypto';
import { db } from '../db.js';

db.exec(`CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  platform TEXT,
  token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER DEFAULT (unixepoch()),
  last_seen INTEGER,
  last_ip TEXT
)`);

try { db.exec('ALTER TABLE devices ADD COLUMN plex_user TEXT'); } catch { /* exists */ }

const hash = t => crypto.createHash('sha256').update(String(t)).digest('hex');
const PAIR_TTL = 10 * 60 * 1000;
const pairs = new Map(); // pairId -> { code, name, platform, created, status, token?, deviceId? }

function cleanup() {
  for (const [id, p] of pairs) if (Date.now() - p.created > PAIR_TTL) pairs.delete(id);
}
setInterval(cleanup, 60000).unref();

function newCode() {
  // 6 digits, easy to type with a remote or phone; avoid collisions with active codes
  for (;;) {
    const c = String(crypto.randomInt(0, 1e6)).padStart(6, '0');
    if (![...pairs.values()].some(p => p.code === c && p.status === 'pending')) return c;
  }
}

function issueToken(name, platform, ip) {
  const token = crypto.randomBytes(32).toString('base64url');
  const r = db.prepare('INSERT INTO devices(name, platform, token_hash, last_seen, last_ip) VALUES (?,?,?,unixepoch(),?)').run(name, platform || null, hash(token), ip || null);
  return { token, deviceId: Number(r.lastInsertRowid) };
}

/** Start pairing: returns a code for the user to approve. Approved immediately when the server has no password. */
export function startPairing({ name, platform }, { autoApprove, ip }) {
  cleanup();
  if (pairs.size > 50) { const e = new Error('Too many pending pairing requests; try again in a few minutes.'); e.status = 429; throw e; }
  const pairId = crypto.randomBytes(16).toString('base64url');
  const p = { code: newCode(), name: String(name || 'Device').slice(0, 60), platform: String(platform || '').slice(0, 30), created: Date.now(), status: 'pending', ip };
  if (autoApprove) Object.assign(p, { status: 'approved' }, issueToken(p.name, p.platform, ip));
  pairs.set(pairId, p);
  return { pairId, code: p.code, expiresIn: PAIR_TTL / 1000, status: p.status };
}

/** Polled by the device. The token is handed out exactly once. */
export function pairingStatus(pairId) {
  const p = pairs.get(pairId);
  if (!p) return { status: 'expired' };
  if (p.status === 'approved' && p.token) {
    const token = p.token; pairs.delete(pairId);
    return { status: 'approved', token, deviceId: p.deviceId, name: p.name };
  }
  return { status: p.status };
}

/** Called by a signed-in user (web UI) with the code shown on the TV. */
export function approvePairing(code, nameOverride) {
  const p = [...pairs.values()].find(x => x.code === String(code || '').trim() && x.status === 'pending');
  if (!p) { const e = new Error('That code was not found or has expired. Check the code on your TV.'); e.status = 404; throw e; }
  if (nameOverride) p.name = String(nameOverride).slice(0, 60);
  Object.assign(p, { status: 'approved' }, issueToken(p.name, p.platform, p.ip));
  return { name: p.name, platform: p.platform };
}

export function pendingPairings() {
  cleanup();
  return [...pairs.values()].filter(p => p.status === 'pending').map(p => ({ code: p.code, name: p.name, platform: p.platform, age: Math.round((Date.now() - p.created) / 1000) }));
}

let lastTouch = new Map();
/** Returns the device for a token (and records when it was last seen). */
export function deviceForToken(token, ip) {
  if (!token) return null;
  const d = db.prepare('SELECT * FROM devices WHERE token_hash = ?').get(hash(token));
  if (!d) return null;
  if (Date.now() - (lastTouch.get(d.id) || 0) > 60000) {
    lastTouch.set(d.id, Date.now());
    db.prepare('UPDATE devices SET last_seen = unixepoch(), last_ip = ? WHERE id = ?').run(ip || null, d.id);
  }
  return d;
}

export function listDevices() {
  return db.prepare('SELECT id, name, platform, created_at, last_seen, last_ip FROM devices ORDER BY last_seen DESC').all();
}
export function renameDevice(id, name) { db.prepare('UPDATE devices SET name = ? WHERE id = ?').run(String(name).slice(0, 60), Number(id)); }
export function revokeDevice(id) { db.prepare('DELETE FROM devices WHERE id = ?').run(Number(id)); }
export function setDevicePlexUser(id, uuid) { db.prepare('UPDATE devices SET plex_user = ? WHERE id = ?').run(uuid || null, Number(id)); }

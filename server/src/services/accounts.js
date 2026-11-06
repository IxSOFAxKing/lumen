// Plex accounts that may sign in to Lumen: the Plex server's owner, members of the owner's Plex Home, and friends
// the server is shared with. Signing in with Plex is checked against plex.tv: the account must be able to reach
// the Plex server Lumen is connected to. What each account may use (Live TV, Discover) is set per account.
import { db } from '../db.js';
import * as plex from './plex.js';

db.exec(`CREATE TABLE IF NOT EXISTS accounts (
  plex_id TEXT PRIMARY KEY,
  uuid TEXT,
  username TEXT,
  title TEXT,
  thumb TEXT,
  role TEXT NOT NULL,          -- owner | home | friend
  plex_token TEXT,             -- the account's plex.tv token (re-checks access)
  server_token TEXT,           -- the account's token for Lumen's Plex server
  live INTEGER,                -- NULL = default for the role
  discover INTEGER,
  blocked INTEGER DEFAULT 0,
  ui TEXT,                     -- this account's own app settings (JSON), over the server's defaults
  created_at INTEGER DEFAULT (unixepoch()),
  last_seen INTEGER,
  verified_at INTEGER
)`);

const err = (status, message) => Object.assign(new Error(message), { status });

/** Who a plex.tv token belongs to. */
async function plexIdentity(token) {
  const res = await fetch('https://plex.tv/api/v2/user', { headers: plex.plexHeaders({ 'X-Plex-Token': token }), signal: AbortSignal.timeout(15000) });
  if (res.status === 401 || res.status === 403) throw err(401, 'Plex didn\'t accept that sign-in. Try again.');
  if (!res.ok) throw err(502, `plex.tv responded ${res.status}`);
  const u = await res.json();
  return { id: String(u.id), uuid: u.uuid || null, username: u.username || u.email || '', title: u.title || u.friendlyName || u.username || 'Plex user', thumb: u.thumb || null };
}

/**
 * Check a plex.tv token: the account must have access to Lumen's Plex server. Saves and returns the account.
 * Throws 403 when it has no access.
 */
export async function verify(token) {
  const c = plex.plexConf();
  if (!plex.isConfigured() || !c.machineId) throw err(409, 'Plex isn\'t connected to Lumen yet. Sign in with your Lumen username and password and connect Plex in Settings first.');
  const me = await plexIdentity(token);
  const servers = await plex.listServers(token);
  const server = servers.find(s => s.machineId === c.machineId);
  if (!server) throw err(403, `${me.title} doesn't have access to the Plex server "${c.serverName || 'this server'}". Ask the owner to share it with you in Plex.`);
  let role = 'friend';
  if (server.owned) role = 'owner';
  else {
    const home = await plex.homeUsers().catch(() => []);
    if (home.some(u => u.id === me.id || (me.uuid && u.uuid === me.uuid))) role = 'home';
  }
  const existing = get(me.id);
  if (existing?.blocked) throw err(403, `${me.title} isn't allowed to use this Lumen server.`);
  db.prepare(`INSERT INTO accounts(plex_id, uuid, username, title, thumb, role, plex_token, server_token, verified_at, last_seen)
    VALUES (?,?,?,?,?,?,?,?,unixepoch(),unixepoch())
    ON CONFLICT(plex_id) DO UPDATE SET uuid=excluded.uuid, username=excluded.username, title=excluded.title, thumb=excluded.thumb, role=excluded.role,
      plex_token=excluded.plex_token, server_token=excluded.server_token, verified_at=unixepoch(), last_seen=unixepoch()`)
    .run(me.id, me.uuid, me.username, me.title, me.thumb, role, token, server.accessToken || (role === 'owner' ? c.token : null));
  return get(me.id);
}

export function get(id) {
  return id ? db.prepare('SELECT * FROM accounts WHERE plex_id = ?').get(String(id)) || null : null;
}

/** What an account may use. Home members get everything; friends get Plex only unless the owner allows more. */
export function permissions(a) {
  if (!a) return { admin: true, live: true, discover: true, manage: true };
  const full = a.role !== 'friend';
  return {
    admin: a.role === 'owner',
    manage: a.role !== 'friend', // favourites, recordings, reminders
    live: a.live === null || a.live === undefined ? full : !!a.live,
    discover: a.discover === null || a.discover === undefined ? full : !!a.discover,
  };
}

export function publicAccount(a) {
  if (!a) return null;
  const p = permissions(a);
  return { id: a.plex_id, uuid: a.uuid, username: a.username, title: a.title, thumb: a.thumb, role: a.role,
    live: p.live, discover: p.discover, liveSet: a.live !== null, discoverSet: a.discover !== null,
    blocked: !!a.blocked, lastSeen: a.last_seen, createdAt: a.created_at };
}

export function list() {
  return db.prepare('SELECT * FROM accounts ORDER BY role = \'owner\' DESC, role = \'home\' DESC, title COLLATE NOCASE').all().map(publicAccount);
}

/** Owner changes: live / discover (true, false or null for the default), blocked. */
export function update(id, patch = {}) {
  const a = get(id);
  if (!a) throw err(404, 'Account not found');
  const tri = v => (v === null ? null : v ? 1 : 0);
  if ('live' in patch) db.prepare('UPDATE accounts SET live = ? WHERE plex_id = ?').run(tri(patch.live), a.plex_id);
  if ('discover' in patch) db.prepare('UPDATE accounts SET discover = ? WHERE plex_id = ?').run(tri(patch.discover), a.plex_id);
  if ('blocked' in patch) {
    if (a.role === 'owner' && patch.blocked) throw err(400, 'The server owner can\'t be blocked.');
    db.prepare('UPDATE accounts SET blocked = ? WHERE plex_id = ?').run(patch.blocked ? 1 : 0, a.plex_id);
    if (patch.blocked) db.prepare('DELETE FROM devices WHERE plex_account = ?').run(a.plex_id); // signed out everywhere
  }
  return publicAccount(get(id));
}

/** Forget an account (signs it out everywhere; it can sign in again unless blocked first). */
export function remove(id) {
  db.prepare('DELETE FROM devices WHERE plex_account = ?').run(String(id));
  db.prepare('DELETE FROM accounts WHERE plex_id = ? AND role != \'owner\'').run(String(id));
}

// ---------- per-account app settings ----------
export function accountUi(a) { try { return JSON.parse(a?.ui || '{}') || {}; } catch { return {}; } }
export function saveAccountUi(a, patch) {
  const next = { ...accountUi(a), ...patch };
  db.prepare('UPDATE accounts SET ui = ? WHERE plex_id = ?').run(JSON.stringify(next), a.plex_id);
  return next;
}

// ---------- keep access current ----------
// Access is re-checked with plex.tv twice a day: someone whose share was removed in Plex loses Lumen too.
const RECHECK_S = 12 * 3600;
const checking = new Set();
export function touch(a) {
  if (!a) return;
  const nowS = Math.floor(Date.now() / 1000);
  if (!a.last_seen || nowS - a.last_seen > 300) db.prepare('UPDATE accounts SET last_seen = unixepoch() WHERE plex_id = ?').run(a.plex_id);
  if (a.plex_token && nowS - (a.verified_at || 0) > RECHECK_S && !checking.has(a.plex_id)) {
    checking.add(a.plex_id);
    verify(a.plex_token)
      .catch(e => {
        if (e.status === 403 || e.status === 401) {
          console.warn(`[accounts] ${a.title} no longer has access to the Plex server; signing them out`);
          remove(a.plex_id);
        }
      })
      .finally(() => checking.delete(a.plex_id));
  }
}

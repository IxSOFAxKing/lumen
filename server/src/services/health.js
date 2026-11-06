// System health for Settings → System: is Plex / Overseerr / each IPTV provider answering, how fresh is the guide,
// and the most recent warnings and errors from the log (kept in memory).
import { db, getSetting } from '../db.js';
import * as plex from './plex.js';
import { listSessions } from './transcoder.js';

// ---------- recent log lines ----------
const MAX_LOG = 60;
const recent = [];
export function captureLogs() {
  if (console.__lumenCaptured) return;
  console.__lumenCaptured = true;
  for (const level of ['warn', 'error']) {
    const orig = console[level].bind(console);
    console[level] = (...args) => {
      try {
        const msg = args.map(a => (a instanceof Error ? a.message : typeof a === 'string' ? a : JSON.stringify(a))).join(' ').slice(0, 600);
        recent.push({ t: Date.now(), level, msg });
        if (recent.length > MAX_LOG) recent.shift();
      } catch { /* never break logging */ }
      orig(...args);
    };
  }
}
export const recentLogs = () => [...recent].reverse();

async function timed(fn) {
  const t = Date.now();
  try { const v = await fn(); return { ok: true, ms: Date.now() - t, ...(v || {}) }; }
  catch (e) { return { ok: false, ms: Date.now() - t, error: e.message || String(e) }; }
}

async function plexHealth() {
  if (!plex.isConfigured()) return { configured: false };
  const c = plex.plexConf();
  const r = await timed(async () => {
    const info = await plex.testConnection(c.url, c.token);
    return { name: info.name, version: info.version };
  });
  return { configured: true, url: c.url, ...r };
}

async function overseerrHealth() {
  const c = getSetting('overseerr', {}) || {};
  if (!c.url || !c.apiKey) return { configured: false };
  const r = await timed(async () => {
    const res = await fetch(c.url.replace(/\/+$/, '') + '/api/v1/status', { headers: { 'X-Api-Key': c.apiKey, Accept: 'application/json' }, signal: AbortSignal.timeout(8000) });
    if (!res.ok) throw new Error(`Overseerr responded ${res.status}`);
    const j = await res.json();
    return { version: j.version };
  });
  return { configured: true, url: c.url, ...r };
}

async function providerHealth(p) {
  const nowS = Math.floor(Date.now() / 1000);
  const counts = db.prepare(`SELECT (SELECT COUNT(*) FROM channels WHERE provider_id = ?) AS channels,
    (SELECT COUNT(*) FROM programmes WHERE provider_id = ?) AS programmes,
    (SELECT MAX(stop) FROM programmes WHERE provider_id = ?) AS guideUntil`).get(p.id, p.id, p.id);
  let info = null; try { info = JSON.parse(p.account_info || 'null'); } catch { /* ignore */ }
  const reach = p.enabled ? await timed(async () => {
    const res = await fetch(p.url, { method: 'GET', redirect: 'manual', signal: AbortSignal.timeout(8000), headers: { 'User-Agent': p.user_agent || 'Mozilla/5.0' } });
    try { await res.body?.cancel(); } catch { /* ignore */ }
    if (res.status >= 500) throw new Error(`responded ${res.status}`);
  }) : null;
  const warnings = [];
  if (!p.enabled) warnings.push('Disabled');
  if (p.last_sync && nowS - p.last_sync > 3 * 86400) warnings.push('Playlist not updated for over 3 days');
  if (!counts.programmes) warnings.push('No TV guide data');
  else if (counts.guideUntil && counts.guideUntil < nowS + 6 * 3600) warnings.push('TV guide runs out within 6 hours');
  const exp = Number(info?.user_info?.exp_date) || null;
  if (exp && exp - nowS < 7 * 86400) warnings.push(exp < nowS ? 'Subscription expired' : 'Subscription expires within a week');
  if (info?.user_info?.status && !/active/i.test(info.user_info.status)) warnings.push(`Account status: ${info.user_info.status}`);
  return {
    id: p.id, name: p.name, enabled: !!p.enabled, reachable: reach, lastSync: p.last_sync, lastEpgSync: p.last_epg_sync,
    channels: counts.channels, programmes: counts.programmes, guideUntil: counts.guideUntil, expires: exp,
    maxConnections: info?.user_info?.max_connections ?? null, activeConnections: info?.user_info?.active_cons ?? null, warnings,
  };
}

export async function health() {
  const providers = db.prepare('SELECT * FROM providers ORDER BY name').all();
  const [p, o, prov] = await Promise.all([plexHealth(), overseerrHealth(), Promise.all(providers.map(providerHealth))]);
  return {
    time: Date.now(), uptime: Math.round(process.uptime()), node: process.version,
    plex: p, overseerr: o, providers: prov,
    transcodes: listSessions().length,
    logs: recentLogs(),
  };
}

import { getSetting } from '../db.js';
import { getConfig, setConfig } from './settings.js';

export const PRODUCT = 'Lumen';
export const VERSION = '1.0.0';

export function plexHeaders(extra = {}) {
  return {
    Accept: 'application/json',
    'X-Plex-Product': PRODUCT,
    'X-Plex-Version': VERSION,
    'X-Plex-Client-Identifier': getSetting('clientId'),
    'X-Plex-Platform': 'Web',
    'X-Plex-Platform-Version': '1.0',
    'X-Plex-Device': 'Browser',
    'X-Plex-Device-Name': 'Lumen Web',
    'X-Plex-Model': 'hosted',
    ...extra,
  };
}

export function plexConf() {
  const c = getConfig('plex');
  return { ...c, url: (c.url || '').replace(/\/+$/, '') };
}

export function isConfigured() {
  const c = plexConf();
  return Boolean(c.url && c.token);
}

/** Build an absolute URL to the Plex server including the token. */
export function plexUrl(path, params = {}) {
  const c = plexConf();
  const u = new URL(c.url + (path.startsWith('/') ? path : '/' + path));
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null) u.searchParams.set(k, v);
  u.searchParams.set('X-Plex-Token', c.token);
  return u.toString();
}

// ---------- protection: Lumen must never flood the Plex server ----------
/** Simple concurrency limiter. */
export class Gate {
  constructor(n) { this.n = n; this.active = 0; this.queue = []; }
  async run(fn) {
    if (this.active >= this.n) await new Promise(r => this.queue.push(r));
    this.active++;
    try { return await fn(); } finally { this.active--; this.queue.shift()?.(); }
  }
}
const gates = { api: new Gate(6), image: new Gate(3) };

// Request accounting: logs a warning if Lumen ever sends Plex an unusual number of requests.
let reqCount = 0; const reqPaths = new Map();
const DEBUG = /^(1|true|yes)$/i.test(process.env.PLEX_DEBUG || '');
let debugSeq = 0, debugInflight = 0;
if (DEBUG) console.log('[plex-debug] logging every request sent to Plex');
setInterval(() => {
  if (reqCount > 240) {
    const top = [...reqPaths.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5).map(([p, n]) => `${n}× ${p}`).join(', ');
    console.warn(`[plex] high request rate: ${reqCount} requests to Plex in the last minute (${top})`);
  }
  reqCount = 0; reqPaths.clear();
}, 60000).unref();

// Token bucket: never more than ~15 requests/second to Plex (bursts of 30), whatever the browser does.
let tokens = 30, lastRefill = Date.now();
async function rateLimit() {
  for (;;) {
    const now = Date.now();
    tokens = Math.min(30, tokens + ((now - lastRefill) / 1000) * 15);
    lastRefill = now;
    if (tokens >= 1) { tokens--; return; }
    await new Promise(r => setTimeout(r, 70));
  }
}

/**
 * Request the Plex server.
 * gate: 'api' (default for JSON), 'image', or null (streams handle their own limits).
 * signal: abort when the browser goes away, so abandoned requests don't pile up on Plex.
 */
export async function plexFetch(path, { params = {}, method = 'GET', headers = {}, raw = false, timeout = 20000, signal, gate } = {}) {
  if (!isConfigured()) { const e = new Error('Plex is not configured'); e.status = 400; throw e; }
  const signals = [timeout ? AbortSignal.timeout(timeout) : null, signal].filter(Boolean);
  const g = gates[gate === undefined ? (raw ? null : 'api') : gate];
  reqCount++;
  const key = path.split('?')[0].replace(/\/\d+/g, '/N').replace(/\/session\/[^/]+/, '/session/S');
  reqPaths.set(key, (reqPaths.get(key) || 0) + 1);
  const doFetch = () => fetch(plexUrl(path, params), {
    method,
    headers: plexHeaders({ 'X-Plex-Token': plexConf().token, ...headers }),
    signal: signals.length > 1 ? AbortSignal.any(signals) : signals[0],
  });
  await rateLimit();
  if (!DEBUG) { const res0 = g ? await g.run(doFetch) : await doFetch(); return handle(res0); }
  // PLEX_DEBUG=1: log every request to Plex with its timing, so a misbehaving request can be pinpointed.
  const id = ++debugSeq;
  const q = new URLSearchParams(params); q.delete('X-Plex-Token');
  const shown = path + (Object.keys(params).length ? (path.includes('?') ? '&' : '?') + q : '');
  debugInflight++;
  console.log(`[plex-debug] #${id} -> ${method} ${shown.slice(0, 220)} (in flight: ${debugInflight})`);
  const t0 = Date.now();
  try {
    const res0 = g ? await g.run(doFetch) : await doFetch();
    console.log(`[plex-debug] #${id} <- ${res0.status} in ${Date.now() - t0}ms`);
    return handle(res0);
  } catch (e) {
    console.log(`[plex-debug] #${id} !! ${e.name === 'TimeoutError' ? 'timed out' : e.name === 'AbortError' ? 'cancelled' : e.message} after ${Date.now() - t0}ms`);
    throw e;
  } finally { debugInflight--; }

  async function handle(res) {
  if (raw) return res;
  if (!res.ok) {
    const body = (await res.text().catch(() => '')).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 200);
    const e = new Error(`Plex responded ${res.status} for ${path.split('?')[0]}${body ? ` (${body})` : ''}`);
    e.status = 502; // never relay 401: the browser would treat it as "Lumen session expired"
    e.plexStatus = res.status;
    if (res.status === 401 || res.status === 403) console.warn(`[plex] Plex refused ${path.split('?')[0]} with ${res.status} — the token may not have access to this endpoint`);
    throw e;
  }
  const text = await res.text();
  if (!text) return {};
  try { return JSON.parse(text); } catch { return { raw: text }; }
  }
}

// ---------- response cache (stale-while-revalidate) ----------
const cache = new Map();
/** Cached GET for browse endpoints. Serves stale data instantly and refreshes it in the background. */
export async function plexCached(path, opts = {}, ttl = 60000) {
  const key = path + '|' + JSON.stringify(opts.params || {}) + '|' + JSON.stringify(opts.headers || {});
  const hit = cache.get(key);
  const now = Date.now();
  // Recently failed requests are not retried for 30s, so nothing can hammer Plex with a failing call.
  if (hit?.err && now - hit.t < 30000) throw hit.err;
  if (hit && !hit.err && now - hit.t < ttl) return hit.v;
  if (hit && !hit.err && now - hit.t < 15 * 60000) {
    if (!hit.refreshing) {
      hit.refreshing = true;
      plexFetch(path, opts).then(v => cache.set(key, { v, t: Date.now() })).catch(() => { hit.refreshing = false; });
    }
    return hit.v;
  }
  let v;
  try { v = await plexFetch(path, opts); }
  catch (err) { cache.set(key, { err, t: Date.now() }); throw err; }
  cache.set(key, { v, t: now });
  if (cache.size > 400) cache.delete(cache.keys().next().value);
  return v;
}
export function clearPlexCache() { cache.clear(); }

// ---------- plex.tv account helpers (PIN login + server discovery) ----------

export async function createPin() {
  const res = await fetch('https://plex.tv/api/v2/pins?strong=true', {
    method: 'POST', headers: plexHeaders(),
  });
  if (!res.ok) throw new Error('Could not create Plex PIN: ' + res.status);
  const pin = await res.json();
  const clientId = getSetting('clientId');
  const authUrl = 'https://app.plex.tv/auth#?' + new URLSearchParams({
    clientID: clientId, code: pin.code, 'context[device][product]': PRODUCT,
  }).toString();
  return { id: pin.id, code: pin.code, authUrl };
}

export async function checkPin(id) {
  const res = await fetch(`https://plex.tv/api/v2/pins/${encodeURIComponent(id)}`, { headers: plexHeaders() });
  if (!res.ok) throw new Error('PIN check failed: ' + res.status);
  const pin = await res.json();
  return pin.authToken || null;
}

export async function listServers(token) {
  const res = await fetch('https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1', {
    headers: plexHeaders({ 'X-Plex-Token': token }),
  });
  if (!res.ok) throw new Error('Could not list Plex servers: ' + res.status);
  const list = await res.json();
  return list.filter(r => (r.provides || '').includes('server')).map(r => ({
    name: r.name,
    machineId: r.clientIdentifier,
    owned: r.owned,
    accessToken: r.accessToken,
    connections: (r.connections || []).map(c => ({ uri: c.uri, local: c.local, relay: c.relay, address: c.address, port: c.port, protocol: c.protocol })),
  }));
}

/** Try each connection and return the first reachable one. */
export async function pickConnection(server) {
  const ordered = [...server.connections].sort((a, b) => (b.local - a.local) || (a.relay - b.relay));
  for (const c of ordered) {
    try {
      const r = await fetch(c.uri + '/identity', { headers: plexHeaders({ 'X-Plex-Token': server.accessToken }), signal: AbortSignal.timeout(4000) });
      if (r.ok) return c.uri;
    } catch { /* try next */ }
  }
  return null;
}

export async function testConnection(url, token) {
  const res = await fetch(url.replace(/\/+$/, '') + '/', { headers: plexHeaders({ 'X-Plex-Token': token }), signal: AbortSignal.timeout(8000) });
  if (!res.ok) throw new Error('Plex server responded ' + res.status);
  const j = await res.json();
  return { name: j.MediaContainer?.friendlyName, machineId: j.MediaContainer?.machineIdentifier, version: j.MediaContainer?.version };
}

export function saveServer(patch) { return setConfig('plex', patch); }

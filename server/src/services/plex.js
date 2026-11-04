import { AsyncLocalStorage } from 'node:async_hooks';
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

/**
 * The Plex Home user a request is made for ({ uuid, token }); requests outside a user context use the
 * server owner's token. Set per request by the /api/plex routes.
 */
export const plexUserCtx = new AsyncLocalStorage();
export const activeToken = () => plexUserCtx.getStore()?.token || plexConf().token;

/** Build an absolute URL to the Plex server including the token. */
export function plexUrl(path, params = {}) {
  const c = plexConf();
  const u = new URL(c.url + (path.startsWith('/') ? path : '/' + path));
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null) u.searchParams.set(k, v);
  u.searchParams.set('X-Plex-Token', activeToken());
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
/** How a request to Plex failed at the network level (undici puts the reason in err.cause). */
export function failureKind(err) {
  const code = err?.cause?.code || err?.code || '';
  if (err?.plexDown) return 'down';
  if (/ECONNREFUSED|EHOSTUNREACH|ENETUNREACH|ENOTFOUND|EAI_AGAIN/.test(code)) return 'down';
  if (/ECONNRESET|UND_ERR_SOCKET|EPIPE|UND_ERR_CLOSED/.test(code)) return 'dropped'; // cut off mid-request: Plex crashed or restarted
  if (err?.name === 'TimeoutError') return 'timeout';
  if (err?.name === 'AbortError') return 'cancelled';
  return 'other';
}

// Circuit breaker: when Plex stops answering (e.g. it crashed and is restarting), stop contacting it briefly.
let downUntil = 0;
export function plexIsDown() { return Date.now() < downUntil; }

export async function plexFetch(path, { params = {}, method = 'GET', headers = {}, raw = false, timeout = 20000, signal, gate } = {}) {
  if (!isConfigured()) { const e = new Error('Plex is not configured'); e.status = 400; throw e; }
  if (plexIsDown()) {
    const e = new Error('Plex server is not responding (it may be restarting); retrying shortly');
    e.status = 503; e.plexDown = true; throw e;
  }
  const signals = [timeout ? AbortSignal.timeout(timeout) : null, signal].filter(Boolean);
  const g = gates[gate === undefined ? (raw ? null : 'api') : gate];
  reqCount++;
  const key = path.split('?')[0].replace(/\/\d+/g, '/N').replace(/\/session\/[^/]+/, '/session/S');
  reqPaths.set(key, (reqPaths.get(key) || 0) + 1);
  const doFetch = () => fetch(plexUrl(path, params), {
    method,
    headers: plexHeaders({ 'X-Plex-Token': activeToken(), ...headers }),
    signal: signals.length > 1 ? AbortSignal.any(signals) : signals[0],
  });
  await rateLimit();
  const tripOnFailure = async (fn) => {
    try { return await fn(); }
    catch (e) {
      const kind = failureKind(e);
      if ((kind === 'down' || kind === 'dropped') && !plexIsDown()) {
        downUntil = Date.now() + 20000;
        console.warn(`[plex] lost connection to Plex (${e.cause?.code || e.message}); pausing requests for 20s`);
      }
      throw e;
    }
  };
  if (!DEBUG) { const res0 = await tripOnFailure(() => g ? g.run(doFetch) : doFetch()); return handle(res0); }
  // PLEX_DEBUG=1: log every request to Plex with its timing, so a misbehaving request can be pinpointed.
  const id = ++debugSeq;
  const q = new URLSearchParams(params); q.delete('X-Plex-Token');
  const shown = path + (Object.keys(params).length ? (path.includes('?') ? '&' : '?') + q : '');
  debugInflight++;
  console.log(`[plex-debug] #${id} -> ${method} ${shown.slice(0, 220)} (in flight: ${debugInflight})`);
  const t0 = Date.now();
  try {
    const res0 = await tripOnFailure(() => g ? g.run(doFetch) : doFetch());
    console.log(`[plex-debug] #${id} <- ${res0.status} in ${Date.now() - t0}ms`);
    return handle(res0);
  } catch (e) {
    console.log(`[plex-debug] #${id} !! ${failureKind(e)} (${e.cause?.code || e.message}) after ${Date.now() - t0}ms`);
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
  // Each Plex Home user sees their own Continue Watching, watched state, etc.
  const key = (plexUserCtx.getStore()?.uuid || '') + '|' + path + '|' + JSON.stringify(opts.params || {}) + '|' + JSON.stringify(opts.headers || {});
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

// ---------- Plex Home users ----------

/** plex.tv answers some endpoints in XML only; parse <Tag a="b" …> elements into objects. */
function xmlElements(text, tag) {
  const out = [];
  const dec = v => v.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  for (const m of text.matchAll(new RegExp(`<${tag}\\b([^>]*)>`, 'gi'))) {
    const o = {};
    for (const a of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) o[a[1]] = dec(a[2]);
    out.push(o);
  }
  return out;
}

async function plexTv(path, { method = 'GET', token, params = {} } = {}) {
  const u = new URL('https://plex.tv' + path);
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') u.searchParams.set(k, v);
  const res = await fetch(u, { method, headers: plexHeaders({ 'X-Plex-Token': token }), signal: AbortSignal.timeout(15000) });
  const text = await res.text();
  if (!res.ok) {
    const e = new Error(res.status === 401 || res.status === 403 ? 'Plex refused: incorrect PIN or not allowed' : `plex.tv responded ${res.status}`);
    e.status = res.status === 401 || res.status === 403 ? 403 : 502;
    throw e;
  }
  let json = null;
  try { json = JSON.parse(text); } catch { /* XML */ }
  return { json, text };
}

const bool = v => v === true || v === 1 || v === '1' || v === 'true';
const accountToken = () => plexConf().accountToken || plexConf().token;

/** Users of the Plex Home the server owner belongs to. */
export async function homeUsers() {
  const { json, text } = await plexTv('/api/home/users', { token: accountToken() });
  const list = json ? (json.users || json.MediaContainer?.User || []) : xmlElements(text, 'User');
  return list.map(u => ({
    id: String(u.id), uuid: u.uuid || String(u.id), title: u.title || u.friendlyName || u.username || 'User', thumb: u.thumb || null,
    admin: bool(u.admin), protected: bool(u.protected) || bool(u.hasPassword), restricted: bool(u.restricted),
  }));
}

/** Switch to a Home user (checks the PIN with plex.tv) and return that user's token for this Plex server. */
export async function switchHomeUser(user, pin) {
  const { json, text } = await plexTv(`/api/home/users/${encodeURIComponent(user.id)}/switch`, { method: 'POST', token: accountToken(), params: { pin } });
  const userToken = json?.authToken || json?.authenticationToken || json?.user?.authToken
    || xmlElements(text, 'user')[0]?.authenticationToken || xmlElements(text, 'user')[0]?.authToken;
  if (!userToken) throw Object.assign(new Error('Plex did not return a token for this user'), { status: 502 });
  const machineId = plexConf().machineId;
  let serverToken = null;
  try {
    const servers = await listServers(userToken);
    serverToken = servers.find(s => s.machineId === machineId)?.accessToken || null;
  } catch { /* fall through */ }
  if (!serverToken && user.admin) serverToken = plexConf().token;
  if (!serverToken) throw Object.assign(new Error(`${user.title} doesn't have access to this Plex server (share it with them in Plex first).`), { status: 403 });
  return serverToken;
}

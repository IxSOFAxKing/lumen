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

export async function plexFetch(path, { params = {}, method = 'GET', headers = {}, raw = false, timeout = 20000 } = {}) {
  if (!isConfigured()) { const e = new Error('Plex is not configured'); e.status = 400; throw e; }
  const res = await fetch(plexUrl(path, params), {
    method,
    headers: plexHeaders({ 'X-Plex-Token': plexConf().token, ...headers }),
    signal: timeout ? AbortSignal.timeout(timeout) : undefined,
  });
  if (raw) return res;
  if (!res.ok) {
    const e = new Error(`Plex responded ${res.status} for ${path}`);
    e.status = res.status === 401 ? 401 : 502;
    throw e;
  }
  const text = await res.text();
  if (!text) return {};
  try { return JSON.parse(text); } catch { return { raw: text }; }
}

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

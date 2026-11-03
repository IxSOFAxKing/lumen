async function request(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
  });
  if (res.status === 401 && !url.startsWith('/api/auth')) {
    window.dispatchEvent(new CustomEvent('lumen:unauthorized'));
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) {
    const err = new Error((data && data.error) || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return data;
}

// In-memory GET cache so going back to a page is instant. Writes clear it (except playback heartbeats).
const cache = new Map();
const HEARTBEAT = /\/(ping|timeline|progress|play)(\/|$|\?)|\/play\//;
function mutate(method, u, b) {
  if (!HEARTBEAT.test(u)) cache.clear();
  return request(method, u, b);
}

export const api = {
  get: (u) => request('GET', u),
  /** GET with a short-lived cache (default 60s). */
  cached(u, ttl = 60000) {
    const hit = cache.get(u);
    if (hit && Date.now() - hit.t < ttl) return hit.p;
    const p = request('GET', u);
    cache.set(u, { p, t: Date.now() });
    p.catch(() => cache.delete(u));
    if (cache.size > 300) cache.delete(cache.keys().next().value);
    return p;
  },
  clearCache: () => cache.clear(),
  post: (u, b = {}) => mutate('POST', u, b),
  put: (u, b = {}) => mutate('PUT', u, b),
  del: (u) => mutate('DELETE', u),
};

export const qs = (o) => {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(o)) if (v !== undefined && v !== null && v !== '') p.set(k, v);
  const s = p.toString();
  return s ? '?' + s : '';
};

/** Plex image through the Lumen proxy */
export function plexImg(path, w = 300, h = 450) {
  if (!path) return null;
  if (/^https?:\/\//.test(path)) return path;
  return `/api/plex/image${qs({ path, w, h })}`;
}

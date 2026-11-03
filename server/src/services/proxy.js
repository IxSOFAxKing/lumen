import crypto from 'node:crypto';
import { getSetting } from '../db.js';

const sign = u => crypto.createHmac('sha256', getSetting('sessionSecret')).update(u).digest('base64url').slice(0, 22);

/** Return a local URL that proxies (and for HLS, rewrites) the given upstream URL. */
export function proxied(url, ua) {
  const q = new URLSearchParams({ u: url, s: sign(url + '|' + (ua || '')) });
  if (ua) q.set('ua', ua);
  return `/api/stream/proxy?${q}`;
}
export function verify(url, ua, s) {
  const expect = sign(url + '|' + (ua || ''));
  return typeof s === 'string' && s.length === expect.length && crypto.timingSafeEqual(Buffer.from(s), Buffer.from(expect));
}

/** Signed, cached image proxy URL for remote artwork (channel logos, posters). */
export function imgUrl(u) {
  if (typeof u !== 'string' || !/^https?:\/\//i.test(u)) return u;
  return `/api/img?${new URLSearchParams({ u, s: sign('img|' + u) })}`;
}
export function verifyImg(u, s) {
  const expect = sign('img|' + u);
  return typeof s === 'string' && s.length === expect.length && crypto.timingSafeEqual(Buffer.from(s), Buffer.from(expect));
}

const IMG_KEYS = new Set(['logo', 'poster', 'cover', 'cover_big', 'movie_image', 'image', 'backdrop_path', 'stream_icon', 'icon']);
/** Deep-walk an API payload and route remote artwork through the image cache. */
export function proxyImages(v, key) {
  if (Array.isArray(v)) return v.map(x => proxyImages(x, key));
  if (v && typeof v === 'object') { const o = {}; for (const [k, x] of Object.entries(v)) o[k] = proxyImages(x, k); return o; }
  return IMG_KEYS.has(key) ? imgUrl(v) : v;
}

export function rewritePlaylist(text, baseUrl, ua) {
  const abs = u => new URL(u, baseUrl).toString();
  return text.split(/\r?\n/).map(line => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (m, u) => `URI="${proxied(abs(u), ua)}"`);
    return proxied(abs(t), ua);
  }).join('\n');
}

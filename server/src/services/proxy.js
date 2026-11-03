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

export function rewritePlaylist(text, baseUrl, ua) {
  const abs = u => new URL(u, baseUrl).toString();
  return text.split(/\r?\n/).map(line => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (m, u) => `URI="${proxied(abs(u), ua)}"`);
    return proxied(abs(t), ua);
  }).join('\n');
}

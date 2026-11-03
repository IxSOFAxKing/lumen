import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { config } from '../config.js';

const DIR = path.join(config.dataDir, 'cache', 'img');
const MAX_BYTES = 1024 * 1024 * 1024; // 1 GB
fs.mkdirSync(DIR, { recursive: true });
const inflight = new Map();

function fileFor(key) {
  const h = crypto.createHash('sha1').update(key).digest('hex');
  return path.join(DIR, h.slice(0, 2), h);
}

/**
 * Serve an image from the disk cache, fetching it with fetcher() on a miss.
 * fetcher() must resolve to a fetch Response.
 */
export async function serveCachedImage(key, fetcher, res) {
  const file = fileFor(key);
  const send = (type) => {
    res.setHeader('Content-Type', type || 'image/jpeg');
    res.setHeader('Cache-Control', 'public, max-age=2592000, immutable');
    fs.createReadStream(file).on('error', () => res.status(404).end()).pipe(res);
  };
  try {
    const type = await fs.promises.readFile(file + '.t', 'utf8');
    return send(type);
  } catch { /* miss */ }

  let job = inflight.get(file);
  if (!job) {
    job = (async () => {
      const up = await fetcher();
      const type = up.headers.get('content-type') || '';
      if (!up.ok || !type.startsWith('image/')) { const e = new Error('not an image'); e.status = up.ok ? 415 : up.status; throw e; }
      const buf = Buffer.from(await up.arrayBuffer());
      if (buf.length > 8 * 1024 * 1024) { const e = new Error('image too large'); e.status = 413; throw e; }
      await fs.promises.mkdir(path.dirname(file), { recursive: true });
      await fs.promises.writeFile(file, buf);
      await fs.promises.writeFile(file + '.t', type);
      return type;
    })().finally(() => inflight.delete(file));
    inflight.set(file, job);
  }
  try {
    send(await job);
  } catch (e) {
    res.status(e.status && e.status < 600 ? e.status : 502).end();
  }
}

/** Remove the oldest cached images when the cache exceeds MAX_BYTES. */
export async function pruneImageCache() {
  const files = [];
  let total = 0;
  for (const sub of await fs.promises.readdir(DIR).catch(() => [])) {
    for (const f of await fs.promises.readdir(path.join(DIR, sub)).catch(() => [])) {
      if (f.endsWith('.t')) continue;
      const p = path.join(DIR, sub, f);
      const st = await fs.promises.stat(p).catch(() => null);
      if (st) { files.push({ p, size: st.size, t: st.atimeMs || st.mtimeMs }); total += st.size; }
    }
  }
  if (total <= MAX_BYTES) return;
  files.sort((a, b) => a.t - b.t);
  for (const f of files) {
    if (total <= MAX_BYTES * 0.8) break;
    await fs.promises.rm(f.p, { force: true }); await fs.promises.rm(f.p + '.t', { force: true });
    total -= f.size;
  }
}
setInterval(() => pruneImageCache().catch(() => {}), 3600e3).unref();

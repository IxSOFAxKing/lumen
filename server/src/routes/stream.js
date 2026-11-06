import { Router } from 'express';
import fs from 'node:fs';
import { Readable } from 'node:stream';
import { db } from '../db.js';
import { ah, pipeFetch } from '../util.js';
import { getProvider, liveUrl, userAgent } from '../services/iptv.js';
import { sessionFile, touchSession, stopSession } from '../services/transcoder.js';
import { verify, rewritePlaylist } from '../services/proxy.js';
import { viewerKey, hold } from '../services/liveconn.js';

const r = Router();

// Direct MPEG-TS proxy for live channels (played in browser with mpegts.js).
r.get('/live/:pid/:cid', ah(async (req, res) => {
  const p = getProvider(req.params.pid);
  const ch = db.prepare('SELECT * FROM channels WHERE provider_id = ? AND id = ?').get(p.id, req.params.cid);
  if (!ch) return res.status(404).end();
  const ac = new AbortController();
  // Counts as this viewer's live stream until it ends (a new channel for the same viewer closes it).
  const release = hold(viewerKey(req), { stop: () => { ac.abort(); res.destroy(); } });
  res.on('close', () => { ac.abort(); release(); });
  const upstream = await fetch(liveUrl(p, ch, 'ts'), { headers: { 'User-Agent': userAgent(p) }, signal: ac.signal, redirect: 'follow' });
  if (!upstream.ok) return res.status(502).json({ error: `Provider returned ${upstream.status}` });
  res.setHeader('Content-Type', 'video/mp2t');
  res.setHeader('Cache-Control', 'no-store');
  const body = Readable.fromWeb(upstream.body);
  body.on('error', () => res.destroy());
  body.pipe(res);
}));

// Signed generic proxy (HLS playlists are rewritten so every sub-request comes back through here).
r.get('/proxy', ah(async (req, res) => {
  const { u, s, ua } = req.query;
  if (!u || !/^https?:\/\//i.test(u) || !verify(u, ua, s)) return res.status(403).end();
  const ac = new AbortController();
  res.on('close', () => ac.abort());
  const headers = { 'User-Agent': ua || 'Mozilla/5.0' };
  if (req.headers.range) headers.Range = req.headers.range;
  const upstream = await fetch(u, { headers, signal: ac.signal, redirect: 'follow' });
  const ct = upstream.headers.get('content-type') || '';
  if (/mpegurl/i.test(ct) || /\.m3u8(\?|$)/i.test(new URL(upstream.url).pathname)) {
    const text = await upstream.text();
    if (text.trimStart().startsWith('#EXTM3U')) {
      res.status(upstream.status).type('application/vnd.apple.mpegurl').setHeader('Cache-Control', 'no-cache');
      return res.send(rewritePlaylist(text, upstream.url, ua));
    }
    return res.status(upstream.status).type(ct || 'application/octet-stream').send(text);
  }
  pipeFetch(upstream, res);
}));

// Files produced by the transcoder
r.get('/hls/:id/:file', (req, res) => {
  const f = sessionFile(req.params.id, req.params.file);
  if (!f) return res.status(404).json({ error: 'Session expired' });
  if (!fs.existsSync(f)) return res.status(404).end();
  if (f.endsWith('.m3u8')) {
    res.type('application/vnd.apple.mpegurl').setHeader('Cache-Control', 'no-cache');
  } else {
    res.type('video/mp2t').setHeader('Cache-Control', 'private, max-age=3600');
  }
  fs.createReadStream(f).on('error', () => res.status(404).end()).pipe(res);
});

r.post('/session/:id/ping', (req, res) => res.json({ alive: !!touchSession(req.params.id) }));
r.delete('/session/:id', (req, res) => res.json({ stopped: stopSession(req.params.id) }));
r.post('/session/:id/stop', (req, res) => res.json({ stopped: stopSession(req.params.id) })); // for sendBeacon

export default r;

import express from 'express';
import compression from 'compression';
import path from 'node:path';
import fs from 'node:fs';
import { config } from './config.js';
import { db } from './db.js';
import * as auth from './auth.js';
import plexRoutes from './routes/plex.js';
import iptvRoutes from './routes/iptv.js';
import streamRoutes from './routes/stream.js';
import { allConfig, setConfig, DEFAULTS, getConfig } from './services/settings.js';
import { detectHardware, listSessions, stopSession, stopAll } from './services/transcoder.js';
import { syncProvider, applyVodSwitch } from './services/iptv.js';
import { syncEpg, purgeOldProgrammes } from './services/epg.js';
import { initRecorder, stopAllRecordings } from './services/recorder.js';
import { serveCachedImage } from './services/imgcache.js';
import { verifyImg } from './services/proxy.js';

const app = express();
app.set('trust proxy', true);
app.disable('x-powered-by');
// gzip JSON / JS / CSS, but never the media streams (they must flow unbuffered).
const NO_COMPRESS = /^\/api\/(stream|img|plex\/(t|file|image))\b/;
app.use(compression({ filter: (req, res) => !NO_COMPRESS.test(req.path) && compression.filter(req, res) }));
app.use(express.json({ limit: '2mb' }));

// ---------- Auth ----------
app.get('/api/auth/status', (req, res) => res.json({ required: auth.authEnabled(), authed: auth.isAuthed(req) }));
app.post('/api/auth/login', auth.login);
app.post('/api/auth/logout', auth.logout);
app.get('/api/health', (req, res) => res.json({ ok: true }));
app.use('/api', auth.requireAuth);

// ---------- Settings & system ----------
app.get('/api/settings', (req, res) => res.json(allConfig()));
app.put('/api/settings/:section', (req, res) => {
  const { section } = req.params;
  if (!DEFAULTS[section] || section === 'plex') return res.status(400).json({ error: 'Unknown section' });
  const before = getConfig(section);
  const after = setConfig(section, req.body || {});
  if (section === 'iptv' && (before.vodEnabled !== false) !== (after.vodEnabled !== false)) applyVodSwitch(after.vodEnabled !== false);
  res.json(after);
});
app.get('/api/system/hardware', async (req, res, next) => { try { res.json(await detectHardware()); } catch (e) { next(e); } });
app.get('/api/system/sessions', (req, res) => res.json(listSessions()));
app.delete('/api/system/sessions/:id', (req, res) => res.json({ stopped: stopSession(req.params.id) }));

app.get('/api/img', (req, res) => {
  const { u, s } = req.query;
  if (!u || !/^https?:\/\//i.test(u) || !verifyImg(u, s)) return res.status(403).end();
  serveCachedImage(`url|${u}`, () => fetch(u, { headers: { 'User-Agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(15000), redirect: 'follow' }), res);
});
app.use('/api/plex', plexRoutes);
app.use('/api/iptv', iptvRoutes);
app.use('/api/stream', streamRoutes);

app.use('/api', (req, res) => res.status(404).json({ error: 'Not found' }));

// ---------- Static web app ----------
const vendor = {
  'hls.js': path.join(config.vendorDir, 'hls.js/dist/hls.min.js'),
  'mpegts.js': path.join(config.vendorDir, 'mpegts.js/dist/mpegts.js'),
};
app.get('/vendor/:name', (req, res) => {
  const f = vendor[req.params.name];
  if (!f || !fs.existsSync(f)) return res.status(404).end();
  res.setHeader('Cache-Control', 'public, max-age=86400');
  res.type('application/javascript').sendFile(f);
});
app.use(express.static(config.webDir, { index: false, maxAge: 0, etag: true, lastModified: true }));
app.get(/^\/(?!api\/).*/, (req, res) => res.sendFile(path.join(config.webDir, 'index.html')));

// ---------- Errors ----------
app.use((err, req, res, next) => {
  const status = err.status || (err.name === 'TimeoutError' ? 504 : 500);
  if (status >= 500) console.error(`[error] ${req.method} ${req.originalUrl}:`, err.message);
  if (res.headersSent) return res.end();
  res.status(status).json({ error: err.message || 'Internal error' });
});

// ---------- Scheduled jobs ----------
function scheduler() {
  const conf = getConfig('iptv');
  const nowS = Date.now() / 1000;
  for (const p of db.prepare('SELECT * FROM providers WHERE enabled = 1').all()) {
    if (!p.last_sync || nowS - p.last_sync > conf.playlistRefreshHours * 3600) {
      syncProvider(p.id).catch(e => console.error(`[sched] playlist ${p.name}:`, e.message));
    } else if (!p.last_epg_sync || nowS - p.last_epg_sync > conf.epgRefreshHours * 3600) {
      syncEpg(p.id).catch(e => console.error(`[sched] epg ${p.name}:`, e.message));
    }
  }
  purgeOldProgrammes();
}
setTimeout(scheduler, 20000);
setInterval(scheduler, 15 * 60 * 1000).unref();
initRecorder();

const server = app.listen(config.port, () => {
  console.log(`Lumen listening on :${config.port} (auth ${auth.authEnabled() ? 'enabled' : 'disabled'}, data ${config.dataDir})`);
});
server.requestTimeout = 0; // long-lived streams

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => {
  console.log('Shutting down...');
  stopAll(); stopAllRecordings();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
});

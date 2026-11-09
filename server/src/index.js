import express from 'express';
import compression from 'compression';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import * as devices from './services/devices.js';
import { config } from './config.js';
import { db } from './db.js';
import * as auth from './auth.js';
import * as accounts from './services/accounts.js';
import * as plexSvc from './services/plex.js';
import plexRoutes from './routes/plex.js';
import discoverRoutes from './routes/discover.js';
import iptvRoutes from './routes/iptv.js';
import streamRoutes from './routes/stream.js';
import { allConfig, setConfig, DEFAULTS, getConfig } from './services/settings.js';
import { detectHardware, listSessions, stopSession, stopAll } from './services/transcoder.js';
import { syncProvider, applyVodSwitch } from './services/iptv.js';
import { syncEpg, purgeOldProgrammes } from './services/epg.js';
import { initRecorder, stopAllRecordings } from './services/recorder.js';
import { serveCachedImage } from './services/imgcache.js';
import { exportAll, importAll } from './services/backup.js';
import { verifyImg } from './services/proxy.js';
import { captureLogs, health } from './services/health.js';

captureLogs(); // recent warnings/errors for Settings → System → Health

const app = express();
app.set('trust proxy', true);
app.disable('x-powered-by');
// gzip JSON / JS / CSS, but never the media streams (they must flow unbuffered).
const NO_COMPRESS = /^\/api\/(stream|img|plex\/(t|file|image))\b/;
app.use(compression({ filter: (req, res) => !NO_COMPRESS.test(req.path) && compression.filter(req, res) }));
app.use(express.json({ limit: '25mb' }));

// ---------- Auth ----------
app.get('/api/auth/status', auth.status);
app.post('/api/auth/setup', auth.setup);
app.post('/api/auth/login', auth.login);
app.post('/api/auth/logout', auth.logout);
// Sign in with Plex (web pop-up flow, and apps that signed in to Plex themselves).
app.post('/api/auth/plex/pin', auth.plexPinStart);
app.get('/api/auth/plex/pin/:id', auth.plexPinCheck);
app.post('/api/auth/plex/token', auth.plexToken);
const VERSION = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url))).version;
// Used by the Android TV app to discover Lumen servers on the local network.
app.get('/api/health', (req, res) => res.json({ ok: true, app: 'lumen', version: VERSION, name: process.env.LUMEN_NAME || os.hostname(), authRequired: auth.authEnabled(),
  // Apps signed in with Plex use these to find the Lumen server that belongs to their Plex server.
  plexLogin: plexSvc.isConfigured() && !!plexSvc.plexConf().machineId, plexMachineId: plexSvc.plexConf().machineId || null }));

// ---------- Device pairing (no login needed to *request* pairing; a signed-in user approves it) ----------
app.post('/api/pair/start', (req, res, next) => {
  try { res.json(devices.startPairing(req.body || {}, { autoApprove: false, ip: req.ip })); } catch (e) { next(e); }
});
app.get('/api/pair/status/:id', (req, res) => res.json(devices.pairingStatus(req.params.id)));
// Short link for the Downloader app on Android TV: http://<server>:8080/tv.apk
app.get(['/tv.apk', '/tv'], (req, res) => res.redirect(302, 'https://github.com/IxSOFAxKing/lumen/releases/latest/download/lumen-tv.apk'));
app.use('/api', auth.requireAuth);

// ---------- Settings & system ----------
const admin = auth.requireAdmin;
app.put('/api/auth/account', admin, auth.changeAccount);
app.get('/api/devices', admin, (req, res) => res.json({ devices: devices.listDevices(), pending: devices.pendingPairings(), current: req.device?.id || null }));
app.post('/api/pair/approve', admin, (req, res, next) => { try { res.json(devices.approvePairing(req.body?.code, req.body?.name)); } catch (e) { next(e); } });
app.put('/api/devices/:id', admin, (req, res) => { devices.renameDevice(req.params.id, req.body?.name || 'Device'); res.json({ ok: true }); });
app.delete('/api/devices/:id', admin, (req, res) => { devices.revokeDevice(req.params.id); res.json({ ok: true }); });

// People who signed in with Plex, and what they may use (owner only).
app.get('/api/accounts', admin, (req, res) => res.json({ accounts: accounts.list() }));
app.put('/api/accounts/:id', admin, (req, res, next) => { try { res.json(accounts.update(req.params.id, req.body || {})); } catch (e) { next(e); } });
app.delete('/api/accounts/:id', admin, (req, res) => { accounts.remove(req.params.id); res.json({ ok: true }); });

/** Accounts other than the owner keep their own app settings, over the server's defaults. */
const ownUi = req => req.account && req.account.role !== 'owner';
app.get('/api/settings', (req, res) => {
  const all = allConfig();
  // Plex tokens never leave the server.
  all.plex = { configured: plexSvc.isConfigured(), serverName: all.plex.serverName || '', machineId: all.plex.machineId || '' };
  if (ownUi(req)) all.ui = { ...all.ui, ...accounts.accountUi(req.account) };
  if (!req.perms.admin) { delete all.transcode; }
  res.json(all);
});
app.put('/api/settings/:section', (req, res, next) => {
  const { section } = req.params;
  if (!DEFAULTS[section] || section === 'plex') return res.status(400).json({ error: 'Unknown section' });
  if (section === 'ui' && ownUi(req)) return res.json({ ...getConfig('ui'), ...accounts.saveAccountUi(req.account, req.body || {}) });
  if (!req.perms.admin) return admin(req, res, next);
  const before = getConfig(section);
  const after = setConfig(section, req.body || {});
  if (section === 'iptv' && (before.vodEnabled !== false) !== (after.vodEnabled !== false)) applyVodSwitch(after.vodEnabled !== false);
  res.json(after);
});
app.get('/api/settings/export', admin, (req, res) => {
  const data = exportAll({ includeProviders: req.query.providers === '1' });
  res.setHeader('Content-Disposition', `attachment; filename="lumen-settings-${new Date().toISOString().slice(0, 10)}.json"`);
  res.json(data);
});
app.post('/api/settings/import', admin, (req, res, next) => {
  try { const report = importAll(req.body); res.json(report); for (const p of db.prepare('SELECT id FROM providers WHERE last_sync IS NULL').all()) syncProvider(p.id).catch(() => {}); }
  catch (e) { next(e); }
});
app.get('/api/system/hardware', admin, async (req, res, next) => { try { res.json(await detectHardware()); } catch (e) { next(e); } });
app.get('/api/system/health', admin, async (req, res, next) => { try { res.json(await health()); } catch (e) { next(e); } });
app.get('/api/system/sessions', admin, (req, res) => res.json(listSessions()));
app.delete('/api/system/sessions/:id', admin, (req, res) => res.json({ stopped: stopSession(req.params.id) }));

app.get('/api/img', (req, res) => {
  const { u, s } = req.query;
  if (!u || !/^https?:\/\//i.test(u) || !verifyImg(u, s)) return res.status(403).end();
  serveCachedImage(`url|${u}`, () => fetch(u, { headers: { 'User-Agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(15000), redirect: 'follow' }), res);
});
// Connecting / disconnecting Plex is the owner's job.
app.use('/api/plex', (req, res, next) => (/^\/(connect|disconnect|servers|pin|users\/default)\b/.test(req.path) && !req.perms.admin ? admin(req, res, next) : next()), plexRoutes);
// Discover: per-account permission (the Overseerr connection itself is owner-only).
app.use('/api/discover', (req, res, next) => {
  if (req.method === 'PUT' && req.path === '/config') return admin(req, res, next);
  if (req.perms.discover) return next();
  if (req.method === 'GET' && req.path === '/config') return res.json({ url: '', configured: false, blocked: true });
  return auth.requirePerm('discover')(req, res, next);
}, discoverRoutes);
// Live TV: per-account permission. Watching and (personal) favourites are open to anyone allowed Live TV; recordings
// and reminders need a Home member (or the owner); providers and channel-list edits are owner-only.
const IPTV_MANAGE = /^\/(recordings|reminders)\b/;
// Favourites are personal (each Plex user has their own), so anyone with Live TV manages their own.
const IPTV_OPEN = /^\/(play\/|parental\/unlock|progress|favorites|fav-lists)/;
app.use('/api/iptv', auth.requirePerm('live'), (req, res, next) => {
  if (req.method === 'GET' || IPTV_OPEN.test(req.path)) return next();
  if (IPTV_MANAGE.test(req.path)) return req.perms.manage ? next() : auth.requirePerm('manage')(req, res, next);
  return admin(req, res, next);
}, iptvRoutes);
// Live streams need Live TV; built-in transcoder sessions (also used for Plex) are open to everyone signed in.
app.use('/api/stream', (req, res, next) => (/^\/(live|proxy)\b/.test(req.path) ? auth.requirePerm('live')(req, res, next) : next()), streamRoutes);

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

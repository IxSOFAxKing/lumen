import { Router } from 'express';
import { db } from '../db.js';
import { ah } from '../util.js';
import * as iptv from '../services/iptv.js';
import * as epg from '../services/epg.js';
import * as dvr from '../services/recorder.js';
import { startSession } from '../services/transcoder.js';
import { proxied } from '../services/proxy.js';
import { getConfig } from '../services/settings.js';

const r = Router();
const now = () => Math.floor(Date.now() / 1000);

const publicProvider = p => {
  let info = null; try { info = JSON.parse(p.account_info || 'null'); } catch { /* ignore */ }
  const counts = db.prepare(`SELECT (SELECT COUNT(*) FROM channels WHERE provider_id = ?) AS live,
    (SELECT COUNT(*) FROM vod WHERE provider_id = ? AND kind = 'movie') AS movies,
    (SELECT COUNT(*) FROM vod WHERE provider_id = ? AND kind = 'series') AS series,
    (SELECT COUNT(*) FROM programmes WHERE provider_id = ?) AS programmes`).get(p.id, p.id, p.id, p.id);
  return {
    id: p.id, name: p.name, type: p.type, url: p.url, username: p.username, hasPassword: !!p.password, epg_url: p.epg_url,
    output: p.output, user_agent: p.user_agent, enabled: !!p.enabled, last_sync: p.last_sync, last_epg_sync: p.last_epg_sync,
    epgRunning: epg.isEpgRunning(p.id), counts,
    account: info?.user_info ? {
      status: info.user_info.status, exp_date: Number(info.user_info.exp_date) || null, max_connections: info.user_info.max_connections,
      active_cons: info.user_info.active_cons, is_trial: info.user_info.is_trial === '1', timezone: info.server_info?.timezone,
    } : null,
  };
};

function syncAll(id) {
  return iptv.syncProvider(id).then(() => epg.syncEpg(id)).catch(e => console.error(`[iptv] sync ${id} failed:`, e.message));
}

// ---------- Providers ----------

r.get('/providers', (req, res) => res.json(iptv.listProviders().map(publicProvider)));

r.post('/providers', ah(async (req, res) => {
  const { name, type = 'xtream', url, username, password, epg_url, output = 'ts', user_agent } = req.body || {};
  if (!url) return res.status(400).json({ error: 'URL is required' });
  if (type === 'xtream') {
    if (!username || !password) return res.status(400).json({ error: 'Username and password are required' });
    await iptv.testXtream({ type, url, username, password, user_agent });
  }
  const r2 = db.prepare('INSERT INTO providers(name, type, url, username, password, epg_url, output, user_agent) VALUES (?,?,?,?,?,?,?,?)')
    .run(name || (type === 'xtream' ? new URL(iptv.baseUrl({ url })).hostname : 'M3U Playlist'), type, url.trim(), username || null, password || null, epg_url || null, output, user_agent || null);
  const id = Number(r2.lastInsertRowid);
  try { await iptv.syncProvider(id); }
  catch (e) { db.prepare('DELETE FROM providers WHERE id = ?').run(id); throw e; }
  epg.syncEpg(id).catch(e => console.error('[epg]', e.message));
  res.json(publicProvider(iptv.getProvider(id)));
}));

r.put('/providers/:id', ah(async (req, res) => {
  const p = iptv.getProvider(req.params.id);
  const b = req.body || {};
  const next = {
    name: b.name ?? p.name, url: b.url ?? p.url, username: b.username ?? p.username,
    password: b.password ? b.password : p.password, epg_url: b.epg_url ?? p.epg_url, output: b.output ?? p.output,
    user_agent: b.user_agent ?? p.user_agent, enabled: b.enabled === undefined ? p.enabled : (b.enabled ? 1 : 0),
  };
  db.prepare('UPDATE providers SET name=?, url=?, username=?, password=?, epg_url=?, output=?, user_agent=?, enabled=? WHERE id=?')
    .run(next.name, next.url, next.username, next.password, next.epg_url || null, next.output, next.user_agent || null, next.enabled, p.id);
  res.json(publicProvider(iptv.getProvider(p.id)));
}));

r.delete('/providers/:id', (req, res) => {
  const id = Number(req.params.id);
  for (const t of ['programmes', 'favorites', 'history', 'vod_progress']) db.prepare(`DELETE FROM ${t} WHERE provider_id = ?`).run(id);
  db.prepare('DELETE FROM providers WHERE id = ?').run(id);
  res.json({ ok: true });
});

r.post('/providers/:id/sync', ah(async (req, res) => {
  const result = await iptv.syncProvider(req.params.id);
  epg.syncEpg(req.params.id).catch(e => console.error('[epg]', e.message));
  res.json(result);
}));
r.post('/providers/:id/epg', ah(async (req, res) => res.json(await epg.syncEpg(req.params.id))));

// ---------- Categories & channels ----------

r.get('/providers/:id/categories', (req, res) => {
  const kind = req.query.kind || 'live';
  const all = req.query.all === '1';
  const table = kind === 'live' ? 'channels' : 'vod';
  const kindFilter = kind === 'live' ? '' : `AND t.kind = '${kind === 'series' ? 'series' : 'movie'}'`;
  const rows = db.prepare(`SELECT c.id, c.name, c.hidden, c.sort,
      (SELECT COUNT(*) FROM ${table} t WHERE t.provider_id = c.provider_id AND t.category_id = c.id ${kindFilter}) AS count
    FROM categories c WHERE c.provider_id = ? AND c.kind = ? ${all ? '' : 'AND c.hidden = 0'} ORDER BY c.sort`).all(Number(req.params.id), kind);
  res.json(rows);
});

r.put('/categories/:pid/:kind/:id', (req, res) => {
  db.prepare('UPDATE categories SET hidden = ? WHERE provider_id = ? AND kind = ? AND id = ?')
    .run(req.body.hidden ? 1 : 0, Number(req.params.pid), req.params.kind, req.params.id);
  res.json({ ok: true });
});

function channelRows(pid, { category, q, fav, includeHidden } = {}) {
  const where = ['c.provider_id = ?'];
  const args = [Number(pid)];
  if (!includeHidden) where.push('c.hidden = 0', `(c.category_id IS NULL OR c.category_id NOT IN (SELECT id FROM categories WHERE provider_id = c.provider_id AND kind = 'live' AND hidden = 1))`);
  if (category) { where.push('c.category_id = ?'); args.push(String(category)); }
  if (q) { where.push('c.name LIKE ?'); args.push(`%${q}%`); }
  if (fav) where.push('f.channel_id IS NOT NULL');
  return db.prepare(`SELECT c.id, c.num, c.name, c.logo, c.category_id, c.epg_id, c.archive, c.archive_days, c.hidden,
      CASE WHEN f.channel_id IS NULL THEN 0 ELSE 1 END AS fav
    FROM channels c LEFT JOIN favorites f ON f.provider_id = c.provider_id AND f.channel_id = c.id
    WHERE ${where.join(' AND ')} ORDER BY ${fav ? 'f.position, ' : ''}c.num, c.name LIMIT 5000`).all(...args);
}

function withNowNext(pid, rows) {
  const nn = epg.nowNext(pid, rows.length < 900 ? rows.map(r => r.id) : null);
  return rows.map(r => ({ ...r, now: nn[r.id]?.[0] || null, next: nn[r.id]?.[1] || null }));
}

r.get('/providers/:id/channels', (req, res) => {
  const { category, q, all } = req.query;
  const special = category === '__fav' ? { fav: true } : category === '__all' || !category ? {} : { category };
  if (category === '__recent') {
    const rows = db.prepare(`SELECT c.id, c.num, c.name, c.logo, c.category_id, c.epg_id, c.archive, c.archive_days,
        CASE WHEN f.channel_id IS NULL THEN 0 ELSE 1 END AS fav
      FROM history h JOIN channels c ON c.provider_id = h.provider_id AND c.id = h.channel_id
      LEFT JOIN favorites f ON f.provider_id = c.provider_id AND f.channel_id = c.id
      WHERE h.provider_id = ? ORDER BY h.watched_at DESC LIMIT 50`).all(Number(req.params.id));
    return res.json(withNowNext(req.params.id, rows));
  }
  const rows = channelRows(req.params.id, { ...special, q, includeHidden: all === '1' });
  res.json(req.query.epg === '0' ? rows : withNowNext(req.params.id, rows));
});

r.put('/channels/:pid/:id', (req, res) => {
  db.prepare('UPDATE channels SET hidden = ? WHERE provider_id = ? AND id = ?').run(req.body.hidden ? 1 : 0, Number(req.params.pid), req.params.id);
  res.json({ ok: true });
});

// ---------- Favorites & history ----------

r.get('/favorites', (req, res) => {
  const rows = db.prepare(`SELECT f.provider_id, c.id, c.num, c.name, c.logo, c.archive, c.epg_id, 1 AS fav
    FROM favorites f JOIN channels c ON c.provider_id = f.provider_id AND c.id = f.channel_id ORDER BY f.position, c.num`).all();
  const byP = {};
  for (const r2 of rows) (byP[r2.provider_id] ||= []).push(r2);
  const out = [];
  for (const [pid, list] of Object.entries(byP)) out.push(...withNowNext(pid, list));
  res.json(out);
});
r.post('/favorites', (req, res) => {
  const { providerId, channelId } = req.body;
  const max = db.prepare('SELECT COALESCE(MAX(position), 0) AS m FROM favorites').get().m;
  db.prepare('INSERT OR IGNORE INTO favorites(provider_id, channel_id, position) VALUES (?,?,?)').run(Number(providerId), String(channelId), max + 1);
  res.json({ ok: true });
});
r.delete('/favorites/:pid/:cid', (req, res) => {
  db.prepare('DELETE FROM favorites WHERE provider_id = ? AND channel_id = ?').run(Number(req.params.pid), req.params.cid);
  res.json({ ok: true });
});
r.put('/favorites/order', (req, res) => {
  const upd = db.prepare('UPDATE favorites SET position = ? WHERE provider_id = ? AND channel_id = ?');
  (req.body.order || []).forEach((f, i) => upd.run(i, Number(f.providerId), String(f.channelId)));
  res.json({ ok: true });
});

r.get('/history', (req, res) => {
  const rows = db.prepare(`SELECT h.provider_id, c.id, c.num, c.name, c.logo, c.archive, h.watched_at
    FROM history h JOIN channels c ON c.provider_id = h.provider_id AND c.id = h.channel_id ORDER BY h.watched_at DESC LIMIT 30`).all();
  const nn = {};
  for (const pid of new Set(rows.map(r2 => r2.provider_id))) nn[pid] = epg.nowNext(pid, rows.filter(r2 => r2.provider_id === pid).map(r2 => r2.id));
  res.json(rows.map(r2 => ({ ...r2, now: nn[r2.provider_id]?.[r2.id]?.[0] || null, next: nn[r2.provider_id]?.[r2.id]?.[1] || null })));
});

// ---------- EPG ----------

r.get('/guide/:pid', (req, res) => {
  const pid = req.params.pid;
  const from = Number(req.query.from) || now() - 3600;
  const to = Number(req.query.to) || from + 6 * 3600;
  const { category } = req.query;
  let channels;
  if (category === '__fav') channels = channelRows(pid, { fav: true });
  else if (category && category !== '__all') channels = channelRows(pid, { category });
  else channels = channelRows(pid, {});
  const offset = Number(req.query.offset) || 0, limit = Math.min(Number(req.query.limit) || 100, 500);
  const page = channels.slice(offset, offset + limit);
  const programmes = epg.guide(pid, page.map(c => c.id), from, to);
  res.json({ total: channels.length, offset, channels: page.map(c => ({ ...c, programmes: programmes[c.id] || [] })) });
});

r.get('/epg/:pid/:cid', ah(async (req, res) => {
  const from = Number(req.query.from) || now() - 86400 * 7;
  const to = Number(req.query.to) || now() + 86400 * 2;
  res.json(await epg.channelEpg(req.params.pid, req.params.cid, from, to));
}));

// ---------- Playback ----------

function liveMode(requested) {
  const m = requested || getConfig('transcode').liveMode || 'auto';
  return m;
}

async function hlsFor(input, { isLive, mode, label, startOffset, userAgent }) {
  const s = await startSession({ input, isLive, mode, label, startOffset, userAgent });
  return { type: 'hls', url: s.url, sessionId: s.id, method: mode === 'transcode' ? `transcode (${s.hwAccel})` : 'remux', startOffset: s.startOffset };
}

r.post('/play/live', ah(async (req, res) => {
  const { providerId, channelId } = req.body;
  const p = iptv.getProvider(providerId);
  const ch = db.prepare('SELECT * FROM channels WHERE provider_id = ? AND id = ?').get(p.id, String(channelId));
  if (!ch) return res.status(404).json({ error: 'Channel not found' });
  db.prepare('INSERT INTO history(provider_id, channel_id, watched_at) VALUES (?,?,unixepoch()) ON CONFLICT DO UPDATE SET watched_at = unixepoch()').run(p.id, ch.id);
  const mode = liveMode(req.body.mode);
  const info = { channel: { ...ch, providerId: p.id }, live: true };
  if (mode === 'remux' || mode === 'transcode') {
    return res.json({ ...info, ...(await hlsFor(iptv.liveUrl(p, ch, 'ts'), { isLive: true, mode, label: `Live: ${ch.name}`, userAgent: iptv.userAgent(p) })) });
  }
  const url = iptv.liveUrl(p, ch);
  if (/\.m3u8(\?|$)/i.test(url)) return res.json({ ...info, type: 'hls', url: proxied(url, iptv.userAgent(p)), method: 'direct-hls' });
  res.json({ ...info, type: 'mpegts', url: `/api/stream/live/${p.id}/${encodeURIComponent(ch.id)}`, method: 'direct' });
}));

r.post('/play/catchup', ah(async (req, res) => {
  const { providerId, channelId, start, stop } = req.body;
  const p = iptv.getProvider(providerId);
  const ch = db.prepare('SELECT * FROM channels WHERE provider_id = ? AND id = ?').get(p.id, String(channelId));
  if (!ch) return res.status(404).json({ error: 'Channel not found' });
  const duration = Math.max(1, (Number(stop) - Number(start)) / 60);
  const url = iptv.catchupUrl(p, ch, Number(start), duration);
  const mode = req.body.mode === 'transcode' ? 'transcode' : 'remux';
  res.json({ channel: ch, catchup: true, duration: duration * 60, restartOnSeek: true,
    ...(await hlsFor(url, { isLive: false, mode, label: `Catch-up: ${ch.name}`, startOffset: Number(req.body.offset) || 0, userAgent: iptv.userAgent(p) })) });
}));

r.post('/play/vod', ah(async (req, res) => {
  const { providerId, kind = 'movie', id, offset = 0 } = req.body;
  const p = iptv.getProvider(providerId);
  let ext = req.body.ext;
  if (!ext && kind === 'movie') ext = db.prepare("SELECT ext FROM vod WHERE provider_id = ? AND kind = 'movie' AND id = ?").get(p.id, String(id))?.ext;
  ext = (ext || 'mp4').toLowerCase();
  const url = kind === 'episode' ? iptv.episodeUrl(p, id, ext) : iptv.movieUrl(p, id, ext);
  const mode = req.body.mode || 'auto';
  if ((mode === 'auto' || mode === 'direct') && ['mp4', 'm4v', 'webm', 'mov'].includes(ext)) {
    return res.json({ type: 'file', url: proxied(url, iptv.userAgent(p)), method: 'direct', startOffset: 0, seekTo: Number(offset) || 0 });
  }
  if (ext === 'm3u8') return res.json({ type: 'hls', url: proxied(url, iptv.userAgent(p)), method: 'direct-hls', startOffset: 0, seekTo: Number(offset) || 0 });
  const m = mode === 'transcode' ? 'transcode' : 'remux';
  res.json({ restartOnSeek: true, ...(await hlsFor(url, { isLive: false, mode: m, label: `VOD ${id}`, startOffset: Number(offset) || 0, userAgent: iptv.userAgent(p) })) });
}));

// ---------- VOD & Series ----------

r.get('/vod/:pid', (req, res) => {
  const { kind = 'movie', category, q, sort = 'added', start = 0, size = 60 } = req.query;
  const where = ['v.provider_id = ?', 'v.kind = ?', `(v.category_id IS NULL OR v.category_id NOT IN (SELECT id FROM categories WHERE provider_id = v.provider_id AND kind = ? AND hidden = 1))`];
  const args = [Number(req.params.pid), kind, kind === 'series' ? 'series' : 'vod'];
  if (category && category !== '__all') { where.push('v.category_id = ?'); args.push(String(category)); }
  if (q) { where.push('v.name LIKE ?'); args.push(`%${q}%`); }
  const order = { added: 'v.added DESC', name: 'v.name COLLATE NOCASE', rating: 'v.rating DESC', year: 'v.year DESC' }[sort] || 'v.added DESC';
  const total = db.prepare(`SELECT COUNT(*) AS n FROM vod v WHERE ${where.join(' AND ')}`).get(...args).n;
  const items = db.prepare(`SELECT v.*, pr.position, pr.duration FROM vod v
    LEFT JOIN vod_progress pr ON pr.provider_id = v.provider_id AND pr.kind = v.kind AND pr.id = v.id
    WHERE ${where.join(' AND ')} ORDER BY ${order} LIMIT ? OFFSET ?`).all(...args, Number(size), Number(start));
  res.json({ total, items });
});

r.get('/vod/:pid/movie/:id', ah(async (req, res) => {
  const p = iptv.getProvider(req.params.pid);
  const row = db.prepare("SELECT * FROM vod WHERE provider_id = ? AND kind = 'movie' AND id = ?").get(p.id, req.params.id);
  const info = await iptv.vodInfo(p, req.params.id).catch(() => ({}));
  const prog = db.prepare("SELECT position, duration FROM vod_progress WHERE provider_id = ? AND kind = 'movie' AND id = ?").get(p.id, req.params.id);
  res.json({ ...row, info: info?.info || {}, movie: info?.movie_data || {}, progress: prog || null });
}));

r.get('/vod/:pid/series/:id', ah(async (req, res) => {
  const p = iptv.getProvider(req.params.pid);
  const row = db.prepare("SELECT * FROM vod WHERE provider_id = ? AND kind = 'series' AND id = ?").get(p.id, req.params.id);
  const info = await iptv.seriesInfo(p, req.params.id);
  const progress = Object.fromEntries(db.prepare("SELECT id, position, duration FROM vod_progress WHERE provider_id = ? AND kind = 'episode' AND parent_id = ?")
    .all(p.id, req.params.id).map(x => [x.id, x]));
  const episodes = info?.episodes || {};
  const seasons = Object.keys(episodes).sort((a, b) => Number(a) - Number(b)).map(n => ({
    season: n,
    episodes: (episodes[n] || []).map(e => ({
      id: String(e.id), num: e.episode_num, title: e.title, ext: e.container_extension, plot: e.info?.plot,
      image: e.info?.movie_image, duration: e.info?.duration_secs, rating: e.info?.rating, progress: progress[String(e.id)] || null,
    })),
  }));
  res.json({ ...row, info: info?.info || {}, seasons });
}));

r.post('/progress', (req, res) => {
  const { providerId, kind, id, position, duration, title, poster, parentId } = req.body;
  if (duration && position / duration > 0.93) {
    db.prepare('INSERT INTO vod_progress(provider_id, kind, id, position, duration, title, poster, parent_id, updated_at) VALUES (?,?,?,?,?,?,?,?,unixepoch()) ON CONFLICT DO UPDATE SET position = -1, updated_at = unixepoch()')
      .run(Number(providerId), kind, String(id), -1, duration, title, poster, parentId ? String(parentId) : null);
  } else {
    db.prepare(`INSERT INTO vod_progress(provider_id, kind, id, position, duration, title, poster, parent_id, updated_at) VALUES (?,?,?,?,?,?,?,?,unixepoch())
      ON CONFLICT DO UPDATE SET position = excluded.position, duration = excluded.duration, title = COALESCE(excluded.title, title), poster = COALESCE(excluded.poster, poster), updated_at = unixepoch()`)
      .run(Number(providerId), kind, String(id), position, duration, title || null, poster || null, parentId ? String(parentId) : null);
  }
  res.json({ ok: true });
});

r.get('/progress', (req, res) => {
  res.json(db.prepare('SELECT * FROM vod_progress WHERE position > 30 ORDER BY updated_at DESC LIMIT 30').all());
});

// ---------- Search ----------

r.get('/search', (req, res) => {
  const q = String(req.query.q || '').trim();
  if (q.length < 2) return res.json({ channels: [], movies: [], series: [], programmes: [] });
  const like = `%${q}%`;
  const channels = db.prepare('SELECT provider_id, id, num, name, logo, archive FROM channels WHERE name LIKE ? AND hidden = 0 LIMIT 40').all(like);
  const movies = db.prepare("SELECT * FROM vod WHERE kind = 'movie' AND name LIKE ? LIMIT 40").all(like);
  const series = db.prepare("SELECT * FROM vod WHERE kind = 'series' AND name LIKE ? LIMIT 40").all(like);
  const programmes = db.prepare(`SELECT pr.*, c.id AS channel_id, c.name AS channel_name, c.logo, c.archive FROM programmes pr
    JOIN channels c ON c.provider_id = pr.provider_id AND c.epg_id = pr.channel
    WHERE pr.title LIKE ? AND pr.stop > ? GROUP BY pr.provider_id, pr.channel, pr.start ORDER BY pr.start LIMIT 40`).all(like, now() - 86400 * 3);
  res.json({ channels, movies, series, programmes });
});

// ---------- DVR ----------

r.get('/recordings', (req, res) => res.json(dvr.list()));
r.post('/recordings', (req, res) => {
  const { providerId, channelId, title, descr } = req.body;
  let { start, stop } = req.body;
  start = Number(start) || now();
  stop = Number(stop) || start + 3600;
  if (stop <= now()) return res.status(400).json({ error: 'That programme has already ended — use catch-up instead.' });
  res.json({ id: dvr.schedule({ providerId, channelId, title, descr, start, stop }) });
});
r.post('/recordings/:id/cancel', (req, res) => { dvr.cancel(Number(req.params.id)); res.json({ ok: true }); });
r.delete('/recordings/:id', (req, res) => { dvr.remove(Number(req.params.id)); res.json({ ok: true }); });
r.post('/play/recording/:id', ah(async (req, res) => {
  const rec = db.prepare('SELECT * FROM recordings WHERE id = ?').get(Number(req.params.id));
  if (!rec?.file) return res.status(404).json({ error: 'Recording not found' });
  const mode = req.body?.mode === 'transcode' ? 'transcode' : 'remux';
  res.json({ restartOnSeek: rec.status !== 'recording', duration: rec.stop - rec.start, title: rec.title,
    ...(await hlsFor(rec.file, { isLive: false, mode, label: `Recording: ${rec.title}`, startOffset: Number(req.body?.offset) || 0 })) });
}));

// ---------- Reminders ----------

r.get('/reminders', (req, res) => res.json(db.prepare('SELECT * FROM reminders WHERE start > ? ORDER BY start').all(now() - 600)));
r.post('/reminders', (req, res) => {
  const { providerId, channelId, title, start } = req.body;
  const ch = db.prepare('SELECT name FROM channels WHERE provider_id = ? AND id = ?').get(Number(providerId), String(channelId));
  const r2 = db.prepare('INSERT INTO reminders(provider_id, channel_id, channel_name, title, start) VALUES (?,?,?,?,?)')
    .run(Number(providerId), String(channelId), ch?.name || '', title, Number(start));
  res.json({ id: Number(r2.lastInsertRowid) });
});
r.delete('/reminders/:id', (req, res) => { db.prepare('DELETE FROM reminders WHERE id = ?').run(Number(req.params.id)); res.json({ ok: true }); });

export default r;

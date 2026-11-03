import crypto from 'node:crypto';
import { db, tx } from '../db.js';
import { getConfig } from './settings.js';

const DEFAULT_UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';

export function userAgent(provider) {
  return provider?.user_agent || getConfig('iptv').userAgent || DEFAULT_UA;
}

export function getProvider(id) {
  const p = db.prepare('SELECT * FROM providers WHERE id = ?').get(Number(id));
  if (!p) { const e = new Error('Provider not found'); e.status = 404; throw e; }
  return p;
}
export function listProviders() {
  return db.prepare('SELECT * FROM providers ORDER BY id').all();
}

export function baseUrl(p) {
  let u = (p.url || '').trim().replace(/\/+$/, '');
  u = u.replace(/\/(player_api\.php|get\.php|xmltv\.php).*$/i, '');
  if (!/^https?:\/\//i.test(u)) u = 'http://' + u;
  return u;
}

async function xtream(p, action, params = {}) {
  const u = new URL(baseUrl(p) + '/player_api.php');
  u.searchParams.set('username', p.username);
  u.searchParams.set('password', p.password);
  if (action) u.searchParams.set('action', action);
  for (const [k, v] of Object.entries(params)) u.searchParams.set(k, v);
  const res = await fetch(u, { headers: { 'User-Agent': userAgent(p) }, signal: AbortSignal.timeout(60000) });
  if (!res.ok) { const e = new Error(`Provider responded ${res.status}`); e.status = 502; throw e; }
  const text = await res.text();
  try { return JSON.parse(text); } catch { const e = new Error('Provider returned invalid JSON (check credentials/URL)'); e.status = 502; throw e; }
}

export async function testXtream(p) {
  const info = await xtream(p, null);
  if (!info?.user_info || info.user_info.auth === 0) { const e = new Error('Login failed: invalid username or password'); e.status = 401; throw e; }
  return info;
}

// ---------------- Stream URLs ----------------

export function liveUrl(p, ch, ext) {
  if (p.type === 'm3u') return ch.url;
  return `${baseUrl(p)}/live/${encodeURIComponent(p.username)}/${encodeURIComponent(p.password)}/${ch.id}.${ext || p.output || 'ts'}`;
}
export function movieUrl(p, id, ext = 'mp4') {
  return `${baseUrl(p)}/movie/${encodeURIComponent(p.username)}/${encodeURIComponent(p.password)}/${id}.${ext}`;
}
export function episodeUrl(p, id, ext = 'mp4') {
  return `${baseUrl(p)}/series/${encodeURIComponent(p.username)}/${encodeURIComponent(p.password)}/${id}.${ext}`;
}
/** Catch-up (timeshift) URL. start = unix seconds, duration in minutes. */
export function catchupUrl(p, ch, start, durationMin) {
  const off = Number(getConfig('iptv').timeshiftOffsetMinutes || 0) * 60;
  const d = new Date((start + off) * 1000);
  const pad = n => String(n).padStart(2, '0');
  // Xtream servers expect server-local time; most use UTC-equivalent formatting of the given timestamp.
  const ts = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}:${pad(d.getUTCHours())}-${pad(d.getUTCMinutes())}`;
  if (p.type === 'm3u') {
    // Common "append" style catch-up for M3U sources
    const sep = ch.url.includes('?') ? '&' : '?';
    return `${ch.url}${sep}utc=${start}&lutc=${Math.floor(Date.now() / 1000)}`;
  }
  return `${baseUrl(p)}/timeshift/${encodeURIComponent(p.username)}/${encodeURIComponent(p.password)}/${Math.ceil(durationMin)}/${ts}/${ch.id}.ts`;
}

export function epgUrl(p) {
  if (p.epg_url) return p.epg_url;
  if (p.type === 'xtream') return `${baseUrl(p)}/xmltv.php?username=${encodeURIComponent(p.username)}&password=${encodeURIComponent(p.password)}`;
  return null;
}

// ---------------- Sync ----------------

function preserveHidden(providerId) {
  const hiddenCats = new Set(db.prepare("SELECT kind || ':' || id AS k FROM categories WHERE provider_id = ? AND hidden = 1").all(providerId).map(r => r.k));
  const hiddenCh = new Set(db.prepare('SELECT id FROM channels WHERE provider_id = ? AND hidden = 1').all(providerId).map(r => r.id));
  return { hiddenCats, hiddenCh };
}

export async function syncProvider(id) {
  const p = getProvider(id);
  if (p.type === 'm3u') return syncM3u(p);
  const info = await testXtream(p);
  const [liveCats, live, vodCats, vod, serCats, series] = await Promise.all([
    xtream(p, 'get_live_categories'), xtream(p, 'get_live_streams'),
    xtream(p, 'get_vod_categories').catch(() => []), xtream(p, 'get_vod_streams').catch(() => []),
    xtream(p, 'get_series_categories').catch(() => []), xtream(p, 'get_series').catch(() => []),
  ]);
  const { hiddenCats, hiddenCh } = preserveHidden(p.id);
  tx(() => {
    db.prepare('DELETE FROM categories WHERE provider_id = ?').run(p.id);
    db.prepare('DELETE FROM channels WHERE provider_id = ?').run(p.id);
    db.prepare('DELETE FROM vod WHERE provider_id = ?').run(p.id);
    const insCat = db.prepare('INSERT OR REPLACE INTO categories(provider_id, kind, id, name, sort, hidden) VALUES (?,?,?,?,?,?)');
    const cats = (kind, list) => (Array.isArray(list) ? list : []).forEach((c, i) =>
      insCat.run(p.id, kind, String(c.category_id), c.category_name || 'Unknown', i, hiddenCats.has(`${kind}:${c.category_id}`) ? 1 : 0));
    cats('live', liveCats); cats('vod', vodCats); cats('series', serCats);

    const insCh = db.prepare('INSERT OR REPLACE INTO channels(provider_id, id, num, name, logo, category_id, epg_id, archive, archive_days, hidden) VALUES (?,?,?,?,?,?,?,?,?,?)');
    (Array.isArray(live) ? live : []).forEach((s, i) => insCh.run(p.id, String(s.stream_id), Number(s.num) || i + 1, s.name || 'Channel', s.stream_icon || null,
      String(s.category_id ?? ''), s.epg_channel_id || null, Number(s.tv_archive) ? 1 : 0, Number(s.tv_archive_duration) || 0, hiddenCh.has(String(s.stream_id)) ? 1 : 0));

    const insVod = db.prepare('INSERT OR REPLACE INTO vod(provider_id, kind, id, name, poster, category_id, rating, year, ext, added) VALUES (?,?,?,?,?,?,?,?,?,?)');
    (Array.isArray(vod) ? vod : []).forEach(v => insVod.run(p.id, 'movie', String(v.stream_id), v.name || 'Movie', v.stream_icon || null, String(v.category_id ?? ''),
      Number(v.rating) || null, v.year ? String(v.year) : null, v.container_extension || 'mp4', Number(v.added) || null));
    (Array.isArray(series) ? series : []).forEach(v => insVod.run(p.id, 'series', String(v.series_id), v.name || 'Series', v.cover || null, String(v.category_id ?? ''),
      Number(v.rating) || null, (v.releaseDate || v.release_date || '').slice(0, 4) || null, null, Number(v.last_modified) || null));

    db.prepare('UPDATE providers SET account_info = ?, last_sync = unixepoch() WHERE id = ?').run(JSON.stringify({ user_info: info.user_info, server_info: info.server_info }), p.id);
  });
  return { live: live.length || 0, vod: vod.length || 0, series: series.length || 0 };
}

function parseM3u(text) {
  const lines = text.split(/\r?\n/);
  const out = [];
  let cur = null;
  const attr = (s, name) => { const m = s.match(new RegExp(`${name}="([^"]*)"`, 'i')); return m ? m[1] : null; };
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTINF')) {
      const comma = line.lastIndexOf(',');
      cur = {
        name: (comma >= 0 ? line.slice(comma + 1) : '').trim() || attr(line, 'tvg-name') || 'Channel',
        tvgId: attr(line, 'tvg-id'), logo: attr(line, 'tvg-logo'), group: attr(line, 'group-title') || 'Uncategorized',
        chno: attr(line, 'tvg-chno') || attr(line, 'channel-number'),
        catchup: attr(line, 'catchup') || attr(line, 'catchup-type'), catchupDays: attr(line, 'catchup-days') || attr(line, 'tvg-rec'),
      };
    } else if (line.startsWith('#EXTGRP:') && cur) {
      cur.group = line.slice(8).trim() || cur.group;
    } else if (!line.startsWith('#') && cur) {
      cur.url = line; out.push(cur); cur = null;
    }
  }
  return out;
}

async function syncM3u(p) {
  const res = await fetch(p.url, { headers: { 'User-Agent': userAgent(p) }, signal: AbortSignal.timeout(120000) });
  if (!res.ok) { const e = new Error('M3U download failed: ' + res.status); e.status = 502; throw e; }
  const items = parseM3u(await res.text());
  if (!items.length) { const e = new Error('No channels found in playlist'); e.status = 400; throw e; }
  const { hiddenCats, hiddenCh } = preserveHidden(p.id);
  const groups = [...new Set(items.map(i => i.group))];
  const gid = g => crypto.createHash('md5').update(g).digest('hex').slice(0, 10);
  tx(() => {
    db.prepare('DELETE FROM categories WHERE provider_id = ?').run(p.id);
    db.prepare('DELETE FROM channels WHERE provider_id = ?').run(p.id);
    const insCat = db.prepare('INSERT OR REPLACE INTO categories(provider_id, kind, id, name, sort, hidden) VALUES (?,?,?,?,?,?)');
    groups.forEach((g, i) => insCat.run(p.id, 'live', gid(g), g, i, hiddenCats.has(`live:${gid(g)}`) ? 1 : 0));
    const insCh = db.prepare('INSERT OR REPLACE INTO channels(provider_id, id, num, name, logo, category_id, epg_id, url, archive, archive_days, hidden) VALUES (?,?,?,?,?,?,?,?,?,?,?)');
    items.forEach((c, i) => {
      const id = crypto.createHash('md5').update(c.url).digest('hex').slice(0, 16);
      insCh.run(p.id, id, Number(c.chno) || i + 1, c.name, c.logo, gid(c.group), c.tvgId || c.name, c.url,
        c.catchup ? 1 : 0, Number(c.catchupDays) || 0, hiddenCh.has(id) ? 1 : 0);
    });
    db.prepare('UPDATE providers SET last_sync = unixepoch() WHERE id = ?').run(p.id);
  });
  return { live: items.length, vod: 0, series: 0 };
}

// ---------------- On-demand info ----------------

const infoCache = new Map();
async function cached(key, ttlMs, fn) {
  const hit = infoCache.get(key);
  if (hit && hit.exp > Date.now()) return hit.val;
  const val = await fn();
  infoCache.set(key, { val, exp: Date.now() + ttlMs });
  if (infoCache.size > 500) infoCache.delete(infoCache.keys().next().value);
  return val;
}

export function vodInfo(p, id) {
  return cached(`vod:${p.id}:${id}`, 3600e3, () => xtream(p, 'get_vod_info', { vod_id: id }));
}
export function seriesInfo(p, id) {
  return cached(`series:${p.id}:${id}`, 3600e3, () => xtream(p, 'get_series_info', { series_id: id }));
}

/** Fallback EPG directly from Xtream for one channel (used when XMLTV has no data). */
export async function shortEpg(p, streamId, limit = 10) {
  if (p.type !== 'xtream') return [];
  const data = await cached(`sepg:${p.id}:${streamId}`, 600e3, () => xtream(p, 'get_short_epg', { stream_id: streamId, limit }));
  const dec = s => { try { return Buffer.from(s || '', 'base64').toString('utf8'); } catch { return s; } };
  return (data?.epg_listings || []).map(e => ({
    start: Number(e.start_timestamp), stop: Number(e.stop_timestamp), title: dec(e.title), descr: dec(e.description),
  }));
}

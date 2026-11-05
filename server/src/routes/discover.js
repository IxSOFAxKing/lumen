// Discover & requests through Overseerr (or Jellyseerr — same API).
// The server keeps the Overseerr address and API key (set once in the web Settings), so every Lumen app can
// browse and request without its own setup. Requests are made as the matching Overseerr user when the
// current Plex Home user has one, otherwise as the API key's owner.
import { Router } from 'express';
import { getSetting, setSetting } from '../db.js';
import { ah } from '../util.js';
import { selectedUser } from './plex.js';

const r = Router();
const conf = () => getSetting('overseerr', {}) || {};
const IMG = 'https://image.tmdb.org/t/p/';

async function os(path, { method = 'GET', body, user, params } = {}) {
  const c = conf();
  if (!c.url || !c.apiKey) { const e = new Error('Overseerr is not connected. Add it in Settings → Discover.'); e.status = 400; throw e; }
  const u = new URL(c.url.replace(/\/+$/, '') + '/api/v1' + path);
  for (const [k, v] of Object.entries(params || {})) if (v !== undefined && v !== null && v !== '') u.searchParams.set(k, v);
  const headers = { 'X-Api-Key': c.apiKey, Accept: 'application/json' };
  if (body) headers['Content-Type'] = 'application/json';
  if (user) headers['X-Api-User'] = String(user);
  const res = await fetch(u, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000) });
  const text = await res.text();
  let j = null; try { j = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  if (!res.ok) {
    const e = new Error(j?.message || `Overseerr responded ${res.status}`);
    e.status = res.status === 401 || res.status === 403 ? 502 : (res.status >= 500 ? 502 : res.status);
    throw e;
  }
  return j;
}

// Small cache for browse lists and details (Overseerr itself is quick, TMDB less so).
const cache = new Map();
async function cached(key, ttl, fn) {
  const hit = cache.get(key);
  if (hit && Date.now() - hit.t < ttl) return hit.v;
  const v = await fn();
  cache.set(key, { v, t: Date.now() });
  if (cache.size > 300) cache.delete(cache.keys().next().value);
  return v;
}
const clearCache = () => cache.clear();

const STATUS = { 2: 'pending', 3: 'processing', 4: 'partial', 5: 'available' };
function shape(m) {
  if (!m || (m.mediaType !== 'movie' && m.mediaType !== 'tv')) return null;
  const date = m.releaseDate || m.firstAirDate || '';
  return {
    id: m.id, type: m.mediaType, title: m.title || m.name || m.originalTitle || m.originalName || '',
    year: date ? Number(date.slice(0, 4)) || null : null, date: date || null,
    overview: m.overview || '', rating: m.voteAverage ? Math.round(m.voteAverage * 10) / 10 : null,
    poster: m.posterPath ? `${IMG}w342${m.posterPath}` : null,
    backdrop: m.backdropPath ? `${IMG}w1280${m.backdropPath}` : null,
    status: STATUS[m.mediaInfo?.status] || 'none',
    ratingKey: m.mediaInfo?.ratingKey || null,
  };
}
const list = j => ({ page: j?.page || 1, totalPages: j?.totalPages || 1, results: (j?.results || []).map(shape).filter(Boolean) });

/** Overseerr user matching the current Plex Home user (by Plex username / display name). */
async function osUser(req) {
  const u = selectedUser(req);
  if (!u?.title) return null;
  const users = await cached('users', 10 * 60000, () => os('/user', { params: { take: 200 } })).catch(() => null);
  const name = u.title.toLowerCase();
  return (users?.results || []).find(x => [x.plexUsername, x.displayName, x.username].some(n => n && n.toLowerCase() === name))?.id || null;
}

// ---------- configuration (saved from the web Settings, shared by every app) ----------
r.get('/config', (req, res) => { const c = conf(); res.json({ url: c.url || '', configured: !!(c.url && c.apiKey), hasKey: !!c.apiKey }); });
r.put('/config', ah(async (req, res) => {
  const cur = conf();
  const url = String(req.body?.url ?? cur.url ?? '').trim().replace(/\/+$/, '');
  const apiKey = req.body?.apiKey ? String(req.body.apiKey).trim() : cur.apiKey;
  if (url && !/^https?:\/\//i.test(url)) return res.status(400).json({ error: 'The address must start with http:// or https://' });
  setSetting('overseerr', { url, apiKey: url ? apiKey : '' });
  clearCache();
  if (!url) return res.json({ ok: true, configured: false });
  try {
    const s = await os('/status');
    const me = await os('/auth/me').catch(() => null);
    res.json({ ok: true, configured: true, version: s?.version, user: me?.displayName || null });
  } catch (e) { res.status(400).json({ error: `Saved, but Lumen couldn't reach Overseerr: ${e.message}` }); }
}));

// ---------- browse ----------
const LISTS = {
  trending: '/discover/trending', movies: '/discover/movies', tv: '/discover/tv',
  'movies-upcoming': '/discover/movies/upcoming', 'tv-upcoming': '/discover/tv/upcoming',
};
r.get('/list/:name', ah(async (req, res) => {
  const p = LISTS[req.params.name];
  if (!p) return res.status(404).json({ error: 'Unknown list' });
  const page = Number(req.query.page) || 1;
  res.json(await cached(`l:${p}:${page}`, 10 * 60000, async () => list(await os(p, { params: { page } }))));
}));

r.get('/search', ah(async (req, res) => {
  const q = String(req.query.q || '').trim();
  if (q.length < 2) return res.json({ results: [] });
  // Overseerr wants the query encoded once more than usual for some characters.
  res.json(list(await os('/search', { params: { query: q, page: Number(req.query.page) || 1 } })));
}));

r.get('/:type/:id', ah(async (req, res) => {
  const { type, id } = req.params;
  if (type !== 'movie' && type !== 'tv') return res.status(404).json({ error: 'Not found' });
  const m = await cached(`d:${type}:${id}`, 2 * 60000, () => os(`/${type}/${encodeURIComponent(id)}`));
  const base = shape({ ...m, mediaType: type });
  res.json({
    ...base,
    tagline: m.tagline || null,
    runtime: m.runtime || m.episodeRunTime?.[0] || null,
    genres: (m.genres || []).map(g => g.name),
    certification: m.releases?.results?.find(x => x.iso_3166_1 === 'US')?.release_dates?.find(d => d.certification)?.certification
      || m.contentRatings?.results?.find(x => x.iso_3166_1 === 'US')?.rating || null,
    cast: (m.credits?.cast || []).slice(0, 12).map(c => ({ name: c.name, character: c.character, photo: c.profilePath ? `${IMG}w185${c.profilePath}` : null })),
    seasons: type === 'tv' ? (m.seasons || []).filter(s => s.seasonNumber > 0).map(s => ({
      number: s.seasonNumber, name: s.name, episodes: s.episodeCount,
      status: STATUS[(m.mediaInfo?.seasons || []).find(x => x.seasonNumber === s.seasonNumber)?.status] || 'none',
    })) : [],
    requests: (m.mediaInfo?.requests || []).map(q => ({ id: q.id, status: q.status, by: q.requestedBy?.displayName || null })),
    trailer: (m.relatedVideos || []).find(v => v.type === 'Trailer' && v.site === 'YouTube')?.url || null,
  });
}));

// ---------- requests ----------
r.post('/request', ah(async (req, res) => {
  const { type, id, seasons } = req.body || {};
  if ((type !== 'movie' && type !== 'tv') || !id) return res.status(400).json({ error: 'type and id are required' });
  const body = { mediaType: type, mediaId: Number(id), is4k: false };
  if (type === 'tv') body.seasons = Array.isArray(seasons) && seasons.length ? seasons.map(Number) : 'all';
  const user = await osUser(req);
  const out = await os('/request', { method: 'POST', body, user });
  clearCache();
  res.json({ ok: true, id: out?.id, status: out?.status, as: user ? 'you' : 'admin' });
}));

const REQ_STATUS = { 1: 'Waiting for approval', 2: 'Approved', 3: 'Declined' };
r.get('/requests', ah(async (req, res) => {
  const j = await os('/request', { params: { take: 40, skip: 0, sort: 'added', filter: 'all' } });
  const items = await Promise.all((j?.results || []).map(async q => {
    const type = q.media?.mediaType || q.type;
    const tmdb = q.media?.tmdbId;
    let d = null;
    if (tmdb && (type === 'movie' || type === 'tv')) d = await cached(`d:${type}:${tmdb}`, 10 * 60000, () => os(`/${type}/${tmdb}`)).catch(() => null);
    const s = d ? shape({ ...d, mediaType: type }) : { id: tmdb, type, title: `TMDB ${tmdb}` };
    return { ...s, requestId: q.id, requestStatus: REQ_STATUS[q.status] || 'Requested', requestedBy: q.requestedBy?.displayName || null, requestedAt: q.createdAt,
      mediaStatus: STATUS[q.media?.status] || 'none', seasons: (q.seasons || []).map(x => x.seasonNumber) };
  }));
  res.json({ results: items });
}));

export default r;

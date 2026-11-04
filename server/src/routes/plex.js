import { Router } from 'express';
import crypto from 'node:crypto';
import * as plex from '../services/plex.js';
import { getConfig } from '../services/settings.js';
import { startSession, stopSession } from '../services/transcoder.js';
import { getSetting, setSetting } from '../db.js';
import { ah, pipeFetch, srtToVtt } from '../util.js';
import { serveCachedImage } from '../services/imgcache.js';
import { cookies } from '../auth.js';
import { setDevicePlexUser } from '../services/devices.js';

const r = Router();
const MC = j => j?.MediaContainer || {};

// ---------- Plex Home user for this request ----------
// Each device (or browser) can pick its own Home user; otherwise the default user (or the owner) is used.
function selectedUser(req) {
  const c = plex.plexConf();
  const uuid = req.device?.plex_user || cookies(req).lumen_plex_user || c.defaultUser || null;
  const u = uuid && c.homeUsers?.[uuid];
  return u?.token ? { uuid, token: u.token, title: u.title } : null;
}
r.use((req, res, next) => {
  const u = selectedUser(req);
  return u ? plex.plexUserCtx.run(u, next) : next();
});

r.get('/users', ah(async (req, res) => {
  const c = plex.plexConf();
  if (!plex.isConfigured()) return res.status(400).json({ error: 'Plex is not connected' });
  const users = await plex.homeUsers();
  const cur = selectedUser(req)?.uuid || users.find(u => u.admin)?.uuid || null;
  res.json({ users: users.map(u => ({ ...u, thumb: u.thumb })), current: cur, default: c.defaultUser || users.find(u => u.admin)?.uuid || null });
}));

/** Switch this device/browser to a Home user (PIN checked by plex.tv). makeDefault also makes it everyone's default. */
r.post('/users/switch', ah(async (req, res) => {
  const { uuid, pin, makeDefault } = req.body || {};
  const users = await plex.homeUsers();
  const u = users.find(x => x.uuid === uuid || x.id === String(uuid));
  if (!u) return res.status(404).json({ error: 'User not found' });
  if (u.protected && !pin) return res.status(403).json({ error: 'This user needs a PIN', needsPin: true });
  const token = await plex.switchHomeUser(u, pin);
  const c = plex.plexConf();
  const homeUsers = { ...(c.homeUsers || {}), [u.uuid]: { title: u.title, thumb: u.thumb, admin: u.admin, token } };
  plex.saveServer({ homeUsers, ...(makeDefault ? { defaultUser: u.uuid } : {}) });
  if (req.device) setDevicePlexUser(req.device.id, u.uuid);
  else {
    const secure = req.secure || req.headers['x-forwarded-proto'] === 'https';
    res.setHeader('Set-Cookie', `lumen_plex_user=${encodeURIComponent(u.uuid)}; Path=/; SameSite=Lax; Max-Age=${60 * 60 * 24 * 365}${secure ? '; Secure' : ''}`);
  }
  res.json({ ok: true, user: { uuid: u.uuid, title: u.title, thumb: u.thumb } });
}));

/** Default Home user for devices that haven't picked one. The user must have been switched to (with its PIN) before. */
r.post('/users/default', ah(async (req, res) => {
  const { uuid } = req.body || {};
  const c = plex.plexConf();
  if (uuid && !c.homeUsers?.[uuid]) return res.status(400).json({ error: 'Switch to this user first (that checks its PIN), then make it the default.' });
  plex.saveServer({ defaultUser: uuid || '' });
  res.json({ ok: true });
}));

// ---------- Setup / auth ----------

let statusCache = null;
r.get('/status', ah(async (req, res) => {
  const c = plex.plexConf();
  if (!plex.isConfigured()) return res.json({ configured: false });
  if (statusCache && Date.now() - statusCache.t < 60000 && statusCache.url === c.url) return res.json(statusCache.v);
  let v;
  try {
    const info = await plex.testConnection(c.url, c.token);
    v = { configured: true, online: true, url: c.url, ...info };
  } catch (e) {
    v = { configured: true, online: false, url: c.url, serverName: c.serverName, error: e.message };
  }
  statusCache = { v, t: Date.now(), url: c.url };
  res.json(v);
}));

r.post('/pin', ah(async (req, res) => res.json(await plex.createPin())));

r.get('/pin/:id', ah(async (req, res) => {
  const token = await plex.checkPin(req.params.id);
  if (!token) return res.json({ authorized: false });
  const servers = await plex.listServers(token);
  res.json({ authorized: true, token, servers });
}));

r.post('/servers', ah(async (req, res) => res.json(await plex.listServers(req.body.token))));

r.post('/connect', ah(async (req, res) => {
  let { url, token, server } = req.body || {};
  const accountToken = token || null; // plex.tv account token (PIN sign-in): needed for Plex Home users
  if (server) {
    url = await plex.pickConnection(server);
    token = server.accessToken || token;
    if (!url) return res.status(502).json({ error: 'None of the server connections were reachable from Lumen.' });
  }
  if (!url || !token) return res.status(400).json({ error: 'url and token are required' });
  url = url.replace(/\/+$/, '');
  const info = await plex.testConnection(url, token);
  plex.saveServer({ url, token, serverName: info.name, machineId: info.machineId, ...(server && accountToken ? { accountToken } : {}), homeUsers: {}, defaultUser: '' });
  plex.clearPlexCache(); statusCache = null;
  res.json({ ok: true, url, ...info });
}));

r.post('/disconnect', (req, res) => { plex.saveServer({ url: '', token: '', serverName: '', machineId: '', accountToken: '', homeUsers: {}, defaultUser: '' }); plex.clearPlexCache(); statusCache = null; res.json({ ok: true }); });

// ---------- Browsing ----------

r.get('/home', ah(async (req, res) => {
  const [hubs, cont, playlists] = await Promise.all([
    plex.plexCached('/hubs', { params: { count: 20, includeEmptyHubs: 0, excludeFields: 'summary' } }).catch(() => null),
    plex.plexCached('/hubs/home/continueWatching', { params: { count: 20 } }).catch(() => null),
    plex.plexCached('/playlists', { params: { playlistType: 'video' } }).catch(() => null),
  ]);
  let list = MC(hubs).Hub || [];
  const cw = MC(cont).Hub?.[0];
  if (cw && !list.some(h => h.hubIdentifier === cw.hubIdentifier || h.hubIdentifier === 'home.continue')) list = [cw, ...list];

  // Newest movies and shows across all libraries (for the hero carousel and "New" rows).
  const libs = (MC(await plex.plexCached('/library/sections', {}, 300000).catch(() => null)).Directory || []).filter(l => l.type === 'movie' || l.type === 'show');
  const newest = async (lib) => {
    const type = lib.type === 'movie' ? 1 : 2;
    const sorts = lib.type === 'movie' ? ['addedAt:desc'] : ['episode.addedAt:desc', 'addedAt:desc'];
    for (const sort of sorts) {
      try {
        const j = await plex.plexCached(`/library/sections/${lib.key}/all`, { params: { type, sort }, headers: { 'X-Plex-Container-Start': '0', 'X-Plex-Container-Size': '24' } });
        return MC(j).Metadata || [];
      } catch { /* try next sort */ }
    }
    return [];
  };
  const lists = await Promise.all(libs.slice(0, 8).map(l => newest(l).then(items => ({ type: l.type, items }))));
  const recentMovies = lists.filter(l => l.type === 'movie').flatMap(l => l.items).sort((a, b) => (b.addedAt || 0) - (a.addedAt || 0)).slice(0, 30);
  const recentShows = lists.filter(l => l.type === 'show').flatMap(l => l.items).sort((a, b) => (b.updatedAt || b.addedAt || 0) - (a.updatedAt || a.addedAt || 0)).slice(0, 30);
  const featured = [];
  for (let i = 0; featured.length < 8 && (i < recentMovies.length || i < recentShows.length); i++) {
    for (const m of [recentMovies[i], recentShows[i]]) if (m && m.art && featured.length < 8) featured.push(m);
  }
  // Drop Plex's own "recently added" hubs: the rows above replace them.
  list = list.filter(h => !/recentlyadded|recently_added|home\.movies\.recent|home\.television\.recent/i.test(h.hubIdentifier || ''));
  res.json({ hubs: list, playlists: MC(playlists).Metadata || [], featured, recentMovies, recentShows });
}));

r.get('/libraries', ah(async (req, res) => {
  res.json(MC(await plex.plexCached('/library/sections', {}, 300000)).Directory || []);
}));

r.get('/library/:id/hubs', ah(async (req, res) => {
  const id = req.params.id;
  const [hubs, popular] = await Promise.all([
    plex.plexCached(`/hubs/sections/${id}`, { params: { count: 20, includeEmptyHubs: 0 } }),
    plex.plexCached(`/library/sections/${id}/all`, { params: { sort: 'viewCount:desc' }, headers: { 'X-Plex-Container-Start': '0', 'X-Plex-Container-Size': '20' } }).catch(() => null),
  ]);
  const list = MC(hubs).Hub || [];
  const pop = (MC(popular).Metadata || []).filter(m => (m.viewCount || m.viewedLeafCount) > 0);
  if (pop?.length) list.splice(Math.min(2, list.length), 0, { hubIdentifier: 'lumen.popular', title: 'Most Popular', Metadata: pop, size: pop.length });
  res.json(list);
}));

r.get('/library/:id/items', ah(async (req, res) => {
  const { sort = 'titleSort', start = 0, size = 60, type, unwatched, genre, year, contentRating, collection } = req.query;
  const params = { sort, includeCollections: 0 };
  if (type) params.type = type;
  if (unwatched === '1') params.unwatched = 1;
  if (genre) params.genre = genre;
  if (year) params.year = year;
  if (contentRating) params.contentRating = contentRating;
  if (collection) params.collection = collection;
  const j = await plex.plexCached(`/library/sections/${req.params.id}/all`, {
    params, headers: { 'X-Plex-Container-Start': String(start), 'X-Plex-Container-Size': String(size) },
  });
  const m = MC(j);
  res.json({ items: m.Metadata || [], total: m.totalSize ?? m.size ?? 0, start: Number(start) });
}));

r.get('/library/:id/filter/:name', ah(async (req, res) => {
  const name = req.params.name.replace(/[^a-zA-Z]/g, '');
  res.json(MC(await plex.plexCached(`/library/sections/${req.params.id}/${name}`, {}, 300000)).Directory || []);
}));

r.get('/library/:id/collections', ah(async (req, res) => {
  res.json(MC(await plex.plexCached(`/library/sections/${req.params.id}/collections`)).Metadata || []);
}));

r.get('/meta/:key', ah(async (req, res) => {
  const j = await plex.plexCached(`/library/metadata/${encodeURIComponent(req.params.key)}`, {
    params: { includeExtras: 1, includeOnDeck: 1, includeChildren: 1, includeReviews: 0, includeMarkers: 1, checkFiles: 0 },
  }, 15000);
  const item = MC(j).Metadata?.[0];
  if (!item) return res.status(404).json({ error: 'Not found' });
  res.json(item);
}));

r.get('/meta/:key/children', ah(async (req, res) => {
  res.json(MC(await plex.plexCached(`/library/metadata/${encodeURIComponent(req.params.key)}/children`, { params: { excludeAllLeaves: 1 } }, 20000)).Metadata || []);
}));
r.get('/meta/:key/leaves', ah(async (req, res) => {
  res.json(MC(await plex.plexCached(`/library/metadata/${encodeURIComponent(req.params.key)}/allLeaves`, {}, 20000)).Metadata || []);
}));
r.get('/meta/:key/related', ah(async (req, res) => {
  const j = await plex.plexCached(`/hubs/metadata/${encodeURIComponent(req.params.key)}/related`, { params: { count: 20 } }, 600000).catch(() => null);
  res.json(MC(j).Hub || []);
}));
r.get('/collection/:key/items', ah(async (req, res) => {
  res.json(MC(await plex.plexCached(`/library/collections/${encodeURIComponent(req.params.key)}/children`)).Metadata || []);
}));

r.get('/playlists', ah(async (req, res) => {
  res.json(MC(await plex.plexCached('/playlists')).Metadata || []);
}));
r.get('/playlist/:id', ah(async (req, res) => {
  const [meta, items] = await Promise.all([
    plex.plexFetch(`/playlists/${encodeURIComponent(req.params.id)}`),
    plex.plexFetch(`/playlists/${encodeURIComponent(req.params.id)}/items`),
  ]);
  res.json({ ...(MC(meta).Metadata?.[0] || {}), items: MC(items).Metadata || [] });
}));

r.get('/search', ah(async (req, res) => {
  const q = String(req.query.q || '').trim();
  if (!q) return res.json([]);
  const j = await plex.plexFetch('/hubs/search', { params: { query: q, limit: 20, includeCollections: 1 } });
  res.json((MC(j).Hub || []).filter(h => h.Metadata?.length && ['movie', 'show', 'episode', 'season', 'collection'].includes(h.type)));
}));

r.post('/watched/:key', ah(async (req, res) => {
  await plex.plexFetch('/:/scrobble', { params: { identifier: 'com.plexapp.plugins.library', key: req.params.key } });
  plex.clearPlexCache();
  res.json({ ok: true });
}));
r.post('/unwatched/:key', ah(async (req, res) => {
  await plex.plexFetch('/:/unscrobble', { params: { identifier: 'com.plexapp.plugins.library', key: req.params.key } });
  plex.clearPlexCache();
  res.json({ ok: true });
}));

// ---------- Images ----------

// Images that made Plex fail (or crash) are not requested again for a while, so one bad
// poster can't take the server down repeatedly. Persisted across Lumen restarts.
const BAD_IMAGE_HOURS = 12;
const badImages = new Map(Object.entries(getSetting('badPlexImages', {}) || {}));
const saveBadImages = () => setSetting('badPlexImages', Object.fromEntries([...badImages].filter(([, until]) => until > Date.now())));

r.get('/image', ah(async (req, res) => {
  const path = String(req.query.path || '');
  if (!path.startsWith('/')) return res.status(400).end();
  const w = Math.min(Number(req.query.w) || 400, 3840), h = Math.min(Number(req.query.h) || 600, 2160);
  if ((badImages.get(path) || 0) > Date.now()) return res.status(404).end();
  // Plex's photo transcoder is slow; keep resized artwork on disk.
  // Request the same way Plex's own apps do (JPEG/PNG, no format=webp): WebP encoding of some
  // posters crashes Plex Media Server.
  await serveCachedImage(`plex|${plex.plexConf().machineId}|${path}|${w}x${h}|v2`, async () => {
    try {
      const up = await plex.plexFetch('/photo/:/transcode', {
        raw: true, gate: 'image', timeout: 30000, params: { url: path, width: w, height: h, minSize: 1, upscale: 1 },
        headers: { Accept: 'image/jpeg,image/png,image/*' },
      });
      if (up.status >= 500) { const e = new Error(`Plex returned ${up.status}`); e.serverError = true; throw e; }
      return up;
    } catch (e) {
      // Only blame this image if Plex died while working on it (or errored on it) — not if Plex was
      // already down/restarting when we asked.
      const kind = plex.failureKind(e);
      if (e.serverError || kind === 'dropped' || kind === 'timeout') {
        badImages.set(path, Date.now() + BAD_IMAGE_HOURS * 3600e3);
        saveBadImages();
        console.warn(`[plex] image ${path} failed (${e.cause?.code || e.message}); not requesting it again for ${BAD_IMAGE_HOURS}h`);
      }
      throw e;
    }
  }, res);
}));

// ---------- Playback ----------

const DIRECT_CONTAINERS = ['mp4', 'm4v', 'mov', 'webm'];
const DIRECT_VIDEO = ['h264', 'vp9', 'av1', 'vp8'];
const DIRECT_AUDIO = ['aac', 'mp3', 'opus', 'vorbis', 'flac'];
const TEXT_SUBS = ['srt', 'vtt', 'webvtt', 'subrip'];
// The Android TV app's native player (ExoPlayer) can direct-play far more than a browser.
const NATIVE = {
  containers: ['mp4', 'm4v', 'mov', 'webm', 'mkv', 'mpegts', 'ts', 'avi'],
  video: ['h264', 'hevc', 'h265', 'vp9', 'av1', 'vp8', 'mpeg2video', 'mpeg4'],
  audio: ['aac', 'mp3', 'opus', 'vorbis', 'flac', 'ac3', 'eac3', 'dca', 'dts', 'truehd', 'pcm', 'alac'],
  subs: ['srt', 'vtt', 'webvtt', 'subrip', 'ass', 'ssa', 'pgs', 'dvd_subtitle'],
};

const QUALITY = {
  20000: { bitrate: 20000, res: '1920x1080' }, 12000: { bitrate: 12000, res: '1920x1080' }, 8000: { bitrate: 8000, res: '1920x1080' },
  4000: { bitrate: 4000, res: '1280x720' }, 2000: { bitrate: 2000, res: '1280x720' }, 720: { bitrate: 1500, res: '854x480' },
};

// 3-letter → 2-letter language codes for matching Plex streams against preferences
const LANG3 = { eng: 'en', spa: 'es', fre: 'fr', fra: 'fr', ger: 'de', deu: 'de', ita: 'it', por: 'pt', jpn: 'ja', kor: 'ko', chi: 'zh', zho: 'zh', rus: 'ru', ara: 'ar', hin: 'hi',
  dut: 'nl', nld: 'nl', swe: 'sv', nor: 'no', nob: 'no', dan: 'da', fin: 'fi', pol: 'pl', tur: 'tr', gre: 'el', ell: 'el', heb: 'he', tha: 'th', vie: 'vi', ind: 'id', ces: 'cs', cze: 'cs', hun: 'hu', ukr: 'uk', ron: 'ro', rum: 'ro' };
const langOf = s => { const t = String(s.languageTag || '').slice(0, 2).toLowerCase(); if (t) return t; const c = String(s.languageCode || '').toLowerCase(); return LANG3[c] || c.slice(0, 2); };
const isPrivateIp = ip => /^(::ffff:)?(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.)|^::1$|^f[cd][0-9a-f]{2}:/i.test(ip || '');

r.post('/play', ah(async (req, res) => {
  const { ratingKey, offset = 0, mediaIndex = 0 } = req.body || {};
  let { audioStreamID, subtitleStreamID } = req.body || {};
  const tconf = getConfig('transcode');
  // Preferences come from the client (they can be per-device); fall back to server settings.
  const prefs = { ...getConfig('ui'), ...(req.body.prefs || {}) };
  const local = isPrivateIp(req.ip);
  const quality = String(req.body.quality || (local ? prefs.qualityLocal : prefs.qualityRemote) || tconf.plexQuality || 'auto');
  const meta = MC(await plex.plexFetch(`/library/metadata/${encodeURIComponent(ratingKey)}`, { params: { includeMarkers: 1, includeChapters: 1 } })).Metadata?.[0];
  if (!meta) return res.status(404).json({ error: 'Item not found' });
  const media = meta.Media?.[mediaIndex] || meta.Media?.[0];
  const part = media?.Part?.[0];
  if (!part) return res.status(404).json({ error: 'No playable media' });

  // Preferred audio / subtitle languages (only when the user hasn't picked tracks explicitly)
  {
    const sts = part.Stream || [];
    const audios = sts.filter(s => s.streamType === 2), subs = sts.filter(s => s.streamType === 3);
    let audio = audios.find(s => s.selected) || audios[0];
    if (audioStreamID === undefined && prefs.audioLang) {
      const want = audios.find(s => langOf(s) === prefs.audioLang);
      if (want && want !== audio) { audioStreamID = want.id; audio = want; }
    }
    if (subtitleStreamID === undefined && prefs.subtitleMode && prefs.subtitleMode !== 'plex') {
      const subLang = prefs.subtitleLang || prefs.audioLang || 'en';
      const pick = subs.find(s => langOf(s) === subLang && !s.forced) || subs.find(s => langOf(s) === subLang);
      const forced = subs.find(s => s.forced && langOf(s) === subLang);
      const current = subs.find(s => s.selected);
      let want = null;
      if (prefs.subtitleMode === 'always') want = pick || null;
      else if (prefs.subtitleMode === 'foreign') want = audio && langOf(audio) && langOf(audio) !== subLang ? (pick || null) : (forced || null);
      else if (prefs.subtitleMode === 'never') want = null;
      if ((want?.id || 0) !== (current?.id || 0)) subtitleStreamID = want?.id || 0;
    }
  }

  // Persist stream selection on the Plex server so its transcoder honours it.
  if (audioStreamID !== undefined || subtitleStreamID !== undefined) {
    const params = { allParts: 1 };
    if (audioStreamID !== undefined) params.audioStreamID = audioStreamID;
    if (subtitleStreamID !== undefined) params.subtitleStreamID = subtitleStreamID || 0;
    await plex.plexFetch(`/library/parts/${part.id}`, { method: 'PUT', params }).catch(() => null);
  }
  const streams = part.Stream || [];
  const selAudio = streams.find(s => s.streamType === 2 && (audioStreamID ? s.id === Number(audioStreamID) : s.selected)) || streams.find(s => s.streamType === 2);
  const subId = subtitleStreamID !== undefined ? Number(subtitleStreamID) : (streams.find(s => s.streamType === 3 && s.selected)?.id || 0);
  const selSub = streams.find(s => s.streamType === 3 && s.id === subId) || null;

  const isNative = req.body.client === 'native';
  const direct = isNative
    ? NATIVE.containers.includes(media.container) && NATIVE.video.includes(media.videoCodec) && NATIVE.audio.includes(selAudio?.codec || media.audioCodec)
    : DIRECT_CONTAINERS.includes(media.container) && DIRECT_VIDEO.includes(media.videoCodec)
      && DIRECT_AUDIO.includes(selAudio?.codec || media.audioCodec) && (!selAudio || selAudio === streams.find(s => s.streamType === 2))
      && (!selSub || (selSub.key && TEXT_SUBS.includes(selSub.codec)));
  const wantsOriginal = quality === 'original' || quality === 'auto';

  const base = {
    ratingKey: meta.ratingKey, title: meta.title, grandparentTitle: meta.grandparentTitle, parentIndex: meta.parentIndex, index: meta.index,
    itemType: meta.type, duration: (meta.duration || media.duration || 0) / 1000, partId: part.id,
    markers: meta.Marker || [], chapters: meta.Chapter || [],
    media: (meta.Media || []).map((m, i) => ({ index: i, label: `${m.videoResolution || ''}${/^\d+$/.test(m.videoResolution) ? 'p' : ''} ${m.videoCodec || ''} ${m.container || ''}`.trim(), bitrate: m.bitrate })),
    audio: streams.filter(s => s.streamType === 2).map(s => ({ id: s.id, label: s.displayTitle || s.language || `Track ${s.index}`, selected: s.id === selAudio?.id })),
    subtitles: streams.filter(s => s.streamType === 3).map(s => ({ id: s.id, label: s.displayTitle || s.language || 'Subtitle', selected: s.id === selSub?.id })),
    quality, sourceBitrate: media.bitrate, network: local ? 'local' : 'remote',
  };

  if (direct && wantsOriginal && !req.body.forceTranscode) {
    return res.json({
      ...base, method: 'direct', type: 'file', startOffset: 0, seekTo: offset,
      url: `/api/plex/file?path=${encodeURIComponent(part.key)}`,
      subtitleUrl: selSub ? `/api/plex/subtitle/${selSub.id}?key=${encodeURIComponent(selSub.key)}` : null,
    });
  }

  // "Auto" transcodes cap at 1080p/20 Mbps (4K transcodes can overwhelm a Plex server); "Original" keeps full quality.
  const q = QUALITY[quality] || (quality === 'original' ? { bitrate: 40000, res: '3840x2160' } : QUALITY[20000]);
  const transcoder = req.body.transcoder || tconf.plexTranscoder;

  const builtin = async (reason) => {
    if (reason) console.warn(`[plex] using built-in transcoder for "${meta.title}": ${reason}`);
    const canCopy = media.videoCodec === 'h264' && (quality === 'original' || quality === 'auto') && !selSub;
    const audioIdx = Math.max(0, streams.filter(s => s.streamType === 2).indexOf(selAudio));
    const s = await startSession({
      input: plex.plexUrl(part.key), isLive: false, mode: canCopy ? 'remux' : 'transcode', startOffset: Number(offset) || 0,
      audioIndex: audioIdx, bitrate: QUALITY[quality]?.bitrate, maxHeight: Math.min(Number(q.res.split('x')[1]), Number(tconf.maxHeight) || 1080), label: `Plex: ${meta.title}`,
    });
    return res.json({ ...base, method: canCopy ? 'builtin-remux' : `builtin-transcode (${s.hwAccel})`, type: 'hls', url: s.url, sessionId: s.id, startOffset: s.startOffset, restartOnSeek: true, fallbackReason: reason || null });
  };
  if (transcoder === 'builtin') return builtin();

  const session = crypto.randomUUID();
  const params = new URLSearchParams({
    path: `/library/metadata/${meta.ratingKey}`, mediaIndex: String(meta.Media.indexOf(media)), partIndex: '0', protocol: 'hls', fastSeek: '1',
    directPlay: '0', directStream: wantsOriginal ? '1' : '0', directStreamAudio: '1', subtitleSize: String(Math.round(Number(prefs.subtitleSize) || 100)), audioBoost: '100',
    location: 'lan', offset: String(Math.floor(offset)), session, maxVideoBitrate: String(q.bitrate), videoBitrate: String(q.bitrate),
    videoQuality: '100', videoResolution: q.res, copyts: '1', mediaBufferSize: '102400', autoAdjustQuality: '0', hasMDE: '1',
    subtitles: selSub ? 'burn' : 'none',
    'X-Plex-Session-Identifier': session, 'X-Plex-Client-Identifier': getSetting('clientId'), 'X-Plex-Product': plex.PRODUCT,
    'X-Plex-Platform': 'Chrome', 'X-Plex-Device': 'Browser',
    'X-Plex-Client-Profile-Extra': [
      'add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts&videoCodec=h264&audioCodec=aac,mp3&replace=true)',
      'add-limitation(scope=videoCodec&scopeName=h264&type=upperBound&name=video.level&value=51)',
      'add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=aac)',
    ].join('+'),
  });
  // Make Plex decide first so errors (e.g. transcoder unavailable) are surfaced.
  let decision, decisionError = null;
  try { decision = await plex.plexFetch(`/video/:/transcode/universal/decision?${params}`, { timeout: 30000 }); }
  catch (e) { decisionError = e.message; }
  const dmc = MC(decision);
  const code = Number(dmc.generalDecisionCode || 0);
  if (decisionError || code >= 2000 || Number(dmc.transcodeDecisionCode || 0) >= 2000) {
    const why = decisionError || dmc.transcodeDecisionText || dmc.generalDecisionText || `decision code ${code}`;
    console.warn(`[plex] transcode decision failed for "${meta.title}": ${why}`);
    return builtin(`Plex transcoder: ${why}`);
  }
  res.json({
    ...base, method: `plex-${dmc.Metadata?.[0]?.Media?.[0]?.Part?.[0]?.Stream?.[0]?.decision || 'transcode'}`, type: 'hls', session,
    url: `/api/plex/t/video/:/transcode/universal/start.m3u8?${params}`, startOffset: 0, seekTo: Math.floor(offset), origin: 'plex',
  });
}));

r.post('/stop', ah(async (req, res) => {
  const { session, sessionId } = req.body || {};
  if (sessionId) stopSession(sessionId);
  if (session) { plexSessions.delete(session); await plex.plexFetch('/video/:/transcode/universal/stop', { params: { session } }).catch(() => null); }
  res.json({ ok: true });
}));

r.post('/ping', (req, res) => {
  const s = plexSessions.get(req.body?.session);
  if (s) s.last = Date.now();
  res.json({ alive: !!s });
});

r.post('/timeline', ah(async (req, res) => {
  const { ratingKey, state = 'playing', time = 0, duration = 0, session } = req.body || {};
  await plex.plexFetch('/:/timeline', {
    params: { ratingKey, key: `/library/metadata/${ratingKey}`, state, time: Math.floor(time * 1000), duration: Math.floor(duration * 1000), context: 'library', hasMDE: 1 },
    headers: session ? { 'X-Plex-Session-Identifier': session } : {},
  }).catch(() => null);
  if (state !== 'playing') plex.clearPlexCache(); // so Continue Watching reflects the new position
  res.json({ ok: true });
}));

// Direct file streaming with range support
r.get('/file', ah(async (req, res) => {
  const path = String(req.query.path || '');
  if (!path.startsWith('/library/parts/')) return res.status(400).end();
  const ac = new AbortController();
  res.on('close', () => ac.abort());
  const upstream = await plex.plexFetch(path, { raw: true, timeout: 0, signal: ac.signal, headers: req.headers.range ? { Range: req.headers.range, Accept: '*/*' } : { Accept: '*/*' } });
  pipeFetch(upstream, res);
}));

r.get('/subtitle/:id', ah(async (req, res) => {
  const key = String(req.query.key || `/library/streams/${req.params.id}`);
  if (!key.startsWith('/library/streams/')) return res.status(400).end();
  const upstream = await plex.plexFetch(key, { raw: true, headers: { Accept: '*/*' } });
  let text = await upstream.text();
  if (!text.startsWith('WEBVTT')) text = srtToVtt(text);
  res.type('text/vtt').send(text);
}));

// ---- Plex transcode session tracking ----
// At most 2 concurrent requests per transcode session reach Plex, and sessions the player
// abandoned (closed tab, crash, network drop) are stopped on the Plex server after 60s of silence.
const plexSessions = new Map(); // id -> { gate, last }
const sessionAlias = new Map();  // Plex's internal path session id -> the session id Lumen created
const passthrough = { run: fn => fn() };
function trackSession(id) {
  if (!id) return null;
  id = sessionAlias.get(id) || id;
  let s = plexSessions.get(id);
  if (!s) { s = { gate: new plex.Gate(2), last: 0 }; plexSessions.set(id, s); }
  s.last = Date.now();
  return s;
}
setInterval(() => {
  for (const [id, s] of plexSessions) {
    if (Date.now() - s.last > 60000) {
      plexSessions.delete(id);
      for (const [a, root] of sessionAlias) if (root === id) sessionAlias.delete(a);
      plex.plexFetch('/video/:/transcode/universal/stop', { params: { session: id } }).catch(() => null);
      console.log(`[plex] stopped idle transcode session ${id.slice(0, 8)}`);
    }
  }
}, 15000).unref();

// Transparent proxy for Plex transcoder (playlists + segments). Relative URLs resolve back through here.
r.use('/t', ah(async (req, res) => {
  const path = req.originalUrl.replace(/^.*?\/api\/plex\/t/, '');
  if (!path.startsWith('/video/:/transcode/universal/')) return res.status(400).end();
  const [p, qs] = path.split('?');
  const params = Object.fromEntries(new URLSearchParams(qs || ''));
  const sid = params.session || p.match(/\/session\/([^/]+)\//)?.[1];
  const t = trackSession(sid);
  // Cancel the Plex request if the player gives up on it, instead of leaving it running on the server.
  const ac = new AbortController();
  res.on('close', () => ac.abort());
  let upstream;
  try {
    upstream = await (t ? t.gate : passthrough).run(() => ac.signal.aborted ? null
      : plex.plexFetch(p, { raw: true, params, headers: { Accept: '*/*' }, timeout: 120000, signal: ac.signal }));
  } catch (e) {
    if (ac.signal.aborted) return; // browser went away
    throw e;
  }
  if (!upstream) return;
  if (!upstream.ok) {
    const body = (await upstream.text().catch(() => '')).replace(/\s+/g, ' ').slice(0, 300);
    console.warn(`[plex] transcoder returned ${upstream.status} for ${p}${body ? `: ${body}` : ''}`);
    return res.status(upstream.status).type('text/plain').send(body || `Plex returned ${upstream.status}`);
  }
  if (p.endsWith('.m3u8') || /mpegurl/i.test(upstream.headers.get('content-type') || '')) {
    const base = plex.plexConf().url;
    const fix = u => {
      if (/^https?:\/\//i.test(u)) { const x = new URL(u); if (!u.startsWith(base)) return u; u = x.pathname + x.search; }
      if (!u.startsWith('/')) return u; // relative: already resolves through this proxy
      const x = new URL(u, 'http://plex');
      x.searchParams.delete('X-Plex-Token');
      return '/api/plex/t' + x.pathname + x.search;
    };
    const raw = await upstream.text();
    // Plex's variant playlists live under session/<internal id>/ — tie that id to our session.
    if (params.session) for (const m of raw.matchAll(/session\/([^/\s]+)\//g)) if (m[1] !== params.session) sessionAlias.set(m[1], params.session);
    const text = raw.split(/\r?\n/).map(line => {
      const t = line.trim();
      if (!t) return line;
      if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (m, u) => `URI="${fix(u)}"`);
      return fix(t);
    }).join('\n');
    return res.type('application/vnd.apple.mpegurl').setHeader('Cache-Control', 'no-cache').send(text);
  }
  pipeFetch(upstream, res, { headers: ['content-type', 'content-length'], extra: { 'Cache-Control': 'no-cache' } });
}));

export default r;

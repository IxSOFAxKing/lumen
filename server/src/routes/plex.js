import { Router } from 'express';
import crypto from 'node:crypto';
import * as plex from '../services/plex.js';
import { getConfig } from '../services/settings.js';
import { startSession, stopSession } from '../services/transcoder.js';
import { getSetting } from '../db.js';
import { ah, pipeFetch, srtToVtt } from '../util.js';

const r = Router();
const MC = j => j?.MediaContainer || {};

// ---------- Setup / auth ----------

r.get('/status', ah(async (req, res) => {
  const c = plex.plexConf();
  if (!plex.isConfigured()) return res.json({ configured: false });
  try {
    const info = await plex.testConnection(c.url, c.token);
    res.json({ configured: true, online: true, url: c.url, ...info });
  } catch (e) {
    res.json({ configured: true, online: false, url: c.url, serverName: c.serverName, error: e.message });
  }
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
  if (server) {
    url = await plex.pickConnection(server);
    token = server.accessToken || token;
    if (!url) return res.status(502).json({ error: 'None of the server connections were reachable from Lumen.' });
  }
  if (!url || !token) return res.status(400).json({ error: 'url and token are required' });
  url = url.replace(/\/+$/, '');
  const info = await plex.testConnection(url, token);
  plex.saveServer({ url, token, serverName: info.name, machineId: info.machineId });
  res.json({ ok: true, url, ...info });
}));

r.post('/disconnect', (req, res) => { plex.saveServer({ url: '', token: '', serverName: '', machineId: '' }); res.json({ ok: true }); });

// ---------- Browsing ----------

r.get('/home', ah(async (req, res) => {
  const [hubs, cont, playlists] = await Promise.all([
    plex.plexFetch('/hubs', { params: { count: 20, includeEmptyHubs: 0, excludeFields: 'summary' } }),
    plex.plexFetch('/hubs/home/continueWatching', { params: { count: 20 } }).catch(() => null),
    plex.plexFetch('/playlists', { params: { playlistType: 'video' } }).catch(() => null),
  ]);
  let list = MC(hubs).Hub || [];
  const cw = MC(cont).Hub?.[0];
  if (cw && !list.some(h => h.hubIdentifier === cw.hubIdentifier || h.hubIdentifier === 'home.continue')) list = [cw, ...list];
  res.json({ hubs: list, playlists: MC(playlists).Metadata || [] });
}));

r.get('/libraries', ah(async (req, res) => {
  res.json(MC(await plex.plexFetch('/library/sections')).Directory || []);
}));

r.get('/library/:id/hubs', ah(async (req, res) => {
  const id = req.params.id;
  const [hubs, popular] = await Promise.all([
    plex.plexFetch(`/hubs/sections/${id}`, { params: { count: 20, includeEmptyHubs: 0 } }),
    plex.plexFetch(`/library/sections/${id}/all`, { params: { sort: 'viewCount:desc' }, headers: { 'X-Plex-Container-Start': '0', 'X-Plex-Container-Size': '20' } }).catch(() => null),
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
  const j = await plex.plexFetch(`/library/sections/${req.params.id}/all`, {
    params, headers: { 'X-Plex-Container-Start': String(start), 'X-Plex-Container-Size': String(size) },
  });
  const m = MC(j);
  res.json({ items: m.Metadata || [], total: m.totalSize ?? m.size ?? 0, start: Number(start) });
}));

r.get('/library/:id/filter/:name', ah(async (req, res) => {
  const name = req.params.name.replace(/[^a-zA-Z]/g, '');
  res.json(MC(await plex.plexFetch(`/library/sections/${req.params.id}/${name}`)).Directory || []);
}));

r.get('/library/:id/collections', ah(async (req, res) => {
  res.json(MC(await plex.plexFetch(`/library/sections/${req.params.id}/collections`)).Metadata || []);
}));

r.get('/meta/:key', ah(async (req, res) => {
  const j = await plex.plexFetch(`/library/metadata/${encodeURIComponent(req.params.key)}`, {
    params: { includeExtras: 1, includeOnDeck: 1, includeChildren: 1, includeReviews: 0, includeMarkers: 1, checkFiles: 0 },
  });
  const item = MC(j).Metadata?.[0];
  if (!item) return res.status(404).json({ error: 'Not found' });
  res.json(item);
}));

r.get('/meta/:key/children', ah(async (req, res) => {
  res.json(MC(await plex.plexFetch(`/library/metadata/${encodeURIComponent(req.params.key)}/children`, { params: { excludeAllLeaves: 1 } })).Metadata || []);
}));
r.get('/meta/:key/leaves', ah(async (req, res) => {
  res.json(MC(await plex.plexFetch(`/library/metadata/${encodeURIComponent(req.params.key)}/allLeaves`)).Metadata || []);
}));
r.get('/meta/:key/related', ah(async (req, res) => {
  const j = await plex.plexFetch(`/hubs/metadata/${encodeURIComponent(req.params.key)}/related`, { params: { count: 20 } }).catch(() => null);
  res.json(MC(j).Hub || []);
}));
r.get('/collection/:key/items', ah(async (req, res) => {
  res.json(MC(await plex.plexFetch(`/library/collections/${encodeURIComponent(req.params.key)}/children`)).Metadata || []);
}));

r.get('/playlists', ah(async (req, res) => {
  res.json(MC(await plex.plexFetch('/playlists')).Metadata || []);
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
  res.json({ ok: true });
}));
r.post('/unwatched/:key', ah(async (req, res) => {
  await plex.plexFetch('/:/unscrobble', { params: { identifier: 'com.plexapp.plugins.library', key: req.params.key } });
  res.json({ ok: true });
}));

// ---------- Images ----------

r.get('/image', ah(async (req, res) => {
  const path = String(req.query.path || '');
  if (!path.startsWith('/')) return res.status(400).end();
  const w = Math.min(Number(req.query.w) || 400, 3840), h = Math.min(Number(req.query.h) || 600, 2160);
  const upstream = await plex.plexFetch('/photo/:/transcode', {
    raw: true, params: { url: path, width: w, height: h, minSize: 1, upscale: 1, format: 'webp', quality: 80 },
    headers: { Accept: 'image/*' },
  });
  if (!upstream.ok) return res.status(upstream.status).end();
  pipeFetch(upstream, res, { headers: ['content-type', 'content-length'], extra: { 'Cache-Control': 'public, max-age=604800' } });
}));

// ---------- Playback ----------

const DIRECT_CONTAINERS = ['mp4', 'm4v', 'mov', 'webm'];
const DIRECT_VIDEO = ['h264', 'vp9', 'av1', 'vp8'];
const DIRECT_AUDIO = ['aac', 'mp3', 'opus', 'vorbis', 'flac'];
const TEXT_SUBS = ['srt', 'vtt', 'webvtt', 'subrip'];

const QUALITY = {
  20000: { bitrate: 20000, res: '1920x1080' }, 12000: { bitrate: 12000, res: '1920x1080' }, 8000: { bitrate: 8000, res: '1920x1080' },
  4000: { bitrate: 4000, res: '1280x720' }, 2000: { bitrate: 2000, res: '1280x720' }, 720: { bitrate: 1500, res: '854x480' },
};

r.post('/play', ah(async (req, res) => {
  const { ratingKey, offset = 0, mediaIndex = 0, audioStreamID, subtitleStreamID } = req.body || {};
  const tconf = getConfig('transcode');
  const quality = String(req.body.quality || tconf.plexQuality || 'auto');
  const meta = MC(await plex.plexFetch(`/library/metadata/${encodeURIComponent(ratingKey)}`, { params: { includeMarkers: 1, includeChapters: 1 } })).Metadata?.[0];
  if (!meta) return res.status(404).json({ error: 'Item not found' });
  const media = meta.Media?.[mediaIndex] || meta.Media?.[0];
  const part = media?.Part?.[0];
  if (!part) return res.status(404).json({ error: 'No playable media' });

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

  const direct = DIRECT_CONTAINERS.includes(media.container) && DIRECT_VIDEO.includes(media.videoCodec)
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
    quality, sourceBitrate: media.bitrate,
  };

  if (direct && wantsOriginal && !req.body.forceTranscode) {
    return res.json({
      ...base, method: 'direct', type: 'file', startOffset: 0, seekTo: offset,
      url: `/api/plex/file?path=${encodeURIComponent(part.key)}`,
      subtitleUrl: selSub ? `/api/plex/subtitle/${selSub.id}?key=${encodeURIComponent(selSub.key)}` : null,
    });
  }

  const q = QUALITY[quality] || { bitrate: 40000, res: '3840x2160' };

  if (tconf.plexTranscoder === 'builtin') {
    const canCopy = media.videoCodec === 'h264' && (quality === 'original' || quality === 'auto') && !selSub;
    const audioIdx = Math.max(0, streams.filter(s => s.streamType === 2).indexOf(selAudio));
    const s = await startSession({
      input: plex.plexUrl(part.key), isLive: false, mode: canCopy ? 'remux' : 'transcode', startOffset: Number(offset) || 0,
      audioIndex: audioIdx, bitrate: QUALITY[quality]?.bitrate, maxHeight: Number(q.res.split('x')[1]), label: `Plex: ${meta.title}`,
    });
    return res.json({ ...base, method: canCopy ? 'builtin-remux' : `builtin-transcode (${s.hwAccel})`, type: 'hls', url: s.url, sessionId: s.id, startOffset: s.startOffset, restartOnSeek: true });
  }

  const session = crypto.randomUUID();
  const params = new URLSearchParams({
    path: `/library/metadata/${meta.ratingKey}`, mediaIndex: String(meta.Media.indexOf(media)), partIndex: '0', protocol: 'hls', fastSeek: '1',
    directPlay: '0', directStream: wantsOriginal ? '1' : '0', directStreamAudio: '1', subtitleSize: '100', audioBoost: '100',
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
  const decision = await plex.plexFetch(`/video/:/transcode/universal/decision?${params}`, {}).catch(() => null);
  const dmc = MC(decision);
  if (dmc.generalDecisionCode && Number(dmc.generalDecisionCode) >= 2000) {
    return res.status(502).json({ error: `Plex cannot play this: ${dmc.generalDecisionText || dmc.transcodeDecisionText}` });
  }
  res.json({
    ...base, method: `plex-${dmc.Metadata?.[0]?.Media?.[0]?.Part?.[0]?.Stream?.[0]?.decision || 'transcode'}`, type: 'hls', session,
    url: `/api/plex/t/video/:/transcode/universal/start.m3u8?${params}`, startOffset: 0, seekTo: Math.floor(offset),
  });
}));

r.post('/stop', ah(async (req, res) => {
  const { session, sessionId } = req.body || {};
  if (sessionId) stopSession(sessionId);
  if (session) await plex.plexFetch('/video/:/transcode/universal/stop', { params: { session } }).catch(() => null);
  res.json({ ok: true });
}));

r.post('/timeline', ah(async (req, res) => {
  const { ratingKey, state = 'playing', time = 0, duration = 0, session } = req.body || {};
  await plex.plexFetch('/:/timeline', {
    params: { ratingKey, key: `/library/metadata/${ratingKey}`, state, time: Math.floor(time * 1000), duration: Math.floor(duration * 1000), context: 'library', hasMDE: 1 },
    headers: session ? { 'X-Plex-Session-Identifier': session } : {},
  }).catch(() => null);
  res.json({ ok: true });
}));

// Direct file streaming with range support
r.get('/file', ah(async (req, res) => {
  const path = String(req.query.path || '');
  if (!path.startsWith('/library/parts/')) return res.status(400).end();
  const upstream = await plex.plexFetch(path, { raw: true, timeout: 0, headers: req.headers.range ? { Range: req.headers.range, Accept: '*/*' } : { Accept: '*/*' } });
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

// Transparent proxy for Plex transcoder (playlists + segments). Relative URLs resolve back through here.
r.use('/t', ah(async (req, res) => {
  const path = req.originalUrl.replace(/^.*?\/api\/plex\/t/, '');
  if (!path.startsWith('/video/:/transcode/universal/')) return res.status(400).end();
  const [p, qs] = path.split('?');
  const params = Object.fromEntries(new URLSearchParams(qs || ''));
  const upstream = await plex.plexFetch(p, { raw: true, params, headers: { Accept: '*/*' }, timeout: 120000 });
  pipeFetch(upstream, res, { headers: ['content-type', 'content-length'], extra: { 'Cache-Control': 'no-cache' } });
}));

export default r;

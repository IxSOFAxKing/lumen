import { api } from './api.js';
import { openPlayer } from './player.js';
import { toast, toastErr, nowS, fmtTime } from './ui.js';

const PLEX_QUALITIES = [
  { value: 'auto', label: 'Auto (direct play when possible)' }, { value: 'original', label: 'Original (direct stream)' },
  { value: '20000', label: '1080p · 20 Mbps' }, { value: '12000', label: '1080p · 12 Mbps' }, { value: '8000', label: '1080p · 8 Mbps' },
  { value: '4000', label: '720p · 4 Mbps' }, { value: '2000', label: '720p · 2 Mbps' }, { value: '720', label: '480p · 1.5 Mbps' },
];

// ---------------- Plex ----------------

async function plexNext(item) {
  if (item.type !== 'episode') return null;
  try {
    const leaves = await api.get(`/api/plex/meta/${item.grandparentRatingKey}/leaves`);
    const i = leaves.findIndex(l => String(l.ratingKey) === String(item.ratingKey));
    return i >= 0 ? leaves[i + 1] || null : null;
  } catch { return null; }
}

/**
 * Play a Plex item. opts: { offset, queue: [items], queueIndex }
 */
export async function playPlex(ratingKey, opts = {}) {
  const state = { quality: opts.quality, audio: undefined, subtitle: undefined, mediaIndex: opts.mediaIndex || 0, forceTranscode: false, transcoder: undefined };
  const fetchDesc = async (offset) => api.post('/api/plex/play', {
    ratingKey, offset, quality: state.quality, mediaIndex: state.mediaIndex,
    audioStreamID: state.audio, subtitleStreamID: state.subtitle, forceTranscode: state.forceTranscode, transcoder: state.transcoder,
  });
  try {
    const desc = await fetchDesc(opts.offset || 0);
    const item = await api.get(`/api/plex/meta/${ratingKey}`).catch(() => ({}));
    let nextItem = null;
    if (opts.queue && opts.queueIndex !== undefined) nextItem = opts.queue[opts.queueIndex + 1] || null;
    else nextItem = await plexNext(item);

    const subtitle = item.type === 'episode'
      ? `${item.grandparentTitle} · S${item.parentIndex}E${item.index}${item.title ? ' · ' + item.title : ''}`
      : [item.year, item.contentRating].filter(Boolean).join(' · ');
    const buildMeta = (d) => ({
      title: item.type === 'episode' ? item.grandparentTitle : (item.title || d.title),
      subtitle, duration: d.duration,
      markers: (d.markers || []).map(m => ({ type: m.type, start: m.startTimeOffset / 1000, end: m.endTimeOffset / 1000 })),
      audio: d.audio, subtitles: d.subtitles, qualities: PLEX_QUALITIES, quality: d.quality,
      versions: d.media, mediaIndex: state.mediaIndex,
      next: nextItem ? {
        label: nextItem.type === 'episode' ? `S${nextItem.parentIndex}E${nextItem.index} · ${nextItem.title}` : nextItem.title,
        run: () => playPlex(nextItem.ratingKey, opts.queue ? { queue: opts.queue, queueIndex: opts.queueIndex + 1 } : {}),
      } : null,
      progress: (pos, dur, st) => api.post('/api/plex/timeline', { ratingKey, state: st, time: pos, duration: dur, session: currentDesc?.session }).catch(() => {}),
      reload: async ({ offset, mode, audio, subtitle: sub, quality, mediaIndex }) => {
        if (audio !== undefined) state.audio = audio;
        if (sub !== undefined) state.subtitle = sub;
        if (quality !== undefined) state.quality = quality;
        if (mediaIndex !== undefined) state.mediaIndex = mediaIndex;
        if (mode === 'transcode' || mode === 'remux') { state.forceTranscode = true; if (mode === 'transcode' && (!state.quality || state.quality === 'auto' || state.quality === 'original')) state.quality = '20000'; }
        if (mode === 'direct') { state.forceTranscode = false; state.quality = 'auto'; state.transcoder = undefined; }
        if (mode === 'builtin') { state.forceTranscode = true; state.transcoder = 'builtin'; }
        const d = await fetchDesc(offset);
        currentDesc = d;
        return { desc: d, meta: buildMeta(d) };
      },
    });
    let currentDesc = desc;
    return openPlayer(desc, buildMeta(desc));
  } catch (e) { toastErr(e); }
}

// ---------------- IPTV Live ----------------

/** Open a live channel full screen. channels = list for zapping (same category). */
export async function playLive(providerId, channel, channels = [channel], title = 'Channels') {
  const open = async (ch, mode) => {
    const d = await api.post('/api/iptv/play/live', { providerId, channelId: ch.id, mode });
    const meta = {
      title: `${ch.num ?? ''} ${ch.name}`.trim(), subtitle: ch.now ? `${fmtTime(ch.now.start)} – ${fmtTime(ch.now.stop)} · ${ch.now.title}` : '',
      live: true, channel: ch,
      reload: async ({ mode: m }) => (await open(ch, m)),
    };
    return { desc: d, meta };
  };
  try {
    const list = channels.length ? channels : [channel];
    const idx = Math.max(0, list.findIndex(c => String(c.id) === String(channel.id)));
    const zap = { channels: list, index: idx, open: (ch) => open(ch), title };
    const { desc, meta } = await open(channel);
    return openPlayer(desc, { ...meta, zap });
  } catch (e) { toastErr(e); }
}

export async function playCatchup(providerId, channel, prog) {
  if (prog.start > nowS()) return toast('This programme has not started yet');
  try {
    const stop = Math.min(prog.stop, nowS());
    const req = (offset, mode) => api.post('/api/iptv/play/catchup', { providerId, channelId: channel.id, start: prog.start, stop: prog.stop, offset, mode });
    const desc = await req(0);
    return openPlayer(desc, {
      title: prog.title, subtitle: `${channel.name} · ${fmtTime(prog.start)} – ${fmtTime(prog.stop)} (catch-up)`,
      duration: stop - prog.start,
      reload: ({ offset, mode }) => req(offset, mode === 'direct' ? 'remux' : mode),
    });
  } catch (e) { toastErr(e); }
}

// ---------------- IPTV VOD ----------------

export async function playVod(providerId, { kind = 'movie', id, ext, title, subtitle, poster, parentId, offset = 0, duration, next }) {
  const req = (off, mode) => api.post('/api/iptv/play/vod', { providerId, kind, id, ext, offset: off, mode });
  try {
    const desc = await req(offset);
    return openPlayer(desc, {
      title, subtitle, duration,
      next,
      progress: (pos, dur) => api.post('/api/iptv/progress', { providerId, kind, id, position: pos, duration: dur, title, poster, parentId }).catch(() => {}),
      reload: ({ offset: off, mode }) => req(off, mode),
    });
  } catch (e) { toastErr(e); }
}

export async function playRecording(rec) {
  const req = (offset, mode) => api.post(`/api/iptv/play/recording/${rec.id}`, { offset, mode });
  try {
    const desc = await req(0);
    return openPlayer(desc, {
      title: rec.title, subtitle: `${rec.channel_name} · recorded ${new Date(rec.start * 1000).toLocaleString()}`,
      duration: desc.duration,
      reload: ({ offset, mode }) => req(offset, mode === 'direct' ? 'remux' : mode),
    });
  } catch (e) { toastErr(e); }
}

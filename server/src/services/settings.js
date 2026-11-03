import { getSetting, setSetting } from '../db.js';
import { config } from '../config.js';

export const DEFAULTS = {
  plex: { url: '', token: '', serverName: '', machineId: '' },
  transcode: {
    hwAccel: config.hwAccel,            // none | vaapi | qsv | nvenc | videotoolbox
    vaapiDevice: config.vaapiDevice,
    preset: 'veryfast',                 // software x264 preset
    liveMode: 'auto',                   // auto | direct | remux | transcode
    maxHeight: 1080,
    videoBitrate: 8000,                 // kbps when transcoding
    audioBitrate: 192,
    deinterlace: true,
    tonemap: false,
    plexTranscoder: 'plex',             // plex (use Plex server transcoder) | builtin (Lumen ffmpeg)
    plexQuality: 'auto',                // auto | original | 20000 | 12000 | 8000 | 4000 | 2000 | 720
    segmentSeconds: 4,
  },
  iptv: {
    epgRefreshHours: 12,
    playlistRefreshHours: 24,
    epgPastHours: 72,
    epgFutureDays: 7,
    userAgent: '',
    timeshiftOffsetMinutes: 0,
  },
  ui: { startPage: 'home', theme: 'dark', zapOverlaySeconds: 4, showPreview: true, liveBuffer: 'smooth' },
};

function merge(def, val) {
  if (!val || typeof val !== 'object') return structuredClone(def);
  const out = structuredClone(def);
  for (const k of Object.keys(val)) out[k] = val[k];
  return out;
}

export function getConfig(section) {
  return merge(DEFAULTS[section], getSetting(section));
}
export function setConfig(section, patch) {
  const next = { ...getConfig(section), ...patch };
  setSetting(section, next);
  return next;
}
export function allConfig() {
  return Object.fromEntries(Object.keys(DEFAULTS).map(k => [k, getConfig(k)]));
}

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
    vodEnabled: true,                   // global switch for IPTV movies & series
    recordPadBefore: 1, recordPadAfter: 3, // minutes
    epgRefreshHours: 12,
    playlistRefreshHours: 24,
    epgPastHours: 72,
    epgFutureDays: 7,
    userAgent: '',
    timeshiftOffsetMinutes: 0,
  },
  ui: {
    startPage: 'home', zapOverlaySeconds: 4, showPreview: true, liveBuffer: 'smooth',
    // Live TV
    chShowNumbers: true, chShowLogos: true, chShowProgress: true, chShowNext: false,
    chDensity: 'comfortable', chLogoSize: 'medium', zapWrap: true, resumeLastChannel: false,
    // Appearance
    theme: 'dark',                // dark | light | oled | system
    accent: '#ff9f3d',
    uiScale: 100,                 // percent
    cardSize: 'medium',           // small | medium | large
    showCardTitles: true,
    blurSpoilers: false,
    navStyle: 'rail',             // rail | wide | icons
    navOrder: [], navHidden: [],
    homeOrder: [], homeHidden: [], hiddenHubs: [],
    heroInterval: 8, heroSource: 'recent', heroCollection: '', heroCollectionTitle: '', heroAutoplay: true,
    ratingDisplay: 'audience', pinnedRows: [], libraryViews: {},
    // Player
    autoplayNext: true, autoplayCountdown: 10,
    audioLang: '', subtitleMode: 'plex', subtitleLang: '',
    qualityLocal: 'auto', qualityRemote: '8000',
    resumeMode: 'resume', watchedAt: 90,
    controlsHideSeconds: 3, playerButtons: {},
    clock: 'off',
    autoSkipIntro: false, autoSkipCredits: false,
    skipIntroMode: 'button', skipCreditsMode: 'button', autoSkipDelay: 0, forceFallbackMarkers: false, chapterMarks: true,
    rewindOnResume: 0, defaultSleep: 0, maxVolume: 100,
    seekBack: 10, seekForward: 30,
    subtitleSize: 100, subtitleColor: '#ffffff', subtitleBg: 60,
    // TV Guide
    guideScale: 6, guideRowHeight: 62, guideHours: 8, guideStart: 'now', guideCategoryColors: true,
  },
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

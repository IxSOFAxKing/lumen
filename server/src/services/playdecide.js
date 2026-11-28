// How a Plex item should play: which audio / subtitle tracks, and whether the original file can be played as it is.
// Pure functions (no Plex calls), so they can be tested (test/playdecide.test.js).

export const DIRECT_CONTAINERS = ['mp4', 'm4v', 'mov', 'webm'];
export const DIRECT_VIDEO = ['h264', 'vp9', 'av1', 'vp8'];
export const DIRECT_AUDIO = ['aac', 'mp3', 'opus', 'vorbis', 'flac'];
export const TEXT_SUBS = ['srt', 'vtt', 'webvtt', 'subrip'];
// The Android app's player (ExoPlayer + FFmpeg) can play far more than a browser.
export const NATIVE = {
  containers: ['mp4', 'm4v', 'mov', 'webm', 'mkv', 'mpegts', 'ts', 'avi'],
  video: ['h264', 'hevc', 'h265', 'vp9', 'av1', 'vp8', 'mpeg2video', 'mpeg4'],
  audio: ['aac', 'mp3', 'opus', 'vorbis', 'flac', 'ac3', 'eac3', 'dca', 'dts', 'truehd', 'pcm', 'alac'],
  subs: ['srt', 'vtt', 'webvtt', 'subrip', 'ass', 'ssa', 'pgs', 'dvd_subtitle'],
};

// 3-letter → 2-letter language codes for matching Plex streams against preferences
const LANG3 = { eng: 'en', spa: 'es', fre: 'fr', fra: 'fr', ger: 'de', deu: 'de', ita: 'it', por: 'pt', jpn: 'ja', kor: 'ko', chi: 'zh', zho: 'zh', rus: 'ru', ara: 'ar', hin: 'hi',
  dut: 'nl', nld: 'nl', swe: 'sv', nor: 'no', nob: 'no', dan: 'da', fin: 'fi', pol: 'pl', tur: 'tr', gre: 'el', ell: 'el', heb: 'he', tha: 'th', vie: 'vi', ind: 'id', ces: 'cs', cze: 'cs', hun: 'hu', ukr: 'uk', ron: 'ro', rum: 'ro' };
export const langOf = s => { const t = String(s.languageTag || '').slice(0, 2).toLowerCase(); if (t) return t; const c = String(s.languageCode || '').toLowerCase(); return LANG3[c] || c.slice(0, 2); };
/** Commentary / audio-description tracks: never picked automatically. */
export const isExtraAudio = s => /comment|descri|narrat/i.test(`${s.title || ''} ${s.displayTitle || ''} ${s.extendedDisplayTitle || ''}`);

/**
 * Preferred audio / subtitle languages, only when no track was picked by hand.
 * Returns { audioStreamID, subtitleStreamID } (undefined = keep Plex's current choice).
 */
export function preferredTracks(streams, prefs = {}, picked = {}) {
  let { audioStreamID, subtitleStreamID } = picked;
  const audios = streams.filter(s => s.streamType === 2), subs = streams.filter(s => s.streamType === 3);
  let audio = audios.find(s => s.selected) || audios.find(s => !isExtraAudio(s)) || audios[0];
  // The main track in that language (never a commentary), unless the current one already fits.
  if (audioStreamID === undefined && prefs.audioLang && !(audio && langOf(audio) === prefs.audioLang && !isExtraAudio(audio))) {
    const want = audios.find(s => langOf(s) === prefs.audioLang && !isExtraAudio(s));
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
    if ((want?.id || 0) !== (current?.id || 0)) subtitleStreamID = want?.id || 0;
  }
  return { audioStreamID, subtitleStreamID };
}

/** The audio and subtitle tracks that will play. */
export function selectedTracks(streams, audioStreamID, subtitleStreamID) {
  const selAudio = streams.find(s => s.streamType === 2 && (audioStreamID ? s.id === Number(audioStreamID) : s.selected))
    || streams.find(s => s.streamType === 2 && !isExtraAudio(s)) || streams.find(s => s.streamType === 2) || null;
  const subId = subtitleStreamID !== undefined ? Number(subtitleStreamID) : (streams.find(s => s.streamType === 3 && s.selected)?.id || 0);
  const selSub = streams.find(s => s.streamType === 3 && s.id === subId) || null;
  return { selAudio, selSub };
}

/**
 * Can the original file play as it is? Apps ('native') say which sound / picture formats the device really plays;
 * browsers get a conservative list (and only the first audio track, text subtitles).
 */
export function canPlayOriginal({ native, media, streams, selAudio, selSub, audioCodecs, videoCodecs }) {
  const aCodec = selAudio?.codec || media.audioCodec;
  if (native) {
    return NATIVE.containers.includes(media.container) && NATIVE.video.includes(media.videoCodec) && NATIVE.audio.includes(aCodec)
      && (!Array.isArray(audioCodecs) || audioCodecs.includes(aCodec))
      && (!Array.isArray(videoCodecs) || videoCodecs.includes(media.videoCodec));
  }
  return !!(DIRECT_CONTAINERS.includes(media.container) && DIRECT_VIDEO.includes(media.videoCodec)
    && DIRECT_AUDIO.includes(aCodec) && (!selAudio || selAudio === streams.find(s => s.streamType === 2))
    && (!selSub || (selSub.key && TEXT_SUBS.includes(selSub.codec))));
}

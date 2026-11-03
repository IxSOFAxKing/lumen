/**
 * Attach a playback source to a <video> element.
 * desc: { type: 'hls' | 'mpegts' | 'file', url, seekTo, live }
 * Returns { destroy(), levels?, setLevel? }
 */
export function attachEngine(video, desc, { onFatal, onReady } = {}) {
  let hls = null, ts = null, destroyed = false, watchdog = null;
  const fatal = (reason) => { if (!destroyed) onFatal?.(reason); };

  const startAt = Number(desc.seekTo) || 0;

  if (desc.type === 'hls') {
    if (window.Hls && window.Hls.isSupported()) {
      hls = new window.Hls({
        startPosition: startAt > 0 ? startAt : -1,
        maxBufferLength: 30, maxMaxBufferLength: 120, backBufferLength: 90,
        liveSyncDurationCount: 3, enableWorker: true, lowLatencyMode: false,
        manifestLoadingMaxRetry: 6, manifestLoadingRetryDelay: 1000,
        levelLoadingMaxRetry: 6, fragLoadingMaxRetry: 6,
        xhrSetup: (xhr) => { xhr.withCredentials = true; },
      });
      let mediaRecoveries = 0, manifestLoaded = false;
      hls.on(window.Hls.Events.ERROR, (_, data) => {
        if (!data.fatal) return;
        const code = data.response?.code;
        // A failed/unparseable master playlist can't be recovered with startLoad(); report it instead of spinning forever.
        if (!manifestLoaded) { fatal(`could not load stream (${data.details}${code ? `, HTTP ${code}` : ''})`); return; }
        if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries < 2) { mediaRecoveries++; hls.recoverMediaError(); return; }
        if (data.type === window.Hls.ErrorTypes.NETWORK_ERROR && code !== 404 && mediaRecoveries < 4) { mediaRecoveries++; setTimeout(() => hls?.startLoad(), 1500); return; }
        fatal(`${data.type}: ${data.details}${code ? ` (HTTP ${code})` : ''}`);
      });
      hls.on(window.Hls.Events.MANIFEST_PARSED, () => { manifestLoaded = true; onReady?.(); });
      hls.loadSource(desc.url);
      hls.attachMedia(video);
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = desc.url;
      if (startAt) video.addEventListener('loadedmetadata', () => { video.currentTime = startAt; }, { once: true });
    } else {
      fatal('HLS is not supported in this browser');
    }
  } else if (desc.type === 'mpegts') {
    const m = window.mpegts;
    if (m && m.getFeatureList().mseLivePlayback) {
      ts = m.createPlayer({ type: 'mpegts', isLive: true, url: new URL(desc.url, location.href).toString(), withCredentials: true }, {
        enableWorker: true, enableStashBuffer: true, stashInitialSize: 1024 * 384, lazyLoad: false,
        liveBufferLatencyChasing: true, liveBufferLatencyMaxLatency: 6, liveBufferLatencyMinRemain: 1.5,
        autoCleanupSourceBuffer: true,
      });
      ts.on(m.Events.ERROR, (type, detail) => fatal(`${type}: ${detail}`));
      ts.attachMediaElement(video);
      ts.load();
      onReady?.();
    } else {
      fatal('MSE not supported');
    }
  } else {
    video.src = desc.url;
    if (startAt) video.addEventListener('loadedmetadata', () => { video.currentTime = startAt; }, { once: true });
  }

  const onErr = () => {
    const e = video.error;
    if (e && !destroyed) fatal(`media error ${e.code}${e.message ? ': ' + e.message : ''}`);
  };
  video.addEventListener('error', onErr);

  // Detect "audio plays but video codec unsupported" (e.g. HEVC in browsers without support)
  const onPlaying = () => {
    clearTimeout(watchdog);
    watchdog = setTimeout(() => {
      if (!destroyed && !video.paused && video.videoWidth === 0 && !desc.audioOnly) fatal('video track could not be decoded');
    }, 2500);
  };
  video.addEventListener('playing', onPlaying);

  const p = video.play();
  if (p && p.catch) p.catch(() => { /* autoplay may need user gesture */ });

  return {
    hls, ts,
    destroy() {
      destroyed = true;
      clearTimeout(watchdog);
      video.removeEventListener('error', onErr);
      video.removeEventListener('playing', onPlaying);
      try { hls?.destroy(); } catch { /* */ }
      try { ts?.pause(); ts?.unload(); ts?.detachMediaElement(); ts?.destroy(); } catch { /* */ }
      try { video.pause(); video.removeAttribute('src'); while (video.firstChild) video.firstChild.remove(); video.load(); } catch { /* */ }
    },
  };
}

/** Release server resources for a playback descriptor. */
export function releaseDesc(desc, { beacon = false } = {}) {
  if (!desc) return;
  if (desc.sessionId) {
    if (beacon && navigator.sendBeacon) navigator.sendBeacon(`/api/stream/session/${desc.sessionId}/stop`);
    else fetch(`/api/stream/session/${desc.sessionId}`, { method: 'DELETE' }).catch(() => {});
  }
  if (desc.session) {
    const body = JSON.stringify({ session: desc.session });
    if (beacon && navigator.sendBeacon) navigator.sendBeacon('/api/plex/stop', new Blob([body], { type: 'application/json' }));
    else fetch('/api/plex/stop', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body }).catch(() => {});
  }
}

export const FALLBACK = ['direct', 'remux', 'transcode'];
export function nextMode(desc) {
  const m = String(desc?.method || 'direct');
  if (m.startsWith('direct')) return 'remux';
  if (m.startsWith('plex-')) return 'builtin';
  if (m.startsWith('remux') || m === 'builtin-remux') return 'transcode';
  return null;
}

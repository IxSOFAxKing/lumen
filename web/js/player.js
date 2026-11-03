import { h, esc, fmtDuration, fmtTime, nowS, logo, toast } from './ui.js';
import { icon } from './icons.js';
import { attachEngine, releaseDesc, nextMode } from './engine.js';
import { api } from './api.js';

let current = null;

export function isPlayerOpen() { return !!current; }
export function closePlayer() { current?.close(); }

/**
 * Open the fullscreen player.
 * desc: playback descriptor from the server.
 * meta: {
 *   title, subtitle, live, duration, markers:[{type,start,end}],
 *   audio:[{id,label,selected}], subtitles:[...], qualities:[{value,label}], quality,
 *   reload(opts) -> Promise<desc>,            // restart at offset / change mode / change tracks
 *   progress(pos, dur, state),                // periodic progress report
 *   next: { label, run() } | null,            // up-next
 *   zap: { channels:[], index, open(ch) -> Promise<{desc, meta}> }, // live channel switching
 *   subtitleUrl
 * }
 */
export function openPlayer(desc, meta = {}) {
  if (current && !current.closed) { current.report('stopped'); current.load(desc, meta); return current; }
  current = new Player(desc, meta);
  return current;
}

class Player {
  constructor(desc, meta) {
    this.desc = desc; this.meta = meta;
    this.root = document.getElementById('player-root');
    this.video = h('video', { playsinline: true, autoplay: true, crossorigin: 'use-credentials' });
    this.video.volume = Number(localStorage.getItem('lumen.volume') ?? 1);
    this.video.muted = localStorage.getItem('lumen.muted') === 'true';
    this.build();
    this.timers = [];
    this.timers.push(setInterval(() => this.report('playing'), 10000));
    this.timers.push(setInterval(() => { if (this.desc?.sessionId) api.post(`/api/stream/session/${this.desc.sessionId}/ping`).catch(() => {}); }, 20000));
    this.timers.push(setInterval(() => this.tick(), 500));
    this.onKey = this.onKey.bind(this);
    document.addEventListener('keydown', this.onKey);
    this.onUnload = () => releaseDesc(this.desc, { beacon: true });
    window.addEventListener('pagehide', this.onUnload);
    history.pushState({ player: true }, '');
    this.onPop = () => this.close({ fromHistory: true });
    window.addEventListener('popstate', this.onPop);
    this.load(desc, meta);
  }

  // ---------- Building UI ----------
  build() {
    const pbtn = (name, title, fn, cls = '') => h('button', { class: `pbtn ${cls}`, title, 'aria-label': title, html: icon(name), onClick: (e) => { e.stopPropagation(); fn(); } });
    this.el = h('div', { class: 'player', onMousemove: () => this.poke(), onClick: (e) => { if (e.target === this.video || e.target.classList.contains('p-center')) { if (matchMedia('(pointer:coarse)').matches) this.poke(); else this.toggle(); } } });
    this.titleEl = h('div', { class: 'ttl' }); this.subEl = h('div', { class: 'sub' });
    this.top = h('div', { class: 'p-top' }, pbtn('back', 'Back (Esc)', () => this.close()), h('div', { class: 'grow' }, this.titleEl, this.subEl),
      this.liveBadge = h('span', { class: 'badge live hidden' }, 'LIVE'));
    // seekbar
    this.buf = h('div', { class: 'buf' }); this.fill = h('div', { class: 'fill' }); this.knob = h('div', { class: 'knob' }); this.tip = h('div', { class: 'tip' });
    this.marks = h('div');
    this.track = h('div', { class: 'track' }, this.buf, this.marks, this.fill, this.knob);
    this.seekbar = h('div', { class: 'p-seek', onClick: e => { e.stopPropagation(); this.seek(this.posFromEvent(e)); }, onMousemove: e => this.hoverSeek(e) }, this.track, this.tip);
    this.playBtn = pbtn('pause', 'Play/Pause (Space)', () => this.toggle(), 'big');
    this.timeEl = h('span', { class: 'time' });
    this.volBtn = pbtn('vol', 'Mute (M)', () => this.setMuted(!this.video.muted));
    this.vol = h('input', { type: 'range', class: 'p-vol', min: 0, max: 1, step: 0.05, value: this.video.volume, onInput: e => { this.video.volume = e.target.value; this.setMuted(false); localStorage.setItem('lumen.volume', e.target.value); }, onClick: e => e.stopPropagation() });
    this.rwBtn = pbtn('rw10', 'Back 10s (←)', () => this.seek(this.pos() - 10));
    this.ffBtn = pbtn('ff30', 'Forward 30s (→)', () => this.seek(this.pos() + 30));
    this.chUp = pbtn('up', 'Channel up (↑ / PgUp)', () => this.zap(-1));
    this.chDown = pbtn('down', 'Channel down (↓ / PgDn)', () => this.zap(1));
    this.listBtn = pbtn('list', 'Channel list (L)', () => this.toggleSide());
    this.nextBtn = pbtn('next', 'Next', () => this.meta.next?.run());
    this.audioBtn = pbtn('audio', 'Audio track', () => this.menu('audio'));
    this.ccBtn = pbtn('cc', 'Subtitles (C)', () => this.menu('subs'));
    this.qBtn = pbtn('hd', 'Quality / playback', () => this.menu('quality'));
    this.infoBtn = pbtn('info', 'Stream info (I)', () => this.toggleInfo());
    this.pipBtn = pbtn('pip', 'Picture-in-picture (P)', () => this.pip());
    this.fsBtn = pbtn('fs', 'Fullscreen (F)', () => this.fullscreen());
    const ctrls = h('div', { class: 'p-ctrls' }, this.playBtn, this.rwBtn, this.ffBtn, this.chUp, this.chDown, this.nextBtn, this.volBtn, this.vol, this.timeEl,
      h('div', { class: 'spacer' }), this.listBtn, this.audioBtn, this.ccBtn, this.qBtn, this.infoBtn, this.pipBtn, this.fsBtn);
    this.bottom = h('div', { class: 'p-bottom' }, this.seekbar, ctrls);
    this.ui = h('div', { class: 'ui' }, this.top, this.bottom);
    this.center = h('div', { class: 'p-center' });
    this.overlay = h('div');
    this.el.append(this.video, this.center, this.ui, this.overlay);
    this.root.replaceChildren(this.el);

    const v = this.video;
    v.addEventListener('play', () => { this.playBtn.innerHTML = icon('pause'); this.report('playing'); });
    v.addEventListener('pause', () => { this.playBtn.innerHTML = icon('play'); this.poke(); this.report('paused'); });
    v.addEventListener('waiting', () => this.setBusy(true));
    v.addEventListener('playing', () => { this.setBusy(false); this.clearError(); });
    v.addEventListener('canplay', () => this.setBusy(false));
    v.addEventListener('ended', () => this.ended());
    v.addEventListener('volumechange', () => { this.volBtn.innerHTML = icon(v.muted || v.volume === 0 ? 'mute' : 'vol'); });
    document.addEventListener('fullscreenchange', this.onFs = () => { this.fsBtn.innerHTML = icon(document.fullscreenElement ? 'fsx' : 'fs'); });
  }

  // ---------- Loading ----------
  load(desc, meta = this.meta) {
    this.engine?.destroy();
    if (this.desc && this.desc !== desc) releaseDesc(this.desc);
    this.desc = desc; this.meta = meta;
    this.upNextDismissed = false; clearInterval(this.upNextTimer);
    this.upNextEl?.remove(); this.upNextEl = null; this.skipEl?.remove(); this.skipEl = null; this.skipFor = null;
    this.closeMenu?.();
    this.clearError();
    this.setBusy(true);
    this.titleEl.textContent = meta.title || '';
    this.subEl.textContent = meta.subtitle || '';
    const live = !!meta.live;
    this.liveBadge.classList.toggle('hidden', !live);
    this.seekbar.classList.toggle('hidden', live);
    for (const b of [this.rwBtn, this.ffBtn]) b.classList.toggle('hidden', live);
    for (const b of [this.chUp, this.chDown, this.listBtn]) b.classList.toggle('hidden', !meta.zap);
    this.nextBtn.classList.toggle('hidden', !meta.next);
    this.audioBtn.classList.toggle('hidden', !(meta.audio?.length > 1));
    this.ccBtn.classList.toggle('hidden', !(meta.subtitles?.length));
    this.renderMarkers();
    this.engine = attachEngine(this.video, desc, {
      onFatal: (reason) => this.fatal(reason),
    });
    if (desc.subtitleUrl) {
      this.video.append(h('track', { kind: 'subtitles', src: desc.subtitleUrl, default: true, label: 'Subtitles' }));
      setTimeout(() => { for (const t of this.video.textTracks) t.mode = 'showing'; }, 300);
    }
    if (live && meta.zap) this.showBanner();
    this.poke();
  }

  async reload(opts = {}) {
    if (!this.meta.reload) return;
    this.setBusy(true);
    const offset = opts.offset ?? (this.meta.live ? 0 : this.pos());
    try {
      const d = await this.meta.reload({ ...opts, offset });
      if (!d) return;
      if (d.meta) this.load(d.desc, { ...this.meta, ...d.meta });
      else this.load(d, this.meta);
    } catch (e) { this.showError(e.message); }
  }

  fatal(reason) {
    console.warn('[player] fatal:', reason, this.desc);
    const mode = nextMode(this.desc);
    if (mode && this.meta.reload && !this.desc.noFallback) {
      toast(`Direct playback failed — switching to ${mode === 'remux' ? 'remux (audio conversion)' : 'transcoding'}…`);
      this.reload({ mode, offset: this.meta.live ? 0 : this.pos() });
      return;
    }
    if (this.desc.sessionId && this.meta.reload && !this.retried) {
      // transcoder session probably expired (idle/paused). Restart once at current position.
      this.retried = true;
      this.reload({ offset: this.pos() });
      setTimeout(() => { this.retried = false; }, 15000);
      return;
    }
    this.showError(`Playback failed (${reason}).`);
  }

  // ---------- Time ----------
  base() { return Number(this.desc?.startOffset) || 0; }
  pos() { return this.base() + (this.video.currentTime || 0); }
  dur() {
    const d = Number(this.meta.duration || this.desc?.duration || 0);
    if (d) return d;
    return isFinite(this.video.duration) ? this.base() + this.video.duration : 0;
  }
  availableEnd() {
    const v = this.video;
    let end = 0;
    if (v.seekable?.length) end = v.seekable.end(v.seekable.length - 1);
    else if (isFinite(v.duration)) end = v.duration;
    return this.base() + end;
  }
  seek(t) {
    if (this.meta.live) return;
    const dur = this.dur();
    t = Math.max(0, dur ? Math.min(t, dur - 2) : t);
    if (this.desc.restartOnSeek && (t < this.base() || t > this.availableEnd() - 1)) {
      this.reload({ offset: t });
    } else {
      this.video.currentTime = t - this.base();
    }
    this.poke();
  }
  posFromEvent(e) {
    const r = this.track.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * this.dur();
  }
  hoverSeek(e) {
    const t = this.posFromEvent(e);
    const r = this.track.getBoundingClientRect();
    this.tip.style.left = `${e.clientX - r.left}px`;
    this.tip.textContent = fmtDuration(t);
  }

  tick() {
    const v = this.video;
    if (this.meta.live) {
      const ch = this.meta.channel;
      this.timeEl.textContent = ch?.now ? `${fmtTime(ch.now.start)} – ${fmtTime(ch.now.stop)}  ·  ${ch.now.title}` : new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    } else {
      const d = this.dur(), p = this.pos();
      this.timeEl.textContent = `${fmtDuration(p)} / ${fmtDuration(d)}`;
      const pct = d ? (p / d) * 100 : 0;
      this.fill.style.width = `${pct}%`; this.knob.style.left = `${pct}%`;
      let bufEnd = 0;
      for (let i = 0; i < v.buffered.length; i++) if (v.buffered.start(i) <= v.currentTime + 1) bufEnd = Math.max(bufEnd, v.buffered.end(i));
      this.buf.style.width = d ? `${Math.min(100, ((this.base() + bufEnd) / d) * 100)}%` : '0';
      this.updateSkip(p, d);
    }
    if (this.infoEl) this.renderInfo();
  }

  // ---------- Markers / skip / up next ----------
  renderMarkers() {
    this.marks.replaceChildren();
    const d = this.meta.duration;
    if (!d) return;
    for (const m of this.meta.markers || []) {
      this.marks.append(h('div', { class: 'mk', style: { left: `${(m.start / d) * 100}%`, width: `${((m.end - m.start) / d) * 100}%` } }));
    }
  }
  updateSkip(p, d) {
    const m = (this.meta.markers || []).find(x => p >= x.start && p < x.end - 1);
    const wantSkip = m && (m.type === 'intro' || m.type === 'commercial' || (m.type === 'credits' && !this.meta.next));
    if (wantSkip && this.skipFor !== m) {
      this.skipFor = m;
      this.skipEl?.remove();
      this.skipEl = h('div', { class: 'p-skip' }, h('button', { class: 'btn', onClick: (e) => { e.stopPropagation(); this.seek(m.end); this.skipEl?.remove(); this.skipEl = null; } },
        m.type === 'intro' ? 'Skip Intro' : m.type === 'credits' ? 'Skip Credits' : 'Skip'));
      this.el.append(this.skipEl);
    } else if (!wantSkip && this.skipEl) { this.skipEl.remove(); this.skipEl = null; this.skipFor = null; }

    const credits = (this.meta.markers || []).find(x => x.type === 'credits');
    const nearEnd = this.meta.next && d && ((credits && p >= credits.start) || d - p < 30);
    if (nearEnd && !this.upNextEl && !this.upNextDismissed) {
      let left = 10;
      const cnt = h('span', {}, String(left));
      this.upNextEl = h('div', { class: 'p-upnext' }, h('div', { class: 'dim small' }, 'Up next'), h('div', { style: { fontWeight: 700, margin: '4px 0 10px' } }, this.meta.next.label),
        h('div', { class: 'row' }, h('button', { class: 'btn primary sm', onClick: e => { e.stopPropagation(); this.meta.next.run(); } }, 'Play now (', cnt, ')'),
          h('button', { class: 'btn sm', onClick: e => { e.stopPropagation(); this.upNextDismissed = true; clearInterval(this.upNextTimer); this.upNextEl.remove(); this.upNextEl = null; } }, 'Cancel')));
      this.el.append(this.upNextEl);
      this.upNextTimer = setInterval(() => { if (this.video.paused) return; left--; cnt.textContent = String(left); if (left <= 0) { clearInterval(this.upNextTimer); this.meta.next?.run(); } }, 1000);
    } else if (!nearEnd && this.upNextEl) { clearInterval(this.upNextTimer); this.upNextEl.remove(); this.upNextEl = null; }
  }
  ended() {
    if (this.meta.live) return;
    this.report('stopped');
    if (this.desc.restartOnSeek && this.dur() - this.pos() > 15) return; // event playlist caught up; not truly ended
    if (this.meta.next) this.meta.next.run();
  }

  // ---------- Basic controls ----------
  toggle() { if (this.video.paused) this.video.play().catch(() => {}); else this.video.pause(); this.poke(); }
  setMuted(m) { this.video.muted = m; localStorage.setItem('lumen.muted', String(m)); }
  fullscreen() {
    if (document.fullscreenElement) document.exitFullscreen();
    else (this.el.requestFullscreen?.() || this.video.webkitEnterFullscreen?.());
  }
  async pip() {
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else await this.video.requestPictureInPicture();
    } catch (e) { toast('Picture-in-picture unavailable'); }
  }
  setBusy(b) {
    if (b) { if (!this.center.querySelector('.spinner')) this.center.replaceChildren(h('div', { class: 'spinner' })); }
    else if (this.center.querySelector('.spinner')) this.center.replaceChildren();
  }
  showError(msg) {
    this.center.replaceChildren(h('div', { class: 'p-err' }, h('h3', { style: { margin: '0 0 8px' } }, 'Unable to play'), h('div', { class: 'muted' }, msg),
      h('div', { class: 'row', style: { justifyContent: 'center', marginTop: '14px' } },
        this.meta.reload ? h('button', { class: 'btn primary', onClick: (e) => { e.stopPropagation(); this.reload({ mode: 'transcode' }); } }, 'Retry with transcoding') : null,
        h('button', { class: 'btn', onClick: (e) => { e.stopPropagation(); this.close(); } }, 'Close'))));
    this.errorShown = true;
  }
  clearError() { if (this.errorShown) { this.center.replaceChildren(); this.errorShown = false; } }

  poke() {
    this.el.classList.remove('hide-ui');
    clearTimeout(this.hideT);
    this.hideT = setTimeout(() => { if (!this.video.paused && !this.menuEl && !this.sideEl) this.el.classList.add('hide-ui'); }, 3200);
  }

  report(state) {
    if (!this.meta.progress || this.meta.live) return;
    const p = this.pos(), d = this.dur();
    if (p > 1) this.meta.progress(p, d, state);
  }

  // ---------- Menus ----------
  closeMenu() { this.menuEl?.remove(); this.menuEl = null; }
  menu(kind) {
    if (this.menuEl?.dataset.kind === kind) return this.closeMenu();
    this.closeMenu();
    const m = this.meta;
    const opt = (label, sel, fn) => h('div', { class: `opt ${sel ? 'sel' : ''}`, onClick: (e) => { e.stopPropagation(); this.closeMenu(); fn(); } }, label);
    const items = [];
    if (kind === 'audio') {
      items.push(h('h5', {}, 'Audio'));
      for (const a of m.audio || []) items.push(opt(a.label, a.selected, () => this.reload({ audio: a.id })));
    } else if (kind === 'subs') {
      items.push(h('h5', {}, 'Subtitles'));
      const anySel = (m.subtitles || []).some(s => s.selected);
      items.push(opt('Off', !anySel, () => this.reload({ subtitle: 0 })));
      for (const s of m.subtitles || []) items.push(opt(s.label, s.selected, () => this.reload({ subtitle: s.id })));
    } else if (kind === 'quality') {
      if (m.qualities?.length) {
        items.push(h('h5', {}, 'Quality'));
        for (const q of m.qualities) items.push(opt(q.label, String(q.value) === String(m.quality), () => this.reload({ quality: q.value })));
      }
      if (m.versions?.length > 1) {
        items.push(h('h5', {}, 'Version'));
        for (const v of m.versions) items.push(opt(v.label, v.index === (m.mediaIndex || 0), () => this.reload({ mediaIndex: v.index })));
      }
      if (m.reload) {
        items.push(h('h5', {}, 'Playback mode'));
        const cur = String(this.desc.method || '');
        for (const [val, label] of [['direct', 'Direct (no processing)'], ['remux', 'Remux (convert audio)'], ['transcode', 'Transcode (hardware if enabled)']]) {
          items.push(opt(label, cur.includes(val) || (val === 'direct' && cur.startsWith('plex-directplay')), () => this.reload({ mode: val })));
        }
      }
      items.push(h('h5', {}, 'Speed'));
      for (const r of [0.5, 0.75, 1, 1.25, 1.5, 2]) items.push(opt(`${r}×`, this.video.playbackRate === r, () => { this.video.playbackRate = r; }));
      items.push(h('h5', {}, 'Aspect'));
      for (const [val, label] of [['contain', 'Fit'], ['cover', 'Fill / Zoom'], ['fill', 'Stretch']]) items.push(opt(label, (this.video.style.objectFit || 'contain') === val, () => { this.video.style.objectFit = val; }));
    }
    this.menuEl = h('div', { class: 'p-menu', dataset: { kind }, onClick: e => e.stopPropagation() }, items);
    this.el.append(this.menuEl);
    this.poke();
  }

  toggleInfo() {
    if (this.infoEl) { this.infoEl.remove(); this.infoEl = null; return; }
    this.infoEl = h('div', { class: 'p-info' });
    this.el.append(this.infoEl);
    this.renderInfo();
  }
  renderInfo() {
    const v = this.video, d = this.desc;
    const q = v.getVideoPlaybackQuality?.();
    const lines = [
      ['Method', d.method || d.type], ['Engine', d.type], ['Resolution', v.videoWidth ? `${v.videoWidth}×${v.videoHeight}` : '–'],
      ['Dropped frames', q ? `${q.droppedVideoFrames}/${q.totalVideoFrames}` : '–'],
      ['Buffer', v.buffered.length ? `${(v.buffered.end(v.buffered.length - 1) - v.currentTime).toFixed(1)}s` : '–'],
    ];
    if (this.engine?.hls) { const l = this.engine.hls.levels?.[this.engine.hls.currentLevel]; if (l) lines.push(['Bitrate', `${Math.round(l.bitrate / 1000)} kbps`]); }
    if (d.sessionId) lines.push(['Session', d.sessionId]);
    this.infoEl.innerHTML = lines.map(([k, val]) => `<div><span style="color:#9aa1b2">${esc(k)}:</span> ${esc(val)}</div>`).join('');
  }

  // ---------- Live TV ----------
  async zap(dir, target) {
    const z = this.meta.zap;
    if (!z?.channels?.length) return;
    let idx = target !== undefined ? target : (z.index + dir + z.channels.length) % z.channels.length;
    const ch = z.channels[idx];
    z.index = idx;
    this.titleEl.textContent = ch.name;
    this.showBanner(ch);
    clearTimeout(this.zapT);
    this.zapT = setTimeout(async () => {
      try {
        this.setBusy(true);
        const { desc, meta } = await z.open(ch);
        this.load(desc, { ...meta, zap: z });
      } catch (e) { this.showError(e.message); }
    }, 350);
  }

  showBanner(ch = this.meta.channel) {
    if (!ch) return;
    this.bannerEl?.remove();
    const now = ch.now, next = ch.next;
    const pct = now ? Math.min(100, ((nowS() - now.start) / (now.stop - now.start)) * 100) : 0;
    this.bannerEl = h('div', { class: 'p-banner' }, logo(ch.logo, ch.name),
      h('div', { class: 'grow' },
        h('div', { class: 'chn' }, ch.name),
        h('div', { class: 'now' }, now?.title || 'No programme information'),
        now ? h('div', { class: 'progress' }, h('i', { style: { width: `${pct}%` } })) : null,
        h('div', { class: 'chn' }, now ? `${fmtTime(now.start)} – ${fmtTime(now.stop)}` : '', next ? `   ·   Next: ${fmtTime(next.start)} ${next.title}` : '')));
    this.el.append(this.bannerEl);
    clearTimeout(this.bannerT);
    const secs = Number(window.lumenSettings?.ui?.zapOverlaySeconds ?? 4);
    this.bannerT = setTimeout(() => { this.bannerEl?.remove(); this.bannerEl = null; }, secs * 1000);
  }

  toggleSide() {
    if (this.sideEl) { this.sideEl.remove(); this.sideEl = null; return; }
    const z = this.meta.zap;
    if (!z) return;
    const list = h('div', { class: 'chlist' }, z.channels.map((c, i) => {
      const pct = c.now ? Math.min(100, ((nowS() - c.now.start) / (c.now.stop - c.now.start)) * 100) : 0;
      return h('div', { class: `ch ${i === z.index ? 'active' : ''}`, onClick: (e) => { e.stopPropagation(); this.toggleSide(); this.zap(0, i); } },
        h('div', { class: 'num' }, c.num ?? ''), logo(c.logo, c.name),
        h('div', { class: 'grow' }, h('div', { class: 'nm ellipsis' }, c.name), h('div', { class: 'pr ellipsis' }, c.now?.title || ''),
          c.now ? h('div', { class: 'progress' }, h('i', { style: { width: `${pct}%` } })) : null));
    }));
    this.sideEl = h('div', { class: 'p-side', onClick: e => e.stopPropagation() }, h('div', { class: 'row', style: { padding: '14px' } }, h('b', { class: 'grow' }, z.title || 'Channels'),
      h('button', { class: 'pbtn', html: icon('close'), onClick: () => this.toggleSide() })), list);
    this.el.append(this.sideEl);
    this.sideEl.querySelector('.ch.active')?.scrollIntoView({ block: 'center' });
  }

  numberEntry(d) {
    this.numBuf = (this.numBuf || '') + d;
    this.numEl?.remove();
    this.numEl = h('div', { class: 'p-numentry' }, this.numBuf);
    this.el.append(this.numEl);
    clearTimeout(this.numT);
    this.numT = setTimeout(() => {
      const n = Number(this.numBuf); this.numBuf = ''; this.numEl?.remove(); this.numEl = null;
      const z = this.meta.zap;
      const idx = z?.channels.findIndex(c => Number(c.num) === n);
      if (idx >= 0) this.zap(0, idx); else toast(`Channel ${n} not found in this list`);
    }, 1400);
  }

  // ---------- Keyboard ----------
  onKey(e) {
    if (e.target.tagName === 'INPUT' && e.target.type !== 'range') return;
    if (document.querySelector('.modal-bg')) return;
    const live = this.meta.live;
    const k = e.key;
    let handled = true;
    if (k === ' ' || k === 'k' || k === 'MediaPlayPause') this.toggle();
    else if (k === 'Escape' || k === 'Backspace') { if (this.menuEl) this.closeMenu(); else if (this.sideEl) this.toggleSide(); else this.close(); }
    else if (k === 'f') this.fullscreen();
    else if (k === 'm') this.setMuted(!this.video.muted);
    else if (k === 'p') this.pip();
    else if (k === 'i') this.toggleInfo();
    else if (k === 'c') this.menu('subs');
    else if (k === 'l' && this.meta.zap) this.toggleSide();
    else if (k === 'ArrowLeft' && !live) this.seek(this.pos() - 10);
    else if (k === 'ArrowRight' && !live) this.seek(this.pos() + 30);
    else if ((k === 'ArrowUp' || k === 'PageUp' || k === 'ChannelUp') && this.meta.zap && !this.sideEl) this.zap(-1);
    else if ((k === 'ArrowDown' || k === 'PageDown' || k === 'ChannelDown') && this.meta.zap && !this.sideEl) this.zap(1);
    else if (k === 'ArrowUp' && !this.sideEl) { this.video.volume = Math.min(1, this.video.volume + 0.05); }
    else if (k === 'ArrowDown' && !this.sideEl) { this.video.volume = Math.max(0, this.video.volume - 0.05); }
    else if (k === 'Enter' && live && this.meta.zap && !this.sideEl) this.showBanner();
    else if (/^\d$/.test(k) && this.meta.zap) this.numberEntry(k);
    else if (k === 'n' && this.meta.next) this.meta.next.run();
    else handled = false;
    if (handled) { e.preventDefault(); this.poke(); }
  }

  close({ fromHistory = false } = {}) {
    if (this.closed) return;
    this.closed = true;
    this.report('stopped');
    this.timers.forEach(clearInterval);
    clearInterval(this.upNextTimer);
    clearTimeout(this.hideT); clearTimeout(this.zapT); clearTimeout(this.bannerT);
    document.removeEventListener('keydown', this.onKey);
    document.removeEventListener('fullscreenchange', this.onFs);
    window.removeEventListener('pagehide', this.onUnload);
    window.removeEventListener('popstate', this.onPop);
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    this.engine?.destroy();
    releaseDesc(this.desc);
    api.clearCache();
    this.el.remove();
    if (current === this) current = null;
    if (!fromHistory && history.state?.player) history.back();
    this.meta.onClose?.();
  }
}

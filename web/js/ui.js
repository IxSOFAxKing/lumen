import { icon } from './icons.js';

// Let append()/replaceChildren() accept nested arrays and skip null/false (used throughout the views).
for (const name of ['append', 'replaceChildren', 'prepend']) {
  const orig = Element.prototype[name];
  Element.prototype[name] = function (...kids) {
    return orig.apply(this, kids.flat(Infinity).filter(k => k !== null && k !== undefined && k !== false && k !== ''));
  };
}

export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

export const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const ico = (name, cls) => h('span', { html: icon(name, cls), style: { display: 'contents' } });

export function btn(label, onClick, { cls = '', iconName, title } = {}) {
  return h('button', { class: `btn ${cls}`, onClick, title, type: 'button', html: (iconName ? icon(iconName) : '') + (label ? `<span>${esc(label)}</span>` : '') });
}

export function fmtDuration(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const hh = Math.floor(sec / 3600), mm = Math.floor((sec % 3600) / 60), ss = sec % 60;
  return hh ? `${hh}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}` : `${mm}:${String(ss).padStart(2, '0')}`;
}
export function fmtRuntime(sec) {
  sec = Math.round(sec || 0); if (!sec) return '';
  const hh = Math.floor(sec / 3600), mm = Math.round((sec % 3600) / 60);
  return hh ? `${hh}h ${mm}m` : `${mm}m`;
}
export const fmtTime = t => new Date(t * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
export const fmtDay = t => new Date(t * 1000).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
export const fmtDateTime = t => `${fmtDay(t)} ${fmtTime(t)}`;
export const fmtBytes = b => { if (!b) return '0 B'; const u = ['B', 'KB', 'MB', 'GB', 'TB']; const i = Math.min(4, Math.floor(Math.log(b) / Math.log(1024))); return `${(b / 1024 ** i).toFixed(i ? 1 : 0)} ${u[i]}`; };
export const nowS = () => Math.floor(Date.now() / 1000);

export function spinner() { return h('div', { class: 'spinner' }); }
export function empty(title, text, action) {
  return h('div', { class: 'empty' }, h('h3', {}, title), text ? h('p', {}, text) : null, action || null);
}

// ---------- Toasts ----------
export function toast(msg, { type = '', timeout = 4000, action } = {}) {
  const el = h('div', { class: `toast ${type}` }, h('div', { class: 'grow' }, msg),
    action ? h('button', { class: 'btn sm primary', onClick: () => { action.onClick(); el.remove(); } }, action.label) : null);
  document.getElementById('toasts').append(el);
  if (timeout) setTimeout(() => el.remove(), timeout);
  return el;
}
export const toastErr = e => toast(e?.message || String(e), { type: 'err', timeout: 6000 });

// ---------- Modal ----------
export function modal({ title, body, actions = [], size = '', onClose } = {}) {
  const root = document.getElementById('modal-root');
  const close = () => { bg.remove(); document.removeEventListener('keydown', onKey, true); onClose?.(); };
  const onKey = e => { if (e.key === 'Escape') { e.stopPropagation(); close(); } };
  const bg = h('div', { class: 'modal-bg', onMousedown: e => { if (e.target === bg) close(); } },
    h('div', { class: `modal ${size}`, role: 'dialog', 'aria-modal': 'true' },
      h('div', { class: 'mh' }, h('h3', {}, title || ''), h('button', { class: 'btn ghost icon sm', onClick: close, html: icon('close'), 'aria-label': 'Close' })),
      h('div', { class: 'mb' }, body),
      actions.length ? h('div', { class: 'mf' }, actions.map(a => h('button', {
        class: `btn ${a.primary ? 'primary' : ''} ${a.danger ? 'danger' : ''}`, type: 'button',
        onClick: async (ev) => { const b = ev.currentTarget; b.disabled = true; try { const r = await a.onClick?.(); if (r !== false) close(); } catch (e) { toastErr(e); } finally { b.disabled = false; } },
      }, a.label))) : null));
  root.append(bg);
  document.addEventListener('keydown', onKey, true);
  return { close, el: bg };
}

export function confirmDialog(title, text, { okLabel = 'OK', danger = false } = {}) {
  return new Promise(resolve => {
    let done = false;
    modal({ title, body: h('p', {}, text), onClose: () => { if (!done) resolve(false); },
      actions: [{ label: 'Cancel', onClick: () => {} }, { label: okLabel, primary: !danger, danger, onClick: () => { done = true; resolve(true); } }] });
  });
}

// ---------- Cards ----------
export function card({ img, title, sub, progress, corner, onClick, wide, square, placeholder, played }) {
  const art = h('div', { class: 'art' },
    img ? h('img', { src: img, loading: 'lazy', alt: '', onError: e => { e.target.replaceWith(h('div', { class: 'ph' }, placeholder || title || '')); } })
      : h('div', { class: 'ph' }, placeholder || title || ''),
    h('div', { class: 'play-ov', html: icon('play') }),
    corner ? h('div', { class: 'corner' }, corner) : null,
    played ? h('div', { class: 'corner', html: icon('check'), style: { padding: '3px' } }) : null,
    progress > 0 && progress < 1 ? h('div', { class: 'progress' }, h('i', { style: { width: `${Math.round(progress * 100)}%` } })) : null);
  return h('div', { class: `card ${wide ? 'wide' : ''} ${square ? 'square' : ''}`, tabindex: 0, onClick, onKeydown: e => { if (e.key === 'Enter') onClick?.(e); } },
    art, h('div', { class: 't', title }, title || ''), sub ? h('div', { class: 's' }, sub) : null);
}

export function section(title, content, more) {
  return h('div', {}, h('h2', { class: 'sec' }, title, more ? h('a', { class: 'more', href: more }, 'See all ›') : null), content);
}
export const hrow = items => h('div', { class: 'hrow' }, items);

export function logo(src, name) {
  const box = h('div', { class: 'logo' });
  if (src) box.append(h('img', { src, loading: 'lazy', alt: '', onError: e => { e.target.replaceWith(document.createTextNode(initials(name))); } }));
  else box.textContent = initials(name);
  return box;
}
export const initials = n => String(n || '?').replace(/[^\p{L}\p{N} ]/gu, '').split(' ').filter(Boolean).slice(0, 2).map(w => w[0]).join('').toUpperCase() || '?';

/** Calls loadMore() when sentinel becomes visible; returns disconnect fn */
export function infinite(container, loadMore) {
  const sentinel = h('div', { style: { height: '1px' } });
  container.after(sentinel);
  let busy = false;
  const io = new IntersectionObserver(async ([e]) => {
    if (!e.isIntersecting || busy) return;
    busy = true;
    try { const more = await loadMore(); if (more === false) io.disconnect(); } finally { busy = false; }
  }, { rootMargin: '600px' });
  io.observe(sentinel);
  return () => { io.disconnect(); sentinel.remove(); };
}

export function debounce(fn, ms = 300) {
  let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

export function select(options, value, onChange, cls = 'input') {
  return h('select', { class: cls, onChange: e => onChange(e.target.value) },
    options.map(o => h('option', { value: o.value, selected: String(o.value) === String(value) }, o.label)));
}

// local prefs
export const prefs = {
  get(k, d) { try { const v = localStorage.getItem('lumen.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('lumen.' + k, JSON.stringify(v)); } catch { /* ignore */ } },
};

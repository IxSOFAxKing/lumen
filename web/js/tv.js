// Remote-control / D-pad support and TV mode (groundwork for the Android TV app).
//
// - Arrow keys move focus to the nearest item in that direction (spatial navigation).
// - Enter/OK activates the focused item; Back/Escape goes back.
// - TV mode (auto-detected on TV browsers / the Android TV app, or forced in Settings) enlarges focus
//   highlights and hides hover-only controls.
import { isPlayerOpen } from './player.js';

const TV_KEY = 'lumen.tvMode'; // 'auto' | 'on' | 'off' (per device)
const TV_UA = /Android TV|AFT[A-Z0-9]|BRAVIA|SmartTV|SMART-TV|Tizen|Web0S|WebOS|GoogleTV|Google TV|CrKey|Leanback|AndroidTV|HbbTV|Roku/i;

export function tvModeSetting() { try { return localStorage.getItem(TV_KEY) || 'auto'; } catch { return 'auto'; } }
export function setTvModeSetting(v) { try { localStorage.setItem(TV_KEY, v); } catch { /* */ } applyTvMode(); }
export function tvDetected() {
  return !!(window.LumenNative?.isTV?.() || new URLSearchParams(location.search).get('tv') === '1' || TV_UA.test(navigator.userAgent));
}
export function isTvMode() {
  const s = tvModeSetting();
  return s === 'on' || (s === 'auto' && tvDetected());
}
export function applyTvMode() { document.body.classList.toggle('tv-mode', isTvMode()); }

// ---------- spatial navigation ----------
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([type=hidden]):not([disabled]), select:not([disabled]), textarea, [tabindex]:not([tabindex="-1"])';

function visible(el) {
  if (!el.isConnected) return false;
  const r = el.getBoundingClientRect();
  if (r.width < 2 || r.height < 2) return false;
  const st = getComputedStyle(el);
  return st.visibility !== 'hidden' && st.display !== 'none' && !el.closest('.hidden, [hidden]');
}

function scope() {
  const modals = document.querySelectorAll('.modal-bg');
  if (modals.length) return modals[modals.length - 1];
  return document.getElementById('app');
}

function candidates(root) {
  return [...root.querySelectorAll(FOCUSABLE)].filter(el => visible(el) && !el.closest('.arrow') && !el.closest('.slide:not(.on)'));
}

/** Nearest focusable in a direction, scored on primary-axis distance plus a penalty for drifting sideways. */
function nearest(from, dir, list) {
  const a = from.getBoundingClientRect();
  const ac = { x: a.left + a.width / 2, y: a.top + a.height / 2 };
  let best = null, bestScore = Infinity;
  for (const el of list) {
    if (el === from || el.contains(from) || from.contains(el)) continue;
    const b = el.getBoundingClientRect();
    const bc = { x: b.left + b.width / 2, y: b.top + b.height / 2 };
    let primary, ortho;
    if (dir === 'right') { if (b.left < a.right - 4 && bc.x <= ac.x + 1) continue; primary = Math.max(0, b.left - a.right); ortho = Math.abs(bc.y - ac.y) - Math.min(a.height, b.height) / 2; }
    else if (dir === 'left') { if (b.right > a.left + 4 && bc.x >= ac.x - 1) continue; primary = Math.max(0, a.left - b.right); ortho = Math.abs(bc.y - ac.y) - Math.min(a.height, b.height) / 2; }
    else if (dir === 'down') { if (b.top < a.bottom - 4 && bc.y <= ac.y + 1) continue; primary = Math.max(0, b.top - a.bottom); ortho = Math.abs(bc.x - ac.x) - Math.min(a.width, b.width) / 2; }
    else { if (b.bottom > a.top + 4 && bc.y >= ac.y - 1) continue; primary = Math.max(0, a.top - b.bottom); ortho = Math.abs(bc.x - ac.x) - Math.min(a.width, b.width) / 2; }
    const score = primary + Math.max(0, ortho) * 2.5;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  return best;
}

function focusEl(el) {
  el.focus({ preventScroll: true });
  el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: isTvMode() ? 'smooth' : 'auto' });
}

function isTyping(el) {
  return el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !['checkbox', 'radio', 'range', 'button', 'submit', 'color', 'file'].includes(el.type)) || el.isContentEditable);
}

/** Back action (also called by the Android TV app's back button via window.lumenBack()). Returns true if handled. */
export function goBack() {
  const modal = document.querySelector('.modal-bg:last-of-type');
  if (modal) { modal.querySelector('.mh .btn')?.click(); return true; }
  if (location.hash && !/^#\/?(home)?$/.test(location.hash) && history.length > 1) { history.back(); return true; }
  return false;
}

export function initTv() {
  // Inside the Android TV app the page is laid out at a fixed width so it fits the screen at a readable size.
  try {
    const vw = window.LumenNative?.viewportWidth?.();
    if (vw) document.querySelector('meta[name=viewport]')?.setAttribute('content', `width=${vw}`);
  } catch { /* older app */ }
  applyTvMode();
  window.lumenBack = () => (isPlayerOpen() ? (document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })), true) : goBack());

  document.addEventListener('keydown', e => {
    if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
    if (isPlayerOpen()) return; // the player has its own remote handling
    const k = e.key;
    const active = document.activeElement;
    const dirs = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

    if (dirs[k]) {
      // Let text fields keep left/right for the caret, selects and ranges keep their own keys.
      if (isTyping(active) && (k === 'ArrowLeft' || k === 'ArrowRight')) return;
      if (active?.tagName === 'SELECT') return;
      // Sliders (e.g. interface size): Left/Right change the value with the remote.
      if (active?.type === 'range' && (k === 'ArrowLeft' || k === 'ArrowRight')) {
        const step = Number(active.step) || 1;
        const v = Math.min(Number(active.max || 100), Math.max(Number(active.min || 0), Number(active.value) + (k === 'ArrowRight' ? step : -step)));
        active.value = String(v);
        active.dispatchEvent(new Event('input', { bubbles: true }));
        active.dispatchEvent(new Event('change', { bubbles: true }));
        e.preventDefault();
        return;
      }
      const root = scope();
      const list = candidates(root);
      if (!list.length) return;
      if (!active || active === document.body || !root.contains(active)) {
        const first = list.find(el => el.closest('#view')) || list[0];
        focusEl(first); e.preventDefault(); return;
      }
      const next = nearest(active, dirs[k], list);
      if (next) { focusEl(next); e.preventDefault(); }
      return;
    }
    if (k === 'Enter' && active && active !== document.body && !isTyping(active)) {
      const tag = active.tagName;
      if (tag !== 'A' && tag !== 'BUTTON' && tag !== 'SELECT' && tag !== 'INPUT') { active.click(); e.preventDefault(); }
      return;
    }
    if ((k === 'Escape' || k === 'GoBack' || k === 'BrowserBack' || (k === 'Backspace' && !isTyping(active))) && !document.querySelector('.modal-bg')) {
      if (goBack()) e.preventDefault();
    }
  });

  // Show focus rings only for keyboard/remote users on desktop; always in TV mode.
  document.addEventListener('mousedown', () => document.body.classList.remove('kbd-nav'), true);
  document.addEventListener('keydown', e => { if (e.key.startsWith('Arrow') || e.key === 'Tab') document.body.classList.add('kbd-nav'); }, true);
}

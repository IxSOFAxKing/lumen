// Appearance & personalisation: theme, accent, scale, sidebar and home-screen layout.

export const HOME_SECTIONS = [
  { id: 'continue', label: 'Continue Watching', source: 'plex' },
  { id: 'liveFavorites', label: 'Live TV · Favorites', source: 'iptv' },
  { id: 'recentMovies', label: 'Recently Added Movies', source: 'plex' },
  { id: 'recentShows', label: 'Recently Added TV Shows', source: 'plex' },
  { id: 'recentChannels', label: 'Recently Watched Channels', source: 'iptv' },
  { id: 'iptvContinue', label: 'Continue Watching · IPTV', source: 'iptv' },
  { id: 'iptvMovies', label: 'New Movies · IPTV', source: 'iptv' },
  { id: 'iptvSeries', label: 'New Series · IPTV', source: 'iptv' },
  { id: 'plexHubs', label: 'Other Plex rows (Top Rated, On Deck…)', source: 'plex' },
  { id: 'playlists', label: 'Plex Playlists', source: 'plex' },
];

export const ACCENTS = ['#ff9f3d', '#e5a00d', '#ff5a6a', '#f472b6', '#a78bfa', '#6d8cff', '#38bdf8', '#2dd4bf', '#3ccf8e', '#a3e635'];

export const ui = () => window.lumenSettings?.ui || {};

/** Apply an ordering (list of ids) to items, keeping unknown/new items at their natural position. */
export function applyOrder(items, order = []) {
  const byId = new Map(items.map(it => [it.id, it]));
  const out = (order || []).filter(id => byId.has(id)).map(id => byId.get(id));
  const placed = new Set(out.map(it => it.id));
  // Items not in the saved order (e.g. a newly added Plex library) go right after their default predecessor.
  items.forEach((it, i) => {
    if (placed.has(it.id)) return;
    const prev = items.slice(0, i).reverse().find(p => placed.has(p.id));
    const at = prev ? out.indexOf(prev) + 1 : 0;
    out.splice(at, 0, it);
    placed.add(it.id);
  });
  return out;
}

export const heroHidden = () => (ui().homeHidden || []).includes('hero');

const PIN_LABEL = { favList: 'Favourites list', iptvGroup: 'Live TV group', plexCollection: 'Plex collection', plexPlaylist: 'Plex playlist', plexGenre: 'Plex genre' };
export function homeSections() {
  const u = ui();
  const pins = (u.pinnedRows || []).map(p => ({ id: `pin:${p.id}`, label: `📌 ${p.title}`, source: 'pinned', note: PIN_LABEL[p.type] || 'Pinned', pin: p }));
  return applyOrder([...pins, ...HOME_SECTIONS], u.homeOrder).map(s => ({ ...s, hidden: (u.homeHidden || []).includes(s.id) }));
}

// ---------- colour helpers ----------
function hexToRgb(hex) {
  const m = String(hex || '').replace('#', '').match(/^([0-9a-f]{6})$/i);
  if (!m) return [255, 159, 61];
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
const toHex = rgb => '#' + rgb.map(v => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');
const shade = (rgb, f) => rgb.map(v => v * f);
const luminance = ([r, g, b]) => {
  const c = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * c(r) + 0.7152 * c(g) + 0.0722 * c(b);
};

let systemListener = null;

export function applyAppearance(u = ui()) {
  const root = document.documentElement;
  // Theme
  const resolveTheme = () => u.theme === 'system' ? (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark') : (u.theme || 'dark');
  root.dataset.theme = resolveTheme();
  if (u.theme === 'system' && !systemListener) {
    systemListener = () => { if (ui().theme === 'system') root.dataset.theme = resolveTheme(); };
    matchMedia('(prefers-color-scheme: light)').addEventListener('change', systemListener);
  }
  // Accent
  const rgb = hexToRgb(u.accent || '#ff9f3d');
  root.style.setProperty('--accent', toHex(rgb));
  root.style.setProperty('--accent-2', toHex(shade(rgb, 0.78)));
  root.style.setProperty('--accent-ink', luminance(rgb) > 0.35 ? '#15100a' : '#ffffff');
  // Scale & sizes
  const app = document.getElementById('app');
  if (app) {
    // Zooming #app also zooms its 100vh height: shrink it back so the whole app (sidebar bottom included) fits the window.
    const z = (Number(u.uiScale) || 100) / 100;
    app.style.zoom = String(z);
    app.style.height = z === 1 ? '' : `calc(100dvh / ${z})`;
  }
  const card = { small: [118, 210], medium: [150, 260], large: [190, 330] }[u.cardSize] || [150, 260];
  root.style.setProperty('--card-w', `${card[0]}px`);
  root.style.setProperty('--card-wide-w', `${card[1]}px`);
  root.style.setProperty('--guide-row-h', `${Number(u.guideRowHeight) || 62}px`);
  // Body flags
  document.body.classList.toggle('no-card-titles', u.showCardTitles === false);
  document.body.classList.toggle('blur-spoilers', !!u.blurSpoilers);
  const nav = document.getElementById('nav');
  if (nav) { nav.classList.remove('style-rail', 'style-wide', 'style-icons'); nav.classList.add(`style-${u.navStyle || 'rail'}`); }
  // Subtitles (browser-rendered text tracks)
  let st = document.getElementById('lumen-dynamic-style');
  if (!st) { st = document.createElement('style'); st.id = 'lumen-dynamic-style'; document.head.append(st); }
  const bg = Math.max(0, Math.min(100, Number(u.subtitleBg ?? 60))) / 100;
  st.textContent = `.player video::cue { font-size: ${(Number(u.subtitleSize) || 100) * 0.011}em; color: ${u.subtitleColor || '#fff'}; background: rgba(0,0,0,${bg}); }`;
}

// ---------- Pinned Home rows ----------
// pin: { id, type: 'plexCollection'|'plexPlaylist'|'plexGenre'|'iptvGroup'|'favList', title, ...params }
export const pinnedRows = () => ui().pinnedRows || [];
export async function addPin(pin, apiPut) {
  const list = pinnedRows().filter(p => p.id !== pin.id);
  list.push(pin);
  await saveUi({ pinnedRows: list }, apiPut);
}
export async function removePin(id, apiPut) {
  const list = pinnedRows().filter(p => p.id !== id);
  await saveUi({ pinnedRows: list }, apiPut);
}
export const isPinned = id => pinnedRows().some(p => p.id === id);

// ---------- Ratings ----------
/** Rating label(s) for a Plex item according to Settings → Appearance → Ratings. */
export function ratingText(m) {
  const mode = ui().ratingDisplay || 'audience';
  if (mode === 'none' || !m) return null;
  const critic = m.rating ? (/rottentomatoes/.test(m.ratingImage || '') ? `🍅 ${Math.round(m.rating * 10)}%` : `Critics ${Number(m.rating).toFixed(1)}`) : null;
  const aud = m.audienceRating ? (/rottentomatoes/.test(m.audienceRatingImage || '') ? `🍿 ${Math.round(m.audienceRating * 10)}%` : `★ ${Number(m.audienceRating).toFixed(1)}`) : null;
  if (mode === 'critic') return critic || aud;
  if (mode === 'both') return [critic, aud].filter(Boolean).join('  ') || null;
  return aud || critic;
}

// ---------- Per-device settings ----------
// When enabled, appearance/player settings for this browser are stored locally (e.g. a TV can use a
// bigger scale and a different sidebar than the desktop). Pinned rows and hidden Plex rows stay shared.
const DEVICE_KEY = 'lumen.deviceUi';
const SHARED_KEYS = ['pinnedRows', 'hiddenHubs'];
function readDevice() { try { return JSON.parse(localStorage.getItem(DEVICE_KEY) || 'null'); } catch { return null; } }
function writeDevice(v) { try { if (v) localStorage.setItem(DEVICE_KEY, JSON.stringify(v)); else localStorage.removeItem(DEVICE_KEY); } catch { /* storage blocked */ } }
const omitShared = o => Object.fromEntries(Object.entries(o || {}).filter(([k]) => !SHARED_KEYS.includes(k)));

export const deviceMode = () => !!readDevice();
/** Server UI settings with this device's overrides applied. */
export function withDeviceOverrides(serverUi) { const d = readDevice(); return d ? { ...serverUi, ...omitShared(d) } : serverUi; }
export function setDeviceMode(on) { writeDevice(on ? omitShared(window.lumenSettings?.ui) : null); }

/** Save UI settings — to this device when per-device mode is on (except shared keys), otherwise to the server. */
export async function saveUi(patch, apiPut) {
  Object.assign(window.lumenSettings.ui, patch);
  const d = readDevice();
  if (!d) return apiPut('/api/settings/ui', patch);
  const shared = {}, local = { ...d };
  for (const [k, v] of Object.entries(patch)) (SHARED_KEYS.includes(k) ? shared : local)[k] = v;
  writeDevice(local);
  if (Object.keys(shared).length) return apiPut('/api/settings/ui', shared);
}

// ---------- Clock ----------
let clockTimer = null;
export function clockText() {
  const mode = ui().clock;
  if (!mode || mode === 'off') return '';
  return new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', hour12: mode === '12' });
}
export function startClock() {
  let el = document.getElementById('lumen-clock');
  if (!ui().clock || ui().clock === 'off') { el?.remove(); clearInterval(clockTimer); clockTimer = null; return; }
  if (!el) { el = document.createElement('div'); el.id = 'lumen-clock'; document.body.append(el); }
  const tick = () => { el.textContent = clockText(); };
  tick();
  clearInterval(clockTimer); clockTimer = setInterval(tick, 10000);
}

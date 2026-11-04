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

export function homeSections() {
  const u = ui();
  return applyOrder(HOME_SECTIONS, u.homeOrder).map(s => ({ ...s, hidden: (u.homeHidden || []).includes(s.id) }));
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
  if (app) app.style.zoom = String((Number(u.uiScale) || 100) / 100);
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

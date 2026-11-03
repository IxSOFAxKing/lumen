import { api } from './api.js';
import { h, toast, prefs } from './ui.js';
import { icon } from './icons.js';
import { isPlayerOpen } from './player.js';
import { playLive } from './playback.js';

export const store = { providers: [], libraries: [], plex: null, settings: null };

const routes = {
  home: () => import('./views/home.js'),
  live: () => import('./views/live.js'),
  guide: () => import('./views/guide.js'),
  vod: () => import('./views/vod.js'),
  'vod-item': () => import('./views/vod.js'),
  library: () => import('./views/library.js'),
  item: () => import('./views/item.js'),
  playlist: () => import('./views/item.js'),
  collection: () => import('./views/item.js'),
  recordings: () => import('./views/recordings.js'),
  search: () => import('./views/search.js'),
  settings: () => import('./views/settings.js'),
  multiview: () => import('./views/multiview.js'),
};

export function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, query = ''] = raw.split('?');
  const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
  return { name: parts[0] || '', parts: parts.slice(1), query: Object.fromEntries(new URLSearchParams(query)) };
}
export const go = (hash) => { location.hash = hash; };

let cleanup = null;
let renderToken = 0;

async function render() {
  if (isPlayerOpen()) return;
  const route = parseHash();
  if (!route.name) { go(`#/${store.settings?.ui?.startPage || 'home'}`); return; }
  const loader = routes[route.name] || routes.home;
  const view = document.getElementById('view');
  const token = ++renderToken;
  try { cleanup?.(); } catch { /* ignore */ }
  cleanup = null;
  renderNav(route);
  view.replaceChildren(h('div', { class: 'spinner' }));
  try {
    const mod = await loader();
    if (token !== renderToken) return;
    const container = h('div');
    view.replaceChildren(container);
    view.scrollTop = 0;
    const fn = mod.views?.[route.name] || mod.default;
    cleanup = (await fn(container, route)) || null;
  } catch (e) {
    console.error(e);
    if (token === renderToken) view.replaceChildren(h('div', { class: 'empty' }, h('h3', {}, 'Something went wrong'), h('p', {}, e.message)));
  }
}

function renderNav(route) {
  const nav = document.getElementById('nav');
  const item = (href, label, ic, active) => h('a', { class: `nav-item ${active ? 'active' : ''}`, href, html: `${icon(ic)}<span>${label}</span>` });
  const n = route.name;
  const libIcon = t => t === 'movie' ? 'movie' : t === 'show' ? 'series' : 'library';
  const hasIptv = store.providers.length > 0;
  nav.replaceChildren(
    h('div', { class: 'brand', html: '<img src="/img/icon.svg" alt=""><span>Lumen</span>' }),
    item('#/home', 'Home', 'home', n === 'home'),
    item('#/search', 'Search', 'search', n === 'search'),
    hasIptv ? h('div', { class: 'nav-sec' }, 'Live TV') : null,
    hasIptv ? item('#/live', 'Live TV', 'tv', n === 'live') : null,
    hasIptv ? item('#/guide', 'TV Guide', 'guide', n === 'guide') : null,
    hasIptv ? item('#/multiview', 'Multi-View', 'grid', n === 'multiview') : null,
    hasIptv ? item('#/vod?kind=movie', 'Movies', 'movie', (n === 'vod' || n === 'vod-item') && (route.query.kind || route.parts[0]) !== 'series') : null,
    hasIptv ? item('#/vod?kind=series', 'Series', 'series', (n === 'vod' || n === 'vod-item') && (route.query.kind || route.parts[0]) === 'series') : null,
    hasIptv ? item('#/recordings', 'Recordings', 'rec', n === 'recordings') : null,
    store.libraries.length ? h('div', { class: 'nav-sec' }, store.plex?.serverName || store.plex?.name || 'Plex') : null,
    store.libraries.filter(l => l.type === 'movie' || l.type === 'show')
      .map(l => item(`#/library/${l.key}`, l.title, libIcon(l.type), n === 'library' && route.parts[0] === String(l.key))),
    h('div', { class: 'spacer' }),
    item('#/settings', 'Settings', 'settings', n === 'settings'),
  );
}

export async function refreshStore() {
  const [providers, plex, settings] = await Promise.all([
    api.get('/api/iptv/providers').catch(() => []),
    api.get('/api/plex/status').catch(() => ({ configured: false })),
    api.get('/api/settings').catch(() => null),
  ]);
  store.providers = providers.filter(p => p.enabled);
  store.allProviders = providers;
  store.plex = plex;
  store.settings = settings;
  window.lumenSettings = settings;
  document.documentElement.dataset.theme = settings?.ui?.theme || 'dark';
  store.libraries = plex.configured && plex.online ? await api.get('/api/plex/libraries').catch(() => []) : [];
  renderNav(parseHash());
}

/** Currently selected IPTV provider id (persisted). */
export function activeProvider() {
  const saved = prefs.get('provider');
  const p = store.providers.find(x => x.id === saved) || store.providers[0];
  return p || null;
}
export function setActiveProvider(id) { prefs.set('provider', Number(id)); }

// ---------- Login ----------
async function showLogin() {
  document.getElementById('app').classList.add('hidden');
  const input = h('input', { class: 'input', type: 'password', placeholder: 'Password', autofocus: true, style: { width: '100%' } });
  const err = h('div', { class: 'small', style: { color: 'var(--err)', minHeight: '20px', marginTop: '8px' } });
  const box = h('div', { class: 'login' }, h('form', { class: 'card-box', onSubmit: async (e) => {
    e.preventDefault();
    try { await api.post('/api/auth/login', { password: input.value }); box.remove(); document.getElementById('app').classList.remove('hidden'); start(); }
    catch (ex) { err.textContent = ex.message; }
  } }, h('img', { src: '/img/icon.svg', alt: '' }), h('h2', { style: { margin: '0 0 18px' } }, 'Lumen'), input, err,
    h('button', { class: 'btn primary', style: { width: '100%', marginTop: '6px' }, type: 'submit' }, 'Sign in')));
  document.body.append(box);
}

// ---------- Reminders ----------
const notified = new Set();
async function checkReminders() {
  try {
    const list = await api.get('/api/iptv/reminders');
    const now = Date.now() / 1000;
    for (const r of list) {
      if (notified.has(r.id) || r.start - now > 60 || now - r.start > 300) continue;
      notified.add(r.id);
      toast(`⏰ "${r.title}" is starting on ${r.channel_name}`, {
        timeout: 30000,
        action: { label: 'Watch', onClick: async () => {
          const chans = await api.get(`/api/iptv/providers/${r.provider_id}/channels?q=${encodeURIComponent(r.channel_name)}`);
          const ch = chans.find(c => c.id === r.channel_id) || chans[0];
          if (ch) playLive(r.provider_id, ch, [ch]);
        } },
      });
      if (document.hidden && 'Notification' in window && Notification.permission === 'granted') new Notification('Lumen reminder', { body: `${r.title} on ${r.channel_name}` });
      api.del(`/api/iptv/reminders/${r.id}`).catch(() => {});
    }
  } catch { /* ignore */ }
}

let started = false;
async function start() {
  if (started) return; started = true;
  await refreshStore();
  window.addEventListener('hashchange', render);
  window.addEventListener('lumen:unauthorized', () => location.reload());
  render();
  setInterval(checkReminders, 30000);
  checkReminders();
}

(async function boot() {
  const s = await api.get('/api/auth/status').catch(() => ({ required: false, authed: true }));
  if (s.required && !s.authed) return showLogin();
  start();
})();

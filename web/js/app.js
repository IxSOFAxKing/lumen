import { api } from './api.js';
import { h, toast, prefs, esc } from './ui.js';
import { applyAppearance, applyOrder, withDeviceOverrides, startClock } from './appearance.js';
import { icon } from './icons.js';
import { isPlayerOpen } from './player.js';
import { playLive } from './playback.js';
import { initTv } from './tv.js';

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
  pair: () => import('./views/settings.js'),
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

/** Every sidebar entry that can currently be shown (before the user's order/visibility is applied). */
export function navEntries(route = parseHash()) {
  const n = route.name;
  const libIcon = t => t === 'movie' ? 'movie' : t === 'show' ? 'series' : 'library';
  const hasIptv = store.providers.length > 0;
  const prov = activeProvider();
  const vodKind = route.query.kind || route.parts[0];
  const list = [
    { id: 'home', href: '#/home', label: 'Home', icon: 'home', active: n === 'home' },
    { id: 'search', href: '#/search', label: 'Search', icon: 'search', active: n === 'search' },
  ];
  if (hasIptv) list.push(
    { id: 'live', href: '#/live', label: 'Live TV', icon: 'tv', active: n === 'live', group: 'iptv' },
    { id: 'guide', href: '#/guide', label: 'Guide', icon: 'guide', active: n === 'guide', group: 'iptv' },
    { id: 'multiview', href: '#/multiview', label: 'Multi-View', icon: 'grid', active: n === 'multiview', group: 'iptv' },
    prov?.vod_enabled ? { id: 'vodMovies', href: '#/vod?kind=movie', label: 'VOD Movies', icon: 'movie', active: (n === 'vod' || n === 'vod-item') && vodKind !== 'series', group: 'iptv' } : null,
    prov?.series_enabled ? { id: 'vodSeries', href: '#/vod?kind=series', label: 'VOD Series', icon: 'series', active: (n === 'vod' || n === 'vod-item') && vodKind === 'series', group: 'iptv' } : null,
    { id: 'dvr', href: '#/recordings', label: 'DVR', icon: 'rec', active: n === 'recordings', group: 'iptv' },
  );
  for (const l of store.libraries.filter(l => l.type === 'movie' || l.type === 'show')) {
    list.push({ id: `lib:${l.key}`, href: `#/library/${l.key}`, label: l.title, icon: libIcon(l.type), active: n === 'library' && route.parts[0] === String(l.key), group: 'plex' });
  }
  return list.filter(Boolean);
}

function renderNav(route) {
  const nav = document.getElementById('nav');
  const u = window.lumenSettings?.ui || {};
  const hidden = new Set(u.navHidden || []);
  const entries = applyOrder(navEntries(route), u.navOrder).filter(e => !hidden.has(e.id));
  const item = e => h('a', { class: `nav-item ${e.active ? 'active' : ''}`, href: e.href, title: e.label, html: `${icon(e.icon)}<span>${esc(e.label)}</span>` });
  const kids = [h('div', { class: 'brand', html: '<img src="/img/icon.svg" alt=""><span>Lumen</span>' })];
  entries.forEach((e, i) => {
    // thin divider whenever the source changes (general / IPTV / Plex)
    if (i > 0 && (e.group || 'main') !== (entries[i - 1].group || 'main')) kids.push(h('div', { class: 'nav-sec' }));
    kids.push(item(e));
  });
  kids.push(h('div', { class: 'spacer' }), item({ href: '#/settings', label: 'Settings', icon: 'settings', active: route.name === 'settings' }));
  nav.replaceChildren(...kids);
}
export function rerenderNav() { renderNav(parseHash()); }

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
  if (settings) settings.ui = withDeviceOverrides(settings.ui || {});
  window.lumenSettings = settings;
  applyAppearance(settings?.ui || {});
  startClock();
  store.libraries = plex.configured && plex.online ? await api.cached('/api/plex/libraries', 300000).catch(() => []) : [];
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
async function showLogin({ setup = false } = {}) {
  if (document.querySelector('.login')) return;
  document.getElementById('app').classList.add('hidden');
  const user = h('input', { class: 'input', type: 'text', placeholder: 'Username', autocomplete: 'username', autocapitalize: 'off', style: { width: '100%', marginBottom: '10px' } });
  const input = h('input', { class: 'input', type: 'password', placeholder: 'Password', autocomplete: setup ? 'new-password' : 'current-password', style: { width: '100%' } });
  const confirm = setup ? h('input', { class: 'input', type: 'password', placeholder: 'Repeat password', autocomplete: 'new-password', style: { width: '100%', marginTop: '10px' } }) : null;
  const err = h('div', { class: 'small', style: { color: 'var(--err)', minHeight: '20px', marginTop: '8px' } });
  const box = h('div', { class: 'login' }, h('form', { class: 'card-box', onSubmit: async (e) => {
    e.preventDefault();
    if (setup && input.value !== confirm.value) { err.textContent = 'The passwords don\'t match'; return; }
    try {
      await api.post(setup ? '/api/auth/setup' : '/api/auth/login', { username: user.value, password: input.value });
      box.remove(); document.getElementById('app').classList.remove('hidden');
      if (started) location.reload(); else start();
    } catch (ex) { err.textContent = ex.message; }
  } }, h('img', { src: '/img/icon.svg', alt: '' }), h('h2', { style: { margin: '0 0 6px' } }, setup ? 'Welcome to Lumen' : 'Lumen'),
    setup ? h('p', { class: 'muted small', style: { margin: '0 0 16px' } }, 'Create the username and password that will protect this Lumen server.') : h('div', { style: { height: '12px' } }),
    user, input, confirm, err,
    h('button', { class: 'btn primary', style: { width: '100%', marginTop: '6px' }, type: 'submit' }, setup ? 'Create account' : 'Sign in')));
  document.body.append(box);
  setTimeout(() => user.focus(), 50);
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
  // Show the login screen instead of reloading (a reload loop would re-request everything).
  let loggingIn = false;
  window.addEventListener('lumen:unauthorized', (e) => {
    if (window.LumenNative?.unpaired) { window.LumenNative.unpaired(); return; } // the TV app re-pairs instead of showing a password prompt
    if (!loggingIn) { loggingIn = true; showLogin({ setup: !!e.detail?.setup }); }
  });
  render();
  // Optionally jump straight back into the last live channel (TiviMate-style).
  if (window.lumenSettings?.ui?.resumeLastChannel && store.providers.length) {
    api.get('/api/iptv/history').then(list => { const ch = list[0]; if (ch) playLive(ch.provider_id, ch, list.filter(c => c.provider_id === ch.provider_id), 'Recently watched'); }).catch(() => {});
  }
  // Warm up the other views in the background so navigation doesn't wait for module downloads.
  (window.requestIdleCallback || (f => setTimeout(f, 1500)))(() => Object.values(routes).forEach(load => load().catch(() => {})));
  setInterval(checkReminders, 30000);
  checkReminders();
}

(async function boot() {
  initTv();
  const s = await api.get('/api/auth/status').catch(() => ({ required: false, authed: true }));
  if (s.required && !s.authed) return window.LumenNative?.unpaired ? window.LumenNative.unpaired() : showLogin({ setup: !!s.setup });
  start();
})();

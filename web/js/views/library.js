import { api, qs } from '../api.js';
import { h, section, empty, spinner, select, infinite, prefs, skeletonRows } from '../ui.js';
import { icon } from '../icons.js';
import { store } from '../app.js';
import { plexRow, plexCard } from '../plexcards.js';
import { pinButton } from './item.js';
import { ratingText, saveUi } from '../appearance.js';
import { plexImg } from '../api.js';
import { go } from '../app.js';
import { playPlex } from '../playback.js';
import { fmtRuntime } from '../ui.js';

const SORTS = [
  { value: 'titleSort', label: 'Title' }, { value: 'addedAt:desc', label: 'Recently added' },
  { value: 'originallyAvailableAt:desc', label: 'Release date' }, { value: 'rating:desc', label: 'Critic rating' },
  { value: 'audienceRating:desc', label: 'Audience rating' }, { value: 'viewCount:desc', label: 'Most played' },
  { value: 'lastViewedAt:desc', label: 'Recently watched' }, { value: 'duration:desc', label: 'Duration' }, { value: 'random', label: 'Random' },
];

export default async function library(el, route) {
  const id = route.parts[0];
  const lib = store.libraries.find(l => String(l.key) === String(id));
  el.className = 'page';
  const tab = route.query.tab || prefs.get(`libtab.${id}`, 'recommended');
  const tabs = h('div', { class: 'tabs' }, [['recommended', 'Recommended'], ['library', 'Library'], ['collections', 'Collections']].map(([k, label]) =>
    h('a', { class: `tab ${tab === k ? 'active' : ''}`, href: `#/library/${id}?tab=${k}`, onClick: () => prefs.set(`libtab.${id}`, k) }, label)));
  const body = h('div');
  el.append(h('div', { class: 'page-head' }, h('h1', {}, lib?.title || 'Library')), tabs, body);

  if (tab === 'recommended') {
    body.append(...skeletonRows(3));
    const hubs = await api.cached(`/api/plex/library/${id}/hubs`);
    body.replaceChildren();
    if (!hubs.length) body.append(empty('Nothing here yet'));
    for (const hub of hubs) {
      if (!hub.Metadata?.length) continue;
      const wide = hub.type === 'episode' || hub.type === 'clip' || /ondeck|continue/i.test(hub.hubIdentifier || '');
      body.append(section(hub.title, plexRow(hub.Metadata, { wide })));
    }
    return;
  }

  if (tab === 'collections') {
    body.append(spinner());
    const cols = await api.cached(`/api/plex/library/${id}/collections`);
    body.replaceChildren(cols.length ? h('div', { class: 'grid' }, cols.map(c => plexCard(c))) : empty('No collections'));
    return;
  }

  // ---- full library grid ----
  const state = { sort: prefs.get(`libsort.${id}`, 'titleSort'), unwatched: route.query.unwatched === '1', genre: route.query.genre || '', start: 0, total: Infinity };
  const genres = await api.cached(`/api/plex/library/${id}/filter/genre`, 600000).catch(() => []);
  const views = window.lumenSettings?.ui?.libraryViews || {};
  let viewMode = views[id] || 'grid';
  const grid = h('div', { class: viewMode === 'list' ? 'lib-list' : 'grid' });
  const count = h('span', { class: 'dim' });
  const pinSlot = h('span');
  const drawPin = () => {
    const g = genres.find(x => String(x.key) === String(state.genre));
    pinSlot.replaceChildren(g ? pinButton({ id: `plexGenre:${id}:${g.key}`, type: 'plexGenre', section: id, genre: g.key, title: `${g.title} · ${lib?.title || 'Library'}` }) : '');
  };
  const viewToggle = h('div', { class: 'chips' },
    ...[['grid', 'Grid'], ['list', 'List']].map(([v, l]) => h('button', { class: `chip ${viewMode === v ? 'active' : ''}`, dataset: { v }, onClick: async (e) => {
      viewMode = v;
      viewToggle.querySelectorAll('.chip').forEach(c => c.classList.toggle('active', c.dataset.v === v));
      const lv = { ...(window.lumenSettings.ui.libraryViews || {}), [id]: v };
      saveUi({ libraryViews: lv }, api.put).catch(() => {});
      grid.className = v === 'list' ? 'lib-list' : 'grid';
      reload();
    } }, l)));
  const bar = h('div', { class: 'row wrap', style: { marginBottom: '16px' } },
    select(SORTS, state.sort, v => { state.sort = v; prefs.set(`libsort.${id}`, v); reload(); }),
    select([{ value: '', label: 'All genres' }, ...genres.map(g => ({ value: g.key, label: g.title }))], state.genre, v => { state.genre = v; drawPin(); reload(); }),
    h('label', { class: 'check', style: { margin: 0 } }, h('input', { type: 'checkbox', checked: state.unwatched, onChange: e => { state.unwatched = e.target.checked; reload(); } }), 'Unwatched only'),
    pinSlot, h('span', { class: 'spacer' }), count, viewToggle);
  drawPin();
  body.append(bar, grid);

  async function load() {
    if (state.start >= state.total) return false;
    const d = await api.cached(`/api/plex/library/${id}/items${qs({ sort: state.sort, start: state.start, size: 60, unwatched: state.unwatched ? 1 : '', genre: state.genre })}`);
    state.total = d.total; state.start += d.items.length;
    count.textContent = `${d.total} items`;
    grid.append(...d.items.map(m => viewMode === 'list' ? listRow(m) : plexCard(m)));
    if (!d.items.length && state.start === 0) grid.append(empty('No items match'));
    return state.start < state.total;
  }
  let stop = null;
  async function reload() {
    stop?.();
    grid.replaceChildren(); state.start = 0; state.total = Infinity;
    await load();
    stop = infinite(grid, load);
  }
  await reload();
  return () => stop?.();
}

/** List-view row for a library item: poster, title, year, runtime, rating, summary. */
function listRow(m) {
  const prog = m.viewOffset && m.duration ? m.viewOffset / m.duration : 0;
  const unwatched = m.type === 'show' ? (m.leafCount || 0) - (m.viewedLeafCount || 0) : 0;
  const meta = [m.year, m.type === 'show' ? (m.childCount ? `${m.childCount} season${m.childCount > 1 ? 's' : ''}` : null) : (m.duration ? fmtRuntime(m.duration / 1000) : null),
    m.contentRating, ratingText(m), unwatched > 0 ? `${unwatched} unwatched` : (m.viewCount > 0 && !m.viewOffset ? '✓ Watched' : null)].filter(Boolean).join('  ·  ');
  return h('div', { class: 'lib-row', tabindex: 0, onClick: () => go(`#/item/${m.ratingKey}`) },
    h('div', { class: 'thumb' }, m.thumb ? h('img', { src: plexImg(m.thumb, 160, 240), loading: 'lazy', alt: '' }) : null, prog ? h('div', { class: 'progress' }, h('i', { style: { width: `${prog * 100}%` } })) : null),
    h('div', { class: 'grow' }, h('div', { class: 'tt' }, m.title), h('div', { class: 'dim small' }, meta), m.summary ? h('div', { class: 'sum' }, m.summary) : null),
    (m.type === 'movie' || m.type === 'episode') ? h('button', { class: 'btn ghost icon', title: 'Play', html: icon('play'), onClick: e => { e.stopPropagation(); playPlex(m.ratingKey, { offset: (m.viewOffset || 0) / 1000 }); } }) : null);
}

import { api, qs } from '../api.js';
import { h, card, empty, spinner, btn, debounce, infinite, select, fmtDuration, fmtRuntime, prefs, section, hrow, skeletonRows } from '../ui.js';
import { store, activeProvider, setActiveProvider, go } from '../app.js';
import { playVod } from '../playback.js';

const SORTS = [{ value: 'added', label: 'Recently added' }, { value: 'name', label: 'Name' }, { value: 'rating', label: 'Rating' }, { value: 'year', label: 'Year' }];

async function vodBrowse(el, route) {
  const prov = activeProvider();
  if (!prov) { el.append(empty('No IPTV provider', 'Add one in Settings.', btn('Settings', () => go('#/settings'), { cls: 'primary' }))); return; }
  const pid = prov.id;
  const kind = route.query.kind === 'series' ? 'series' : 'movie';
  el.className = 'page';
  const enabled = kind === 'series' ? prov.series_enabled : prov.vod_enabled;
  if (!enabled) { el.className = 'page'; el.append(empty(`${kind === 'series' ? 'Series' : 'Movies'} are turned off for ${prov.name}`, 'Enable them in Settings → IPTV Providers → edit provider.', btn('Settings', () => go('#/settings?tab=iptv'), { cls: 'primary' }))); return; }
  const st = { cat: route.query.cat || prefs.get(`vodcat.${pid}.${kind}`, '__discover'), q: '', sort: prefs.get(`vodsort.${kind}`, 'added'), start: 0, total: Infinity };
  const cats = await api.cached(`/api/iptv/providers/${pid}/categories?kind=${kind === 'series' ? 'series' : 'vod'}`, 120000);
  const side = h('div', { class: 'side' });
  const grid = h('div', { class: 'grid' });
  const discover = h('div');
  const count = h('span', { class: 'dim' });
  function renderCats() {
    const item = (id, name, n) => h('div', { class: `cat ${st.cat === id ? 'active' : ''}`, onClick: () => { st.cat = id; prefs.set(`vodcat.${pid}.${kind}`, id); renderCats(); reload(); } },
      h('span', { class: 'ellipsis' }, name), n != null ? h('span', { class: 'n' }, n) : null);
    side.replaceChildren(item('__discover', 'Discover'), item('__all', 'All titles'), ...cats.filter(c => c.count > 0).map(c => item(c.id, c.name, c.count)));
  }
  renderCats();
  const provSel = store.providers.length > 1 ? select(store.providers.map(p => ({ value: p.id, label: p.name })), pid, v => { setActiveProvider(v); location.reload(); }) : null;
  el.append(h('div', { class: 'page-head' }, h('h1', {}, kind === 'series' ? 'Series' : 'Movies'), provSel,
    h('input', { class: 'input', placeholder: 'Search…', onInput: debounce(e => { st.q = e.target.value.trim(); reload(); }, 300) }),
    select(SORTS, st.sort, v => { st.sort = v; prefs.set(`vodsort.${kind}`, v); reload(); }), h('span', { class: 'spacer' }), count),
  h('div', { class: 'sidebar-layout' }, side, h('div', { style: { minWidth: 0 } }, discover, grid)));

  async function load() {
    if (st.start >= st.total) return false;
    const d = await api.cached(`/api/iptv/vod/${pid}${qs({ kind, category: st.cat === '__discover' ? '__all' : st.cat, q: st.q, sort: st.sort, start: st.start, size: 60 })}`);
    st.total = d.total; st.start += d.items.length;
    count.textContent = `${d.total.toLocaleString()} titles`;
    grid.append(...d.items.map(v => card({
      img: v.poster, title: v.name, sub: [v.year, v.rating ? `★ ${Number(v.rating).toFixed(1)}` : null].filter(Boolean).join(' · '),
      progress: v.position > 0 && v.duration ? v.position / v.duration : 0, played: v.position === -1,
      onClick: () => go(`#/vod-item/${kind}/${pid}/${encodeURIComponent(v.id)}`),
    })));
    if (!d.total) grid.append(empty('Nothing found'));
    return st.start < st.total;
  }
  const vcard = v => card({
    img: v.poster, title: v.name, sub: [v.year, v.rating ? `★ ${Number(v.rating).toFixed(1)}` : null].filter(Boolean).join(' · '),
    progress: v.position > 0 && v.duration ? v.position / v.duration : 0, played: v.position === -1,
    onClick: () => go(`#/vod-item/${kind}/${pid}/${encodeURIComponent(v.id)}`),
  });
  async function showDiscover() {
    discover.replaceChildren(...skeletonRows(3));
    const d = await api.cached(`/api/iptv/vod/${pid}/discover?kind=${kind}`, 120000);
    if (st.cat !== '__discover' || st.q) return;
    const total = cats.reduce((n, c) => n + (c.count || 0), 0);
    count.textContent = `${total.toLocaleString()} titles`;
    const goCat = id => () => { st.cat = id; prefs.set(`vodcat.${pid}.${kind}`, id); renderCats(); reload(); };
    discover.replaceChildren(
      d.recent.length ? section('Recently Added', hrow(d.recent.map(vcard))) : null,
      d.top.length ? section('Top Rated', hrow(d.top.map(vcard))) : null,
      ...d.categories.filter(c => c.items.length).map(c => h('div', {}, h('h2', { class: 'sec' }, c.name, h('a', { class: 'more', href: 'javascript:void 0', onClick: goCat(c.id) }, `See all ${c.count} ›`)), hrow(c.items.map(vcard)))),
      !d.recent.length ? empty('Nothing here yet', 'Sync the provider in Settings to load titles.') : null);
  }
  let stop;
  async function reload() {
    stop?.(); grid.replaceChildren(); discover.replaceChildren(); st.start = 0; st.total = Infinity;
    if (st.cat === '__discover' && !st.q) return showDiscover();
    await load(); stop = infinite(grid, load);
  }
  await reload();
  return () => stop?.();
}

function detailHero({ backdrop, poster, title, meta, plot, actions, extra }) {
  return h('div', { class: 'hero' }, h('div', { class: 'bg', style: { backgroundImage: backdrop ? `url("${backdrop}")` : 'none' } }),
    h('div', { class: 'inner' }, h('div', { class: 'poster' }, poster ? h('img', { src: poster, alt: '' }) : null),
      h('div', {}, h('h1', {}, title), h('div', { class: 'meta' }, meta), extra || null,
        h('div', { class: 'summary', onClick: e => e.currentTarget.classList.toggle('open') }, plot || ''), h('div', { class: 'row wrap' }, actions))));
}

async function vodItem(el, route) {
  const [kind, pid, id] = route.parts;
  if (kind === 'movie') {
    const m = await api.cached(`/api/iptv/vod/${pid}/movie/${encodeURIComponent(id)}`, 20000);
    const info = m.info || {};
    const dur = Number(info.duration_secs) || 0;
    const resume = m.progress?.position > 0 ? m.progress.position : 0;
    const play = (offset) => playVod(pid, { kind: 'movie', id, ext: m.movie?.container_extension || m.ext, title: m.name, poster: m.poster, offset, duration: dur || undefined });
    el.append(detailHero({
      backdrop: info.backdrop_path?.[0] || info.cover_big, poster: info.movie_image || m.poster, title: info.name || m.name, plot: info.plot || info.description,
      meta: [info.releasedate || m.year, dur ? fmtRuntime(dur) : info.duration, info.rating ? `★ ${info.rating}` : null, info.genre].filter(Boolean).map(x => h('span', {}, x)),
      extra: h('div', { class: 'dim small' }, [info.director && `Director: ${info.director}`, (info.cast || info.actors) && `Cast: ${info.cast || info.actors}`].filter(Boolean).join('  ·  ')),
      actions: [btn(resume ? `Resume ${fmtDuration(resume)}` : 'Play', () => play(resume), { cls: 'primary', iconName: 'play' }),
        resume ? btn('From beginning', () => play(0), { iconName: 'rw' }) : null,
        info.youtube_trailer ? btn('Trailer', () => window.open(`https://www.youtube.com/watch?v=${info.youtube_trailer}`, '_blank'), { iconName: 'movie' }) : null],
    }));
    return;
  }
  // series
  const s = await api.cached(`/api/iptv/vod/${pid}/series/${encodeURIComponent(id)}`, 20000);
  const info = s.info || {};
  const seasons = s.seasons || [];
  const allEps = seasons.flatMap(se => se.episodes.map(e => ({ ...e, season: se.season })));
  const playEp = (ep, offset = 0) => {
    const i = allEps.indexOf(ep);
    const nx = allEps[i + 1];
    playVod(pid, {
      kind: 'episode', id: ep.id, ext: ep.ext, title: `${s.name || info.name} S${ep.season}E${ep.num}`, subtitle: ep.title, poster: s.poster || info.cover, parentId: id,
      offset, duration: Number(ep.duration) || undefined,
      next: nx ? { label: `S${nx.season}E${nx.num} · ${nx.title}`, run: () => playEp(nx, 0) } : null,
    });
  };
  const nextUp = allEps.find(e => e.progress && e.progress.position > 0) || allEps.find(e => !e.progress) || allEps[0];
  el.append(detailHero({
    backdrop: info.backdrop_path?.[0] || info.cover, poster: info.cover || s.poster, title: info.name || s.name, plot: info.plot,
    meta: [info.releaseDate || info.release_date, info.rating ? `★ ${info.rating}` : null, info.genre, `${seasons.length} season${seasons.length !== 1 ? 's' : ''}`].filter(Boolean).map(x => h('span', {}, x)),
    extra: info.cast ? h('div', { class: 'dim small' }, `Cast: ${info.cast}`) : null,
    actions: nextUp ? [btn(`${nextUp.progress?.position > 0 ? 'Resume' : 'Play'} S${nextUp.season}E${nextUp.num}`, () => playEp(nextUp, nextUp.progress?.position > 0 ? nextUp.progress.position : 0), { cls: 'primary', iconName: 'play' })] : [],
  }));
  const page = h('div', { class: 'page', style: { paddingTop: 0 } });
  el.append(page);
  if (!seasons.length) { page.append(empty('No episodes available')); return; }
  let cur = String(nextUp?.season || seasons[0].season);
  const tabs = h('div', { class: 'tabs' });
  const list = h('div', { class: 'ep-list' });
  const render = () => {
    tabs.replaceChildren(...seasons.map(se => h('div', { tabindex: 0, class: `tab ${String(se.season) === cur ? 'active' : ''}`, onClick: () => { cur = String(se.season); render(); } }, `Season ${se.season}`)));
    const se = seasons.find(x => String(x.season) === cur);
    list.replaceChildren(...(se?.episodes || []).map(ep => {
      const e = allEps.find(x => x.id === ep.id);
      const prog = e.progress?.position > 0 && e.progress.duration ? e.progress.position / e.progress.duration : 0;
      return h('div', { class: `ep ${e.progress ? '' : 'unwatched'}`, onClick: () => playEp(e, e.progress?.position > 0 ? e.progress.position : 0) },
        h('div', { class: 'thumb' }, e.image ? h('img', { src: e.image, loading: 'lazy', alt: '' }) : null, prog ? h('div', { class: 'progress' }, h('i', { style: { width: `${prog * 100}%` } })) : null),
        h('div', { class: 'grow' }, h('div', { class: 'dim small' }, `Episode ${e.num}${e.duration ? ' · ' + fmtRuntime(e.duration) : ''}`),
          h('h4', {}, e.title || `Episode ${e.num}`, e.progress?.position === -1 ? h('span', { class: 'badge ok', style: { marginLeft: '8px' } }, 'WATCHED') : null), h('p', {}, e.plot || '')));
    }));
  };
  render();
  page.append(tabs, list);
}

export const views = { vod: vodBrowse, 'vod-item': vodItem };
export default vodBrowse;

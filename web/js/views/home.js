import { api, plexImg } from '../api.js';
import { h, section, hrow, card, empty, btn, logo, nowS, fmtTime, fmtRuntime, heroCarousel, skeletonRows } from '../ui.js';
import { store, go, activeProvider } from '../app.js';
import { plexRow, plexCard } from '../plexcards.js';
import { playLive, playVod, playPlex } from '../playback.js';

function channelCard(ch, list, title) {
  const now = ch.now;
  const pct = now ? Math.min(100, ((nowS() - now.start) / (now.stop - now.start)) * 100) : 0;
  return h('div', { class: 'ch-card', tabindex: 0, onClick: () => playLive(ch.provider_id, ch, list, title), onKeydown: e => { if (e.key === 'Enter') playLive(ch.provider_id, ch, list, title); } },
    logo(ch.logo, ch.name),
    h('div', { class: 'grow' }, h('div', { class: 'ellipsis', style: { fontWeight: 600 } }, ch.name),
      h('div', { class: 'small muted ellipsis' }, now ? now.title : 'No info'),
      now ? h('div', { class: 'progress', style: { marginTop: '6px' } }, h('i', { style: { width: `${pct}%` } })) : null,
      now ? h('div', { class: 'small dim' }, `${fmtTime(now.start)} – ${fmtTime(now.stop)}`) : null));
}

function heroSlide(m) {
  const isShow = m.type === 'show';
  const resume = m.viewOffset ? m.viewOffset / 1000 : 0;
  return {
    art: plexImg(m.art, 1920, 1080),
    kicker: isShow ? 'New episodes' : 'Recently added',
    title: m.title,
    meta: [m.year, m.contentRating, !isShow && m.duration ? fmtRuntime(m.duration / 1000) : null,
      isShow && m.childCount ? `${m.childCount} season${m.childCount > 1 ? 's' : ''}` : null,
      m.audienceRating ? `★ ${Number(m.audienceRating).toFixed(1)}` : null, (m.Genre || []).slice(0, 2).map(g => g.tag).join(' · ') || null],
    summary: m.summary,
    playLabel: isShow ? 'Watch' : resume ? 'Resume' : 'Play',
    play: isShow ? () => go(`#/item/${m.ratingKey}`) : () => playPlex(m.ratingKey, { offset: resume }),
    info: () => go(`#/item/${m.ratingKey}`),
  };
}

export default async function home(el) {
  const nothing = !store.providers.length && !store.plex?.configured;
  if (nothing) {
    el.className = 'page';
    el.append(h('div', { class: 'card-box', style: { maxWidth: '640px', marginTop: '40px' } },
      h('h2', { style: { marginTop: 0 } }, 'Welcome to Lumen'),
      h('p', { class: 'muted' }, 'Connect your Plex server and add your IPTV (Xtream Codes or M3U) provider to get started.'),
      btn('Open Settings', () => go('#/settings'), { cls: 'primary', iconName: 'settings' })));
    return;
  }

  const heroSlot = h('div');
  const rows = h('div', { class: 'home-rows' });
  el.append(heroSlot, rows);
  const plexOnline = store.plex?.configured && store.plex.online;
  if (!plexOnline) rows.style.marginTop = '0';

  // Fixed slot order so rows don't jump around as data arrives.
  const slot = () => { const s = h('div'); rows.append(s); return s; };
  const s = { cw: slot(), fav: slot(), movies: slot(), shows: slot(), recent: slot(), iptvcw: slot(), iptvMovies: slot(), iptvSeries: slot(), hubs: slot(), playlists: slot() };

  // ---- Plex ----
  if (store.plex?.configured && !store.plex.online) {
    s.cw.append(h('div', { class: 'card-box', style: { marginTop: '24px' } }, h('b', {}, 'Plex server is unreachable. '), h('span', { class: 'muted' }, store.plex.error || '')));
  }
  if (plexOnline) {
    heroSlot.append(h('div', { class: 'hero-car skel', style: { borderRadius: 0 } }));
    s.cw.append(...skeletonRows(1, { wide: true }));
    s.movies.append(...skeletonRows(2));
    api.cached('/api/plex/home', 30000).then(({ hubs, playlists, featured, recentMovies, recentShows }) => {
      heroSlot.replaceChildren(featured?.length ? heroCarousel(featured.map(heroSlide)) : h('div', { style: { height: '60px' } }));
      if (!featured?.length) rows.style.marginTop = '0';
      const cont = hubs.find(x => /continue|ondeck/i.test(x.hubIdentifier || ''));
      s.cw.replaceChildren(cont?.Metadata?.length ? section(cont.title || 'Continue Watching', plexRow(cont.Metadata, { wide: true })) : '');
      s.movies.replaceChildren(recentMovies?.length ? section('Recently Added Movies', plexRow(recentMovies)) : '');
      s.shows.replaceChildren(recentShows?.length ? section('Recently Added TV Shows', plexRow(recentShows)) : '');
      s.hubs.replaceChildren(...hubs.filter(hub => hub !== cont && hub.Metadata?.length).map(hub => {
        const wide = hub.type === 'episode' || hub.type === 'clip' || /ondeck/i.test(hub.hubIdentifier || '');
        return section(hub.title, plexRow(hub.Metadata, { wide }));
      }));
      s.playlists.replaceChildren(playlists?.length ? section('Playlists', hrow(playlists.map(p => plexCard(p, { wide: true })))) : '');
    }).catch(e => { heroSlot.replaceChildren(); s.cw.replaceChildren(empty('Could not load Plex', e.message)); s.movies.replaceChildren(); });
  }

  // ---- IPTV ----
  if (store.providers.length) {
    api.cached('/api/iptv/favorites', 20000).then(list => {
      if (list.length) s.fav.append(section('Live TV · Favorites', hrow(list.map(c => channelCard(c, list, 'Favorites'))), '#/live?cat=__fav'));
    }).catch(() => {});
    api.cached('/api/iptv/history', 20000).then(list => {
      if (list.length) s.recent.append(section('Recently Watched Channels', hrow(list.slice(0, 20).map(c => channelCard(c, list, 'Recently watched')))));
    }).catch(() => {});
    api.cached('/api/iptv/progress', 20000).then(list => {
      if (!list.length) return;
      s.iptvcw.append(section('Continue Watching · IPTV', hrow(list.map(p => card({
        img: p.poster, title: p.title || 'Untitled', sub: p.kind === 'episode' ? 'Episode' : 'Movie', progress: p.duration ? p.position / p.duration : 0,
        onClick: () => playVod(p.provider_id, { kind: p.kind, id: p.id, title: p.title, poster: p.poster, parentId: p.parent_id, offset: p.position, duration: p.duration }),
      })))));
    }).catch(() => {});
    const prov = activeProvider();
    const vodRow = (slotEl, kind, title) => api.cached(`/api/iptv/vod/${prov.id}?kind=${kind}&sort=added&size=24`, 120000).then(d => {
      if (!d.items.length) return;
      slotEl.append(section(title, hrow(d.items.map(v => card({
        img: v.poster, title: v.name, sub: [v.year, v.rating ? `★ ${Number(v.rating).toFixed(1)}` : null].filter(Boolean).join(' · '),
        onClick: () => go(`#/vod-item/${kind}/${prov.id}/${encodeURIComponent(v.id)}`),
      }))), `#/vod?kind=${kind}`));
    }).catch(() => {});
    if (prov?.vod_enabled) vodRow(s.iptvMovies, 'movie', 'New Movies · IPTV');
    if (prov?.series_enabled) vodRow(s.iptvSeries, 'series', 'New Series · IPTV');
  }
}

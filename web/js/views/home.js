import { api, plexImg } from '../api.js';
import { h, hrow, card, empty, btn, logo, nowS, fmtTime, fmtRuntime, heroCarousel, skeletonRows, toast, esc } from '../ui.js';
import { icon } from '../icons.js';
import { store, go, activeProvider } from '../app.js';
import { plexRow, plexCard } from '../plexcards.js';
import { playLive, playVod, playPlex } from '../playback.js';
import { homeSections, heroHidden, ui } from '../appearance.js';

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

async function saveUi(patch) {
  Object.assign(window.lumenSettings.ui, patch);
  await api.put('/api/settings/ui', patch).catch(() => {});
}

/** A home row with a ⋯ menu that can hide it. */
function row(title, content, { more, onHide } = {}) {
  const wrap = h('div');
  const menuBtn = onHide ? h('button', { class: 'btn ghost sm icon row-menu', title: 'Row options', html: icon('list'), onClick: e => {
    e.stopPropagation();
    document.querySelector('.row-pop')?.remove();
    const pop = h('div', { class: 'p-menu row-pop', style: { position: 'absolute', right: '0', top: '36px', bottom: 'auto', zIndex: 20 } },
      h('div', { class: 'opt', onClick: async () => { pop.remove(); await onHide(); wrap.remove(); toast(`"${title}" hidden. Restore it in Settings → Appearance.`, { action: { label: 'Settings', onClick: () => go('#/settings?tab=appearance') } }); } },
        h('span', { html: icon('eyeoff'), style: { display: 'inline-flex', width: '18px' } }), 'Hide this row'),
      h('div', { class: 'opt', onClick: () => { pop.remove(); go('#/settings?tab=appearance'); } }, h('span', { html: icon('settings'), style: { display: 'inline-flex', width: '18px' } }), 'Customize Home…'));
    e.currentTarget.parentElement.append(pop);
    setTimeout(() => document.addEventListener('click', () => pop.remove(), { once: true }));
  } }) : null;
  wrap.append(h('h2', { class: 'sec', style: { position: 'relative' } }, title, more ? h('a', { class: 'more', href: more }, 'See all ›') : null, menuBtn), content);
  return wrap;
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

  const plexOnline = store.plex?.configured && store.plex.online;
  const showHero = plexOnline && !heroHidden();
  const heroSlot = h('div');
  const rows = h('div', { class: 'home-rows' });
  el.append(heroSlot, rows);
  if (!showHero) rows.style.marginTop = '0';
  if (!showHero) rows.style.paddingTop = '8px';

  // One slot per section, in the user's order; hidden sections get no slot.
  const s = {};
  for (const sec of homeSections()) {
    if (sec.hidden) continue;
    s[sec.id] = h('div');
    rows.append(s[sec.id]);
  }
  const hideSection = id => () => saveUi({ homeHidden: [...new Set([...(ui().homeHidden || []), id])] });
  const put = (id, title, content, opts = {}) => { if (s[id]) s[id].replaceChildren(row(title, content, { onHide: hideSection(id), ...opts })); };
  const clear = id => s[id]?.replaceChildren();

  // ---- Plex ----
  if (store.plex?.configured && !store.plex.online) {
    rows.prepend(h('div', { class: 'card-box', style: { marginTop: '24px' } }, h('b', {}, 'Plex server is unreachable. '), h('span', { class: 'muted' }, store.plex.error || '')));
  }
  if (plexOnline) {
    if (showHero) heroSlot.append(h('div', { class: 'hero-car skel', style: { borderRadius: 0 } }));
    s.continue?.append(...skeletonRows(1, { wide: true }));
    s.recentMovies?.append(...skeletonRows(1));
    api.cached('/api/plex/home', 30000).then(({ hubs, playlists, featured, recentMovies, recentShows }) => {
      if (showHero) {
        heroSlot.replaceChildren(featured?.length ? heroCarousel(featured.map(heroSlide), { interval: (Number(ui().heroInterval) || 8) * 1000 }) : '');
        if (!featured?.length) { rows.style.marginTop = '0'; rows.style.paddingTop = '8px'; }
      }
      const cont = hubs.find(x => /continue|ondeck/i.test(x.hubIdentifier || ''));
      if (cont?.Metadata?.length) put('continue', cont.title || 'Continue Watching', plexRow(cont.Metadata, { wide: true })); else clear('continue');
      if (recentMovies?.length) put('recentMovies', 'Recently Added Movies', plexRow(recentMovies)); else clear('recentMovies');
      if (recentShows?.length) put('recentShows', 'Recently Added TV Shows', plexRow(recentShows)); else clear('recentShows');
      if (s.plexHubs) {
        const hiddenHubs = new Set((ui().hiddenHubs || []).map(x => x.id));
        s.plexHubs.replaceChildren(...hubs.filter(hub => hub !== cont && hub.Metadata?.length && !hiddenHubs.has(hub.hubIdentifier)).map(hub => {
          const wide = hub.type === 'episode' || hub.type === 'clip' || /ondeck/i.test(hub.hubIdentifier || '');
          return row(hub.title, plexRow(hub.Metadata, { wide }), {
            onHide: () => saveUi({ hiddenHubs: [...(ui().hiddenHubs || []).filter(x => x.id !== hub.hubIdentifier), { id: hub.hubIdentifier, title: hub.title }] }),
          });
        }));
      }
      if (playlists?.length) put('playlists', 'Playlists', hrow(playlists.map(p => plexCard(p, { wide: true })))); else clear('playlists');
    }).catch(e => { heroSlot.replaceChildren(); s.continue?.replaceChildren(empty('Could not load Plex', e.message)); clear('recentMovies'); });
  }

  // ---- IPTV ----
  if (store.providers.length) {
    if (s.liveFavorites) api.cached('/api/iptv/favorites', 20000).then(list => {
      if (list.length) put('liveFavorites', 'Live TV · Favorites', hrow(list.map(c => channelCard(c, list, 'Favorites'))), { more: '#/live?cat=__fav' });
    }).catch(() => {});
    if (s.recentChannels) api.cached('/api/iptv/history', 20000).then(list => {
      if (list.length) put('recentChannels', 'Recently Watched Channels', hrow(list.slice(0, 20).map(c => channelCard(c, list, 'Recently watched'))));
    }).catch(() => {});
    if (s.iptvContinue) api.cached('/api/iptv/progress', 20000).then(list => {
      if (!list.length) return;
      put('iptvContinue', 'Continue Watching · IPTV', hrow(list.map(p => card({
        img: p.poster, title: p.title || 'Untitled', sub: p.kind === 'episode' ? 'Episode' : 'Movie', progress: p.duration ? p.position / p.duration : 0,
        onClick: () => playVod(p.provider_id, { kind: p.kind, id: p.id, title: p.title, poster: p.poster, parentId: p.parent_id, offset: p.position, duration: p.duration }),
      }))));
    }).catch(() => {});
    const prov = activeProvider();
    const vodRow = (id, kind, title) => api.cached(`/api/iptv/vod/${prov.id}?kind=${kind}&sort=added&size=24`, 120000).then(d => {
      if (!d.items.length) return;
      put(id, title, hrow(d.items.map(v => card({
        img: v.poster, title: v.name, sub: [v.year, v.rating ? `★ ${Number(v.rating).toFixed(1)}` : null].filter(Boolean).join(' · '),
        onClick: () => go(`#/vod-item/${kind}/${prov.id}/${encodeURIComponent(v.id)}`),
      }))), { more: `#/vod?kind=${kind}` });
    }).catch(() => {});
    if (prov?.vod_enabled && s.iptvMovies) vodRow('iptvMovies', 'movie', 'New Movies · IPTV');
    if (prov?.series_enabled && s.iptvSeries) vodRow('iptvSeries', 'series', 'New Series · IPTV');
  }
}

import { api, plexImg } from '../api.js';
import { h, hrow, card, empty, btn, logo, nowS, fmtTime, fmtRuntime, heroCarousel, skeletonRows, toast, esc } from '../ui.js';
import { icon } from '../icons.js';
import { store, go, activeProvider } from '../app.js';
import { plexRow, plexCard } from '../plexcards.js';
import { playLive, playVod, playPlex } from '../playback.js';
import { homeSections, heroHidden, ui, ratingText, removePin, saveUi as saveUiCentral } from '../appearance.js';

function channelCard(ch, list, title) {
  const now = ch.now;
  const pct = now ? Math.min(100, ((nowS() - now.start) / (now.stop - now.start)) * 100) : 0;
  return h('div', { class: 'ch-card', tabindex: 0, onClick: () => playLive(ch.provider_id, ch, list, title) },
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
    kicker: m._kicker || (isShow ? 'New episodes' : 'Recently added'),
    title: m.title,
    meta: [m.year, m.contentRating, !isShow && m.duration ? fmtRuntime(m.duration / 1000) : null,
      isShow && m.childCount ? `${m.childCount} season${m.childCount > 1 ? 's' : ''}` : null,
      ratingText(m), (m.Genre || []).slice(0, 2).map(g => g.tag).join(' · ') || null],
    summary: m.summary,
    playLabel: isShow ? 'Watch' : resume ? 'Resume' : 'Play',
    play: isShow && !m._play ? () => go(`#/item/${m.ratingKey}`) : () => playPlex(m.ratingKey, { offset: resume }),
    info: () => go(`#/item/${m.ratingKey}`),
  };
}

/** Items for the featured banner, per Settings → Appearance → Home screen → Featured banner shows. */
async function heroItems({ featured, hubs, recentMovies, recentShows }) {
  const src = ui().heroSource || 'recent';
  const withArt = l => (l || []).filter(m => m.art || m.grandparentArt).slice(0, 10);
  if (src === 'continue') {
    const cont = hubs.find(x => /continue|ondeck/i.test(x.hubIdentifier || ''));
    return withArt((cont?.Metadata || []).map(m => m.type === 'episode' ? { ...m, ratingKey: m.ratingKey, art: m.grandparentArt || m.art, title: m.grandparentTitle, _kicker: `Continue · S${m.parentIndex}E${m.index}`, _play: true } : { ...m, _kicker: 'Continue watching' }));
  }
  if (src === 'collection' && ui().heroCollection) {
    const items = await api.cached(`/api/plex/collection/${ui().heroCollection}/items`, 300000);
    return withArt(items.map(m => ({ ...m, _kicker: ui().heroCollectionTitle || 'Collection' })));
  }
  if (src === 'random') {
    const all = [...(recentMovies || []), ...(recentShows || [])].filter(m => m.art);
    for (let i = all.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [all[i], all[j]] = [all[j], all[i]]; }
    return all.slice(0, 8).map(m => ({ ...m, _kicker: 'Spotlight' }));
  }
  return featured;
}

async function saveUi(patch) {
  await saveUiCentral(patch, api.put).catch(() => {});
}

/** A home row with a ⋯ menu that can hide it. */
function row(title, content, { more, onHide, hideLabel = 'Hide this row' } = {}) {
  const wrap = h('div');
  const menuBtn = onHide ? h('button', { class: 'btn ghost sm icon row-menu', title: 'Row options', html: icon('list'), onClick: e => {
    e.stopPropagation();
    document.querySelector('.row-pop')?.remove();
    const pop = h('div', { class: 'p-menu row-pop', style: { position: 'absolute', right: '0', top: '36px', bottom: 'auto', zIndex: 20 } },
      h('div', { class: 'opt', onClick: async () => { pop.remove(); await onHide(); wrap.remove(); toast(hideLabel === 'Hide this row' ? `"${title}" hidden. Restore it in Settings → Appearance.` : `"${title}" removed from Home.`, { action: { label: 'Settings', onClick: () => go('#/settings?tab=appearance') } }); } },
        h('span', { html: icon('eyeoff'), style: { display: 'inline-flex', width: '18px' } }), hideLabel),
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
    api.cached('/api/plex/home', 30000).then(async ({ hubs, playlists, featured, recentMovies, recentShows }) => {
      if (showHero) {
        const items = await heroItems({ featured, hubs, recentMovies, recentShows }).catch(() => featured);
        heroSlot.replaceChildren(items?.length ? heroCarousel(items.map(heroSlide), { interval: (Number(ui().heroInterval) || 8) * 1000, autoplay: ui().heroAutoplay !== false }) : '');
        if (!items?.length) { rows.style.marginTop = '0'; rows.style.paddingTop = '8px'; }
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

  // ---- Pinned rows ----
  for (const sec of homeSections().filter(x => x.pin && s[x.id])) {
    const p = sec.pin;
    const unpin = async () => { await removePin(p.id, api.put); };
    const show = content => s[sec.id].replaceChildren(row(p.title, content, { onHide: unpin, hideLabel: 'Unpin from Home', more: pinLink(p) }));
    s[sec.id].append(...skeletonRows(1, { wide: p.type === 'favList' || p.type === 'iptvGroup' }));
    pinnedContent(p).then(content => content ? show(content) : s[sec.id].replaceChildren()).catch(() => s[sec.id].replaceChildren());
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

function pinLink(p) {
  if (p.type === 'plexCollection') return `#/collection/${p.key}`;
  if (p.type === 'plexPlaylist') return `#/playlist/${p.key}`;
  if (p.type === 'plexGenre') return `#/library/${p.section}?tab=library&genre=${encodeURIComponent(p.genre)}`;
  if (p.type === 'favList') return `#/live?cat=__fav:${p.listId}`;
  if (p.type === 'iptvGroup') return `#/live?cat=${encodeURIComponent(p.cat)}`;
  return null;
}
async function pinnedContent(p) {
  if (p.type === 'favList' || p.type === 'iptvGroup') {
    const list = p.type === 'favList' ? await api.cached(`/api/iptv/favorites?list=${p.listId}`, 20000)
      : (await api.cached(`/api/iptv/providers/${p.pid}/channels?category=${encodeURIComponent(p.cat)}`, 30000)).map(c => ({ ...c, provider_id: p.pid }));
    return list.length ? hrow(list.slice(0, 60).map(c => channelCard(c, list, p.title))) : null;
  }
  if (p.type === 'plexCollection') { const items = await api.cached(`/api/plex/collection/${p.key}/items`, 120000); return items.length ? plexRow(items) : null; }
  if (p.type === 'plexPlaylist') { const pl = await api.cached(`/api/plex/playlist/${p.key}`, 60000); const items = pl.items || []; return items.length ? plexRow(items, { wide: items.every(i => i.type === 'episode') }) : null; }
  if (p.type === 'plexGenre') { const d = await api.cached(`/api/plex/library/${p.section}/items?sort=addedAt:desc&size=30&genre=${encodeURIComponent(p.genre)}`, 120000); return d.items.length ? plexRow(d.items) : null; }
  return null;
}

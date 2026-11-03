import { api } from '../api.js';
import { h, section, hrow, card, empty, btn, logo, nowS, fmtTime, spinner } from '../ui.js';
import { store, go } from '../app.js';
import { plexRow, plexCard } from '../plexcards.js';
import { playLive, playVod } from '../playback.js';

function channelCard(ch, list) {
  const now = ch.now;
  const pct = now ? Math.min(100, ((nowS() - now.start) / (now.stop - now.start)) * 100) : 0;
  return h('div', { class: 'ch-card', tabindex: 0, onClick: () => playLive(ch.provider_id, ch, list, 'Favorites') },
    logo(ch.logo, ch.name),
    h('div', { class: 'grow' }, h('div', { class: 'ellipsis', style: { fontWeight: 600 } }, ch.name),
      h('div', { class: 'small muted ellipsis' }, now ? now.title : 'No info'),
      now ? h('div', { class: 'progress', style: { marginTop: '6px' } }, h('i', { style: { width: `${pct}%` } })) : null,
      now ? h('div', { class: 'small dim' }, `${fmtTime(now.start)} – ${fmtTime(now.stop)}`) : null));
}

export default async function home(el) {
  el.className = 'page';
  el.append(h('div', { class: 'page-head' }, h('h1', {}, greeting())));
  const nothing = !store.providers.length && !store.plex?.configured;
  if (nothing) {
    el.append(h('div', { class: 'card-box', style: { maxWidth: '640px' } },
      h('h2', { style: { marginTop: 0 } }, 'Welcome to Lumen'),
      h('p', { class: 'muted' }, 'Connect your Plex server and add your IPTV (Xtream Codes or M3U) provider to get started.'),
      btn('Open Settings', () => go('#/settings'), { cls: 'primary', iconName: 'settings' })));
    return;
  }

  const slots = {
    cw: h('div'), fav: h('div'), recent: h('div'), vodcw: h('div'), plex: h('div'),
  };
  el.append(slots.cw, slots.fav, slots.recent, slots.vodcw, slots.plex);

  // IPTV favourites + recents
  if (store.providers.length) {
    api.get('/api/iptv/favorites').then(list => {
      if (list.length) slots.fav.append(section('Favorite Channels', hrow(list.map(c => channelCard(c, list))), '#/live?cat=__fav'));
    }).catch(() => {});
    api.get('/api/iptv/history').then(list => {
      if (list.length) slots.recent.append(section('Recently Watched Channels', hrow(list.slice(0, 20).map(c => channelCard(c, list)))));
    }).catch(() => {});
    api.get('/api/iptv/progress').then(list => {
      if (!list.length) return;
      slots.vodcw.append(section('Continue Watching (IPTV)', hrow(list.map(p => card({
        img: p.poster, title: p.title || 'Untitled', sub: p.kind === 'episode' ? 'Episode' : 'Movie', progress: p.duration ? p.position / p.duration : 0,
        onClick: () => playVod(p.provider_id, { kind: p.kind, id: p.id, title: p.title, poster: p.poster, parentId: p.parent_id, offset: p.position, duration: p.duration }),
      })))));
    }).catch(() => {});
  }

  // Plex hubs
  if (store.plex?.configured) {
    if (!store.plex.online) {
      slots.plex.append(h('div', { class: 'card-box' }, h('b', {}, 'Plex server is unreachable. '), h('span', { class: 'muted' }, store.plex.error || '')));
    } else {
      slots.plex.append(spinner());
      try {
        const { hubs, playlists } = await api.get('/api/plex/home');
        slots.plex.replaceChildren();
        const cont = hubs.find(x => /continue|ondeck/i.test(x.hubIdentifier || ''));
        if (cont?.Metadata?.length) slots.cw.append(section(cont.title || 'Continue Watching', plexRow(cont.Metadata, { wide: true })));
        for (const hub of hubs) {
          if (hub === cont || !hub.Metadata?.length) continue;
          const wide = hub.type === 'episode' || hub.type === 'clip' || /ondeck/i.test(hub.hubIdentifier || '');
          slots.plex.append(section(hub.title, plexRow(hub.Metadata, { wide })));
        }
        if (playlists?.length) slots.plex.append(section('Playlists', hrow(playlists.map(p => plexCard(p, { wide: true })))));
      } catch (e) {
        slots.plex.replaceChildren(empty('Could not load Plex', e.message));
      }
    }
  }
}

function greeting() {
  const hr = new Date().getHours();
  return hr < 5 ? 'Good night' : hr < 12 ? 'Good morning' : hr < 18 ? 'Good afternoon' : 'Good evening';
}

import { api, qs } from '../api.js';
import { h, section, hrow, card, logo, debounce, empty, spinner, fmtDateTime } from '../ui.js';
import { store, go } from '../app.js';
import { plexRow } from '../plexcards.js';
import { playLive } from '../playback.js';
import { programmeModal } from './guide.js';

export default async function search(el, route) {
  el.className = 'page';
  const input = h('input', { class: 'input', placeholder: 'Search movies, shows, channels and programmes…', style: { width: '100%', height: '48px', fontSize: '17px' }, value: route.query.q || '', autofocus: true });
  const results = h('div');
  el.append(input, results);
  let token = 0;
  const run = debounce(async () => {
    const q = input.value.trim();
    history.replaceState(history.state, '', `#/search${qs({ q })}`);
    const my = ++token;
    if (q.length < 2) { results.replaceChildren(); return; }
    results.replaceChildren(spinner());
    const [plex, iptv] = await Promise.all([
      store.plex?.online ? api.get(`/api/plex/search?q=${encodeURIComponent(q)}`).catch(() => []) : [],
      store.providers.length ? api.get(`/api/iptv/search?q=${encodeURIComponent(q)}`).catch(() => null) : null,
    ]);
    if (my !== token) return;
    results.replaceChildren();
    for (const hub of plex) results.append(section(`Plex · ${hub.title}`, plexRow(hub.Metadata, { wide: hub.type === 'episode' })));
    if (iptv?.channels.length) results.append(section('Live channels', hrow(iptv.channels.map(c => h('div', { class: 'ch-card', onClick: () => playLive(c.provider_id, c, iptv.channels.filter(x => x.provider_id === c.provider_id), 'Search results') }, logo(c.logo, c.name), h('div', { class: 'grow ellipsis', style: { fontWeight: 600 } }, c.name))))));
    if (iptv?.programmes.length) results.append(section('TV programmes', hrow(iptv.programmes.map(p => h('div', { class: 'ch-card', style: { width: '280px' }, onClick: () => programmeModal(p.provider_id, { id: p.channel_id, name: p.channel_name, logo: p.logo, archive: p.archive }, p) },
      logo(p.logo, p.channel_name), h('div', { class: 'grow' }, h('div', { class: 'ellipsis', style: { fontWeight: 600 } }, p.title), h('div', { class: 'small muted ellipsis' }, `${p.channel_name} · ${fmtDateTime(p.start)}`)))))));
    if (iptv?.movies.length) results.append(section('IPTV Movies', hrow(iptv.movies.map(v => card({ img: v.poster, title: v.name, sub: v.year, onClick: () => go(`#/vod-item/movie/${v.provider_id}/${encodeURIComponent(v.id)}`) })))));
    if (iptv?.series.length) results.append(section('IPTV Series', hrow(iptv.series.map(v => card({ img: v.poster, title: v.name, sub: v.year, onClick: () => go(`#/vod-item/series/${v.provider_id}/${encodeURIComponent(v.id)}`) })))));
    if (!results.children.length) results.append(empty('No results', `Nothing matched "${q}".`));
  }, 350);
  input.addEventListener('input', run);
  if (input.value) run();
  setTimeout(() => input.focus(), 50);
}

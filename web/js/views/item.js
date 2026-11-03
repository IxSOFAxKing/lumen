import { api, plexImg } from '../api.js';
import { h, btn, section, fmtRuntime, fmtDuration, toast, toastErr, empty, spinner } from '../ui.js';
import { icon } from '../icons.js';
import { go } from '../app.js';
import { plexRow, plexCard } from '../plexcards.js';
import { playPlex } from '../playback.js';

function hero(m, actions) {
  const art = plexImg(m.art || m.grandparentArt || m.parentArt, 1920, 1080);
  const poster = plexImg(m.type === 'episode' ? (m.grandparentThumb || m.thumb) : (m.thumb || m.parentThumb), 400, 600);
  const meta = [];
  if (m.year) meta.push(m.year);
  if (m.contentRating) meta.push(h('span', { class: 'badge' }, m.contentRating));
  if (m.duration && m.type !== 'show') meta.push(fmtRuntime(m.duration / 1000));
  if (m.audienceRating) meta.push(`★ ${Number(m.audienceRating).toFixed(1)}`);
  else if (m.rating) meta.push(`★ ${Number(m.rating).toFixed(1)}`);
  if (m.type === 'show' && m.childCount) meta.push(`${m.childCount} season${m.childCount > 1 ? 's' : ''}`);
  if (m.type === 'season' && m.leafCount) meta.push(`${m.leafCount} episodes`);
  const media = m.Media?.[0];
  if (media?.videoResolution) meta.push(h('span', { class: 'badge' }, /^\d+$/.test(media.videoResolution) ? `${media.videoResolution}p` : media.videoResolution.toUpperCase()));
  if (media?.audioChannels) meta.push(h('span', { class: 'badge' }, `${(media.audioCodec || '').toUpperCase()} ${media.audioChannels > 2 ? (media.audioChannels - 1) + '.1' : media.audioChannels === 2 ? 'Stereo' : 'Mono'}`));
  const summary = h('div', { class: 'summary', onClick: e => e.currentTarget.classList.toggle('open') }, m.summary || '');
  const genres = (m.Genre || []).map(g => g.tag).join(', ');
  return h('div', { class: 'hero' }, h('div', { class: 'bg', style: { backgroundImage: art ? `url("${art}")` : 'none' } }),
    h('div', { class: 'inner' },
      h('div', { class: 'poster' }, poster ? h('img', { src: poster, alt: '' }) : null),
      h('div', {},
        m.type === 'episode' ? h('a', { class: 'muted', href: `#/item/${m.grandparentRatingKey}`, style: { fontWeight: 600 } }, m.grandparentTitle) : null,
        m.type === 'season' ? h('a', { class: 'muted', href: `#/item/${m.parentRatingKey}`, style: { fontWeight: 600 } }, m.parentTitle) : null,
        h('h1', {}, m.type === 'episode' ? `S${m.parentIndex} · E${m.index} — ${m.title}` : m.title),
        m.tagline ? h('div', { class: 'muted', style: { fontStyle: 'italic', marginBottom: '6px' } }, m.tagline) : null,
        h('div', { class: 'meta' }, meta.map(x => (x instanceof Node ? x : h('span', {}, x)))), genres ? h('div', { class: 'dim small' }, genres) : null,
        summary, h('div', { class: 'row wrap' }, actions))));
}

function watchedBtn(m, onDone) {
  const isWatched = m.type === 'show' || m.type === 'season' ? m.viewedLeafCount >= m.leafCount : m.viewCount > 0;
  return btn(isWatched ? 'Mark unwatched' : 'Mark watched', async () => {
    try { await api.post(`/api/plex/${isWatched ? 'unwatched' : 'watched'}/${m.ratingKey}`); toast(isWatched ? 'Marked as unwatched' : 'Marked as watched', { type: 'ok' }); onDone?.(); }
    catch (e) { toastErr(e); }
  }, { iconName: isWatched ? 'eyeoff' : 'check' });
}

function episodeRow(ep) {
  const prog = ep.viewOffset && ep.duration ? ep.viewOffset / ep.duration : 0;
  return h('div', { class: 'ep', onClick: () => playPlex(ep.ratingKey, { offset: (ep.viewOffset || 0) / 1000 }) },
    h('div', { class: 'thumb' }, ep.thumb ? h('img', { src: plexImg(ep.thumb, 440, 248), loading: 'lazy', alt: '' }) : null,
      prog ? h('div', { class: 'progress' }, h('i', { style: { width: `${prog * 100}%` } })) : null),
    h('div', { class: 'grow' }, h('div', { class: 'dim small' }, `Episode ${ep.index ?? ''}${ep.duration ? ' · ' + fmtRuntime(ep.duration / 1000) : ''}${ep.originallyAvailableAt ? ' · ' + ep.originallyAvailableAt : ''}`),
      h('h4', {}, ep.title, ep.viewCount > 0 && !ep.viewOffset ? h('span', { class: 'badge ok', style: { marginLeft: '8px' } }, 'WATCHED') : null),
      h('p', {}, ep.summary || '')),
    h('button', { class: 'btn ghost icon sm', title: 'Details', html: icon('info'), onClick: e => { e.stopPropagation(); go(`#/item/${ep.ratingKey}`); } }));
}

function castRow(m) {
  const roles = (m.Role || []).slice(0, 20);
  if (!roles.length) return null;
  return section('Cast', h('div', { class: 'cast' }, roles.map(r => h('div', { class: 'p' },
    h('div', { class: 'ph' }, r.thumb ? h('img', { src: r.thumb.startsWith('http') ? r.thumb : plexImg(r.thumb, 200, 200), loading: 'lazy', alt: '' }) : null),
    h('div', { style: { fontWeight: 600 } }, r.tag), h('div', { class: 'dim' }, r.role || '')))));
}

async function itemView(el, route) {
  const key = route.parts[0];
  const m = await api.get(`/api/plex/meta/${key}`);
  const reload = () => { el.replaceChildren(); itemView(el, route); };
  const actions = [];
  const resume = m.viewOffset ? m.viewOffset / 1000 : 0;

  if (m.type === 'movie' || m.type === 'episode') {
    actions.push(btn(resume ? `Resume ${fmtDuration(resume)}` : 'Play', () => playPlex(m.ratingKey, { offset: resume }), { cls: 'primary', iconName: 'play' }));
    if (resume) actions.push(btn('From beginning', () => playPlex(m.ratingKey, { offset: 0 }), { iconName: 'rw' }));
    const trailer = (m.Extras?.Metadata || []).find(x => x.subtype === 'trailer' || x.extraType === 1);
    if (trailer) actions.push(btn('Trailer', () => playPlex(trailer.ratingKey), { iconName: 'movie' }));
    if ((m.Media || []).length > 1) actions.push(h('span', { class: 'dim small' }, `${m.Media.length} versions available (choose in player)`));
  } else if (m.type === 'show' || m.type === 'season') {
    const onDeck = m.OnDeck?.Metadata?.[0];
    const leavesKey = m.type === 'show' ? m.ratingKey : m.ratingKey;
    if (onDeck) actions.push(btn(`${onDeck.viewOffset ? 'Resume' : 'Play'} S${onDeck.parentIndex}E${onDeck.index}`, () => playPlex(onDeck.ratingKey, { offset: (onDeck.viewOffset || 0) / 1000 }), { cls: 'primary', iconName: 'play' }));
    else actions.push(btn('Play', async () => { const l = await api.get(`/api/plex/meta/${leavesKey}/leaves`); if (l[0]) playPlex(l[0].ratingKey); }, { cls: 'primary', iconName: 'play' }));
    actions.push(btn('Shuffle', async () => {
      const l = await api.get(`/api/plex/meta/${leavesKey}/leaves`);
      const q = l.sort(() => Math.random() - 0.5);
      if (q[0]) playPlex(q[0].ratingKey, { queue: q, queueIndex: 0 });
    }, { iconName: 'shuffle' }));
  }
  actions.push(watchedBtn(m, reload));
  el.append(hero(m, actions));
  const page = h('div', { class: 'page', style: { paddingTop: 0 } });
  el.append(page);

  if (m.type === 'show') {
    const seasons = await api.get(`/api/plex/meta/${m.ratingKey}/children`);
    page.append(section('Seasons', plexRow(seasons)));
  }
  if (m.type === 'season') {
    const eps = await api.get(`/api/plex/meta/${m.ratingKey}/children`);
    page.append(section('Episodes', h('div', { class: 'ep-list' }, eps.map(episodeRow))));
  }
  if (m.type === 'episode') {
    page.append(h('div', { class: 'row', style: { marginTop: '10px' } }, btn(`Go to ${m.parentTitle || 'season'}`, () => go(`#/item/${m.parentRatingKey}`), { cls: 'sm' })));
  }
  const cast = castRow(m);
  if (cast) page.append(cast);
  const extras = (m.Extras?.Metadata || []);
  if (extras.length) page.append(section('Extras', plexRow(extras, { wide: true, forcePlay: true })));
  api.get(`/api/plex/meta/${m.ratingKey}/related`).then(hubs => {
    for (const hub of hubs) if (hub.Metadata?.length) page.append(section(hub.title, plexRow(hub.Metadata)));
  }).catch(() => {});
}

async function listView(el, title, sub, items, art) {
  el.className = 'page';
  const playAll = (shuffle) => {
    const q = items.filter(i => ['movie', 'episode', 'clip'].includes(i.type));
    if (shuffle) q.sort(() => Math.random() - 0.5);
    if (q[0]) playPlex(q[0].ratingKey, { queue: q, queueIndex: 0 });
  };
  el.append(h('div', { class: 'page-head' }, h('div', { class: 'grow' }, h('h1', {}, title), h('div', { class: 'muted' }, sub)),
    btn('Play all', () => playAll(false), { cls: 'primary', iconName: 'play' }), btn('Shuffle', () => playAll(true), { iconName: 'shuffle' })));
  if (!items.length) { el.append(empty('This list is empty')); return; }
  const allEps = items.every(i => i.type === 'episode');
  el.append(allEps ? h('div', { class: 'ep-list' }, items.map(episodeRow)) : h('div', { class: 'grid' }, items.map(i => plexCard(i))));
}

async function playlistView(el, route) {
  const pl = await api.get(`/api/plex/playlist/${route.parts[0]}`);
  return listView(el, pl.title, `${pl.leafCount || pl.items.length} items${pl.duration ? ' · ' + fmtRuntime(pl.duration / 1000) : ''}`, pl.items);
}

async function collectionView(el, route) {
  const key = route.parts[0];
  const [meta, items] = await Promise.all([api.get(`/api/plex/meta/${key}`).catch(() => ({})), api.get(`/api/plex/collection/${key}/items`)]);
  return listView(el, meta.title || 'Collection', meta.summary || `${items.length} items`, items);
}

export const views = { item: itemView, playlist: playlistView, collection: collectionView };
export default itemView;

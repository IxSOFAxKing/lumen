import { card, h, hrow, contextMenu, toast, toastErr } from './ui.js';
import { plexImg, api } from './api.js';
import { go } from './app.js';
import { playPlex } from './playback.js';

/** Turn a Plex metadata object into a card element. */
export function plexCard(m, { wide = false, forcePlay = false } = {}) {
  const isEp = m.type === 'episode';
  const isClip = m.type === 'clip';
  const useWide = wide || isEp || isClip;
  const imgPath = useWide
    ? (isEp ? (m.thumb || m.grandparentArt || m.art) : (m.art || m.thumb || m.composite))
    : (m.type === 'season' ? (m.thumb || m.parentThumb) : (m.thumb || m.grandparentThumb || m.composite));
  const img = useWide ? plexImg(imgPath, 480, 270) : plexImg(imgPath, 300, 450);
  let title = m.title, sub = m.year ? String(m.year) : '';
  if (isEp) { title = m.grandparentTitle || m.title; sub = `S${m.parentIndex ?? '?'} · E${m.index ?? '?'} — ${m.title}`; }
  else if (m.type === 'season') { title = m.title; sub = m.parentTitle || ''; }
  else if (m.type === 'show') sub = m.childCount ? `${m.childCount} season${m.childCount > 1 ? 's' : ''}` : (m.year || '');
  else if (m.type === 'playlist') sub = `${m.leafCount || 0} items`;
  else if (m.type === 'collection') sub = `${m.childCount || 0} items`;
  const progress = m.viewOffset && m.duration ? m.viewOffset / m.duration : 0;
  const unwatched = m.type === 'show' || m.type === 'season' ? (m.leafCount || 0) - (m.viewedLeafCount || 0) : 0;
  const played = (m.type === 'movie' || isEp) && m.viewCount > 0 && !m.viewOffset;
  const onClick = () => {
    if (m.type === 'playlist') return go(`#/playlist/${m.ratingKey}`);
    if (m.type === 'collection') return go(`#/collection/${m.ratingKey}`);
    if (forcePlay || (m.viewOffset && (m.type === 'movie' || isEp)) || isClip) return playPlex(m.ratingKey, { offset: (m.viewOffset || 0) / 1000 });
    go(`#/item/${m.ratingKey}`);
  };
  const el = card({ img, title, sub, progress, wide: useWide, onClick, corner: unwatched > 0 ? String(unwatched) : null, played, placeholder: title });
  // Options menu: right-click the card, or the ⋯ button that appears on hover.
  const open = (x, y) => itemMenu(m, x, y);
  el.addEventListener('contextmenu', e => { e.preventDefault(); open(e.clientX, e.clientY); });
  el.querySelector('.art')?.append(h('button', { class: 'card-more', title: 'More options', 'aria-label': 'More options',
    onClick: e => { e.stopPropagation(); const r = e.currentTarget.getBoundingClientRect(); open(r.left, r.bottom + 4); } }, '⋯'));
  return el;
}

const refresh = () => { api.clearCache(); window.dispatchEvent(new HashChangeEvent('hashchange')); };

/** Play / resume / more info / watched… for any Plex card. */
export function itemMenu(m, x, y) {
  const isEp = m.type === 'episode';
  const items = [];
  if (m.type === 'movie' || isEp || m.type === 'clip') {
    if (m.viewOffset) {
      items.push([`Resume from ${new Date(m.viewOffset).toISOString().slice(m.viewOffset >= 3600000 ? 11 : 14, 19)}`, () => playPlex(m.ratingKey, { offset: m.viewOffset / 1000 })]);
      items.push(['Play from the beginning', () => playPlex(m.ratingKey, { offset: 0 })]);
    } else items.push(['Play', () => playPlex(m.ratingKey, { offset: 0 })]);
  }
  if (m.type === 'playlist') items.push(['Open', () => go(`#/playlist/${m.ratingKey}`)]);
  else if (m.type === 'collection') items.push(['Open', () => go(`#/collection/${m.ratingKey}`)]);
  else items.push(['More info', () => go(`#/item/${isEp && m.grandparentRatingKey ? m.grandparentRatingKey : m.ratingKey}`)]);
  if (isEp && m.grandparentRatingKey) items.push([`Go to ${m.grandparentTitle || 'show'}`, () => go(`#/item/${m.grandparentRatingKey}`)]);
  if (['movie', 'episode', 'show', 'season'].includes(m.type)) {
    const watched = m.type === 'show' || m.type === 'season' ? (m.leafCount && m.viewedLeafCount >= m.leafCount) : m.viewCount > 0 && !m.viewOffset;
    items.push([watched ? 'Mark as unwatched' : 'Mark as watched', async () => {
      try { await api.post(`/api/plex/${watched ? 'unwatched' : 'watched'}/${m.ratingKey}`); toast(watched ? 'Marked as unwatched' : 'Marked as watched'); refresh(); } catch (e) { toastErr(e); }
    }]);
  }
  if (m.viewOffset) items.push(['Remove from Continue Watching', async () => {
    try { await api.post(`/api/plex/continue/remove/${m.ratingKey}`); toast('Removed from Continue Watching'); refresh(); } catch (e) { toastErr(e); }
  }]);
  contextMenu(items, x, y, isEp ? `${m.grandparentTitle} · S${m.parentIndex}E${m.index}` : m.title);
}

export function plexRow(items, opts) { return hrow(items.map(m => plexCard(m, opts))); }

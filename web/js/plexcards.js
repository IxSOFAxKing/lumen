import { card, h, hrow } from './ui.js';
import { plexImg } from './api.js';
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
  return card({ img, title, sub, progress, wide: useWide, onClick, corner: unwatched > 0 ? String(unwatched) : null, played, placeholder: title });
}

export function plexRow(items, opts) { return hrow(items.map(m => plexCard(m, opts))); }

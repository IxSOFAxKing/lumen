// Discover: browse what's popular and request it through Overseerr (configured in Settings → Discover).
import { api } from '../api.js';
import { h, btn, card, hrow, section, spinner, empty, modal, toast, toastErr, debounce } from '../ui.js';
import { go } from '../app.js';

const STATUS = { available: 'Available', partial: 'Partly available', processing: 'Requested', pending: 'Requested' };
const badge = s => STATUS[s] || null;

function itemCard(m) {
  return card({
    img: m.poster, title: m.title, placeholder: m.title,
    sub: [m.year, m.type === 'tv' ? 'TV' : 'Movie', m.rating ? `★ ${m.rating}` : null].filter(Boolean).join(' · '),
    corner: m.status === 'available' ? null : badge(m.status), played: m.status === 'available',
    onClick: () => openDetails(m),
  });
}

/** Detail dialog with Request / Play. */
export async function openDetails(m) {
  const body = h('div', {}, spinner());
  const dlg = modal({ title: m.title, body, size: 'lg' });
  window.addEventListener('hashchange', () => dlg.close(), { once: true });
  let d;
  try { d = await api.get(`/api/discover/${m.type}/${m.id}`); }
  catch (e) { body.replaceChildren(empty('Could not load details', e.message)); return; }
  const chosen = new Set(d.seasons.filter(s => s.status === 'none').map(s => s.number));
  const status = h('div');
  const drawStatus = () => status.replaceChildren(d.status !== 'none' ? h('span', { class: `badge ${d.status === 'available' ? 'ok' : ''}` }, (badge(d.status) || '').toUpperCase()) : '');
  drawStatus();
  const request = async (ev) => {
    const b = ev.currentTarget; b.disabled = true;
    try {
      const r = await api.post('/api/discover/request', { type: d.type, id: d.id, seasons: d.type === 'tv' ? [...chosen] : undefined });
      toast(`Requested ${d.title}${r.as === 'admin' ? '' : ' (as you)'}`, { type: 'ok' });
      d.status = 'pending'; drawStatus(); b.textContent = 'Requested';
    } catch (e) { toastErr(e); b.disabled = false; }
  };
  const seasons = d.type === 'tv' && d.seasons.length ? h('div', { class: 'disc-seasons' },
    h('div', { class: 'muted small', style: { marginBottom: '6px' } }, 'Seasons to request'),
    ...d.seasons.map(s => {
      const have = s.status !== 'none';
      const cb = h('input', { type: 'checkbox', checked: !have && chosen.has(s.number), disabled: have, onChange: e => { e.target.checked ? chosen.add(s.number) : chosen.delete(s.number); } });
      return h('label', { class: 'disc-season' }, cb, ` ${s.name || `Season ${s.number}`}`, h('span', { class: 'dim small' }, ` · ${s.episodes || '?'} episodes${have ? ` · ${badge(s.status)}` : ''}`));
    })) : null;
  const canRequest = d.status === 'none' || (d.type === 'tv' && d.seasons.some(s => s.status === 'none'));
  body.replaceChildren(h('div', { class: 'disc-detail' },
    d.backdrop ? h('div', { class: 'disc-backdrop', style: { backgroundImage: `url("${d.backdrop}")` } }) : null,
    h('div', { class: 'disc-main' },
      d.poster ? h('img', { class: 'disc-poster', src: d.poster, alt: '' }) : null,
      h('div', { class: 'grow' },
        h('div', { class: 'muted' }, [d.year, d.certification, d.runtime ? `${d.runtime} min` : null, d.genres.slice(0, 3).join(', ') || null, d.rating ? `★ ${d.rating}` : null].filter(Boolean).join('  ·  ')),
        d.tagline ? h('p', { style: { fontStyle: 'italic', margin: '8px 0 0' } }, d.tagline) : null,
        h('p', {}, d.overview || 'No description.'),
        status,
        seasons,
        h('div', { class: 'row wrap', style: { marginTop: '12px', gap: '8px' } },
          d.ratingKey ? btn('Play in Lumen', () => { dlg.close(); go(`#/item/${d.ratingKey}`); }, { cls: 'primary', iconName: 'play' }) : null,
          canRequest ? btn(d.type === 'tv' ? 'Request seasons' : 'Request', request, { cls: d.ratingKey ? '' : 'primary', iconName: 'plus' }) : null,
          d.trailer ? h('a', { class: 'btn', href: d.trailer, target: '_blank', rel: 'noopener' }, 'Trailer') : null),
        d.cast.length ? h('div', { class: 'disc-cast' }, ...d.cast.map(c => h('div', { class: 'disc-person' },
          c.photo ? h('img', { src: c.photo, alt: '', loading: 'lazy' }) : h('div', { class: 'ph' }, c.name[0]),
          h('div', { class: 'small' }, c.name), h('div', { class: 'dim small' }, c.character || '')))) : null))));
}

const ROWS = [['trending', 'Trending'], ['movies', 'Popular movies'], ['tv', 'Popular TV shows'], ['movies-upcoming', 'Upcoming movies'], ['tv-upcoming', 'Upcoming TV shows']];

export default async function discover(el, route) {
  el.className = 'page';
  const cfg = await api.get('/api/discover/config').catch(() => ({ configured: false }));
  if (!cfg.configured) {
    el.append(empty('Connect Overseerr to discover and request', 'Add your Overseerr (or Jellyseerr) address and API key in Settings → Discover. Every Lumen app will use it.',
      btn('Open Settings', () => go('#/settings?tab=discover'), { cls: 'primary', iconName: 'settings' })));
    return;
  }
  const tab = route.query.tab || 'browse';
  const q = h('input', { class: 'input', placeholder: 'Search movies and TV shows to request…', value: route.query.q || '', style: { flex: '1', minWidth: '220px' } });
  const tabs = h('div', { class: 'tabs' },
    h('a', { class: `tab ${tab === 'browse' ? 'active' : ''}`, href: '#/discover' }, 'Discover'),
    h('a', { class: `tab ${tab === 'requests' ? 'active' : ''}`, href: '#/discover?tab=requests' }, 'Requests'));
  const content = h('div');
  el.append(h('div', { class: 'row wrap', style: { gap: '14px', alignItems: 'center', marginBottom: '10px' } }, h('h1', { style: { margin: 0 } }, 'Discover'), tabs, q), content);

  const browse = () => {
    content.replaceChildren();
    for (const [name, title] of ROWS) {
      const slot = h('div', {}, h('div', { class: 'skel skel-title' }));
      content.append(slot);
      api.cached(`/api/discover/list/${name}`, 600000).then(r => slot.replaceChildren(r.results.length ? section(title, hrow(r.results.map(itemCard))) : ''))
        .catch(e => slot.replaceChildren(name === 'trending' ? empty('Could not reach Overseerr', e.message) : ''));
    }
  };
  const search = async (text) => {
    if (text.trim().length < 2) return tab === 'browse' ? browse() : requests();
    content.replaceChildren(spinner());
    try {
      const r = await api.get(`/api/discover/search?q=${encodeURIComponent(text.trim())}`);
      content.replaceChildren(r.results.length ? h('div', { class: 'grid' }, r.results.map(itemCard)) : empty(`Nothing found for "${text.trim()}"`));
    } catch (e) { content.replaceChildren(empty('Search failed', e.message)); }
  };
  const requests = async () => {
    content.replaceChildren(spinner());
    try {
      const r = await api.get('/api/discover/requests');
      content.replaceChildren(r.results.length ? h('div', { class: 'disc-reqs' }, ...r.results.map(m => h('div', { class: 'prov', style: { cursor: 'pointer' }, onClick: () => openDetails(m) },
        m.poster ? h('img', { src: m.poster, alt: '', style: { width: '46px', borderRadius: '6px' } }) : null,
        h('div', { class: 'grow' }, h('b', {}, m.title), h('div', { class: 'dim small' },
          [m.type === 'tv' ? `TV${m.seasons.length ? ` · seasons ${m.seasons.join(', ')}` : ''}` : 'Movie', m.requestedBy ? `by ${m.requestedBy}` : null, m.requestedAt ? new Date(m.requestedAt).toLocaleDateString() : null].filter(Boolean).join(' · '))),
        h('span', { class: `badge ${m.mediaStatus === 'available' ? 'ok' : ''}` }, m.mediaStatus === 'available' ? 'AVAILABLE' : m.requestStatus.toUpperCase()))))
        : empty('No requests yet', 'Requests made from any Lumen app show up here.'));
    } catch (e) { content.replaceChildren(empty('Could not load requests', e.message)); }
  };
  q.addEventListener('input', debounce(() => search(q.value), 450));
  if (q.value) search(q.value); else if (tab === 'requests') requests(); else browse();
}

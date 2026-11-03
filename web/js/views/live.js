import { api, qs } from '../api.js';
import { h, logo, nowS, fmtTime, fmtDay, empty, spinner, debounce, toast, toastErr, btn, prefs, modal } from '../ui.js';
import { icon } from '../icons.js';
import { store, activeProvider, setActiveProvider, go } from '../app.js';
import { attachEngine, releaseDesc } from '../engine.js';
import { playLive, playCatchup } from '../playback.js';
import { programmeModal } from './guide.js';

export default async function live(el, route) {
  const prov = activeProvider();
  if (!prov) { el.append(empty('No IPTV provider', 'Add an Xtream Codes or M3U provider in Settings.', btn('Settings', () => go('#/settings'), { cls: 'primary' }))); return; }
  el.style.height = '100%';
  const pid = prov.id;
  let cat = route.query.cat || prefs.get(`livecat.${pid}`, '__all');
  let channels = [], selected = null, preview = null, previewDesc = null, q = '', reordering = false, rendered = 0, moreIO = null;
  const CHUNK = 150;

  const catsCol = h('div', { class: 'col cats' });
  const listCol = h('div', { class: 'col chlist' });
  const pvVideo = h('video', { playsinline: true, autoplay: true });
  const pvMsg = h('div', { class: 'msg' }, 'Select a channel to preview');
  const info = h('div', { class: 'info' });
  const pvCol = h('div', { class: 'col preview' }, h('div', { class: 'pv', onDblclick: () => fullscreen() }, pvVideo, pvMsg), info);
  el.append(h('div', { class: 'live' }, catsCol, listCol, pvCol));

  // ---- categories ----
  const cats = await api.cached(`/api/iptv/providers/${pid}/categories?kind=live`, 120000);
  const searchInput = h('input', { class: 'input', placeholder: 'Search channels…', style: { width: '100%', marginBottom: '8px' }, onInput: debounce(e => { q = e.target.value.trim(); loadChannels(); }, 250) });
  const provSel = store.providers.length > 1 ? h('select', { class: 'input', style: { width: '100%', marginBottom: '8px' }, onChange: e => { setActiveProvider(e.target.value); go('#/live'); location.reload(); } },
    store.providers.map(p => h('option', { value: p.id, selected: p.id === pid }, p.name))) : null;
  function renderCats() {
    const item = (id, name, n) => h('div', { class: `cat ${cat === id ? 'active' : ''}`, onClick: () => { cat = id; reordering = false; prefs.set(`livecat.${pid}`, id); renderCats(); loadChannels(); } },
      h('span', { class: 'ellipsis' }, name), n != null ? h('span', { class: 'n' }, n) : null);
    catsCol.replaceChildren(provSel || '', searchInput,
      item('__fav', '★ Favorites'), item('__recent', '⟲ Recently watched'), item('__all', 'All channels'),
      cats.filter(c => c.count > 0).map(c => item(c.id, c.name, c.count)));
  }
  renderCats();

  // ---- channels ----
  async function loadChannels() {
    listCol.replaceChildren(spinner());
    channels = await api.cached(`/api/iptv/providers/${pid}/channels${qs({ category: q ? '__all' : cat, q })}`, 30000);
    renderList();
  }
  function catTitle() { return cat === '__fav' ? 'Favorites' : cat === '__recent' ? 'Recently watched' : cat === '__all' ? 'All channels' : cats.find(c => c.id === cat)?.name || 'Channels'; }

  function chRow(c, i) {
    const pct = c.now ? Math.min(100, ((nowS() - c.now.start) / (c.now.stop - c.now.start)) * 100) : 0;
    const row = h('div', { class: `ch ${selected?.id === c.id ? 'active' : ''}`, tabindex: 0, dataset: { i },
      onClick: () => { if (selected?.id === c.id) fullscreen(); else select(c); },
      onKeydown: e => {
        if (e.key === 'Enter') fullscreen();
        else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
          e.preventDefault();
          const j = i + (e.key === 'ArrowDown' ? 1 : -1);
          if (j >= rendered && j < channels.length) renderMore();
          const n = listCol.querySelector(`[data-i="${j}"]`); n?.focus(); n?.click();
        }
        else if (e.key === 'f') toggleFav(c);
      },
      onContextmenu: e => { e.preventDefault(); chMenu(c); } },
      reordering ? h('div', { class: 'grip', title: 'Drag to reorder', html: icon('grip'), onPointerdown: e => startDrag(e, i), onClick: e => e.stopPropagation() }) : null,
      h('div', { class: 'num' }, reordering ? i + 1 : (c.num ?? '')), logo(c.logo, c.name),
      h('div', { class: 'grow' },
        h('div', { class: 'nm ellipsis' }, c.name, c.archive ? h('span', { class: 'badge', style: { marginLeft: '6px' }, title: 'Catch-up available' }, '⟲') : null),
        h('div', { class: 'pr ellipsis' }, c.now ? `${fmtTime(c.now.start)}  ${c.now.title}` : ''),
        c.now ? h('div', { class: 'progress' }, h('i', { style: { width: `${pct}%` } })) : null),
      reordering
        ? h('div', { class: 'row', style: { gap: '2px' } },
          h('button', { class: 'btn ghost sm icon', title: 'Move up', html: icon('up'), disabled: i === 0, onClick: e => { e.stopPropagation(); moveFav(i, i - 1); } }),
          h('button', { class: 'btn ghost sm icon', title: 'Move down', html: icon('down'), disabled: i === channels.length - 1, onClick: e => { e.stopPropagation(); moveFav(i, i + 1); } }))
        : h('button', { class: `btn ghost sm icon ${c.fav ? 'fav-star' : 'dim'}`, title: c.fav ? 'Remove favorite' : 'Add favorite', html: icon(c.fav ? 'starf' : 'star'), onClick: e => { e.stopPropagation(); toggleFav(c); } }));
    return row;
  }

  // Large lists render in chunks as you scroll, so big providers stay snappy.
  function renderMore() {
    const end = Math.min(channels.length, rendered + CHUNK);
    const frag = document.createDocumentFragment();
    for (let i = rendered; i < end; i++) frag.append(chRow(channels[i], i));
    listCol.querySelector('.more-sentinel')?.before(frag);
    rendered = end;
    if (rendered >= channels.length) listCol.querySelector('.more-sentinel')?.remove();
  }
  function renderList() {
    moreIO?.disconnect();
    rendered = 0;
    if (!channels.length) { listCol.replaceChildren(empty(cat === '__fav' ? 'No favorites yet' : 'No channels', cat === '__fav' ? 'Tap the ☆ next to any channel to add it.' : '')); return; }
    const sentinel = h('div', { class: 'more-sentinel', style: { height: '1px' } });
    const bar = cat === '__fav' && channels.length > 1 ? h('div', { class: 'reorder-bar' },
      h('span', { class: 'grow small muted' }, reordering ? 'Drag ⠿ or use the arrows to reorder' : `${channels.length} favorites`),
      h('button', { class: `btn sm ${reordering ? 'primary' : ''}`, onClick: () => { reordering = !reordering; renderList(); } }, reordering ? 'Done' : 'Reorder')) : null;
    listCol.replaceChildren(bar, sentinel);
    renderMore();
    moreIO = new IntersectionObserver(([e]) => { if (e.isIntersecting) renderMore(); }, { root: listCol, rootMargin: '800px' });
    if (sentinel.isConnected) moreIO.observe(sentinel);
  }

  // ---- favorites reordering ----
  async function saveFavOrder() {
    try { await api.put('/api/iptv/favorites/order', { order: channels.map(c => ({ providerId: pid, channelId: c.id })) }); }
    catch (e) { toastErr(e); }
  }
  function moveFav(from, to) {
    if (to < 0 || to >= channels.length || from === to) return;
    const [c] = channels.splice(from, 1);
    channels.splice(to, 0, c);
    renderList();
    listCol.querySelector(`[data-i="${to}"]`)?.scrollIntoView({ block: 'nearest' });
    saveFavOrder();
  }
  function startDrag(e, from) {
    e.preventDefault(); e.stopPropagation();
    const rowEl = e.currentTarget.closest('.ch');
    rowEl.classList.add('dragging');
    let target = null, below = false;
    const clear = () => listCol.querySelectorAll('.drop-above,.drop-below').forEach(x => x.classList.remove('drop-above', 'drop-below'));
    const move = ev => {
      const over = document.elementFromPoint(ev.clientX, ev.clientY)?.closest('.chlist .ch');
      clear();
      if (!over || over === rowEl) { target = null; return; }
      const r = over.getBoundingClientRect();
      below = ev.clientY > r.top + r.height / 2;
      over.classList.add(below ? 'drop-below' : 'drop-above');
      target = Number(over.dataset.i);
      // auto-scroll near the edges
      const lr = listCol.getBoundingClientRect();
      if (ev.clientY < lr.top + 40) listCol.scrollTop -= 12; else if (ev.clientY > lr.bottom - 40) listCol.scrollTop += 12;
    };
    const up = () => {
      document.removeEventListener('pointermove', move); document.removeEventListener('pointerup', up);
      rowEl.classList.remove('dragging'); clear();
      if (target === null) return;
      let to = target + (below ? 1 : 0);
      if (to > from) to--;
      moveFav(from, to);
    };
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', up);
  }

  async function toggleFav(c) {
    try {
      if (c.fav) await api.del(`/api/iptv/favorites/${pid}/${encodeURIComponent(c.id)}`);
      else await api.post('/api/iptv/favorites', { providerId: pid, channelId: c.id });
      c.fav = c.fav ? 0 : 1;
      toast(c.fav ? `Added ${c.name} to favorites` : `Removed ${c.name} from favorites`);
      if (cat === '__fav' && !c.fav) channels = channels.filter(x => x !== c);
      renderList();
      if (selected?.id === c.id) renderInfo();
    } catch (e) { toastErr(e); }
  }

  function chMenu(c) {
    const m = modal({ title: c.name, body: h('div', { class: 'row wrap' },
      btn(c.fav ? 'Remove favorite' : 'Add to favorites', () => { m.close(); toggleFav(c); }, { iconName: 'star' }),
      btn('Record now (1h)', async () => { m.close(); await record(c, nowS(), c.now?.stop || nowS() + 3600, c.now?.title || c.name); }, { iconName: 'rec' }),
      btn('Hide channel', async () => { m.close(); await api.put(`/api/iptv/channels/${pid}/${encodeURIComponent(c.id)}`, { hidden: true }); channels = channels.filter(x => x !== c); renderList(); toast('Channel hidden — manage hidden channels in Settings'); }, { iconName: 'eyeoff' }),
      btn('Open in Multi-View', () => { m.close(); stopPreview(); go(`#/multiview?add=${pid}:${encodeURIComponent(c.id)}`); }, { iconName: 'grid' })) });
  }

  async function record(c, start, stop, title) {
    try { await api.post('/api/iptv/recordings', { providerId: pid, channelId: c.id, start, stop, title }); toast(`Recording "${title}"`, { type: 'ok' }); }
    catch (e) { toastErr(e); }
  }

  // ---- preview ----
  async function select(c) {
    selected = c;
    listCol.querySelectorAll('.ch.active').forEach(x => x.classList.remove('active'));
    listCol.querySelector(`[data-i="${channels.indexOf(c)}"]`)?.classList.add('active');
    renderInfo();
    if (window.lumenSettings?.ui?.showPreview === false) return;
    stopPreview();
    pvMsg.replaceChildren(h('div', { class: 'spinner' }));
    pvMsg.classList.remove('hidden');
    try {
      const desc = await api.post('/api/iptv/play/live', { providerId: pid, channelId: c.id });
      if (selected !== c) { releaseDesc(desc); return; }
      previewDesc = desc;
      const tryAttach = (d, mode) => {
        preview = attachEngine(pvVideo, d, { onFatal: async () => {
          preview?.destroy();
          const nm = mode === 'direct' ? 'remux' : mode === 'remux' ? 'transcode' : null;
          if (!nm || selected !== c) { pvMsg.textContent = 'Preview unavailable'; pvMsg.classList.remove('hidden'); return; }
          const d2 = await api.post('/api/iptv/play/live', { providerId: pid, channelId: c.id, mode: nm }).catch(() => null);
          if (!d2 || selected !== c) return;
          releaseDesc(previewDesc); previewDesc = d2; tryAttach(d2, nm);
        } });
      };
      tryAttach(desc, String(desc.method).startsWith('direct') ? 'direct' : desc.method.startsWith('remux') ? 'remux' : 'transcode');
      pvVideo.onplaying = () => pvMsg.classList.add('hidden');
    } catch (e) { pvMsg.textContent = e.message; }
  }
  function stopPreview() {
    preview?.destroy(); preview = null;
    releaseDesc(previewDesc); previewDesc = null;
  }
  function fullscreen(c = selected) {
    if (!c) return;
    stopPreview();
    pvMsg.textContent = 'Select a channel to preview'; pvMsg.classList.remove('hidden');
    playLive(pid, c, channels, catTitle());
  }

  async function renderInfo() {
    const c = selected;
    if (!c) return;
    info.replaceChildren(
      h('div', { class: 'row', style: { marginBottom: '10px' } }, logo(c.logo, c.name), h('div', { class: 'grow' }, h('h2', { class: 'ellipsis' }, c.name), h('div', { class: 'dim small' }, `Channel ${c.num ?? ''}${c.archive ? ` · Catch-up ${c.archive_days || ''} days` : ''}`))),
      h('div', { class: 'row wrap', style: { marginBottom: '14px' } },
        btn('Watch', () => fullscreen(c), { cls: 'primary', iconName: 'play' }),
        btn(c.fav ? 'Favorite' : 'Favorite', () => toggleFav(c), { iconName: c.fav ? 'starf' : 'star' }),
        c.now ? btn('Record', () => record(c, nowS(), c.now.stop, c.now.title), { iconName: 'rec' }) : null,
        btn('', () => chMenu(c), { iconName: 'list', cls: 'icon', title: 'More' })),
      h('div', { class: 'epg-mini' }, spinner()));
    const list = await api.get(`/api/iptv/epg/${pid}/${encodeURIComponent(c.id)}${qs({ from: nowS() - (c.archive ? 86400 * Math.min(c.archive_days || 3, 7) : 3600 * 3), to: nowS() + 86400 })}`).catch(() => []);
    if (selected !== c) return;
    const box = info.querySelector('.epg-mini');
    if (!list.length) { box.replaceChildren(h('div', { class: 'dim' }, 'No guide data for this channel.')); return; }
    const t = nowS();
    let lastDay = '';
    const rows = [];
    for (const p of list) {
      const day = fmtDay(p.start);
      if (day !== lastDay) { rows.push(h('div', { class: 'dim small', style: { marginTop: '10px', fontWeight: 700 } }, day)); lastDay = day; }
      const isNow = p.start <= t && p.stop > t, past = p.stop <= t;
      rows.push(h('div', { class: `it ${isNow ? 'now' : ''} ${past ? 'past' : ''}`, onClick: () => programmeModal(pid, c, p, { onWatch: () => fullscreen(c) }) },
        h('div', { class: 'tm' }, `${fmtTime(p.start)} – ${fmtTime(p.stop)}`),
        h('div', { class: 'grow' }, h('div', { style: { fontWeight: 600 } }, p.title, past && c.archive ? h('span', { class: 'badge', style: { marginLeft: '6px' } }, '⟲ Catch-up') : null),
          isNow && p.descr ? h('div', { class: 'small muted' }, p.descr) : null)));
    }
    box.replaceChildren(...rows);
    box.querySelector('.it.now')?.scrollIntoView({ block: 'nearest' });
  }

  await loadChannels();
  const refresh = setInterval(() => { if (!document.hidden && !reordering) api.get(`/api/iptv/providers/${pid}/channels${qs({ category: q ? '__all' : cat, q })}`).then(l => { const sel = selected?.id; channels = l; renderList(); selected = channels.find(x => x.id === sel) || selected; }).catch(() => {}); }, 120000);
  return () => { clearInterval(refresh); moreIO?.disconnect(); stopPreview(); };
}

import { api, qs } from '../api.js';
import { h, logo, nowS, fmtTime, fmtDay, empty, spinner, debounce, toast, toastErr, btn, prefs, modal, confirmDialog } from '../ui.js';
import { icon } from '../icons.js';
import { store, activeProvider, setActiveProvider, go } from '../app.js';
import { attachEngine, releaseDesc } from '../engine.js';
import { playLive } from '../playback.js';
import { programmeModal } from './guide.js';
import { promptPin } from '../parental.js';
import { addPin, isPinned, removePin, ui } from '../appearance.js';
import { channelRowContent } from '../chrow.js';

export default async function live(el, route) {
  const prov = activeProvider();
  if (!prov) { el.append(empty('No IPTV provider', 'Add an Xtream Codes or M3U provider in Settings.', btn('Settings', () => go('#/settings'), { cls: 'primary' }))); return; }
  el.style.height = '100%';
  const pid = prov.id;
  const u = ui();
  let cat = route.query.cat || prefs.get(`livecat.${pid}`, '__all');
  let channels = [], selected = null, preview = null, previewDesc = null, q = '', reordering = false, rendered = 0, moreIO = null;
  let cats = [], favLists = [];
  const CHUNK = 150;

  const catsCol = h('div', { class: 'col cats' });
  const listCol = h('div', { class: `col chlist density-${u.chDensity || 'comfortable'} logos-${u.chLogoSize || 'medium'}` });
  const pvVideo = h('video', { playsinline: true, autoplay: true });
  const pvMsg = h('div', { class: 'msg' }, 'Select a channel to preview');
  const info = h('div', { class: 'info' });
  // Preview controls (shown on hover): play/pause, sound, open the full player, browser fullscreen.
  const pvBtn = (name, title, fn) => h('button', { class: 'pbtn', title, 'aria-label': title, html: icon(name), onClick: e => { e.stopPropagation(); fn(e); } });
  const pvPlay = pvBtn('pause', 'Play / pause', () => { pvVideo.paused ? pvVideo.play().catch(() => {}) : pvVideo.pause(); });
  const pvMute = pvBtn('vol', 'Mute', () => { pvVideo.muted = !pvVideo.muted; });
  const pvVol = h('input', { type: 'range', min: 0, max: 1, step: 0.05, value: pvVideo.volume, onInput: e => { pvVideo.volume = Number(e.target.value); pvVideo.muted = false; }, onClick: e => e.stopPropagation() });
  pvVideo.addEventListener('play', () => { pvPlay.innerHTML = icon('pause'); });
  pvVideo.addEventListener('pause', () => { pvPlay.innerHTML = icon('play'); });
  pvVideo.addEventListener('volumechange', () => { pvMute.innerHTML = icon(pvVideo.muted || pvVideo.volume === 0 ? 'mute' : 'vol'); pvVol.value = pvVideo.muted ? 0 : pvVideo.volume; });
  const pvBox = h('div', { class: 'pv', onDblclick: () => fullscreen() }, pvVideo, pvMsg);
  const pvCtrls = h('div', { class: 'pv-ctrls' }, pvPlay, pvMute, pvVol, h('span', { class: 'pv-live' }, 'LIVE'), h('div', { class: 'spacer', style: { flex: 1 } }),
    pvBtn('play', 'Watch in the full player', () => fullscreen()),
    pvBtn('fs', 'Fullscreen', () => { document.fullscreenElement ? document.exitFullscreen() : pvBox.requestFullscreen?.().catch(() => {}); }));
  pvBox.append(pvCtrls);
  const pvCol = h('div', { class: 'col preview' }, pvBox, info);
  el.append(h('div', { class: 'live' }, catsCol, listCol, pvCol));

  // ---- categories & favourite lists ----
  const searchInput = h('input', { class: 'input', placeholder: 'Search channels…', style: { width: '100%', marginBottom: '8px' }, onInput: debounce(e => { q = e.target.value.trim(); loadChannels(); }, 250) });
  const provSel = store.providers.length > 1 ? h('select', { class: 'input', style: { width: '100%', marginBottom: '8px' }, onChange: e => { setActiveProvider(e.target.value); go('#/live'); location.reload(); } },
    store.providers.map(p => h('option', { value: p.id, selected: p.id === pid }, p.name))) : null;

  async function loadCats() {
    [cats, favLists] = await Promise.all([
      api.cached(`/api/iptv/providers/${pid}/categories?kind=live`, 120000),
      api.cached('/api/iptv/fav-lists', 60000),
    ]);
  }
  const favCat = l => `__fav:${l.id}`;
  function catTitle() {
    if (cat === '__recent') return 'Recently watched';
    if (cat === '__all') return 'All channels';
    const fl = favLists.find(l => favCat(l) === cat || (cat === '__fav' && l === favLists[0]));
    if (fl) return fl.name;
    return cats.find(c => c.id === cat)?.name || 'Channels';
  }
  async function openCat(id, locked) {
    if (locked && !(await promptPin('This group is locked. Enter your PIN to view it.'))) return;
    if (locked) { await loadCats(); }
    cat = id; reordering = false; prefs.set(`livecat.${pid}`, id); renderCats(); loadChannels();
  }
  function renderCats() {
    const item = (id, name, n, { locked, menu } = {}) => h('div', { class: `cat ${cat === id ? 'active' : ''}`, tabindex: 0,
      onClick: () => openCat(id, locked),
      onContextmenu: menu ? e => { e.preventDefault(); menu(); } : null },
      h('span', { class: 'ellipsis' }, locked ? '🔒 ' : '', name), n != null ? h('span', { class: 'n' }, n) : null);
    catsCol.replaceChildren(provSel || '', searchInput,
      ...favLists.map(l => item(favCat(l), `★ ${l.name}`, l.count || null, { menu: () => listMenu(l) })),
      h('div', { class: 'cat dim small', tabindex: 0, onClick: newList }, '+ New favourites list'),
      item('__recent', '⟲ Recently watched'), item('__all', 'All channels'),
      cats.filter(c => c.count > 0).map(c => item(c.id, c.name, c.count, { locked: !!c.locked, menu: () => groupMenu(c) })));
  }

  async function newList() {
    const name = window.prompt('Name for the new favourites list (e.g. Sports, Kids, News):');
    if (!name?.trim()) return;
    try { const l = await api.post('/api/iptv/fav-lists', { name: name.trim() }); await loadCats(); openCat(`__fav:${l.id}`); toast(`Created "${l.name}"`, { type: 'ok' }); }
    catch (e) { toastErr(e); }
  }
  function listMenu(l) {
    const pinId = `favList:${l.id}`;
    const m = modal({ title: `★ ${l.name}`, body: h('div', { class: 'row wrap' },
      btn('Rename', async () => { m.close(); const n = window.prompt('Rename list', l.name); if (n?.trim()) { await api.put(`/api/iptv/fav-lists/${l.id}`, { name: n.trim() }); await loadCats(); renderCats(); } }, { iconName: 'edit' }),
      btn(isPinned(pinId) ? 'Unpin from Home' : 'Pin to Home', async () => { m.close(); isPinned(pinId) ? await removePin(pinId, api.put) : await addPin({ id: pinId, type: 'favList', listId: l.id, title: l.name }, api.put); toast(isPinned(pinId) ? 'Pinned to Home' : 'Removed from Home', { type: 'ok' }); }, { iconName: 'home' }),
      btn('Delete list', async () => { m.close(); if (!(await confirmDialog(`Delete "${l.name}"?`, 'The channels stay available; only this list is removed.', { okLabel: 'Delete', danger: true }))) return; try { await api.del(`/api/iptv/fav-lists/${l.id}`); await loadCats(); openCat('__all'); } catch (e) { toastErr(e); } }, { cls: 'danger', iconName: 'trash' })) });
  }
  function groupMenu(c) {
    const pinId = `iptvGroup:${pid}:${c.id}`;
    const m = modal({ title: c.name, body: h('div', {}, h('div', { class: 'row wrap' },
      btn('Rename group', async () => { m.close(); const n = window.prompt(`Rename "${c.original_name || c.name}" (leave empty to restore the original name)`, c.name === c.original_name ? '' : c.name); if (n === null) return; await api.put(`/api/iptv/categories/${pid}/live/${encodeURIComponent(c.id)}`, { name: n }); await loadCats(); renderCats(); }, { iconName: 'edit' }),
      btn(isPinned(pinId) ? 'Unpin from Home' : 'Pin to Home', async () => { m.close(); isPinned(pinId) ? await removePin(pinId, api.put) : await addPin({ id: pinId, type: 'iptvGroup', pid, cat: c.id, title: c.name }, api.put); toast(isPinned(pinId) ? 'Pinned to Home' : 'Removed from Home', { type: 'ok' }); }, { iconName: 'home' }),
      btn(c.locked ? 'Unlock group' : 'Lock with PIN', async () => { m.close(); try { await api.put(`/api/iptv/categories/${pid}/live/${encodeURIComponent(c.id)}`, { locked: !c.locked }); await loadCats(); renderCats(); toast(c.locked ? 'Group unlocked' : 'Group locked', { type: 'ok' }); } catch (e) { toastErr(e); } }, { iconName: 'eyeoff' }),
      btn('Hide group', async () => { m.close(); await api.put(`/api/iptv/categories/${pid}/live/${encodeURIComponent(c.id)}`, { hidden: true }); await loadCats(); if (cat === c.id) openCat('__all'); else renderCats(); toast('Group hidden — restore it in Settings → IPTV Providers → Groups'); }, { iconName: 'eyeoff' })),
      h('p', { class: 'dim small' }, 'Tip: reorder groups in Settings → IPTV Providers → Groups, and channels with “Reorder” above the channel list.')) });
  }

  // ---- channels ----
  let loadToken = 0;
  async function loadChannels() {
    const my = ++loadToken;
    listCol.replaceChildren(spinner());
    const list = await api.cached(`/api/iptv/providers/${pid}/channels${qs({ category: q ? '__all' : cat, q })}`, 30000);
    if (my !== loadToken) return; // a newer group/search was selected meanwhile
    channels = list;
    renderList();
  }
  const isFavView = () => cat === '__fav' || cat.startsWith('__fav:');
  const currentListId = () => (cat.startsWith('__fav:') ? Number(cat.slice(6)) : favLists[0]?.id);

  function chRow(c, i) {
    const row = h('div', { class: `ch ${selected?.id === c.id ? 'active' : ''}`, tabindex: 0, dataset: { i },
      onClick: () => { if (reordering) return; if (selected?.id === c.id) fullscreen(); else select(c); },
      onKeydown: e => {
        if (e.key === 'Enter' && !reordering) { e.preventDefault(); fullscreen(); }
        else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
          e.preventDefault(); e.stopPropagation();
          const j = i + (e.key === 'ArrowDown' ? 1 : -1);
          if (j >= rendered && j < channels.length) renderMore();
          const n = listCol.querySelector(`[data-i="${j}"]`); n?.focus(); if (!reordering) n?.click();
        }
        else if (e.key === 'f') toggleFav(c);
        else if (e.key === 'ContextMenu' || e.key === 'm') chMenu(c);
      },
      onContextmenu: e => { e.preventDefault(); chMenu(c); } },
      reordering ? h('div', { class: 'grip', title: 'Drag to reorder', html: icon('grip'), onPointerdown: e => startDrag(e, i), onClick: e => e.stopPropagation() }) : null,
      ...channelRowContent(c, { index: reordering ? i : undefined, extra: reordering
        ? h('div', { class: 'row', style: { gap: '2px' } },
          h('button', { class: 'btn ghost sm icon', title: 'Move up', html: icon('up'), disabled: i === 0, onClick: e => { e.stopPropagation(); moveCh(i, i - 1); } }),
          h('button', { class: 'btn ghost sm icon', title: 'Move down', html: icon('down'), disabled: i === channels.length - 1, onClick: e => { e.stopPropagation(); moveCh(i, i + 1); } }))
        : h('button', { class: `btn ghost sm icon ${c.fav ? 'fav-star' : 'dim'}`, title: 'Favourite lists', html: icon(c.fav ? 'starf' : 'star'), onClick: e => { e.stopPropagation(); toggleFav(c); } }) }));
    return row;
  }

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
    if (!channels.length) { listCol.replaceChildren(empty(isFavView() ? 'This list is empty' : 'No channels', isFavView() ? 'Tap the ☆ next to any channel to add it.' : '')); return; }
    const sentinel = h('div', { class: 'more-sentinel', style: { height: '1px' } });
    const canReorder = !q && cat !== '__recent' && channels.length > 1;
    const bar = canReorder ? h('div', { class: 'reorder-bar' },
      h('span', { class: 'grow small muted' }, reordering ? 'Drag ⠿ or use the arrows. Changes save automatically.' : `${catTitle()} · ${channels.length} channels`),
      reordering && !isFavView() && cat !== '__all' ? btn('Reset order', async () => { await api.del(`/api/iptv/channels-order/${pid}`, { ids: channels.map(c => c.id) }).catch(toastErr); reordering = false; loadChannels(); }, { cls: 'sm' }) : null,
      cat === '__all' && !reordering ? null : h('button', { class: `btn sm ${reordering ? 'primary' : ''}`, onClick: () => { reordering = !reordering; renderList(); } }, reordering ? 'Done' : 'Reorder')) : null;
    listCol.replaceChildren(bar, sentinel);
    renderMore();
    moreIO = new IntersectionObserver(([e]) => { if (e.isIntersecting) renderMore(); }, { root: listCol, rootMargin: '800px' });
    if (sentinel.isConnected) moreIO.observe(sentinel);
  }

  // ---- reordering (favourite lists and groups) ----
  async function saveOrder() {
    try {
      if (isFavView()) await api.put('/api/iptv/favorites/order', { listId: currentListId(), order: channels.map(c => ({ providerId: pid, channelId: c.id })) });
      else await api.put(`/api/iptv/channels-order/${pid}`, { order: channels.map(c => c.id) });
    } catch (e) { toastErr(e); }
  }
  function moveCh(from, to) {
    if (to < 0 || to >= channels.length || from === to) return;
    const [c] = channels.splice(from, 1);
    channels.splice(to, 0, c);
    renderList();
    listCol.querySelector(`[data-i="${to}"]`)?.scrollIntoView({ block: 'nearest' });
    saveOrder();
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
      const lr = listCol.getBoundingClientRect();
      if (ev.clientY < lr.top + 40) listCol.scrollTop -= 12; else if (ev.clientY > lr.bottom - 40) listCol.scrollTop += 12;
    };
    const up = () => {
      document.removeEventListener('pointermove', move); document.removeEventListener('pointerup', up);
      rowEl.classList.remove('dragging'); clear();
      if (target === null) return;
      let to = target + (below ? 1 : 0);
      if (to > from) to--;
      moveCh(from, to);
    };
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', up);
  }

  // ---- favourites ----
  async function setFav(c, listId, on) {
    if (on) await api.post('/api/iptv/favorites', { providerId: pid, channelId: c.id, listId });
    else await api.del(`/api/iptv/favorites/${pid}/${encodeURIComponent(c.id)}?list=${listId}`);
    c.fav_lists = on ? [...new Set([...(c.fav_lists || []), listId])] : (c.fav_lists || []).filter(x => x !== listId);
    c.fav = c.fav_lists.length ? 1 : 0;
  }
  async function toggleFav(c) {
    try {
      if (favLists.length <= 1) {
        const l = favLists[0]?.id || 1, on = !(c.fav_lists || []).includes(l);
        await setFav(c, l, on);
        toast(on ? `Added ${c.name} to favourites` : `Removed ${c.name} from favourites`);
      } else {
        // Several lists: choose which ones
        const boxes = favLists.map(l => h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: (c.fav_lists || []).includes(l.id), dataset: { id: l.id } }), `★ ${l.name}`));
        await new Promise(resolve => modal({ title: `Favourites · ${c.name}`, body: h('div', {}, ...boxes), onClose: resolve,
          actions: [{ label: 'Cancel' }, { label: 'Save', primary: true, onClick: async () => {
            for (const b of boxes) { const inp = b.querySelector('input'), id = Number(inp.dataset.id); if (inp.checked !== (c.fav_lists || []).includes(id)) await setFav(c, id, inp.checked); }
            toast('Favourites updated', { type: 'ok' });
          } }] }));
      }
      await loadCats(); renderCats();
      if (isFavView() && !(c.fav_lists || []).includes(currentListId())) channels = channels.filter(x => x !== c);
      renderList();
      if (selected?.id === c.id) renderInfo();
    } catch (e) { toastErr(e); }
  }

  // ---- channel menu ----
  function chMenu(c) {
    const m = modal({ title: c.name, body: h('div', {},
      h('div', { class: 'row wrap' },
        btn('Favourite lists…', () => { m.close(); toggleFav(c); }, { iconName: 'star' }),
        btn('Rename / renumber', () => { m.close(); editChannel(c); }, { iconName: 'edit' }),
        btn('Record now', async () => { m.close(); await record(c, nowS(), c.now?.stop || nowS() + 3600, c.now?.title || c.name, false); }, { iconName: 'rec' }),
        btn('Open in Multi-View', () => { m.close(); stopPreview(); go(`#/multiview?add=${pid}:${encodeURIComponent(c.id)}`); }, { iconName: 'grid' }),
        btn('Hide channel', async () => { m.close(); await api.put(`/api/iptv/channels/${pid}/${encodeURIComponent(c.id)}`, { hidden: true }); channels = channels.filter(x => x !== c); renderList(); toast('Channel hidden'); }, { iconName: 'eyeoff' })),
      c.original_name && c.original_name !== c.name ? h('p', { class: 'dim small' }, `Provider name: ${c.original_name}`) : null) });
  }
  function editChannel(c) {
    const name = h('input', { class: 'input', value: c.name === c.original_name ? '' : c.name, placeholder: c.original_name || c.name });
    const num = h('input', { class: 'input', type: 'number', min: 0, value: c.num !== c.original_num ? c.num ?? '' : '', placeholder: String(c.original_num ?? '') });
    modal({ title: 'Rename / renumber channel', body: h('div', {},
      h('label', { class: 'field' }, 'Display name', name, h('span', { class: 'dim small' }, 'Leave empty to use the provider\'s name.')),
      h('label', { class: 'field' }, 'Channel number', num, h('span', { class: 'dim small' }, 'Used for number entry on the remote and for sorting. Leave empty for the provider\'s number.'))),
    actions: [{ label: 'Cancel' }, { label: 'Save', primary: true, onClick: async () => {
      await api.put(`/api/iptv/channels/${pid}/${encodeURIComponent(c.id)}`, { name: name.value, num: num.value });
      toast('Channel updated', { type: 'ok' }); await loadChannels();
    } }] });
  }

  async function record(c, start, stop, title, pad = true) {
    try { await api.post('/api/iptv/recordings', { providerId: pid, channelId: c.id, start, stop, title, pad }); toast(`Recording "${title}"`, { type: 'ok' }); }
    catch (e) { toastErr(e); }
  }

  // ---- preview ----
  async function select(c) {
    selected = c;
    listCol.querySelectorAll('.ch.active').forEach(x => x.classList.remove('active'));
    listCol.querySelector(`[data-i="${channels.indexOf(c)}"]`)?.classList.add('active');
    renderInfo();
    if (ui().showPreview === false) return;
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
        btn('Favourite', () => toggleFav(c), { iconName: c.fav ? 'starf' : 'star' }),
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
      rows.push(h('div', { class: `it ${isNow ? 'now' : ''} ${past ? 'past' : ''}`, tabindex: 0, onClick: () => programmeModal(pid, c, p, { onWatch: () => fullscreen(c) }) },
        h('div', { class: 'tm' }, `${fmtTime(p.start)} – ${fmtTime(p.stop)}`),
        h('div', { class: 'grow' }, h('div', { style: { fontWeight: 600 } }, p.title, past && c.archive ? h('span', { class: 'badge', style: { marginLeft: '6px' } }, '⟲ Catch-up') : null),
          isNow && p.descr ? h('div', { class: 'small muted' }, p.descr) : null)));
    }
    box.replaceChildren(...rows);
    box.querySelector('.it.now')?.scrollIntoView({ block: 'nearest' });
  }

  await loadCats();
  if (cat === '__fav' && favLists[0]) cat = favCat(favLists[0]);
  const locked = cats.find(c => c.id === cat)?.locked;
  if (locked) cat = '__all';
  renderCats();
  await loadChannels();
  const refresh = setInterval(() => { if (!document.hidden && !reordering) api.get(`/api/iptv/providers/${pid}/channels${qs({ category: q ? '__all' : cat, q })}`).then(l => { const sel = selected?.id; channels = l; renderList(); selected = channels.find(x => x.id === sel) || selected; }).catch(() => {}); }, 120000);
  return () => { clearInterval(refresh); moreIO?.disconnect(); stopPreview(); };
}

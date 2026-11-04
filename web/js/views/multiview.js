import { api } from '../api.js';
import { h, btn, logo, modal, debounce, toast, prefs } from '../ui.js';
import { icon } from '../icons.js';
import { activeProvider, store } from '../app.js';
import { attachEngine, releaseDesc } from '../engine.js';

export default async function multiview(el, route) {
  el.style.height = '100%';
  el.style.display = 'flex'; el.style.flexDirection = 'column';
  let layout = prefs.get('mv.layout', 4);
  let tiles = prefs.get('mv.tiles', []); // [{pid, id, name, logo}]
  if (route.query.add) {
    const [pid, id] = route.query.add.split(':');
    if (!tiles.some(t => t && t.id === id)) {
      const emptyIdx = tiles.findIndex(t => !t);
      const entry = { pid: Number(pid), id: decodeURIComponent(id) };
      if (emptyIdx >= 0) tiles[emptyIdx] = entry; else tiles.unshift(entry);
      tiles = tiles.slice(0, 4);
    }
  }
  let focus = 0;
  const players = [];
  const grid = h('div', { class: 'mv' });
  const layoutSel = [1, 2, 3, 4].map(n => h('button', { class: `chip ${layout === n ? 'active' : ''}`, onClick: () => { layout = n; prefs.set('mv.layout', n); render(); } }, `${n} view${n > 1 ? 's' : ''}`));
  el.append(h('div', { class: 'guide-bar' }, h('b', { style: { fontSize: '18px' } }, 'Multi-View'), ...layoutSel, h('span', { class: 'spacer' }),
    h('span', { class: 'dim small' }, 'Click a tile to hear its audio. Each tile uses one provider connection.')), h('div', { style: { flex: 1, minHeight: 0 } }, grid));
  grid.style.height = '100%';

  function save() { prefs.set('mv.tiles', tiles.map(t => t ? { pid: t.pid, id: t.id, name: t.name, logo: t.logo } : null)); }

  function stopTile(i) {
    const p = players[i];
    if (!p) return;
    p.engine?.destroy(); releaseDesc(p.desc); players[i] = null;
  }

  async function startTile(i, tileEl) {
    const t = tiles[i];
    const video = h('video', { playsinline: true, autoplay: true, muted: i !== focus });
    video.muted = i !== focus;
    tileEl.prepend(video);
    try {
      let desc = await api.post('/api/iptv/play/live', { providerId: t.pid, channelId: t.id });
      players[i] = { desc };
      if (!t.name && desc.channel) { t.name = desc.channel.name; t.logo = desc.channel.logo; tileEl.querySelector('.lbl').textContent = t.name; save(); }
      const attach = (d, level) => {
        players[i].engine = attachEngine(video, d, { onFatal: async () => {
          players[i]?.engine?.destroy();
          const mode = level === 0 ? 'remux' : level === 1 ? 'transcode' : null;
          if (!mode) return;
          const d2 = await api.post('/api/iptv/play/live', { providerId: t.pid, channelId: t.id, mode }).catch(() => null);
          if (!d2 || !players[i]) return;
          releaseDesc(players[i].desc); players[i].desc = d2; attach(d2, level + 1);
        } });
      };
      attach(desc, String(desc.method).startsWith('direct') ? 0 : 1);
      players[i].video = video;
    } catch (e) { toast(`${t.name || 'Channel'}: ${e.message}`, { type: 'err' }); }
  }

  function setFocus(i) {
    focus = i;
    grid.querySelectorAll('.tile').forEach((el2, j) => el2.classList.toggle('focus', j === i));
    players.forEach((p, j) => { if (p?.video) p.video.muted = j !== i; });
  }

  async function pick(i) {
    const prov = activeProvider();
    const list = h('div', { class: 'chlist', style: { maxHeight: '55vh', overflow: 'auto' } });
    const search = h('input', { class: 'input', placeholder: 'Search channels…', style: { width: '100%', marginBottom: '10px' } });
    const m = modal({ title: 'Choose channel', body: h('div', {}, search, list) });
    const load = async () => {
      const chans = await api.get(`/api/iptv/providers/${prov.id}/channels?category=${search.value ? '__all' : '__fav'}&q=${encodeURIComponent(search.value)}&epg=0`);
      const all = chans.length || search.value ? chans : await api.get(`/api/iptv/providers/${prov.id}/channels?category=__all&epg=0`);
      list.replaceChildren(...all.slice(0, 300).map(c => h('div', { class: 'ch', onClick: () => { m.close(); stopTile(i); tiles[i] = { pid: prov.id, id: c.id, name: c.name, logo: c.logo }; save(); render(); } },
        h('div', { class: 'num' }, c.num ?? ''), logo(c.logo, c.name), h('div', { class: 'nm grow ellipsis' }, c.name))));
    };
    search.addEventListener('input', debounce(load, 250));
    load();
  }

  function render() {
    players.forEach((_, i) => stopTile(i));
    layoutSel.forEach((b, j) => b.classList.toggle('active', j + 1 === layout));
    grid.className = `mv n${layout}`;
    grid.replaceChildren();
    for (let i = 0; i < layout; i++) {
      const t = tiles[i];
      const tileEl = h('div', { tabindex: 0, class: `tile ${i === focus ? 'focus' : ''}`, onClick: () => setFocus(i) });
      if (t) {
        tileEl.append(h('div', { class: 'lbl' }, t.name || 'Channel'),
          h('div', { class: 'acts' },
            h('button', { class: 'btn sm icon', title: 'Change', html: icon('edit'), onClick: e => { e.stopPropagation(); pick(i); } }),
            h('button', { class: 'btn sm icon', title: 'Fullscreen', html: icon('fs'), onClick: e => { e.stopPropagation(); const v = tileEl.querySelector('video'); (v?.requestFullscreen?.() || v?.webkitEnterFullscreen?.()); } }),
            h('button', { class: 'btn sm icon', title: 'Remove', html: icon('close'), onClick: e => { e.stopPropagation(); stopTile(i); tiles[i] = null; save(); render(); } })));
        startTile(i, tileEl);
      } else {
        tileEl.append(btn('Add channel', (e) => { e.stopPropagation(); pick(i); }, { iconName: 'plus' }));
      }
      grid.append(tileEl);
    }
  }
  if (!activeProvider()) { grid.replaceChildren(h('div', { class: 'empty' }, 'Add an IPTV provider first.')); return; }
  save();
  render();
  return () => players.forEach((_, i) => stopTile(i));
}

import { api, qs } from '../api.js';
import { h, logo, nowS, fmtTime, fmtDay, fmtDateTime, empty, btn, toast, toastErr, modal, prefs, spinner } from '../ui.js';
import { store, activeProvider, setActiveProvider, go } from '../app.js';
import { playLive, playCatchup } from '../playback.js';

/** Colour family for a programme, from its XMLTV category (and title as a fallback). */
export function programmeKind(p) {
  const s = `${p.category || ''} ${p.title || ''}`.toLowerCase();
  if (/sport|football|soccer|nfl|nba|nhl|mlb|ufc|boxing|tennis|golf|racing|f1|cricket|rugby|hockey|baseball|basketball|match|vs\.?\s/.test(s)) return 'sports';
  if (/news|weather|journal|nachrichten|noticias/.test(s)) return 'news';
  if (/movie|film|cinema|feature/.test(s)) return 'movie';
  if (/kid|child|cartoon|animation|anime|family/.test(s)) return 'kids';
  if (/documentar|nature|history|science/.test(s)) return 'doc';
  if (/series|drama|sitcom|comedy|soap|episode/.test(s)) return 'series';
  return '';
}
const PAGE = 60;

/** Programme details modal with Watch / Catch-up / Record / Remind actions. */
export function programmeModal(pid, ch, p, { onWatch, channels } = {}) {
  const t = nowS();
  const isNow = p.start <= t && p.stop > t, past = p.stop <= t, future = p.start > t;
  const canCatchup = (past || isNow) && ch.archive;
  const actions = [];
  if (isNow) actions.push({ label: '▶ Watch live', primary: true, onClick: () => (onWatch ? onWatch() : playLive(pid, ch, channels || [ch])) });
  if (canCatchup) actions.push({ label: isNow ? '⟲ Watch from start' : '⟲ Play catch-up', primary: !isNow, onClick: () => playCatchup(pid, ch, p) });
  if (!past) actions.push({ label: '● Record', onClick: async () => { await api.post('/api/iptv/recordings', { providerId: pid, channelId: ch.id, start: Math.max(p.start, t), stop: p.stop, title: p.title, descr: p.descr }); toast(isNow ? `Recording "${p.title}"` : `Scheduled recording for "${p.title}"`, { type: 'ok' }); } });
  if (future) actions.push({ label: '⏰ Remind me', onClick: async () => {
    await api.post('/api/iptv/reminders', { providerId: pid, channelId: ch.id, title: p.title, start: p.start });
    if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission();
    toast(`Reminder set for ${fmtDateTime(p.start)}`, { type: 'ok' });
  } });
  modal({
    title: p.title,
    body: h('div', {},
      h('div', { class: 'row', style: { marginBottom: '12px' } }, logo(ch.logo, ch.name), h('div', {}, h('b', {}, ch.name), h('div', { class: 'muted small' }, `${fmtDay(p.start)} · ${fmtTime(p.start)} – ${fmtTime(p.stop)} · ${Math.round((p.stop - p.start) / 60)} min`))),
      p.sub_title ? h('div', { style: { fontWeight: 600, marginBottom: '6px' } }, p.sub_title) : null,
      h('div', { class: 'row wrap', style: { marginBottom: '8px' } }, p.category ? h('span', { class: 'badge' }, p.category) : null, p.episode ? h('span', { class: 'badge' }, p.episode) : null,
        isNow ? h('span', { class: 'badge onair' }, 'ON NOW') : null, canCatchup && !isNow ? h('span', { class: 'badge ok' }, 'CATCH-UP') : null),
      h('p', { class: 'muted' }, p.descr || 'No description available.')),
    actions: [...actions, { label: 'Close', onClick: () => {} }],
  });
}

export default async function guide(el, route) {
  const PX_PER_MIN = Number(window.lumenSettings?.ui?.guideScale) || 6;
  const prov = activeProvider();
  if (!prov) { el.append(empty('No IPTV provider', 'Add one in Settings.', btn('Settings', () => go('#/settings'), { cls: 'primary' }))); return; }
  const pid = prov.id;
  el.style.height = '100%';
  let cat = route.query.cat || prefs.get(`guidecat.${pid}`, '__all');
  const gu = window.lumenSettings?.ui || {};
  const HOURS = Math.min(24, Math.max(2, Number(gu.guideHours) || 8));
  const colours = gu.guideCategoryColors !== false;
  // Start of the visible window: "now" = half an hour back on a half-hour boundary; "hour" = the current hour
  const startOf = t => gu.guideStart === 'hour' ? Math.floor(t / 3600) * 3600 : Math.floor((t - 1800) / 1800) * 1800;
  let from = startOf(nowS());
  let offset = 0, total = 0, loading = false;
  let rowsData = [];

  const [cats, favLists] = await Promise.all([api.cached(`/api/iptv/providers/${pid}/categories?kind=live`, 120000), api.cached('/api/iptv/fav-lists', 60000)]);
  if (cat === '__fav' && favLists[0]) cat = `__fav:${favLists[0].id}`;
  const catSel = h('select', { class: 'input', onChange: e => { cat = e.target.value; prefs.set(`guidecat.${pid}`, cat); reset(); } },
    h('option', { value: '__all' }, 'All channels'), favLists.map(l => h('option', { value: `__fav:${l.id}` }, `★ ${l.name}`)),
    cats.filter(c => c.count > 0 && !c.locked).map(c => h('option', { value: c.id }, c.name)));
  catSel.value = cat;
  if (catSel.value !== cat) { cat = '__all'; catSel.value = cat; }
  const days = [];
  for (let d = -7; d <= 7; d++) days.push(d);
  const daySel = h('select', { class: 'input', onChange: e => {
    const d = Number(e.target.value);
    const base = new Date(); base.setHours(0, 0, 0, 0);
    from = d === 0 ? startOf(nowS()) : Math.floor(base.getTime() / 1000) + d * 86400 + 6 * 3600;
    reset();
  } }, days.map(d => { const ts = nowS() + d * 86400; return h('option', { value: d, selected: d === 0 }, d === 0 ? 'Today' : d === -1 ? 'Yesterday' : d === 1 ? 'Tomorrow' : fmtDay(ts)); }));
  const provSel = store.providers.length > 1 ? h('select', { class: 'input', onChange: e => { setActiveProvider(e.target.value); location.reload(); } },
    store.providers.map(p => h('option', { value: p.id, selected: p.id === pid }, p.name))) : null;

  const scroller = h('div', { class: 'guide' });
  const inner = h('div', { class: 'gin' });
  scroller.append(inner);
  el.append(h('div', { class: 'guide-wrap' }, h('div', { class: 'guide-bar' }, h('b', { style: { fontSize: '18px', marginRight: '8px' } }, 'TV Guide'), provSel, catSel, daySel,
    btn('Now', () => { daySel.value = '0'; from = startOf(nowS()); reset(); }, { cls: 'sm' }),
    btn('◀', () => { from -= 3 * 3600; reset(true); }, { cls: 'sm', title: 'Earlier' }), btn('▶', () => { from += 3 * 3600; reset(true); }, { cls: 'sm', title: 'Later' }),
    h('span', { class: 'spacer' }), (colours ? h('div', { class: 'guide-legend' }, [['sports', '#3ccf8e', 'Sports'], ['news', '#38bdf8', 'News'], ['movie', '#f472b6', 'Movies'], ['series', '#a78bfa', 'Series'], ['kids', '#facc15', 'Kids'], ['doc', '#a3e635', 'Docs']]
      .map(([, c, l]) => h('span', {}, h('i', { style: { background: c } }), l)), h('span', {}, '⟲ catch-up')) : h('span', { class: 'dim small' }, 'Click a programme for catch-up, recording and reminders'))), scroller));

  const width = HOURS * 60 * PX_PER_MIN;
  const x = t => ((t - from) / 60) * PX_PER_MIN;

  function header() {
    const times = h('div', { class: 'g-times', style: { width: `${width}px` } });
    for (let t = from; t < from + HOURS * 3600; t += 1800) times.append(h('span', { style: { left: `${x(t)}px`, width: `${30 * PX_PER_MIN}px` } }, fmtTime(t)));
    return h('div', { class: 'g-head' }, h('div', { class: 'g-corner' }, fmtDay(from)), times);
  }

  function row(c) {
    const progs = h('div', { class: 'g-progs', style: { width: `${width}px` } });
    const t = nowS();
    const to = from + HOURS * 3600;
    if (!c.programmes.length) progs.append(h('div', { class: 'g-empty' }, 'No information'));
    for (const p of c.programmes) {
      const l = Math.max(0, x(p.start)), r = Math.min(width, x(Math.min(p.stop, to)));
      if (r - l < 2) continue;
      const isNow = p.start <= t && p.stop > t, past = p.stop <= t;
      const kind = colours ? programmeKind(p) : '';
      progs.append(h('div', { tabindex: 0, class: `g-p ${isNow ? 'now' : ''} ${past ? 'past' : ''} ${kind ? 'k-' + kind : ''} ${past && c.archive ? 'catchup' : ''}`, style: { left: `${l}px`, width: `${r - l - 2}px` }, title: `${p.title}\n${fmtTime(p.start)} – ${fmtTime(p.stop)}`,
        onClick: () => programmeModal(pid, c, p, { channels: rowsData }) },
        h('div', { class: 'tt' }, p.title), h('div', { class: 'tm' }, `${fmtTime(p.start)} – ${fmtTime(p.stop)}`)));
    }
    return h('div', { class: 'g-row' },
      h('div', { class: 'g-ch', tabindex: 0, title: c.name, onClick: () => playLive(pid, c, rowsData, catSel.selectedOptions[0]?.textContent) },
        h('div', { class: 'num' }, c.num ?? ''), logo(c.logo, c.name), h('div', { class: 'nm ellipsis grow' }, c.name),
        c.archive ? h('span', { class: 'badge', title: `Catch-up available${c.archive_days ? ` (${c.archive_days} days)` : ''}` }, '⟲') : null,
        c.fav ? h('span', { class: 'fav-star' }, '★') : null),
      progs);
  }

  let nowLine = null;
  function placeNow() {
    const nx = x(nowS());
    if (!nowLine) { nowLine = h('div', { class: 'g-now' }); inner.append(nowLine); }
    nowLine.style.left = `${210 + nx}px`;
    nowLine.classList.toggle('hidden', nx < 0 || nx > width);
  }

  async function loadMore() {
    if (loading || (total && offset >= total)) return;
    loading = true;
    const sp = spinner(); inner.append(sp);
    try {
      const d = await api.get(`/api/iptv/guide/${pid}${qs({ category: cat, from, to: from + HOURS * 3600, offset, limit: PAGE })}`);
      total = d.total; offset += d.channels.length;
      rowsData.push(...d.channels);
      sp.remove();
      for (const c of d.channels) inner.append(row(c));
      if (!rowsData.length) inner.append(empty('No guide data', 'Make sure EPG has been loaded for this provider (Settings → IPTV → Refresh EPG).'));
      placeNow();
    } catch (e) { sp.remove(); toastErr(e); }
    loading = false;
  }

  function reset(keepScroll) {
    const st = scroller.scrollTop;
    offset = 0; total = 0; rowsData = []; nowLine = null;
    inner.replaceChildren(header());
    inner.style.width = `${210 + width}px`;
    loadMore().then(() => {
      if (keepScroll) scroller.scrollTop = st;
      else scroller.scrollLeft = Math.max(0, x(nowS()) - 120);
    });
  }

  scroller.addEventListener('scroll', () => {
    if (scroller.scrollTop + scroller.clientHeight > scroller.scrollHeight - 400) loadMore();
  });
  reset();
  const tick = setInterval(placeNow, 30000);
  return () => clearInterval(tick);
}

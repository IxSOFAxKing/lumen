import { api } from '../api.js';
import { h, btn, empty, fmtDateTime, fmtTime, fmtBytes, toast, toastErr, confirmDialog, modal } from '../ui.js';
import { store, activeProvider } from '../app.js';
import { playRecording } from '../playback.js';

const STATUS = { scheduled: 'warn', recording: 'rec', completed: 'ok', failed: 'err', cancelled: '' };

export default async function recordings(el) {
  el.className = 'page';
  const body = h('div');
  el.append(h('div', { class: 'page-head' }, h('h1', { class: 'grow' }, 'Recordings'), btn('New recording', () => newRec(), { cls: 'primary', iconName: 'plus' })), body);

  async function render() {
    const [list, reminders] = await Promise.all([api.get('/api/iptv/recordings'), api.get('/api/iptv/reminders')]);
    const upcoming = list.filter(r => r.status === 'scheduled' || r.status === 'recording').sort((a, b) => a.start - b.start);
    const done = list.filter(r => !(r.status === 'scheduled' || r.status === 'recording'));
    const row = r => h('tr', {},
      h('td', {}, h('div', { style: { fontWeight: 600 } }, r.title), h('div', { class: 'dim small' }, r.channel_name)),
      h('td', {}, fmtDateTime(r.start), ' – ', fmtTime(r.stop)),
      h('td', {}, h('span', { class: `badge ${STATUS[r.status] || ''}` }, r.status.toUpperCase()), r.error ? h('div', { class: 'small', style: { color: 'var(--err)' } }, r.error) : null),
      h('td', {}, r.size ? fmtBytes(r.size) : ''),
      h('td', { style: { textAlign: 'right', whiteSpace: 'nowrap' } },
        (r.status === 'completed' || r.status === 'recording' || (r.status === 'cancelled' && r.size)) ? btn('Play', () => playRecording(r), { cls: 'sm primary', iconName: 'play' }) : null, ' ',
        (r.status === 'scheduled' || r.status === 'recording') ? btn(r.status === 'recording' ? 'Stop' : 'Cancel', async () => { await api.post(`/api/iptv/recordings/${r.id}/cancel`); render(); }, { cls: 'sm' }) : null, ' ',
        btn('', async () => { if (await confirmDialog('Delete recording?', `"${r.title}" and its file will be deleted.`, { okLabel: 'Delete', danger: true })) { await api.del(`/api/iptv/recordings/${r.id}`); render(); } }, { cls: 'sm icon danger', iconName: 'trash', title: 'Delete' })));
    const table = rows => h('div', { class: 'card-box', style: { padding: 0, overflowX: 'auto' } }, h('table', { class: 'tbl' },
      h('thead', {}, h('tr', {}, ['Programme', 'Time', 'Status', 'Size', ''].map(t => h('th', {}, t)))), h('tbody', {}, rows.map(row))));
    body.replaceChildren(
      h('h2', { class: 'sec' }, 'Scheduled & in progress'), upcoming.length ? table(upcoming) : h('div', { class: 'dim' }, 'Nothing scheduled. Pick a programme in the TV Guide and choose "Record".'),
      h('h2', { class: 'sec' }, 'Library'), done.length ? table(done) : h('div', { class: 'dim' }, 'No recordings yet.'),
      h('h2', { class: 'sec' }, 'Reminders'), reminders.length ? h('div', { class: 'card-box', style: { padding: 0 } }, h('table', { class: 'tbl' }, h('tbody', {}, reminders.map(r => h('tr', {},
        h('td', {}, h('b', {}, r.title), h('div', { class: 'dim small' }, r.channel_name)), h('td', {}, fmtDateTime(r.start)),
        h('td', { style: { textAlign: 'right' } }, btn('', async () => { await api.del(`/api/iptv/reminders/${r.id}`); render(); }, { cls: 'sm icon danger', iconName: 'trash' }))))))) : h('div', { class: 'dim' }, 'No reminders set.'));
  }

  async function newRec() {
    const prov = activeProvider();
    if (!prov) return;
    const chans = await api.get(`/api/iptv/providers/${prov.id}/channels?category=__all&epg=0`);
    const chSel = h('select', { class: 'input' }, chans.map(c => h('option', { value: c.id }, `${c.num ?? ''} ${c.name}`)));
    const toLocal = d => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    const start = h('input', { class: 'input', type: 'datetime-local', value: toLocal(new Date()) });
    const end = h('input', { class: 'input', type: 'datetime-local', value: toLocal(new Date(Date.now() + 3600e3)) });
    const title = h('input', { class: 'input', placeholder: 'Title (optional)' });
    modal({ title: 'Manual recording', body: h('div', {}, h('label', { class: 'field' }, 'Channel', chSel), h('label', { class: 'field' }, 'Start', start), h('label', { class: 'field' }, 'End', end), h('label', { class: 'field' }, 'Title', title)),
      actions: [{ label: 'Cancel' }, { label: 'Schedule', primary: true, onClick: async () => {
        await api.post('/api/iptv/recordings', { providerId: prov.id, channelId: chSel.value, start: new Date(start.value).getTime() / 1000, stop: new Date(end.value).getTime() / 1000, title: title.value || chSel.selectedOptions[0].textContent.trim() });
        toast('Recording scheduled', { type: 'ok' }); render();
      } }] });
  }

  await render();
  const t = setInterval(() => render().catch(() => {}), 20000);
  return () => clearInterval(t);
}

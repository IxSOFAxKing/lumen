import { api } from '../api.js';
import { h, btn, toast, toastErr, modal, confirmDialog, fmtDateTime, spinner, select, empty, sortableList, debounce } from '../ui.js';
import { icon } from '../icons.js';
import { store, refreshStore, navEntries, rerenderNav } from '../app.js';
import { homeSections, ACCENTS, applyAppearance, saveUi, deviceMode, setDeviceMode, withDeviceOverrides, startClock } from '../appearance.js';
import { tvModeSetting, setTvModeSetting, tvDetected } from '../tv.js';

export default async function settings(el, route) {
  el.className = 'page settings';
  const tab = route.query.tab || 'plex';
  const tabs = [['plex', 'Plex'], ['iptv', 'IPTV Providers'], ['discover', 'Discover'], ['appearance', 'Appearance'], ['playback', 'Playback'], ['devices', 'Devices'], ['transcode', 'Transcoding'], ['system', 'System']];
  el.append(h('div', { class: 'page-head' }, h('h1', {}, 'Settings')),
    h('div', { class: 'tabs' }, tabs.map(([k, l]) => h('a', { class: `tab ${tab === k ? 'active' : ''}`, href: `#/settings?tab=${k}` }, l))));
  const body = h('div');
  el.append(body);
  const fn = { plex: plexTab, iptv: iptvTab, discover: discoverTab, appearance: appearanceTab, transcode: transcodeTab, playback: playbackTab, devices: devicesTab, system: systemTab }[tab] || plexTab;
  return fn(body, route);
}

// ---------------- Plex ----------------
async function plexTab(el) {
  el.append(spinner());
  const st = await api.get('/api/plex/status');
  el.replaceChildren();
  const box = h('div', { class: 'card-box' });
  el.append(box);
  if (st.configured) {
    box.append(h('div', { class: 'row wrap' },
      h('div', { class: 'grow' }, h('h3', { style: { margin: '0 0 4px' } }, st.name || st.serverName || 'Plex server'),
        h('div', { class: 'muted small' }, st.url), st.version ? h('div', { class: 'dim small' }, `Version ${st.version}`) : null),
      h('span', { class: `badge ${st.online ? 'ok' : 'err'}` }, st.online ? 'CONNECTED' : 'OFFLINE'),
      btn('Change server', () => connectFlow(el), {}),
      btn('Disconnect', async () => { if (await confirmDialog('Disconnect Plex?', 'Lumen will forget this Plex server and token.', { danger: true, okLabel: 'Disconnect' })) { await api.post('/api/plex/disconnect'); await refreshStore(); location.reload(); } }, { cls: 'danger' })));
    if (!st.online) box.append(h('p', { style: { color: 'var(--err)' } }, st.error));
    if (st.online) el.append(homeUsersCard());
    box.append(h('p', { class: 'dim small', style: { marginBottom: 0 } }, 'Tip: Plex hardware transcoding requires Plex Pass and is configured on your Plex server. Alternatively choose "Lumen built-in" transcoder under Transcoding to use Lumen\'s own GPU pipeline.'));
  } else {
    connectFlow(el);
  }
}

/** Plex Home users: who this browser watches as, and the default for every app. */
function homeUsersCard() {
  const box = h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Plex Home users'), spinner());
  const pinDialog = (u) => new Promise(resolve => {
    const input = h('input', { class: 'input', type: 'password', inputmode: 'numeric', maxlength: 4, placeholder: 'PIN', style: { width: '120px', letterSpacing: '6px', fontSize: '20px' } });
    let ok = false;
    modal({ title: `PIN for ${u.title}`, body: input, onClose: () => resolve(ok ? input.value : null),
      actions: [{ label: 'Cancel' }, { label: 'Switch', primary: true, onClick: () => { ok = true; } }] });
    setTimeout(() => input.focus(), 50);
    input.addEventListener('keydown', e => { if (e.key === 'Enter') { ok = true; document.querySelector('.modal-bg:last-of-type .mf .btn.primary')?.click(); } });
  });
  const draw = async () => {
    let d;
    try { d = await api.get('/api/plex/users'); }
    catch (e) { box.replaceChildren(h('h3', { style: { marginTop: 0 } }, 'Plex Home users'), h('p', { class: 'muted' }, `Not available: ${e.message}. If you use Plex Home, sign in to Plex again above (Change server → Sign in with Plex).`)); return; }
    const sw = async (u, makeDefault) => {
      let pin;
      try { await api.post('/api/plex/users/switch', { uuid: u.uuid, makeDefault }); }
      catch (e) {
        if (e.status !== 403) { toastErr(e); return; }
        pin = await pinDialog(u); if (pin === null) return; // only when Plex wouldn't give Lumen the user's token
      }
      try { if (pin) await api.post('/api/plex/users/switch', { uuid: u.uuid, pin, makeDefault }); toast(makeDefault ? `${u.title} is now the default user` : `Watching as ${u.title}`, { type: 'ok' }); api.clearCache(); draw(); }
      catch (e) { toastErr(e); }
    };
    box.replaceChildren(h('h3', { style: { marginTop: 0 } }, 'Plex Home users'),
      h('p', { class: 'muted small' }, 'Pick who you watch as in this browser — Continue Watching, watched status and restrictions follow the Plex user. The default user applies to every app and TV that hasn\'t picked one.'),
      ...d.users.map(u => h('div', { class: 'prov' },
        u.thumb ? h('img', { src: u.thumb, alt: '', style: { width: '40px', height: '40px', borderRadius: '50%' } }) : h('div', { class: 'logo', style: { width: '40px', height: '40px', borderRadius: '50%' } }),
        h('div', { class: 'grow' }, h('b', {}, u.title), h('div', { class: 'dim small' }, [u.admin ? 'Admin' : null, u.restricted ? 'Managed' : null, u.protected ? 'PIN' : null].filter(Boolean).join(' · ') || 'Home user')),
        u.uuid === d.current ? h('span', { class: 'badge ok' }, 'WATCHING') : btn('Switch', () => sw(u, false), { cls: 'sm' }),
        u.uuid === d.default && d.defaultSet ? h('span', { class: 'badge' }, 'DEFAULT') : btn('Make default', () => sw(u, true), { cls: 'sm' }))),
      h('p', { class: 'dim small', style: { marginBottom: 0 } }, d.defaultSet
        ? 'TV apps open as the default user.'
        : 'No default user: TV apps ask "Who\'s watching?" when they open.',
        d.defaultSet ? btn('No default — ask on TVs', async () => { await api.post('/api/plex/users/default', { uuid: null }); draw(); }, { cls: 'sm ghost' }) : null));
  };
  draw();
  return box;
}

function connectFlow(el) {
  el.replaceChildren();
  const status = h('div', { class: 'muted', style: { margin: '10px 0' } });
  const servers = h('div');
  const signin = h('div', { class: 'card-box', style: { marginBottom: '18px' } },
    h('h3', { style: { marginTop: 0 } }, 'Sign in with Plex'),
    h('p', { class: 'muted' }, 'Sign in with your Plex account and Lumen will discover your servers automatically.'),
    btn('Sign in with Plex', async () => {
      const win = window.open('about:blank', 'plexauth', 'width=600,height=750');
      try {
        const pin = await api.post('/api/plex/pin');
        if (win) win.location = pin.authUrl; else window.open(pin.authUrl, '_blank');
        status.textContent = `Waiting for authorization… (code ${pin.code})`;
        const started = Date.now();
        const poll = async () => {
          if (Date.now() - started > 5 * 60e3) { status.textContent = 'Timed out. Try again.'; return; }
          const r = await api.get(`/api/plex/pin/${pin.id}`).catch(() => ({ authorized: false }));
          if (!r.authorized) return setTimeout(poll, 2000);
          try { win?.close(); } catch { /* */ }
          status.textContent = r.servers.length ? 'Choose a server:' : 'No servers found on this account.';
          servers.replaceChildren(...r.servers.map(s => h('div', { class: 'prov' },
            h('div', { class: 'grow' }, h('b', {}, s.name), h('div', { class: 'dim small' }, `${s.owned ? 'Owned' : 'Shared'} · ${s.connections.length} connection(s)`)),
            btn('Connect', async (ev) => {
              ev.currentTarget.disabled = true;
              try { await api.post('/api/plex/connect', { server: s, token: r.token }); toast(`Connected to ${s.name}`, { type: 'ok' }); await refreshStore(); location.hash = '#/home'; }
              catch (e) { toastErr(e); ev.target.disabled = false; }
            }, { cls: 'primary sm' }))));
        };
        poll();
      } catch (e) { win?.close(); toastErr(e); }
    }, { cls: 'primary', iconName: 'plex' }), status, servers);

  const url = h('input', { class: 'input', placeholder: 'http://192.168.1.10:32400' });
  const token = h('input', { class: 'input', placeholder: 'X-Plex-Token', type: 'password' });
  const manual = h('div', { class: 'card-box' }, h('h3', { style: { marginTop: 0 } }, 'Or connect manually'),
    h('div', { class: 'set-grid' }, h('label', { class: 'field' }, 'Server URL', url), h('label', { class: 'field' }, 'Plex token', token)),
    h('p', { class: 'dim small' }, 'Find your token: support.plex.tv → "Finding an authentication token / X-Plex-Token".'),
    btn('Connect', async () => {
      try { const r = await api.post('/api/plex/connect', { url: url.value.trim(), token: token.value.trim() }); toast(`Connected to ${r.name}`, { type: 'ok' }); await refreshStore(); location.hash = '#/home'; }
      catch (e) { toastErr(e); }
    }, { cls: 'primary' }));
  el.append(signin, manual);
}

// ---------------- IPTV ----------------
async function iptvTab(el) {
  const list = h('div');
  const extras = h('div', { style: { marginTop: '24px' } });
  const iptvConf = (await api.get('/api/settings')).iptv;
  const vodSwitch = h('label', { class: 'check', style: { margin: 0 } },
    h('input', { type: 'checkbox', checked: iptvConf.vodEnabled !== false, onChange: async e => {
      const on = e.target.checked;
      if (!on && !(await confirmDialog('Hide IPTV Movies & Series?', 'Movies and series from all IPTV providers will be removed from Lumen and no longer downloaded. Live TV is not affected. You can turn this back on any time.', { okLabel: 'Hide them' }))) { e.target.checked = true; return; }
      await api.put('/api/settings/iptv', { vodEnabled: on });
      toast(on ? 'IPTV Movies & Series enabled — syncing providers…' : 'IPTV Movies & Series hidden', { type: 'ok' });
      render();
    } }), h('span', {}, h('b', {}, 'Show IPTV Movies & Series (VOD)'), h('div', { class: 'dim small' }, 'Turn off to use IPTV for live TV only. Hides them everywhere and stops downloading them.')));
  el.append(h('div', { class: 'card-box', style: { marginBottom: '16px', padding: '14px 18px' } }, vodSwitch));
  el.append(h('div', { class: 'row', style: { marginBottom: '14px' } }, h('div', { class: 'grow muted' }, 'Add Xtream Codes logins or M3U playlists. Channels, VOD, series and EPG are synced automatically.'),
    btn('Add provider', () => providerForm(null, render), { cls: 'primary', iconName: 'plus' })), list);
  async function render() {
    const provs = await api.get('/api/iptv/providers');
    await refreshStore();
    if (!provs.length) { list.replaceChildren(empty('No providers yet', 'Add your Xtream Codes login to get Live TV, a TV guide, movies and series.')); return; }
    list.replaceChildren(...provs.map(p => {
      const a = p.account;
      const exp = a?.exp_date ? new Date(a.exp_date * 1000) : null;
      return h('div', { class: 'prov' },
        h('div', { class: 'grow' },
          h('div', { class: 'row' }, h('b', { style: { fontSize: '16px' } }, p.name), h('span', { class: 'badge' }, p.type.toUpperCase()), !p.enabled ? h('span', { class: 'badge warn' }, 'DISABLED') : null,
            a ? h('span', { class: `badge ${a.status === 'Active' ? 'ok' : 'err'}` }, (a.status || '').toUpperCase()) : null),
          h('div', { class: 'dim small', style: { marginTop: '4px' } }, [`${p.counts.live.toLocaleString()} channels`,
            p.type !== 'xtream' || window.lumenSettings?.iptv?.vodEnabled === false ? null : p.vod_enabled ? `${p.counts.movies.toLocaleString()} movies` : 'movies off',
            p.type !== 'xtream' || window.lumenSettings?.iptv?.vodEnabled === false ? null : p.series_enabled ? `${p.counts.series.toLocaleString()} series` : 'series off',
            `${p.counts.programmes.toLocaleString()} EPG entries${p.epgRunning ? ' (updating…)' : ''}`].filter(Boolean).join(' · ')),
          h('div', { class: 'dim small' }, [a ? `Connections ${a.active_cons ?? '?'}/${a.max_connections ?? '?'}` : null, exp ? `Expires ${exp.toLocaleDateString()}` : (a ? 'No expiry' : null), a?.is_trial ? 'Trial' : null,
            p.last_sync ? `Synced ${fmtDateTime(p.last_sync)}` : 'Never synced', p.last_epg_sync ? `EPG ${fmtDateTime(p.last_epg_sync)}` : null].filter(Boolean).join(' · '))),
        btn('Sync', async (ev) => { const b = ev.currentTarget; b.disabled = true; try { const r = await api.post(`/api/iptv/providers/${p.id}/sync`); toast(`Synced: ${r.live} channels, ${r.vod} movies, ${r.series} series`, { type: 'ok' }); render(); } catch (e) { toastErr(e); } b.disabled = false; }, { cls: 'sm', iconName: 'refresh' }),
        btn('EPG', async (ev) => { const b = ev.currentTarget; b.disabled = true; toast('Downloading EPG… this can take a minute'); try { const r = await api.post(`/api/iptv/providers/${p.id}/epg`); toast(r.skipped ? r.skipped : `EPG loaded: ${r.programmes.toLocaleString()} programmes`, { type: 'ok' }); render(); } catch (e) { toastErr(e); } b.disabled = false; }, { cls: 'sm', iconName: 'guide' }),
        btn('Groups', () => groupsModal(p), { cls: 'sm', iconName: 'eye' }),
        btn('', () => providerForm(p, render), { cls: 'sm icon', iconName: 'edit', title: 'Edit' }),
        btn('', async () => { if (await confirmDialog('Remove provider?', `"${p.name}" and its channels, favorites and EPG will be removed.`, { danger: true, okLabel: 'Remove' })) { await api.del(`/api/iptv/providers/${p.id}`); render(); } }, { cls: 'sm icon danger', iconName: 'trash', title: 'Remove' }));
    }));
  }
  await render();
  el.append(extras);
  extras.append(await favListsCard(), await parentalCard());
}

function providerForm(p, done) {
  const type = h('select', { class: 'input' }, h('option', { value: 'xtream' }, 'Xtream Codes API'), h('option', { value: 'm3u' }, 'M3U playlist URL'));
  type.value = p?.type || 'xtream';
  if (p) type.disabled = true;
  const f = (ph, val, t = 'text') => h('input', { class: 'input', placeholder: ph, value: val || '', type: t });
  const name = f('My IPTV', p?.name), url = f('http://provider.example:8080', p?.url), user = f('username', p?.username), pass = f(p?.hasPassword ? '•••••••• (unchanged)' : 'password', '', 'password');
  const epg = f('Optional: custom XMLTV URL (.xml or .xml.gz)', p?.epg_url), ua = f('Optional: custom User-Agent', p?.user_agent);
  const output = h('select', { class: 'input' }, h('option', { value: 'ts' }, 'MPEG-TS (.ts) — recommended'), h('option', { value: 'm3u8' }, 'HLS (.m3u8)'));
  output.value = p?.output || 'ts';
  const enabled = h('input', { type: 'checkbox', checked: p ? p.enabled : true });
  const vodOn = h('input', { type: 'checkbox', checked: p ? p.vod_setting : true });
  const seriesOn = h('input', { type: 'checkbox', checked: p ? p.series_setting : true });
  const vodGlobal = window.lumenSettings?.iptv?.vodEnabled !== false;
  const xtOnly = h('div', {}, h('div', { class: 'set-grid' }, h('label', { class: 'field' }, 'Username', user), h('label', { class: 'field' }, 'Password', pass)), h('label', { class: 'field' }, 'Live stream format', output),
    !vodGlobal ? null : h('div', { class: 'field' }, 'Content to load',
      h('label', { class: 'check', style: { margin: '4px 0 0', color: 'var(--text)' } }, vodOn, 'Movies (VOD)'),
      h('label', { class: 'check', style: { margin: 0, color: 'var(--text)' } }, seriesOn, 'TV series'),
      h('span', { class: 'dim small' }, 'Turn these off to use this provider for live TV only. Syncing is faster too.')));
  const urlLabel = h('span', {}, 'Server URL');
  const sync = () => { xtOnly.classList.toggle('hidden', type.value !== 'xtream'); urlLabel.textContent = type.value === 'xtream' ? 'Server URL (e.g. http://host:port)' : 'M3U playlist URL'; };
  type.addEventListener('change', sync); sync();
  modal({
    title: p ? `Edit ${p.name}` : 'Add IPTV provider',
    body: h('div', {}, h('label', { class: 'field' }, 'Type', type), h('label', { class: 'field' }, 'Name', name), h('label', { class: 'field' }, urlLabel, url), xtOnly,
      h('label', { class: 'field' }, 'EPG URL override', epg), h('label', { class: 'field' }, 'User-Agent', ua),
      p ? h('label', { class: 'check' }, enabled, 'Enabled') : null,
      h('p', { class: 'dim small' }, 'Lumen will log in, then download channels, VOD, series and the TV guide.')),
    actions: [{ label: 'Cancel' }, { label: p ? 'Save' : 'Add & sync', primary: true, onClick: async () => {
      const body = { type: type.value, name: name.value.trim(), url: url.value.trim(), username: user.value.trim(), password: pass.value, epg_url: epg.value.trim(), user_agent: ua.value.trim(), output: output.value, enabled: enabled.checked, vod_enabled: vodOn.checked, series_enabled: seriesOn.checked };
      if (p) await api.put(`/api/iptv/providers/${p.id}`, body);
      else { toast('Connecting and syncing… this may take a moment'); await api.post('/api/iptv/providers', body); toast('Provider added', { type: 'ok' }); }
      done?.();
    } }],
  });
}

async function groupsModal(p) {
  const kinds = [['live', 'Live TV'], ['vod', 'Movies'], ['series', 'Series']];
  let kind = 'live';
  const list = h('div', { style: { maxHeight: '58vh', overflow: 'auto' } });
  const tabsEl = h('div', { class: 'tabs' });
  const filter = h('input', { class: 'input', placeholder: 'Filter… (clear to reorder)', style: { width: '100%' } });
  let cats = [];
  const put = (c, body) => api.put(`/api/iptv/categories/${p.id}/${kind}/${encodeURIComponent(c.id)}`, body);
  const actions = (x, rerender) => {
    const c = cats.find(cc => cc.id === x.id);
    return h('div', { class: 'row', style: { gap: '2px' } },
      h('button', { class: 'btn ghost sm icon', title: 'Rename', html: icon('edit'), onClick: async () => {
        const n = window.prompt(`Rename "${c.original_name}" (empty = original name)`, c.name === c.original_name ? '' : c.name);
        if (n === null) return; await put(c, { name: n }); c.name = n.trim() || c.original_name; x.label = `${c.name}  ·  ${c.count}`; rerender(); } }),
      h('button', { class: `btn ghost sm icon ${c.locked ? 'fav-star' : ''}`, title: c.locked ? 'Locked with PIN (click to unlock)' : 'Lock with PIN', html: c.locked ? '🔒' : '🔓', onClick: async () => {
        try { await put(c, { locked: !c.locked }); c.locked = c.locked ? 0 : 1; rerender(); } catch (e) { toastErr(e); } } }));
  };
  const draw = () => {
    const q = filter.value.toLowerCase();
    if (q) {
      list.replaceChildren(...cats.filter(c => c.name.toLowerCase().includes(q)).map(c => h('label', { class: 'check', style: { padding: '4px 0' } },
        h('input', { type: 'checkbox', checked: !c.hidden, onChange: async e => { c.hidden = !e.target.checked; await put(c, { hidden: c.hidden }); } }),
        h('span', { class: 'grow' }, c.name), h('span', { class: 'dim small' }, c.count))));
      return;
    }
    list.replaceChildren(sortableList(cats.map(c => ({ id: c.id, label: `${c.name}  ·  ${c.count}`, hidden: !!c.hidden, note: c.name !== c.original_name ? `Provider name: ${c.original_name}` : null })), async items => {
      const changed = items.filter(x => !!cats.find(c => c.id === x.id).hidden !== !!x.hidden);
      for (const x of changed) { const c = cats.find(cc => cc.id === x.id); c.hidden = x.hidden; await put(c, { hidden: x.hidden }); }
      await api.put(`/api/iptv/categories-order/${p.id}/${kind}`, { order: items.map(x => x.id) });
      cats = items.map(x => cats.find(c => c.id === x.id));
    }, { actions }));
  };
  const load = async () => {
    tabsEl.replaceChildren(...kinds.map(([k, l]) => h('div', { tabindex: 0, class: `tab ${k === kind ? 'active' : ''}`, onClick: () => { kind = k; load(); } }, l)));
    cats = await api.get(`/api/iptv/providers/${p.id}/categories?kind=${kind}&all=1`);
    draw();
  };
  filter.addEventListener('input', draw);
  const bulk = (hidden) => async () => { for (const c of cats) if (!!c.hidden !== hidden) { c.hidden = hidden; await put(c, { hidden }); } draw(); };
  modal({ title: `Groups · ${p.name}`, size: 'lg', body: h('div', {},
    h('p', { class: 'muted small', style: { marginTop: 0 } }, 'Drag to reorder, rename (✎), lock with your PIN (🔓), or hide (eye) groups. Changes apply to Live TV, the guide and VOD.'), tabsEl,
    h('div', { class: 'row', style: { marginBottom: '8px' } }, filter, btn('Show all', bulk(false), { cls: 'sm' }), btn('Hide all', bulk(true), { cls: 'sm' })), list) });
  load();
}

// ---------------- Transcoding ----------------
async function transcodeTab(el) {
  el.append(spinner());
  const [all, hw] = await Promise.all([api.get('/api/settings'), api.get('/api/system/hardware').catch(() => null)]);
  const t = all.transcode;
  el.replaceChildren();
  const save = async (patch) => { try { Object.assign(t, await api.put('/api/settings/transcode', patch)); toast('Saved', { type: 'ok', timeout: 1200 }); } catch (e) { toastErr(e); } };
  const yes = v => v ? h('span', { class: 'badge ok' }, 'YES') : h('span', { class: 'badge' }, 'NO');
  if (hw) el.append(h('div', { class: 'card-box', style: { marginBottom: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Detected hardware'),
    h('dl', { class: 'kv' }, h('dt', {}, 'FFmpeg'), h('dd', {}, hw.ffmpeg),
      h('dt', {}, 'Render devices'), h('dd', {}, hw.devices.dri.length ? hw.devices.dri.map(d => '/dev/dri/' + d).join(', ') : 'none (map /dev/dri for Intel/AMD)'),
      h('dt', {}, 'NVIDIA device'), h('dd', {}, yes(hw.devices.nvidia)),
      h('dt', {}, 'VAAPI encoder'), h('dd', {}, yes(hw.encoders.h264_vaapi)), h('dt', {}, 'Intel QSV encoder'), h('dd', {}, yes(hw.encoders.h264_qsv)),
      h('dt', {}, 'NVENC encoder'), h('dd', {}, yes(hw.encoders.h264_nvenc)), h('dt', {}, 'Hardware accels'), h('dd', {}, hw.hwaccels.join(', ') || 'none'))));
  const field = (label, control, hint) => h('label', { class: 'field' }, label, control, hint ? h('span', { class: 'dim small' }, hint) : null);
  const num = (key, min, max, step = 1) => h('input', { class: 'input', type: 'number', min, max, step, value: t[key], onChange: e => save({ [key]: Number(e.target.value) }) });
  el.append(h('div', { class: 'card-box' }, h('h3', { style: { marginTop: 0 } }, 'Lumen transcoder'),
    h('div', { class: 'set-grid' },
      field('Hardware acceleration', select([
        { value: 'none', label: 'None (software x264)' }, { value: 'vaapi', label: 'VAAPI (Intel / AMD)' }, { value: 'qsv', label: 'Intel Quick Sync (QSV)' },
        { value: 'nvenc', label: 'NVIDIA NVENC' }, { value: 'videotoolbox', label: 'Apple VideoToolbox' }], t.hwAccel, v => save({ hwAccel: v })),
        'Falls back to software automatically if the GPU pipeline fails.'),
      field('VAAPI device', h('input', { class: 'input', value: t.vaapiDevice, onChange: e => save({ vaapiDevice: e.target.value }) })),
      field('Live TV playback', select([
        { value: 'auto', label: 'Auto (direct, fall back to remux → transcode)' }, { value: 'direct', label: 'Always direct' },
        { value: 'remux', label: 'Always remux (fixes audio / timestamps)' }, { value: 'transcode', label: 'Always transcode' }], t.liveMode, v => save({ liveMode: v }))),
      field('Max resolution', select([{ value: 2160, label: '4K (2160p)' }, { value: 1080, label: '1080p' }, { value: 720, label: '720p' }, { value: 480, label: '480p' }], t.maxHeight, v => save({ maxHeight: Number(v) }))),
      field('Video bitrate (kbps)', num('videoBitrate', 500, 80000, 500)),
      field('Audio bitrate (kbps)', num('audioBitrate', 64, 640, 32)),
      field('Software preset', select(['ultrafast', 'superfast', 'veryfast', 'faster', 'fast', 'medium'].map(v => ({ value: v, label: v })), t.preset, v => save({ preset: v }))),
      field('HLS segment length (s)', num('segmentSeconds', 1, 10))),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: t.deinterlace, onChange: e => save({ deinterlace: e.target.checked }) }), 'Deinterlace live TV when transcoding')),
  h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Plex playback'),
    h('div', { class: 'set-grid' },
      field('Transcoder for Plex content', select([{ value: 'plex', label: 'Plex Media Server (recommended)' }, { value: 'builtin', label: 'Lumen built-in (uses settings above)' }], t.plexTranscoder, v => save({ plexTranscoder: v })),
        'Plex server transcoding supports subtitles burn-in and uses Plex\'s own hardware acceleration (Plex Pass).'),
      h('div', { class: 'dim small' }, 'Default Plex quality (at home / away) is under Settings → Appearance → Player.'))));
}

// ---------------- Playback / UI ----------------
async function playbackTab(el) {
  const all = await api.get('/api/settings');
  const ui = withDeviceOverrides(all.ui), iptv = all.iptv;
  const saveUiPB = async patch => { await saveUi(patch, api.put); Object.assign(ui, patch); await refreshStore(); toast('Saved', { type: 'ok', timeout: 1200 }); };
  const saveIptv = async patch => { Object.assign(iptv, await api.put('/api/settings/iptv', patch)); toast('Saved', { type: 'ok', timeout: 1200 }); };
  const field = (label, control, hint) => h('label', { class: 'field' }, label, control, hint ? h('span', { class: 'dim small' }, hint) : null);
  const num = (obj, key, saver, min, max) => h('input', { class: 'input', type: 'number', min, max, value: obj[key], onChange: e => saver({ [key]: Number(e.target.value) }) });
  el.append(h('div', { class: 'card-box' }, h('h3', { style: { marginTop: 0 } }, 'Interface'),
    h('div', { class: 'set-grid' },
      field('Start page', select(navEntries().filter(e => e.id !== 'search').map(e => ({ value: e.href.slice(2), label: e.label })), ui.startPage, v => saveUiPB({ startPage: v })), 'What opens first when you launch Lumen.'),
      field('Clock', select([{ value: 'off', label: 'Hidden' }, { value: '12', label: '12-hour (3:45 PM)' }, { value: '24', label: '24-hour (15:45)' }], ui.clock || 'off', v => { saveUiPB({ clock: v }); setTimeout(startClock, 50); }), 'Shown in the top-right corner and in the player.'),
      field('Channel info banner (seconds)', num(ui, 'zapOverlaySeconds', saveUiPB, 1, 20)),
      field('Live TV buffering', select([
        { value: 'smooth', label: 'Smooth (recommended) — extra buffer, no frame skipping' },
        { value: 'balanced', label: 'Balanced' },
        { value: 'low', label: 'Low latency — closest to live, may stutter' }], ui.liveBuffer || 'smooth', v => saveUiPB({ liveBuffer: v })),
        'If channels still buffer, also try Settings → Transcoding → Live TV playback → "Always remux".')),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: ui.showPreview, onChange: e => saveUiPB({ showPreview: e.target.checked }) }), 'Show live preview when selecting a channel'),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!ui.resumeLastChannel, onChange: e => saveUiPB({ resumeLastChannel: e.target.checked }) }), 'Start playing the last watched channel when Lumen opens'),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: ui.zapWrap !== false, onChange: e => saveUiPB({ zapWrap: e.target.checked }) }), 'Channel up/down wraps around from the last channel to the first')),
  h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'IPTV'),
    h('div', { class: 'set-grid' },
      field('Playlist refresh (hours)', num(iptv, 'playlistRefreshHours', saveIptv, 1, 168)),
      field('EPG refresh (hours)', num(iptv, 'epgRefreshHours', saveIptv, 1, 168)),
      field('Keep past EPG (hours)', num(iptv, 'epgPastHours', saveIptv, 0, 336), 'Needed for catch-up browsing in the guide.'),
      field('Load future EPG (days)', num(iptv, 'epgFutureDays', saveIptv, 1, 14)),
      field('Catch-up time offset (minutes)', num(iptv, 'timeshiftOffsetMinutes', saveIptv, -720, 720), 'Adjust if catch-up starts at the wrong time (provider timezone).'),
      field('Start recordings early (minutes)', num(iptv, 'recordPadBefore', saveIptv, 0, 30), 'Covers programmes that start early.'),
      field('End recordings late (minutes)', num(iptv, 'recordPadAfter', saveIptv, 0, 120), 'Covers overruns (sports, live events).'),
      field('Default User-Agent', h('input', { class: 'input', value: iptv.userAgent, placeholder: 'Browser default', onChange: e => saveIptv({ userAgent: e.target.value }) }))),
  ), h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Keyboard shortcuts (player)'),
    h('dl', { class: 'kv' }, ...[['Space / K', 'Play / pause'], ['← / →', 'Seek −10s / +30s'], ['↑ / ↓', 'Channel up / down (live) or volume'], ['0–9', 'Type a channel number'],
      ['L', 'Channel list'], ['Enter', 'Show channel info banner'], ['C', 'Subtitles'], ['F', 'Fullscreen'], ['P', 'Picture-in-picture'], ['M', 'Mute'], ['R', 'Record (live TV)'], ['I', 'Stream info'], ['N', 'Next episode'], ['Esc', 'Close player']]
      .flatMap(([k, v]) => [h('dt', {}, h('span', { class: 'badge' }, k)), h('dd', {}, v)]))));
}

function accountForm(auth) {
  const user = h('input', { class: 'input', value: auth.username || '', autocomplete: 'username' });
  const cur = h('input', { class: 'input', type: 'password', placeholder: 'Current password', autocomplete: 'current-password' });
  const pw = h('input', { class: 'input', type: 'password', placeholder: 'New password (leave empty to keep)', autocomplete: 'new-password' });
  return h('form', { style: { display: 'grid', gap: '8px', maxWidth: '360px', marginBottom: '14px' }, onSubmit: async e => {
    e.preventDefault();
    try { await api.put('/api/auth/account', { current: cur.value, username: user.value.trim(), password: pw.value || undefined }); toast('Login updated', { type: 'ok' }); cur.value = ''; pw.value = ''; }
    catch (ex) { toastErr(ex); }
  } }, h('b', {}, 'Change username or password'), user, cur, pw, h('button', { class: 'btn', type: 'submit', style: { justifySelf: 'start' } }, 'Save'));
}

/** Per browser: show "Who's watching?" when Lumen opens. */
function askProfileCheck() {
  const on = localStorage.getItem('lumen.askProfile') !== 'false';
  return h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: on, onChange: e => localStorage.setItem('lumen.askProfile', String(e.target.checked)) }),
    h('span', {}, 'Ask "Who\'s watching?" when Lumen opens (this browser)', h('div', { class: 'dim small' }, 'Pick the Plex Home user each time. Only shown when your Plex account has more than one user.')));
}

// ---------------- Discover (Overseerr) ----------------
async function discoverTab(el) {
  const field = (label, control) => h('label', { class: 'field' }, label, control);
  const cfg = await api.get('/api/discover/config').catch(() => ({}));
  const url = h('input', { class: 'input', value: cfg.url || '', placeholder: 'http://192.168.1.20:5055' });
  const key = h('input', { class: 'input', type: 'password', placeholder: cfg.hasKey ? 'Saved — leave empty to keep it' : 'API key (Overseerr → Settings → General)' });
  const status = h('div', { class: 'muted small', style: { minHeight: '20px' } }, cfg.configured ? 'Connected.' : 'Not connected.');
  el.append(h('div', { class: 'card-box', style: { maxWidth: '640px' } },
    h('h3', { style: { marginTop: 0 } }, 'Overseerr / Jellyseerr'),
    h('p', { class: 'muted' }, 'Browse trending titles and request movies and shows from Lumen. This is saved on your Lumen server, so the TV and phone apps use it too. Requests are made as the matching Overseerr user for the current Plex user when there is one.'),
    field('Address', url), field('API key', key), status,
    h('div', { class: 'row wrap', style: { gap: '8px' } },
      btn('Save & test', async (ev) => {
        const b = ev.currentTarget; b.disabled = true; status.textContent = 'Testing…';
        try { const r = await api.put('/api/discover/config', { url: url.value.trim(), apiKey: key.value.trim() || undefined }); status.textContent = r.configured ? `Connected to Overseerr ${r.version || ''}${r.user ? ` as ${r.user}` : ''}.` : 'Disconnected.'; key.value = ''; await refreshStore(); }
        catch (e) { status.textContent = e.message; }
        b.disabled = false;
      }, { cls: 'primary' }),
      cfg.configured ? btn('Disconnect', async () => { await api.put('/api/discover/config', { url: '' }); status.textContent = 'Disconnected.'; url.value = ''; await refreshStore(); }, { cls: 'danger' }) : null)));
}

// ---------------- System ----------------
async function systemTab(el) {
  const auth = await api.get('/api/auth/status');
  const sess = h('div');
  el.append(h('div', { class: 'card-box' }, h('h3', { style: { marginTop: 0 } }, 'Active transcoding sessions'), sess),

    h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Security'),
      h('p', { class: 'muted' }, `Signed in as ${auth.username || 'admin'}.`, auth.envManaged ? ' The login is set with LUMEN_USERNAME / LUMEN_PASSWORD in docker-compose.yml.' : ''),
      auth.envManaged ? null : accountForm(auth),
      btn('Sign out', async () => { await api.post('/api/auth/logout'); location.reload(); }, { iconName: 'logout' })));
  // Backup / restore
  const withProv = h('input', { type: 'checkbox' });
  const file = h('input', { type: 'file', accept: '.json,application/json', style: { display: 'none' }, onChange: async e => {
    const f = e.target.files[0]; if (!f) return;
    try {
      const data = JSON.parse(await f.text());
      if (!(await confirmDialog('Import settings?', `This applies appearance, player, IPTV and transcoding settings, favourite lists, channel names/numbers/order and group settings from "${f.name}".`, { okLabel: 'Import' }))) return;
      const r = await api.post('/api/settings/import', data);
      toast(`Imported: ${r.settings} setting groups, ${r.favorites} favourites, ${r.channelPrefs} channel customisations${r.providersCreated ? `, ${r.providersCreated} providers` : ''}`, { type: 'ok', timeout: 8000 });
      await refreshStore();
    } catch (err) { toastErr(err); } finally { file.value = ''; }
  } });
  el.append(h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Backup & restore'),
    h('p', { class: 'muted small', style: { marginTop: 0 } }, 'Export your settings and customisations (appearance, player, favourites lists, channel names/numbers/order, groups) to a file, and import them on this or another Lumen install.'),
    h('label', { class: 'check' }, withProv, h('span', {}, 'Include IPTV provider logins', h('div', { class: 'dim small' }, 'The file will then contain your provider passwords — keep it private.'))),
    h('div', { class: 'row wrap' },
      btn('Export settings', () => { const a = h('a', { href: `/api/settings/export${withProv.checked ? '?providers=1' : ''}`, download: '' }); document.body.append(a); a.click(); a.remove(); }, { cls: 'primary', iconName: 'down' }),
      btn('Import settings…', () => file.click(), { iconName: 'up' }), file)));
  const load = async () => {
    const list = await api.get('/api/system/sessions');
    sess.replaceChildren(list.length ? h('table', { class: 'tbl' }, h('thead', {}, h('tr', {}, ['Content', 'Mode', 'Accel', 'Idle', ''].map(x => h('th', {}, x)))),
      h('tbody', {}, list.map(s => h('tr', {}, h('td', {}, s.label || s.id), h('td', {}, s.mode), h('td', {}, s.hwAccel), h('td', {}, `${s.idle}s`),
        h('td', {}, btn('Stop', async () => { await api.del(`/api/system/sessions/${s.id}`); load(); }, { cls: 'sm danger' })))))) : h('div', { class: 'dim' }, 'No active sessions.'));
  };
  await load();
  const t = setInterval(load, 5000);
  return () => clearInterval(t);
}

// ---------------- Appearance ----------------
async function appearanceTab(el) {
  const all = await api.get('/api/settings');
  all.ui = withDeviceOverrides(all.ui);
  window.lumenSettings = all;
  let u = all.ui;
  el.append(h('div', { class: 'card-box', style: { marginBottom: '18px', padding: '14px 18px' } },
    h('label', { class: 'check', style: { margin: 0 } }, h('input', { type: 'checkbox', checked: deviceMode(), onChange: async e => {
      setDeviceMode(e.target.checked);
      if (!e.target.checked) { const fresh = await api.get('/api/settings'); window.lumenSettings = fresh; applyAppearance(fresh.ui); rerenderNav(); }
      toast(e.target.checked ? 'This device now keeps its own appearance & player settings' : 'This device uses the shared settings again', { type: 'ok' });
      el.replaceChildren(); appearanceTab(el);
    } }), h('span', {}, h('b', {}, 'Separate settings for this device'),
      h('div', { class: 'dim small' }, deviceMode() ? 'On: changes below only affect this browser/TV. Pinned rows stay shared.' : 'Off: changes below apply to every device that opens Lumen. Turn on to give e.g. your TV its own scale and sidebar.')))));
  // Apply instantly (optimistic); changes are batched and saved in the background.
  let pending = {};
  const persist = debounce(async () => {
    const patch = pending; pending = {};
    try { await saveUi(patch, api.put); } catch (e) { toastErr(e); }
  }, 300);
  const save = (patch, { nav = false } = {}) => {
    Object.assign(u, patch); Object.assign(pending, patch);
    window.lumenSettings.ui = u;
    applyAppearance(u);
    if (nav) rerenderNav();
    persist();
  };
  const card = (title, ...kids) => h('div', { class: 'card-box', style: { marginBottom: '18px' } }, h('h3', { style: { marginTop: 0 } }, title), ...kids);
  const field = (label, control, hint) => h('div', { class: 'field', style: { marginBottom: '16px' } }, h('div', { style: { marginBottom: '6px', color: 'var(--text-2)', fontSize: '13px' } }, label), control, hint ? h('div', { class: 'dim small', style: { marginTop: '4px' } }, hint) : null);
  const VOL_OFFSET = [[-6, '−6 dB'], [-3, '−3 dB'], [0, 'Off'], [3, '+3 dB'], [6, '+6 dB'], [9, '+9 dB'], [12, '+12 dB']];
  const chips = (opts, key, extra = {}) => {
    const box = h('div', { class: 'chips' });
    const draw = () => box.replaceChildren(...opts.map(([v, l]) => h('button', { class: `chip ${String(u[key]) === String(v) ? 'active' : ''}`, onClick: () => { save({ [key]: v }, extra); draw(); } }, l)));
    draw(); return box;
  };
  const check = (key, label, hint, extra = {}) => h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!u[key], onChange: e => save({ [key]: e.target.checked }, extra) }),
    h('span', {}, label, hint ? h('div', { class: 'dim small' }, hint) : null));
  const range = (key, min, max, step, fmt = v => v) => {
    const val = h('span', { class: 'val' }, fmt(u[key]));
    return h('div', { class: 'range-row' }, h('input', { type: 'range', min, max, step, value: u[key], onInput: e => { val.textContent = fmt(Number(e.target.value)); save({ [key]: Number(e.target.value) }); } }), val);
  };

  // --- TV mode (per device) ---
  const tvChips = h('div', { class: 'chips' });
  const drawTv = () => tvChips.replaceChildren(...[['auto', `Auto${tvDetected() ? ' (TV detected)' : ''}`], ['on', 'On'], ['off', 'Off']].map(([v, l]) =>
    h('button', { class: `chip ${tvModeSetting() === v ? 'active' : ''}`, onClick: () => { setTvModeSetting(v); drawTv(); } }, l)));
  drawTv();
  el.append(card('TV mode', field('Optimise for TV & remote control on this device', tvChips,
    'Bigger focus highlights, no hover-only buttons, remote-friendly navigation (arrows / OK / Back). Turns on automatically on TV browsers and the Android TV app.')));

  // --- Theme ---
  const swatches = h('div', { class: 'swatches' });
  const drawSwatches = () => swatches.replaceChildren(
    ...ACCENTS.map(c => h('button', { class: `swatch ${u.accent?.toLowerCase() === c ? 'on' : ''}`, title: c, style: { background: c }, onClick: () => { save({ accent: c }); drawSwatches(); } })),
    h('label', { class: 'chip', title: 'Custom colour' }, 'Custom', h('input', { type: 'color', value: u.accent || '#ff9f3d', style: { width: '26px', height: '20px', border: 0, padding: 0, background: 'none' }, onInput: e => { save({ accent: e.target.value }); } })));
  drawSwatches();
  el.append(card('Theme',
    field('Mode', chips([['dark', 'Dark'], ['oled', 'OLED Black'], ['light', 'Light'], ['system', 'Match system']], 'theme')),
    field('Accent colour', swatches)));

  // --- Size & layout ---
  el.append(card('Size & layout',
    field('Interface scale', range('uiScale', 75, 140, 5, v => `${v}%`), 'Makes everything bigger or smaller, handy on TVs and small laptops.'),
    field('Poster size', chips([['small', 'Small'], ['medium', 'Medium'], ['large', 'Large']], 'cardSize')),
    field('Sidebar style', chips([['rail', 'Compact (icons + labels)'], ['wide', 'Wide (labels beside icons)'], ['icons', 'Icons only']], 'navStyle', { nav: true })),
    field('Ratings shown', chips([['audience', 'Audience'], ['critic', 'Critics'], ['both', 'Both'], ['none', 'None']], 'ratingDisplay'), 'Used on detail pages, the featured banner and list views (Rotten Tomatoes scores when your Plex agent provides them).'),
    check('showCardTitles', 'Show titles under posters'),
    check('blurSpoilers', 'Hide spoilers', 'Blurs thumbnails and descriptions of episodes you haven\'t watched (hover to read).')));

  // --- Sidebar ---
  const navItems = () => {
    const hidden = new Set(u.navHidden || []);
    const entries = navEntries();
    const ordered = (u.navOrder || []).length ? [...entries].sort((a, b) => {
      const ia = u.navOrder.indexOf(a.id), ib = u.navOrder.indexOf(b.id);
      return (ia < 0 ? 999 + entries.indexOf(a) : ia) - (ib < 0 ? 999 + entries.indexOf(b) : ib);
    }) : entries;
    return ordered.map(e => ({ id: e.id, label: e.label, icon: e.icon, hidden: hidden.has(e.id), note: e.group === 'plex' ? 'Plex library' : e.group === 'iptv' ? 'IPTV' : null }));
  };
  const sidebarBox = h('div');
  const drawSidebar = () => sidebarBox.replaceChildren(sortableList(navItems(), list => {
    const visibleIds = new Set(list.map(x => x.id));
    const keptHidden = (u.navHidden || []).filter(id => !visibleIds.has(id)); // entries not currently available keep their state
    save({ navOrder: list.map(x => x.id), navHidden: [...keptHidden, ...list.filter(x => x.hidden).map(x => x.id)] }, { nav: true });
  }));
  drawSidebar();
  el.append(card('Sidebar', h('p', { class: 'muted small', style: { marginTop: 0 } }, 'Drag to reorder, or use the arrows. Click the eye to hide an item. Settings always stays at the bottom.'), sidebarBox));

  // --- Home screen ---
  const homeBox = h('div');
  const hubsBox = h('div');
  const drawHubs = () => {
    const hubs = u.hiddenHubs || [];
    hubsBox.replaceChildren(!hubs.length ? h('div', { class: 'dim small' }, 'Tip: hover any Plex row on the Home screen and use its ⋯ menu to hide just that row.')
      : h('div', {}, h('div', { class: 'small muted', style: { marginBottom: '6px' } }, 'Hidden Plex rows (click to show again):'),
        h('div', { class: 'chips' }, hubs.map(hb => h('button', { class: 'chip', onClick: () => { save({ hiddenHubs: (u.hiddenHubs || []).filter(x => x.id !== hb.id) }); drawHubs(); } }, '↺ ', hb.title)))));
  };
  homeBox.append(sortableList(homeSections().map(s => ({ id: s.id, label: s.label, hidden: s.hidden, note: s.note || (s.source === 'iptv' ? 'IPTV' : 'Plex') })),
    list => save({ homeOrder: list.map(x => x.id), homeHidden: [...((u.homeHidden || []).includes('hero') ? ['hero'] : []), ...list.filter(x => x.hidden).map(x => x.id)] })));
  drawHubs();
  el.append(card('Home screen', h('p', { class: 'muted small', style: { marginTop: 0 } }, 'Choose which rows appear on Home and in what order. Rows with nothing to show are skipped automatically.'),
    homeBox,
    h('div', { style: { marginTop: '14px' } },
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !(u.homeHidden || []).includes('hero'), onChange: e => {
        const rest = (u.homeHidden || []).filter(x => x !== 'hero'); save({ homeHidden: e.target.checked ? rest : [...rest, 'hero'] }); } }),
        h('span', {}, 'Show the featured banner at the top of Home', h('div', { class: 'dim small' }, 'Rotating spotlight of newly added movies and shows.'))),
      field('Featured banner shows', heroSourceField()),
      check('heroAutoplay', 'Rotate banner slides automatically'),
      field('Featured banner: seconds per slide', range('heroInterval', 4, 20, 1, v => `${v}s`))), hubsBox,
    h('p', { class: 'dim small' }, '📌 Pin more rows to Home from any Plex collection or playlist ("Pin to Home"), a library genre, or a Live TV group / favourites list (right-click it).')));

  function heroSourceField() {
    const wrap = h('div');
    const colSel = h('select', { class: 'input', style: { marginTop: '8px', minWidth: '260px' }, onChange: e => save({ heroCollection: e.target.value, heroCollectionTitle: e.target.selectedOptions[0]?.textContent || '' }) },
      h('option', { value: '' }, 'Loading collections…'));
    const draw = () => {
      wrap.replaceChildren(chips([['recent', 'Newly added'], ['continue', 'Continue watching'], ['random', 'Random picks'], ['collection', 'A collection']], 'heroSource'),
        u.heroSource === 'collection' ? colSel : '');
    };
    wrap.addEventListener('click', () => setTimeout(draw, 0));
    draw();
    Promise.all((store.libraries || []).filter(l => l.type === 'movie' || l.type === 'show').map(l => api.get(`/api/plex/library/${l.key}/collections`).then(cs => cs.map(c => ({ ...c, lib: l.title }))).catch(() => [])))
      .then(all => {
        const cols = all.flat();
        colSel.replaceChildren(h('option', { value: '' }, cols.length ? 'Choose a collection…' : 'No collections found'),
          ...cols.map(c => h('option', { value: c.ratingKey, selected: String(c.ratingKey) === String(u.heroCollection) }, `${c.title} (${c.lib})`)));
      });
    return wrap;
  }

  // --- Player ---
  const prev = h('div', { class: 'sub-preview' }, h('span', {}, 'This is how subtitles will look.'));
  const drawPrev = () => Object.assign(prev.firstChild.style, { fontSize: `${(u.subtitleSize || 100) * 0.2}px`, color: u.subtitleColor || '#fff', background: `rgba(0,0,0,${(u.subtitleBg ?? 60) / 100})` });
  drawPrev();
  const subColors = h('div', { class: 'swatches' });
  const drawSubColors = () => subColors.replaceChildren(...['#ffffff', '#ffeb3b', '#00e5ff', '#76ff03', '#ff80ab'].map(c => h('button', { class: `swatch ${u.subtitleColor === c ? 'on' : ''}`, style: { background: c }, onClick: () => { save({ subtitleColor: c }); drawSubColors(); drawPrev(); } })));
  drawSubColors();
  const subRange = (key, min, max, fmt) => { const r = range(key, min, max, 5, fmt); r.querySelector('input').addEventListener('input', drawPrev); return r; };
  el.append(card('Player',
    h('div', { class: 'set-grid' },
      field('Skip back', chips([[5, '5s'], [10, '10s'], [15, '15s'], [30, '30s']], 'seekBack')),
      field('Skip forward', chips([[10, '10s'], [15, '15s'], [30, '30s'], [60, '60s']], 'seekForward'))),
    check('autoplayNext', 'Autoplay next episode'),
    field('Autoplay countdown', range('autoplayCountdown', 3, 30, 1, v => `${v}s`)),
    h('div', { class: 'set-grid', style: { marginTop: '10px' } },
      field('Skip intro', chips([['off', 'Off'], ['button', 'Show button'], ['auto', 'Skip automatically']], 'skipIntroMode')),
      field('Skip credits', chips([['off', 'Off'], ['button', 'Show button'], ['auto', 'Skip automatically']], 'skipCreditsMode')),
      field('Auto skip delay', chips([[0, 'Immediately'], [3, '3s'], [5, '5s'], [10, '10s']], 'autoSkipDelay')),
      field('Rewind on resume', chips([[0, 'Off'], [5, '5s'], [10, '10s'], [15, '15s'], [30, '30s']], 'rewindOnResume')),
      field('Default sleep timer', chips([[0, 'Off'], [30, '30 min'], [60, '1 h'], [90, '90 min'], [120, '2 h']], 'defaultSleep')),
      field('Maximum volume', chips([[100, '100%'], [125, '125%'], [150, '150%'], [200, '200%']], 'maxVolume')),
      field('Live TV volume offset', chips(VOL_OFFSET, 'liveVolumeOffset')),
      field('Plex volume offset', chips(VOL_OFFSET, 'plexVolumeOffset'))),
    check('forceFallbackMarkers', 'Find intros & credits from chapter names', 'Uses chapter titles like "Intro" or "Credits" even when Plex has markers (always used when it has none).'),
    check('chapterMarks', 'Show chapter marks on the seek bar'),
    askProfileCheck(),
    h('div', { class: 'set-grid', style: { marginTop: '10px' } },
      field('When resuming', chips([['resume', 'Resume'], ['start', 'Start over'], ['ask', 'Ask me']], 'resumeMode')),
      field('Mark as watched at', chips([[90, '90%'], [95, '95%'], ['credits', 'When credits start']], 'watchedAt'))),
    h('h4', { style: { margin: '18px 0 10px' } }, 'Languages'),
    h('div', { class: 'set-grid' },
      field('Preferred audio language', langSelect('audioLang', 'Plex default')),
      field('Subtitle language', langSelect('subtitleLang', 'Same as audio'))),
    field('Show subtitles', chips([['plex', 'Plex default'], ['always', 'Always'], ['foreign', 'Only for foreign audio'], ['never', 'Never']], 'subtitleMode'),
      '"Only for foreign audio" turns subtitles on when the audio isn\'t in your subtitle language (and uses forced subtitles otherwise).'),
    h('h4', { style: { margin: '18px 0 10px' } }, 'Default quality (Plex)'),
    h('div', { class: 'set-grid' },
      field('At home (local network)', qualitySelect('qualityLocal')),
      field('Away from home', qualitySelect('qualityRemote'))),
    h('h4', { style: { margin: '18px 0 10px' } }, 'Controls'),
    field('Hide controls after', range('controlsHideSeconds', 2, 10, 1, v => `${v}s`)),
    field('Buttons shown in the player bar', buttonToggles()),
    h('h4', { style: { margin: '18px 0 10px' } }, 'Subtitles'),
    prev,
    h('div', { class: 'set-grid', style: { marginTop: '12px' } },
      field('Size', subRange('subtitleSize', 50, 200, v => `${v}%`)),
      field('Background', subRange('subtitleBg', 0, 100, v => `${v}%`))),
    field('Colour', subColors, 'Applies to subtitles the browser renders. Subtitles burned in by Plex\'s transcoder use the size setting only.')));

  // --- Live TV ---
  el.append(card('Live TV channel list',
    h('div', { class: 'set-grid' },
      field('Row density', chips([['comfortable', 'Comfortable'], ['compact', 'Compact']], 'chDensity')),
      field('Logo size', chips([['small', 'Small'], ['medium', 'Medium'], ['large', 'Large']], 'chLogoSize'))),
    check('chShowNumbers', 'Show channel numbers'),
    check('chShowLogos', 'Show channel logos'),
    check('chShowProgress', 'Show progress bar of the current programme'),
    check('chShowNext', 'Show the next programme under each channel')));

  function langSelect(key, emptyLabel) {
    const langs = [['', emptyLabel], ['en', 'English'], ['es', 'Spanish'], ['fr', 'French'], ['de', 'German'], ['it', 'Italian'], ['pt', 'Portuguese'], ['nl', 'Dutch'], ['sv', 'Swedish'], ['no', 'Norwegian'], ['da', 'Danish'], ['fi', 'Finnish'], ['pl', 'Polish'], ['ru', 'Russian'], ['uk', 'Ukrainian'], ['tr', 'Turkish'], ['el', 'Greek'], ['ar', 'Arabic'], ['he', 'Hebrew'], ['hi', 'Hindi'], ['ja', 'Japanese'], ['ko', 'Korean'], ['zh', 'Chinese'], ['th', 'Thai'], ['vi', 'Vietnamese']];
    return select(langs.map(([value, label]) => ({ value, label })), u[key] || '', v => save({ [key]: v }));
  }
  function qualitySelect(key) {
    const q = [['auto', 'Auto (direct play when possible)'], ['original', 'Original'], ['20000', '20 Mbps 1080p'], ['12000', '12 Mbps 1080p'], ['8000', '8 Mbps 1080p'], ['4000', '4 Mbps 720p'], ['2000', '2 Mbps 720p'], ['720', '1.5 Mbps 480p']];
    return select(q.map(([value, label]) => ({ value, label })), u[key] || 'auto', v => save({ [key]: v }));
  }
  function buttonToggles() {
    const btns = [['rw', 'Skip back'], ['ff', 'Skip forward'], ['volume', 'Volume'], ['prevCh', 'Previous channel'], ['guide', 'Mini guide'], ['list', 'Channel list'],
      ['audio', 'Audio tracks'], ['subs', 'Subtitles'], ['quality', 'Quality / settings'], ['info', 'Stream info'], ['pip', 'Picture-in-picture'], ['fullscreen', 'Fullscreen']];
    const box = h('div', { class: 'chips' });
    const draw = () => box.replaceChildren(...btns.map(([k, l]) => {
      const on = (u.playerButtons || {})[k] !== false;
      return h('button', { class: `chip ${on ? 'active' : ''}`, onClick: () => { save({ playerButtons: { ...(u.playerButtons || {}), [k]: !on } }); draw(); } }, l);
    }));
    draw();
    return box;
  }

  // --- TV Guide ---
  el.append(card('TV Guide',
    h('div', { class: 'set-grid' },
      field('Zoom (time scale)', range('guideScale', 3, 12, 1, v => `${Math.round((v / 6) * 100)}%`)),
      field('Row height', range('guideRowHeight', 44, 96, 2, v => `${v}px`)),
      field('Hours shown', range('guideHours', 2, 24, 1, v => `${v}h`)),
      field('Guide starts at', chips([['now', 'Just before now'], ['hour', 'Start of the hour']], 'guideStart'))),
    check('guideCategoryColors', 'Colour-code programmes by category (sports, news, movies, kids…)')));

  el.append(h('div', { class: 'row' }, btn('Reset appearance to defaults', async () => {
    if (!(await confirmDialog('Reset appearance?', 'Theme, sizes, sidebar, home screen and player preferences go back to their defaults.', { okLabel: 'Reset', danger: true }))) return;
    const defaults = { theme: 'dark', accent: '#ff9f3d', uiScale: 100, cardSize: 'medium', showCardTitles: true, blurSpoilers: false, navStyle: 'rail', navOrder: [], navHidden: [],
      homeOrder: [], homeHidden: [], hiddenHubs: [], heroInterval: 8, autoplayNext: true, autoplayCountdown: 10, autoSkipIntro: false, autoSkipCredits: false,
      seekBack: 10, seekForward: 30, subtitleSize: 100, subtitleColor: '#ffffff', subtitleBg: 60, guideScale: 6, guideRowHeight: 62 };
    if (deviceMode()) setDeviceMode(false);
    u = await api.put('/api/settings/ui', defaults); window.lumenSettings.ui = u; applyAppearance(u); rerenderNav();
    el.replaceChildren(); appearanceTab(el);
  }, { cls: 'danger', iconName: 'refresh' })));
}

// ---------------- Favourite lists ----------------
async function favListsCard() {
  const box = h('div');
  const draw = async () => {
    const lists = await api.get('/api/iptv/fav-lists');
    box.replaceChildren(sortableList(lists.map(l => ({ id: l.id, label: `★ ${l.name}`, note: `${l.count} channel${l.count === 1 ? '' : 's'}` })),
      items => api.put('/api/iptv/fav-lists-order', { order: items.map(x => x.id) }).catch(toastErr),
      { toggles: false, actions: (x) => h('div', { class: 'row', style: { gap: '2px' } },
        h('button', { class: 'btn ghost sm icon', title: 'Rename', html: icon('edit'), onClick: async () => { const n = window.prompt('Rename list', x.label.slice(2)); if (n?.trim()) { await api.put(`/api/iptv/fav-lists/${x.id}`, { name: n.trim() }); draw(); } } }),
        h('button', { class: 'btn ghost sm icon danger', title: 'Delete', html: icon('trash'), onClick: async () => {
          if (await confirmDialog(`Delete ${x.label}?`, 'Only the list is removed; the channels stay.', { okLabel: 'Delete', danger: true })) { try { await api.del(`/api/iptv/fav-lists/${x.id}`); draw(); } catch (e) { toastErr(e); } } } })) }));
  };
  await draw();
  return h('div', { class: 'card-box', style: { marginBottom: '18px' } }, h('div', { class: 'row', style: { marginBottom: '10px' } }, h('h3', { class: 'grow', style: { margin: 0 } }, 'Favourite lists'),
    btn('New list', async () => { const n = window.prompt('Name for the new list (e.g. Sports, Kids, News):'); if (n?.trim()) { await api.post('/api/iptv/fav-lists', { name: n.trim() }); draw(); } }, { cls: 'sm', iconName: 'plus' })),
    h('p', { class: 'muted small', style: { marginTop: 0 } }, 'Each list appears as its own group in Live TV and the guide. Add channels with the ☆ button (you can pick the lists when you have more than one).'), box);
}

// ---------------- Parental lock ----------------
async function parentalCard() {
  const box = h('div', { class: 'card-box', style: { marginBottom: '18px' } });
  const draw = async () => {
    const st = await api.get('/api/iptv/parental');
    const pin = h('input', { class: 'input', type: 'password', inputmode: 'numeric', maxlength: 8, placeholder: st.enabled ? 'New PIN' : '4–8 digits' });
    const cur = h('input', { class: 'input', type: 'password', inputmode: 'numeric', maxlength: 8, placeholder: 'Current PIN' });
    box.replaceChildren(h('h3', { style: { marginTop: 0 } }, '🔒 Parental lock'),
      h('p', { class: 'muted small', style: { marginTop: 0 } }, st.enabled
        ? 'PIN is set. Lock groups from Groups (🔓 button) or a group\'s right-click menu in Live TV. Locked groups are hidden from Live TV, the guide, VOD and search until the PIN is entered (unlocks for 4 hours on that device).'
        : 'Set a PIN, then lock any channel or VOD group (adult, kids, sports…).'),
      h('div', { class: 'row wrap' }, st.enabled ? cur : null, pin,
        btn(st.enabled ? 'Change PIN' : 'Set PIN', async () => { try { await api.post('/api/iptv/parental/pin', { current: cur.value, pin: pin.value }); toast('PIN saved', { type: 'ok' }); draw(); } catch (e) { toastErr(e); } }, { cls: 'primary' }),
        st.enabled ? btn('Remove PIN', async () => { try { await api.post('/api/iptv/parental/pin', { current: cur.value, pin: '' }); toast('Parental lock removed (all groups unlocked)', { type: 'ok' }); draw(); } catch (e) { toastErr(e); } }, { cls: 'danger' }) : null));
  };
  await draw();
  return box;
}

// ---------------- Devices (Android TV app pairing) ----------------
async function devicesTab(el, route) {
  const native = window.LumenNative;
  if (native) {
    el.append(h('div', { class: 'card-box', style: { marginBottom: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'This Android TV app'),
      h('dl', { class: 'kv' }, h('dt', {}, 'Server'), h('dd', {}, native.serverUrl ? native.serverUrl() : location.origin),
        h('dt', {}, 'App version'), h('dd', {}, native.version ? native.version() : '?')),
      h('p', { class: 'muted small' }, 'Everything you change here — settings, Plex & IPTV logins, favourites — is stored on the Lumen server and shared with every device.'),
      native.openAppSettings ? btn('Change server / pair again', () => native.openAppSettings(), { iconName: 'settings' }) : null));
  }
  const code = h('input', { class: 'input', placeholder: '6-digit code', inputmode: 'numeric', maxlength: 6, value: (route?.query?.code || '').replace(/\D/g, ''),
    style: { width: '170px', fontSize: '22px', letterSpacing: '6px', textAlign: 'center' } });
  const approve = async (c) => {
    try { const r = await api.post('/api/pair/approve', { code: c || code.value }); toast(`"${r.name}" is now connected`, { type: 'ok' }); code.value = ''; draw(); }
    catch (e) { toastErr(e); }
  };
  code.addEventListener('keydown', e => { if (e.key === 'Enter') approve(); });
  const pendingBox = h('div'), listBox = h('div');
  el.append(h('div', { class: 'card-box', style: { marginBottom: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Connect a TV or app'),
    h('p', { class: 'muted small', style: { marginTop: 0 } }, 'Open Lumen on the TV, pick this server, and enter the code it shows (or scan its QR code with your phone).'),
    h('div', { class: 'row wrap' }, code, btn('Connect device', () => approve(), { cls: 'primary', iconName: 'check' })), pendingBox));
  el.append(h('div', { class: 'card-box', style: { marginBottom: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Connected devices'), listBox));
  const host = `${location.protocol}//${location.host}`;
  el.append(h('div', { class: 'card-box' }, h('h3', { style: { marginTop: 0 } }, 'Get the Android TV app'),
    h('ol', { class: 'muted', style: { lineHeight: 1.8, paddingLeft: '20px', margin: 0 } },
      h('li', {}, 'On the TV, install ', h('b', {}, 'Downloader'), ' (by AFTVnews) from the Play Store / Amazon Appstore.'),
      h('li', {}, 'In Downloader, enter ', h('code', {}, `${host}/tv.apk`), ' and install.'),
      h('li', {}, 'Open Lumen on the TV — it finds this server automatically — and connect it with the code above.')),
    h('p', { class: 'dim small' }, 'Updates: install the latest APK the same way. Your settings live on this server, so nothing is lost; with a signing key set up (see docs/ANDROID_TV.md) updates install over the old version and keep the pairing too.')));

  async function draw() {
    const d = await api.get('/api/devices').catch(() => ({ devices: [], pending: [] }));
    pendingBox.replaceChildren(d.pending.length ? h('div', { style: { marginTop: '14px' } }, h('div', { class: 'small muted', style: { marginBottom: '6px' } }, 'Waiting to connect:'),
      ...d.pending.map(p => h('div', { class: 'prov', style: { marginBottom: '6px' } },
        h('div', { class: 'grow' }, h('b', {}, p.name), h('div', { class: 'dim small' }, `${p.platform || 'device'} · code ${p.code}`)),
        btn('Approve', () => approve(p.code), { cls: 'primary sm' })))) : '');
    listBox.replaceChildren(!d.devices.length ? h('div', { class: 'dim' }, 'No devices paired yet.') : h('table', { class: 'tbl' },
      h('thead', {}, h('tr', {}, ['Device', 'Type', 'Last seen', ''].map(t => h('th', {}, t)))),
      h('tbody', {}, d.devices.map(dev => h('tr', {},
        h('td', {}, h('b', {}, dev.name), dev.id === d.current ? h('span', { class: 'badge ok', style: { marginLeft: '8px' } }, 'THIS DEVICE') : null),
        h('td', {}, dev.platform === 'android-tv' ? 'Android TV' : (dev.platform || '—')),
        h('td', {}, dev.last_seen ? `${fmtDateTime(dev.last_seen)}${dev.last_ip ? ` · ${dev.last_ip}` : ''}` : '—'),
        h('td', { style: { textAlign: 'right', whiteSpace: 'nowrap' } },
          btn('', async () => { const n = window.prompt('Device name', dev.name); if (n?.trim()) { await api.put(`/api/devices/${dev.id}`, { name: n.trim() }); draw(); } }, { cls: 'sm icon', iconName: 'edit', title: 'Rename' }), ' ',
          btn('Remove', async () => { if (await confirmDialog(`Remove "${dev.name}"?`, 'It will be signed out and needs to be paired again.', { okLabel: 'Remove', danger: true })) { await api.del(`/api/devices/${dev.id}`); draw(); } }, { cls: 'sm danger' })))))));
  }
  await draw();
  if (code.value.length === 6) setTimeout(() => code.focus(), 50);
  const t = setInterval(draw, 4000);
  return () => clearInterval(t);
}

/** #/pair?code=123456 — opened from the QR code on the TV. */
export const views = { pair: (el, route) => settings(el, { ...route, query: { tab: 'devices', code: route.query.code || '' } }) };

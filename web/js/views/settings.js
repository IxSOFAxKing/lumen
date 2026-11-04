import { api } from '../api.js';
import { h, btn, toast, toastErr, modal, confirmDialog, fmtDateTime, spinner, select, empty, sortableList, debounce } from '../ui.js';
import { icon } from '../icons.js';
import { store, refreshStore, navEntries, rerenderNav } from '../app.js';
import { homeSections, ACCENTS, applyAppearance } from '../appearance.js';

export default async function settings(el, route) {
  el.className = 'page settings';
  const tab = route.query.tab || 'plex';
  const tabs = [['plex', 'Plex'], ['iptv', 'IPTV Providers'], ['appearance', 'Appearance'], ['transcode', 'Transcoding'], ['playback', 'Playback'], ['system', 'System']];
  el.append(h('div', { class: 'page-head' }, h('h1', {}, 'Settings')),
    h('div', { class: 'tabs' }, tabs.map(([k, l]) => h('a', { class: `tab ${tab === k ? 'active' : ''}`, href: `#/settings?tab=${k}` }, l))));
  const body = h('div');
  el.append(body);
  const fn = { plex: plexTab, iptv: iptvTab, appearance: appearanceTab, transcode: transcodeTab, playback: playbackTab, system: systemTab }[tab] || plexTab;
  return fn(body);
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
    box.append(h('p', { class: 'dim small', style: { marginBottom: 0 } }, 'Tip: Plex hardware transcoding requires Plex Pass and is configured on your Plex server. Alternatively choose "Lumen built-in" transcoder under Transcoding to use Lumen\'s own GPU pipeline.'));
  } else {
    connectFlow(el);
  }
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
              try { await api.post('/api/plex/connect', { server: s }); toast(`Connected to ${s.name}`, { type: 'ok' }); await refreshStore(); location.hash = '#/home'; }
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
  const list = h('div', { style: { maxHeight: '55vh', overflow: 'auto' } });
  const tabsEl = h('div', { class: 'tabs' });
  const filter = h('input', { class: 'input', placeholder: 'Filter…', style: { width: '100%', marginBottom: '10px' } });
  let cats = [];
  const draw = () => {
    const q = filter.value.toLowerCase();
    list.replaceChildren(...cats.filter(c => !q || c.name.toLowerCase().includes(q)).map(c => h('label', { class: 'check', style: { padding: '4px 0' } },
      h('input', { type: 'checkbox', checked: !c.hidden, onChange: async e => { c.hidden = !e.target.checked; await api.put(`/api/iptv/categories/${p.id}/${kind}/${encodeURIComponent(c.id)}`, { hidden: c.hidden }); } }),
      h('span', { class: 'grow' }, c.name), h('span', { class: 'dim small' }, c.count))));
  };
  const load = async () => {
    tabsEl.replaceChildren(...kinds.map(([k, l]) => h('div', { class: `tab ${k === kind ? 'active' : ''}`, onClick: () => { kind = k; load(); } }, l)));
    cats = await api.get(`/api/iptv/providers/${p.id}/categories?kind=${kind}&all=1`);
    draw();
  };
  filter.addEventListener('input', draw);
  const bulk = (hidden) => async () => { for (const c of cats) if (!!c.hidden !== hidden) { c.hidden = hidden; await api.put(`/api/iptv/categories/${p.id}/${kind}/${encodeURIComponent(c.id)}`, { hidden }); } draw(); };
  modal({ title: `Groups · ${p.name}`, size: 'lg', body: h('div', {}, h('p', { class: 'muted small', style: { marginTop: 0 } }, 'Uncheck groups to hide them from Live TV, the guide and VOD.'), tabsEl,
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
      field('Default quality', select([{ value: 'auto', label: 'Auto (direct play when possible)' }, { value: 'original', label: 'Original' },
        { value: '20000', label: '20 Mbps 1080p' }, { value: '12000', label: '12 Mbps 1080p' }, { value: '8000', label: '8 Mbps 1080p' }, { value: '4000', label: '4 Mbps 720p' }, { value: '2000', label: '2 Mbps 720p' }, { value: '720', label: '1.5 Mbps 480p' }], t.plexQuality, v => save({ plexQuality: v }))))));
}

// ---------------- Playback / UI ----------------
async function playbackTab(el) {
  const all = await api.get('/api/settings');
  const ui = all.ui, iptv = all.iptv;
  const saveUi = async patch => { Object.assign(ui, await api.put('/api/settings/ui', patch)); await refreshStore(); toast('Saved', { type: 'ok', timeout: 1200 }); };
  const saveIptv = async patch => { Object.assign(iptv, await api.put('/api/settings/iptv', patch)); toast('Saved', { type: 'ok', timeout: 1200 }); };
  const field = (label, control, hint) => h('label', { class: 'field' }, label, control, hint ? h('span', { class: 'dim small' }, hint) : null);
  const num = (obj, key, saver, min, max) => h('input', { class: 'input', type: 'number', min, max, value: obj[key], onChange: e => saver({ [key]: Number(e.target.value) }) });
  el.append(h('div', { class: 'card-box' }, h('h3', { style: { marginTop: 0 } }, 'Interface'),
    h('div', { class: 'set-grid' },
      field('Start page', select([{ value: 'home', label: 'Home' }, { value: 'live', label: 'Live TV' }, { value: 'guide', label: 'TV Guide' }], ui.startPage, v => saveUi({ startPage: v }))),
      field('Channel info banner (seconds)', num(ui, 'zapOverlaySeconds', saveUi, 1, 20)),
      field('Live TV buffering', select([
        { value: 'smooth', label: 'Smooth (recommended) — extra buffer, no frame skipping' },
        { value: 'balanced', label: 'Balanced' },
        { value: 'low', label: 'Low latency — closest to live, may stutter' }], ui.liveBuffer || 'smooth', v => saveUi({ liveBuffer: v })),
        'If channels still buffer, also try Settings → Transcoding → Live TV playback → "Always remux".')),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: ui.showPreview, onChange: e => saveUi({ showPreview: e.target.checked }) }), 'Show live preview when selecting a channel')),
  h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'IPTV'),
    h('div', { class: 'set-grid' },
      field('Playlist refresh (hours)', num(iptv, 'playlistRefreshHours', saveIptv, 1, 168)),
      field('EPG refresh (hours)', num(iptv, 'epgRefreshHours', saveIptv, 1, 168)),
      field('Keep past EPG (hours)', num(iptv, 'epgPastHours', saveIptv, 0, 336), 'Needed for catch-up browsing in the guide.'),
      field('Load future EPG (days)', num(iptv, 'epgFutureDays', saveIptv, 1, 14)),
      field('Catch-up time offset (minutes)', num(iptv, 'timeshiftOffsetMinutes', saveIptv, -720, 720), 'Adjust if catch-up starts at the wrong time (provider timezone).'),
      field('Default User-Agent', h('input', { class: 'input', value: iptv.userAgent, placeholder: 'Browser default', onChange: e => saveIptv({ userAgent: e.target.value }) }))),
  ), h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Keyboard shortcuts (player)'),
    h('dl', { class: 'kv' }, ...[['Space / K', 'Play / pause'], ['← / →', 'Seek −10s / +30s'], ['↑ / ↓', 'Channel up / down (live) or volume'], ['0–9', 'Type a channel number'],
      ['L', 'Channel list'], ['Enter', 'Show channel info banner'], ['C', 'Subtitles'], ['F', 'Fullscreen'], ['P', 'Picture-in-picture'], ['M', 'Mute'], ['R', 'Record (live TV)'], ['I', 'Stream info'], ['N', 'Next episode'], ['Esc', 'Close player']]
      .flatMap(([k, v]) => [h('dt', {}, h('span', { class: 'badge' }, k)), h('dd', {}, v)]))));
}

// ---------------- System ----------------
async function systemTab(el) {
  const auth = await api.get('/api/auth/status');
  const sess = h('div');
  el.append(h('div', { class: 'card-box' }, h('h3', { style: { marginTop: 0 } }, 'Active transcoding sessions'), sess),
    h('div', { class: 'card-box', style: { marginTop: '18px' } }, h('h3', { style: { marginTop: 0 } }, 'Security'),
      h('p', { class: 'muted' }, auth.required ? 'Password protection is enabled (APP_PASSWORD).' : 'Password protection is disabled. Set APP_PASSWORD in docker-compose.yml to require a login.'),
      auth.required ? btn('Sign out', async () => { await api.post('/api/auth/logout'); location.reload(); }, { iconName: 'logout' }) : null));
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
  window.lumenSettings = all;
  let u = all.ui;
  // Apply instantly (optimistic); changes are batched and saved in the background.
  let pending = {};
  const persist = debounce(async () => {
    const patch = pending; pending = {};
    try { await api.put('/api/settings/ui', patch); } catch (e) { toastErr(e); }
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
  homeBox.append(sortableList(homeSections().map(s => ({ id: s.id, label: s.label, hidden: s.hidden, note: s.source === 'iptv' ? 'IPTV' : 'Plex' })),
    list => save({ homeOrder: list.map(x => x.id), homeHidden: [...((u.homeHidden || []).includes('hero') ? ['hero'] : []), ...list.filter(x => x.hidden).map(x => x.id)] })));
  drawHubs();
  el.append(card('Home screen', h('p', { class: 'muted small', style: { marginTop: 0 } }, 'Choose which rows appear on Home and in what order. Rows with nothing to show are skipped automatically.'),
    homeBox,
    h('div', { style: { marginTop: '14px' } },
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !(u.homeHidden || []).includes('hero'), onChange: e => {
        const rest = (u.homeHidden || []).filter(x => x !== 'hero'); save({ homeHidden: e.target.checked ? rest : [...rest, 'hero'] }); } }),
        h('span', {}, 'Show the featured banner at the top of Home', h('div', { class: 'dim small' }, 'Rotating spotlight of newly added movies and shows.'))),
      field('Featured banner: seconds per slide', range('heroInterval', 4, 20, 1, v => `${v}s`))), hubsBox));

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
    check('autoSkipIntro', 'Automatically skip intros', 'Uses Plex intro markers. Otherwise a "Skip Intro" button is shown.'),
    check('autoSkipCredits', 'Automatically skip credits', 'Jumps to the next episode, or to the end, when credits start.'),
    h('h4', { style: { margin: '18px 0 10px' } }, 'Subtitles'),
    prev,
    h('div', { class: 'set-grid', style: { marginTop: '12px' } },
      field('Size', subRange('subtitleSize', 50, 200, v => `${v}%`)),
      field('Background', subRange('subtitleBg', 0, 100, v => `${v}%`))),
    field('Colour', subColors, 'Applies to subtitles the browser renders. Subtitles burned in by Plex\'s transcoder use the size setting only.')));

  // --- TV Guide ---
  el.append(card('TV Guide',
    h('div', { class: 'set-grid' },
      field('Zoom (time scale)', range('guideScale', 3, 12, 1, v => `${Math.round((v / 6) * 100)}%`)),
      field('Row height', range('guideRowHeight', 44, 96, 2, v => `${v}px`)))));

  el.append(h('div', { class: 'row' }, btn('Reset appearance to defaults', async () => {
    if (!(await confirmDialog('Reset appearance?', 'Theme, sizes, sidebar, home screen and player preferences go back to their defaults.', { okLabel: 'Reset', danger: true }))) return;
    const defaults = { theme: 'dark', accent: '#ff9f3d', uiScale: 100, cardSize: 'medium', showCardTitles: true, blurSpoilers: false, navStyle: 'rail', navOrder: [], navHidden: [],
      homeOrder: [], homeHidden: [], hiddenHubs: [], heroInterval: 8, autoplayNext: true, autoplayCountdown: 10, autoSkipIntro: false, autoSkipCredits: false,
      seekBack: 10, seekForward: 30, subtitleSize: 100, subtitleColor: '#ffffff', subtitleBg: 60, guideScale: 6, guideRowHeight: 62 };
    u = await api.put('/api/settings/ui', defaults); window.lumenSettings.ui = u; applyAppearance(u); rerenderNav();
    el.replaceChildren(); appearanceTab(el);
  }, { cls: 'danger', iconName: 'refresh' })));
}

// Export / import of Lumen settings and customisations.
import { db, tx } from '../db.js';
import { allConfig, setConfig, DEFAULTS } from './settings.js';

const SECTIONS = ['ui', 'iptv', 'transcode'];

export function exportAll({ includeProviders = false } = {}) {
  const providers = db.prepare('SELECT * FROM providers').all();
  const cfg = allConfig();
  return {
    app: 'lumen', version: 1, exportedAt: new Date().toISOString(),
    settings: Object.fromEntries(SECTIONS.map(s => [s, cfg[s]])),
    // Providers are referenced by url+username so imports can be matched on another install.
    providers: providers.map(p => ({
      id: p.id, name: p.name, type: p.type, url: p.url, username: p.username, epg_url: p.epg_url, output: p.output, user_agent: p.user_agent,
      vod_enabled: p.vod_enabled, series_enabled: p.series_enabled, ...(includeProviders ? { password: p.password } : {}),
    })),
    favLists: db.prepare('SELECT * FROM fav_lists').all(),
    favorites: db.prepare('SELECT * FROM favorites').all(),
    channelPrefs: db.prepare('SELECT * FROM channel_prefs').all(),
    categoryPrefs: db.prepare('SELECT * FROM category_prefs').all(),
    hiddenCategories: db.prepare('SELECT provider_id, kind, id FROM categories WHERE hidden = 1').all(),
  };
}

export function importAll(data) {
  if (!data || data.app !== 'lumen') { const e = new Error('Not a Lumen settings file'); e.status = 400; throw e; }
  const report = { settings: 0, providersCreated: 0, providersMatched: 0, favorites: 0, channelPrefs: 0, categoryPrefs: 0 };
  for (const s of SECTIONS) if (data.settings?.[s] && DEFAULTS[s]) { setConfig(s, data.settings[s]); report.settings++; }

  // Map exported provider ids to providers on this install (create them if passwords were exported)
  const idMap = new Map();
  const existing = db.prepare('SELECT * FROM providers').all();
  for (const p of data.providers || []) {
    const match = existing.find(e => e.url === p.url && (e.username || '') === (p.username || ''));
    if (match) { idMap.set(p.id, match.id); report.providersMatched++; continue; }
    if (p.type === 'm3u' || p.password) {
      const r = db.prepare('INSERT INTO providers(name, type, url, username, password, epg_url, output, user_agent, vod_enabled, series_enabled) VALUES (?,?,?,?,?,?,?,?,?,?)')
        .run(p.name, p.type, p.url, p.username || null, p.password || null, p.epg_url || null, p.output || 'ts', p.user_agent || null, p.vod_enabled ?? 1, p.series_enabled ?? 1);
      idMap.set(p.id, Number(r.lastInsertRowid)); report.providersCreated++;
    }
  }
  const pid = old => idMap.get(old);
  tx(() => {
    const listMap = new Map();
    for (const l of data.favLists || []) {
      const found = db.prepare('SELECT id FROM fav_lists WHERE name = ?').get(l.name);
      listMap.set(l.id, found ? found.id : Number(db.prepare('INSERT INTO fav_lists(name, position) VALUES (?,?)').run(l.name, l.position || 0).lastInsertRowid));
    }
    for (const f of data.favorites || []) {
      if (!pid(f.provider_id)) continue;
      db.prepare('INSERT OR IGNORE INTO favorites(list_id, provider_id, channel_id, position) VALUES (?,?,?,?)').run(listMap.get(f.list_id) || 1, pid(f.provider_id), f.channel_id, f.position || 0);
      report.favorites++;
    }
    for (const c of data.channelPrefs || []) {
      if (!pid(c.provider_id)) continue;
      db.prepare('INSERT OR REPLACE INTO channel_prefs(provider_id, channel_id, name, num, sort) VALUES (?,?,?,?,?)').run(pid(c.provider_id), c.channel_id, c.name, c.num, c.sort);
      report.channelPrefs++;
    }
    for (const c of data.categoryPrefs || []) {
      if (!pid(c.provider_id)) continue;
      db.prepare('INSERT OR REPLACE INTO category_prefs(provider_id, kind, id, name, sort, locked) VALUES (?,?,?,?,?,?)').run(pid(c.provider_id), c.kind, c.id, c.name, c.sort, c.locked || 0);
      report.categoryPrefs++;
    }
    for (const c of data.hiddenCategories || []) {
      if (pid(c.provider_id)) db.prepare('UPDATE categories SET hidden = 1 WHERE provider_id = ? AND kind = ? AND id = ?').run(pid(c.provider_id), c.kind, c.id);
    }
  });
  return report;
}

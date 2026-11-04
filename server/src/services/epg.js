import sax from 'sax';
import zlib from 'node:zlib';
import { Readable, Writable } from 'node:stream';
import { StringDecoder } from 'node:string_decoder';
import { pipeline } from 'node:stream/promises';
import { db } from '../db.js';
import { getProvider, epgUrl, userAgent, shortEpg } from './iptv.js';
import { getConfig } from './settings.js';

const running = new Set();

/** Parse XMLTV time "20240101123000 +0100" -> unix seconds */
export function parseXmltvTime(s) {
  if (!s) return 0;
  const m = s.trim().match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?/);
  if (!m) return 0;
  const [, Y, M, D, h, mi, sec = '00', tz] = m;
  let t = Date.UTC(+Y, +M - 1, +D, +h, +mi, +sec) / 1000;
  if (tz) {
    const sign = tz[0] === '-' ? -1 : 1;
    t -= sign * (Number(tz.slice(1, 3)) * 3600 + Number(tz.slice(3, 5)) * 60);
  }
  return t;
}

/** Stream stages for the body, gunzipping when it starts with the gzip magic bytes (backpressure-safe). */
async function decompressed(webBody) {
  const src = Readable.fromWeb(webBody);
  const it = src[Symbol.asyncIterator]();
  const first = await it.next();
  const rest = Readable.from((async function* () {
    if (!first.done) yield first.value;
    for (;;) { const r = await it.next(); if (r.done) return; yield r.value; }
  })(), { objectMode: false });
  const gz = !first.done && first.value[0] === 0x1f && first.value[1] === 0x8b;
  return gz ? [rest, zlib.createGunzip()] : [rest];
}

export async function syncEpg(providerId) {
  const p = getProvider(providerId);
  const url = epgUrl(p);
  if (!url) return { programmes: 0, skipped: 'No EPG URL configured' };
  if (running.has(p.id)) return { programmes: 0, skipped: 'already running' };
  running.add(p.id);
  try {
    const conf = getConfig('iptv');
    const now = Math.floor(Date.now() / 1000);
    const minStop = now - Number(conf.epgPastHours || 72) * 3600;
    const maxStart = now + Number(conf.epgFutureDays || 7) * 86400;

    const channels = db.prepare('SELECT id, epg_id, name FROM channels WHERE provider_id = ?').all(p.id);
    const wanted = new Set(channels.map(c => c.epg_id).filter(Boolean));
    const wantedLower = new Map(); // lowercased epg id/name -> channel ids
    for (const c of channels) for (const k of [c.epg_id, c.name]) {
      if (!k) continue; const l = k.toLowerCase();
      if (!wantedLower.has(l)) wantedLower.set(l, []);
      wantedLower.get(l).push(c.id);
    }
    const aliasUpdates = []; // [xmltvId, channelId]
    const xmlIds = new Set();

    const res = await fetch(url, { headers: { 'User-Agent': userAgent(p) }, signal: AbortSignal.timeout(600000) });
    if (!res.ok) throw new Error('EPG download failed: ' + res.status);

    // Programmes stream into a staging table in small batches (constant memory, even for huge guides),
    // then replace the live data in one quick transaction at the end.
    db.exec(`CREATE TABLE IF NOT EXISTS programmes_stage AS SELECT * FROM programmes WHERE 0`);
    db.prepare('DELETE FROM programmes_stage WHERE provider_id = ?').run(p.id);
    const ins = db.prepare('INSERT INTO programmes_stage(provider_id, channel, start, stop, title, sub_title, descr, category, icon, episode) VALUES (?,?,?,?,?,?,?,?,?,?)');
    let batch = [];
    const flush = () => {
      if (!batch.length) return;
      db.exec('BEGIN');
      try { for (const r of batch) ins.run(...r); db.exec('COMMIT'); } catch (e) { db.exec('ROLLBACK'); throw e; }
      batch = [];
    };
    let count = 0;
    const accept = new Set(wanted);

    const parser = sax.parser(false, { lowercase: true, trim: true });
    let curChan = null, curProg = null, text = '', field = null;
    parser.onopentag = (node) => {
      const a = node.attributes;
      if (node.name === 'channel') curChan = { id: a.id, names: [] };
      else if (node.name === 'programme') {
        curProg = { channel: a.channel, start: parseXmltvTime(a.start), stop: parseXmltvTime(a.stop) };
      } else if (curProg && node.name === 'icon') curProg.icon = a.src;
      else if (curProg && node.name === 'episode-num' && a.system === 'onscreen') field = 'episode';
      text = '';
      if (curProg && ['title', 'sub-title', 'desc', 'category'].includes(node.name)) field = node.name;
      if (curChan && node.name === 'display-name') field = 'display-name';
    };
    parser.ontext = t => { text += t; };
    parser.oncdata = t => { text += t; };
    parser.onclosetag = (name) => {
      if (curChan) {
        if (name === 'display-name') curChan.names.push(text);
        if (name === 'channel') {
          xmlIds.add(curChan.id);
          if (!wanted.has(curChan.id)) {
            for (const k of [curChan.id, ...curChan.names]) {
              const ids = wantedLower.get((k || '').toLowerCase());
              if (ids) { accept.add(curChan.id); ids.forEach(cid => aliasUpdates.push([curChan.id, cid])); break; }
            }
          }
          curChan = null;
        }
      } else if (curProg) {
        if (field && name !== 'programme') {
          if (field === 'title' && !curProg.title) curProg.title = text;
          else if (field === 'sub-title') curProg.sub_title = text;
          else if (field === 'desc' && !curProg.descr) curProg.descr = text;
          else if (field === 'category' && !curProg.category) curProg.category = text;
          else if (field === 'episode') curProg.episode = text;
          field = null;
        }
        if (name === 'programme') {
          const pr = curProg; curProg = null;
          if (accept.has(pr.channel) && pr.stop > minStop && pr.start < maxStart) {
            batch.push([p.id, pr.channel, pr.start, pr.stop, pr.title || '', pr.sub_title || null, pr.descr || null, pr.category || null, pr.icon || null, pr.episode || null]);
            if (batch.length >= 2000) flush();
            count++;
          }
        }
      }
      text = '';
    };
    parser.onerror = () => { parser.error = null; parser.resume(); }; // tolerate malformed XML
    const decoder = new StringDecoder('utf8');
    const sink = new Writable({
      write(chunk, enc, cb) { try { parser.write(decoder.write(chunk)); cb(); } catch (e) { cb(e); } },
      final(cb) { try { parser.write(decoder.end()); parser.close(); cb(); } catch (e) { cb(e); } },
    });

    try {
      await pipeline(...(await decompressed(res.body)), sink);
      flush();
    } catch (e) {
      db.prepare('DELETE FROM programmes_stage WHERE provider_id = ?').run(p.id);
      throw e;
    }
    db.exec('BEGIN');
    try {
      db.prepare('DELETE FROM programmes WHERE provider_id = ?').run(p.id);
      db.prepare('INSERT INTO programmes SELECT * FROM programmes_stage WHERE provider_id = ?').run(p.id);
      db.prepare('DELETE FROM programmes_stage WHERE provider_id = ?').run(p.id);
      // Apply name-based EPG mappings for channels whose epg_id didn't match exactly.
      const upd = db.prepare('UPDATE channels SET epg_id = ? WHERE provider_id = ? AND id = ?');
      for (const [xid, cid] of aliasUpdates) upd.run(xid, p.id, cid);
      db.prepare('UPDATE providers SET last_epg_sync = unixepoch() WHERE id = ?').run(p.id);
      db.exec('COMMIT');
    } catch (e) {
      db.exec('ROLLBACK');
      throw e;
    }
    return { programmes: count, xmltvChannels: xmlIds.size, mapped: aliasUpdates.length };
  } finally {
    running.delete(p.id);
  }
}

export function isEpgRunning(id) { return running.has(Number(id)); }

/** Programmes for many channels in a window. Returns { [channelId]: [...] } */
export function guide(providerId, channelIds, from, to) {
  const rows = channelIds.length ? db.prepare(`
    SELECT c.id AS cid, pr.start, pr.stop, pr.title, pr.sub_title, pr.descr, pr.category, pr.icon, pr.episode
    FROM channels c JOIN programmes pr ON pr.provider_id = c.provider_id AND pr.channel = c.epg_id
    WHERE c.provider_id = ? AND c.id IN (${channelIds.map(() => '?').join(',')}) AND pr.stop > ? AND pr.start < ?
    ORDER BY pr.start`).all(Number(providerId), ...channelIds.map(String), from, to) : [];
  const out = {};
  for (const r of rows) (out[r.cid] ||= []).push(r);
  return out;
}

/** Now & next for each channel in a provider (or a subset). */
export function nowNext(providerId, channelIds = null) {
  const now = Math.floor(Date.now() / 1000);
  const filter = channelIds ? `AND c.id IN (${channelIds.map(() => '?').join(',')})` : '';
  const rows = db.prepare(`
    SELECT c.id AS cid, pr.start, pr.stop, pr.title, pr.descr
    FROM channels c JOIN programmes pr ON pr.provider_id = c.provider_id AND pr.channel = c.epg_id
    WHERE c.provider_id = ? ${filter} AND pr.stop > ? AND pr.start < ?
    ORDER BY pr.start`).all(Number(providerId), ...(channelIds || []).map(String), now, now + 6 * 3600);
  const out = {};
  for (const r of rows) {
    const e = (out[r.cid] ||= []);
    if (e.length < 2) e.push(r);
  }
  return out;
}

export async function channelEpg(providerId, channelId, from, to) {
  const list = guide(providerId, [channelId], from, to)[channelId] || [];
  if (list.length) return list;
  try {
    const p = getProvider(providerId);
    return await shortEpg(p, channelId, 20);
  } catch { return []; }
}

export function purgeOldProgrammes() {
  const hours = Number(getConfig('iptv').epgPastHours || 72);
  db.prepare('DELETE FROM programmes WHERE stop < ?').run(Math.floor(Date.now() / 1000) - hours * 3600);
}

import sax from 'sax';
import zlib from 'node:zlib';
import { Readable, Transform, Writable } from 'node:stream';
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

/** Auto-detect gzip by magic bytes. */
function maybeGunzip() {
  let decided = false, gunzip = null;
  return new Transform({
    transform(chunk, enc, cb) {
      if (!decided) {
        decided = true;
        if (chunk[0] === 0x1f && chunk[1] === 0x8b) {
          gunzip = zlib.createGunzip();
          gunzip.on('data', d => this.push(d));
          gunzip.on('error', e => this.destroy(e));
        }
      }
      if (gunzip) gunzip.write(chunk, cb); else cb(null, chunk);
    },
    flush(cb) {
      if (!gunzip) return cb();
      gunzip.once('end', () => cb());
      gunzip.end();
    },
  });
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

    const ins = db.prepare('INSERT INTO programmes(provider_id, channel, start, stop, title, sub_title, descr, category, icon, episode) VALUES (?,?,?,?,?,?,?,?,?,?)');
    const rows = []; // buffered so the DB transaction stays synchronous (no interleaving with other requests)
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
            rows.push([p.id, pr.channel, pr.start, pr.stop, pr.title || '', pr.sub_title || null, pr.descr || null, pr.category || null, pr.icon || null, pr.episode || null]);
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

    await pipeline(Readable.fromWeb(res.body), maybeGunzip(), sink);
    db.exec('BEGIN');
    try {
      db.prepare('DELETE FROM programmes WHERE provider_id = ?').run(p.id);
      for (const r of rows) ins.run(...r);
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

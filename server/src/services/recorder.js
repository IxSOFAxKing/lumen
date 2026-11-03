import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { db } from '../db.js';
import { config } from '../config.js';
import { getProvider, liveUrl, userAgent } from './iptv.js';

const active = new Map(); // recording id -> process

const safe = s => (s || 'recording').replace(/[^\w\s.-]+/g, '').replace(/\s+/g, ' ').trim().slice(0, 80) || 'recording';

function startRecording(rec) {
  let p, ch;
  try {
    p = getProvider(rec.provider_id);
    ch = db.prepare('SELECT * FROM channels WHERE provider_id = ? AND id = ?').get(rec.provider_id, rec.channel_id);
    if (!ch) throw new Error('Channel no longer exists');
  } catch (e) {
    db.prepare("UPDATE recordings SET status = 'failed', error = ? WHERE id = ?").run(e.message, rec.id);
    return;
  }
  const now = Math.floor(Date.now() / 1000);
  const duration = Math.max(10, rec.stop - now);
  const d = new Date(rec.start * 1000);
  const stamp = d.toISOString().slice(0, 16).replace(/[:T]/g, '-');
  const dir = path.join(config.recordingsDir, safe(rec.channel_name));
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${safe(rec.title)} - ${stamp}.ts`);
  const args = ['-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
    '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_on_network_error', '1', '-reconnect_delay_max', '10',
    '-user_agent', userAgent(p), '-i', liveUrl(p, ch, 'ts'),
    '-map', '0:v?', '-map', '0:a?', '-c', 'copy', '-t', String(duration), '-f', 'mpegts', file];
  const proc = spawn(config.ffmpeg, args, { stdio: ['ignore', 'ignore', 'pipe'] });
  let log = '';
  proc.stderr.on('data', d => { log = (log + d).slice(-4000); });
  active.set(rec.id, proc);
  db.prepare("UPDATE recordings SET status = 'recording', file = ?, error = NULL WHERE id = ?").run(file, rec.id);
  console.log(`[dvr] recording #${rec.id} "${rec.title}" for ${duration}s`);
  proc.on('exit', (code) => {
    active.delete(rec.id);
    const cur = db.prepare('SELECT status FROM recordings WHERE id = ?').get(rec.id);
    let size = 0; try { size = fs.statSync(file).size; } catch { /* missing */ }
    if (cur?.status === 'cancelled') { db.prepare('UPDATE recordings SET size = ? WHERE id = ?').run(size, rec.id); return; }
    const ok = size > 1024 * 100;
    db.prepare('UPDATE recordings SET status = ?, size = ?, error = ? WHERE id = ?')
      .run(ok ? 'completed' : 'failed', size, ok ? null : (log.trim().split('\n').pop() || `ffmpeg exited ${code}`), rec.id);
  });
}

export function schedule({ providerId, channelId, title, descr, start, stop }) {
  const ch = db.prepare('SELECT name FROM channels WHERE provider_id = ? AND id = ?').get(Number(providerId), String(channelId));
  if (!ch) { const e = new Error('Channel not found'); e.status = 404; throw e; }
  const r = db.prepare('INSERT INTO recordings(provider_id, channel_id, channel_name, title, descr, start, stop) VALUES (?,?,?,?,?,?,?)')
    .run(Number(providerId), String(channelId), ch.name, title || ch.name, descr || null, Math.floor(start), Math.floor(stop));
  tick();
  return Number(r.lastInsertRowid);
}

export function cancel(id) {
  db.prepare("UPDATE recordings SET status = 'cancelled' WHERE id = ? AND status IN ('scheduled','recording')").run(id);
  const proc = active.get(Number(id));
  if (proc) proc.kill('SIGINT');
}

export function remove(id, deleteFile = true) {
  cancel(id);
  const r = db.prepare('SELECT file FROM recordings WHERE id = ?').get(id);
  if (deleteFile && r?.file) fs.rm(r.file, { force: true }, () => {});
  db.prepare('DELETE FROM recordings WHERE id = ?').run(id);
}

export function list() {
  return db.prepare('SELECT * FROM recordings ORDER BY start DESC').all();
}

function tick() {
  const now = Math.floor(Date.now() / 1000);
  // Start due recordings (allow 30s pre-roll)
  for (const rec of db.prepare("SELECT * FROM recordings WHERE status = 'scheduled' AND start <= ? AND stop > ?").all(now + 30, now)) {
    startRecording(rec);
  }
  // Missed recordings
  db.prepare("UPDATE recordings SET status = 'failed', error = 'Missed (server was offline)' WHERE status = 'scheduled' AND stop <= ?").run(now);
}

export function initRecorder() {
  // Recordings interrupted by a restart
  for (const rec of db.prepare("SELECT * FROM recordings WHERE status = 'recording'").all()) {
    if (rec.stop > Date.now() / 1000 + 30) {
      db.prepare("UPDATE recordings SET status = 'scheduled' WHERE id = ?").run(rec.id);
    } else {
      db.prepare("UPDATE recordings SET status = 'completed' WHERE id = ?").run(rec.id);
    }
  }
  setInterval(tick, 15000).unref();
  tick();
}

export function stopAllRecordings() { for (const p of active.values()) p.kill('SIGINT'); }

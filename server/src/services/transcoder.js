import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { config } from '../config.js';
import { getConfig } from './settings.js';

/** Active HLS transcode sessions keyed by id. */
const sessions = new Map();

const sleep = ms => new Promise(r => setTimeout(r, ms));

/**
 * Build the ffmpeg video pipeline for the selected hardware acceleration.
 * Returns { input: [], filters: string|null, encoder: [] }.
 */
export function videoPipeline(t, { deinterlace = false, maxHeight } = {}) {
  const h = Number(maxHeight || t.maxHeight || 1080);
  const br = Number(t.videoBitrate || 8000);
  const rate = [`-b:v`, `${br}k`, `-maxrate`, `${br}k`, `-bufsize`, `${br * 2}k`];
  const hmin = `min(ih\\,${h})`;
  switch (t.hwAccel) {
    case 'vaapi':
      return {
        input: ['-hwaccel', 'vaapi', '-hwaccel_device', t.vaapiDevice || config.vaapiDevice, '-hwaccel_output_format', 'vaapi'],
        filters: [deinterlace && 'deinterlace_vaapi', `scale_vaapi=w=-2:h='${hmin}':format=nv12`].filter(Boolean).join(','),
        encoder: ['-c:v', 'h264_vaapi', ...rate],
      };
    case 'qsv':
      return {
        input: ['-init_hw_device', 'qsv=hw', '-filter_hw_device', 'hw', '-hwaccel', 'qsv', '-hwaccel_output_format', 'qsv'],
        filters: [deinterlace && 'deinterlace_qsv', `scale_qsv=w=-1:h='${hmin}'`].filter(Boolean).join(','),
        encoder: ['-c:v', 'h264_qsv', '-preset', 'veryfast', '-look_ahead', '0', ...rate],
      };
    case 'nvenc':
      return {
        input: ['-hwaccel', 'cuda', '-hwaccel_output_format', 'cuda'],
        filters: [deinterlace && 'yadif_cuda=deint=interlaced', `scale_cuda=w=-2:h='${hmin}'`].filter(Boolean).join(','),
        encoder: ['-c:v', 'h264_nvenc', '-preset', 'p4', '-tune', 'll', '-rc', 'vbr', ...rate],
      };
    case 'videotoolbox':
      return {
        input: ['-hwaccel', 'videotoolbox'],
        filters: [deinterlace && 'yadif=deint=interlaced', `scale=-2:'${hmin}'`].filter(Boolean).join(','),
        encoder: ['-c:v', 'h264_videotoolbox', '-realtime', '1', ...rate],
      };
    default:
      return {
        input: [],
        filters: [deinterlace && 'yadif=deint=interlaced', `scale=-2:'${hmin}'`, 'format=yuv420p'].filter(Boolean).join(','),
        encoder: ['-c:v', 'libx264', '-preset', t.preset || 'veryfast', '-profile:v', 'high', '-level', '4.1', ...rate],
      };
  }
}

function buildArgs(opts, t, outDir) {
  const { input, isLive, mode, startOffset = 0, audioIndex = 0, userAgent, headers } = opts;
  const isUrl = /^https?:\/\//i.test(input);
  const args = ['-hide_banner', '-loglevel', 'warning', '-nostdin', '-y'];
  const transcoding = mode === 'transcode';
  const pipe = transcoding ? videoPipeline(t, { deinterlace: isLive && t.deinterlace, maxHeight: opts.maxHeight }) : null;

  if (pipe) args.push(...pipe.input);
  if (isUrl) {
    args.push('-reconnect', '1', '-reconnect_on_network_error', '1', '-reconnect_delay_max', '5');
    if (isLive) args.push('-reconnect_streamed', '1');
    if (userAgent) args.push('-user_agent', userAgent);
    if (headers) args.push('-headers', Object.entries(headers).map(([k, v]) => `${k}: ${v}`).join('\r\n') + '\r\n');
  }
  args.push('-fflags', '+genpts+discardcorrupt', '-analyzeduration', '5000000', '-probesize', '5000000');
  if (startOffset > 0) args.push('-ss', String(startOffset));
  args.push('-i', input);

  args.push('-map', '0:v:0?', '-map', `0:a:${Number(audioIndex) || 0}?`, '-sn', '-dn');
  if (transcoding) {
    if (pipe.filters) args.push('-vf', pipe.filters);
    args.push(...pipe.encoder);
    const seg = Number(t.segmentSeconds || 4);
    args.push('-force_key_frames', `expr:gte(t,n_forced*${seg})`);
  } else {
    args.push('-c:v', 'copy');
  }
  args.push('-c:a', 'aac', '-b:a', `${t.audioBitrate || 192}k`, '-ac', '2', '-af', 'aresample=async=1');
  if (isLive) args.push('-max_muxing_queue_size', '2048');

  const seg = Number(t.segmentSeconds || 4);
  args.push('-f', 'hls', '-hls_time', String(seg), '-start_number', '0',
    '-hls_segment_filename', path.join(outDir, 'seg_%05d.ts'));
  if (isLive) {
    args.push('-hls_list_size', '12', '-hls_flags', 'delete_segments+independent_segments+omit_endlist');
  } else {
    args.push('-hls_list_size', '0', '-hls_playlist_type', 'event', '-hls_flags', 'independent_segments+temp_file');
  }
  args.push(path.join(outDir, 'index.m3u8'));
  return args;
}

function spawnFfmpeg(session, t) {
  const args = buildArgs(session.opts, t, session.dir);
  session.args = args;
  session.log = '';
  const proc = spawn(config.ffmpeg, args, { stdio: ['ignore', 'ignore', 'pipe'] });
  session.proc = proc;
  session.startedAt = Date.now();
  proc.stderr.on('data', d => { session.log = (session.log + d.toString()).slice(-8000); });
  proc.on('exit', (code, signal) => {
    session.exitCode = code;
    session.running = false;
    if (session.killed) return;
    // Hardware pipeline failed quickly -> transparently retry in software.
    const quick = Date.now() - session.startedAt < 15000;
    if (code !== 0 && quick && session.opts.mode === 'transcode' && t.hwAccel !== 'none' && !session.fellBack) {
      console.warn(`[transcode ${session.id}] ${t.hwAccel} failed (code ${code}), falling back to software.\n${session.log.slice(-1500)}`);
      session.fellBack = true;
      clearDir(session.dir);
      spawnFfmpeg(session, { ...t, hwAccel: 'none' });
      return;
    }
    if (code !== 0 && !signal) console.warn(`[transcode ${session.id}] ffmpeg exited ${code}\n${session.log.slice(-1500)}`);
  });
  session.running = true;
}

function clearDir(dir) {
  try { for (const f of fs.readdirSync(dir)) fs.rmSync(path.join(dir, f), { force: true }); } catch { /* ignore */ }
}

/**
 * Start an HLS session.
 * opts: { input, isLive, mode: 'remux'|'transcode', startOffset, audioIndex, userAgent, headers, maxHeight, bitrate, label }
 */
export async function startSession(opts) {
  const t = { ...getConfig('transcode') };
  if (opts.bitrate) t.videoBitrate = opts.bitrate;
  const id = crypto.randomBytes(8).toString('hex');
  const dir = path.join(config.transcodeDir, id);
  fs.mkdirSync(dir, { recursive: true });
  const session = { id, dir, opts, lastAccess: Date.now(), createdAt: Date.now(), hwAccel: opts.mode === 'transcode' ? t.hwAccel : 'copy' };
  sessions.set(id, session);
  spawnFfmpeg(session, t);

  // Wait for the playlist (with at least one segment) to appear.
  const playlist = path.join(dir, 'index.m3u8');
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    if (fs.existsSync(playlist)) {
      const txt = fs.readFileSync(playlist, 'utf8');
      if (txt.includes('#EXTINF')) break;
    }
    if (!session.running && session.fellBack !== true && session.exitCode != null) {
      // Might be in fallback restart; give it a moment
      await sleep(300);
      if (!session.running) {
        stopSession(id);
        const err = new Error('Transcoder failed to start: ' + (session.log.split('\n').filter(Boolean).slice(-3).join(' | ') || 'unknown error'));
        err.status = 502;
        throw err;
      }
    }
    await sleep(250);
  }
  if (!fs.existsSync(playlist)) { stopSession(id); const e = new Error('Timed out waiting for stream'); e.status = 504; throw e; }
  return { id, url: `/api/stream/hls/${id}/index.m3u8`, hwAccel: session.fellBack ? 'none' : session.hwAccel, startOffset: opts.startOffset || 0 };
}

export function touchSession(id) {
  const s = sessions.get(id);
  if (s) s.lastAccess = Date.now();
  return s;
}

export function stopSession(id) {
  const s = sessions.get(id);
  if (!s) return false;
  s.killed = true;
  try { s.proc?.kill('SIGKILL'); } catch { /* ignore */ }
  sessions.delete(id);
  setTimeout(() => fs.rm(s.dir, { recursive: true, force: true }, () => {}), 1000);
  return true;
}

export function listSessions() {
  return [...sessions.values()].map(s => ({
    id: s.id, label: s.opts.label, mode: s.opts.mode, hwAccel: s.fellBack ? 'none (fallback)' : s.hwAccel,
    live: !!s.opts.isLive, running: s.running, startedAt: s.createdAt, idle: Math.round((Date.now() - s.lastAccess) / 1000),
  }));
}

export function sessionFile(id, file) {
  const s = touchSession(id);
  if (!s) return null;
  if (!/^[\w.-]+$/.test(file)) return null;
  return path.join(s.dir, file);
}

// Reap idle sessions
setInterval(() => {
  const now = Date.now();
  for (const s of sessions.values()) {
    const limit = s.opts.isLive ? 45000 : 180000;
    if (now - s.lastAccess > limit) {
      console.log(`[transcode ${s.id}] idle, stopping`);
      stopSession(s.id);
    }
  }
}, 10000).unref();

// Clean leftovers from previous runs
try { for (const f of fs.readdirSync(config.transcodeDir)) fs.rmSync(path.join(config.transcodeDir, f), { recursive: true, force: true }); } catch { /* ignore */ }

export function stopAll() { for (const id of [...sessions.keys()]) stopSession(id); }

/** Detect which hardware encoders the bundled ffmpeg supports + which devices exist. */
export async function detectHardware() {
  const run = (args) => new Promise(resolve => {
    const p = spawn(config.ffmpeg, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    p.stdout.on('data', d => out += d); p.stderr.on('data', d => out += d);
    p.on('error', () => resolve(''));
    p.on('exit', () => resolve(out));
  });
  const [encoders, hwaccels, version] = await Promise.all([
    run(['-hide_banner', '-encoders']), run(['-hide_banner', '-hwaccels']), run(['-version']),
  ]);
  const has = n => encoders.includes(n);
  let dri = [];
  try { dri = fs.readdirSync('/dev/dri'); } catch { /* none */ }
  return {
    ffmpeg: version.split('\n')[0] || 'ffmpeg not found',
    hwaccels: hwaccels.split('\n').slice(1).map(s => s.trim()).filter(Boolean),
    encoders: { h264_vaapi: has('h264_vaapi'), h264_qsv: has('h264_qsv'), h264_nvenc: has('h264_nvenc'), h264_videotoolbox: has('h264_videotoolbox'), libx264: has('libx264') },
    devices: { dri, nvidia: fs.existsSync('/dev/nvidia0') || fs.existsSync('/dev/nvidiactl') },
  };
}

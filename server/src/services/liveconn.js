// One live stream per viewer: most IPTV plans allow only one or two connections at a time. When a browser / TV
// starts a channel, Lumen first closes the live streams it is still holding for that same viewer (the preview, the
// previous channel, a player that didn't hang up yet) and gives the provider a moment to notice, so the new channel
// isn't refused. Multi-View asks to keep its other tiles open.
import crypto from 'node:crypto';
import { stopSession } from './transcoder.js';

const held = new Map(); // viewer -> Set of { stop() }
const sleep = ms => new Promise(r => setTimeout(r, ms));

/** Who is watching: the app's device, else the browser's sign-in cookie, else the address. */
export function viewerKey(req) {
  if (req.device?.id) return `d${req.device.id}`;
  const c = /lumen_session=([^;]+)/.exec(req.headers.cookie || '')?.[1];
  if (c) return 's' + crypto.createHash('sha1').update(c).digest('hex').slice(0, 16);
  return `ip${req.ip}`;
}

export function hold(viewer, h) {
  let set = held.get(viewer);
  if (!set) { set = new Set(); held.set(viewer, set); }
  set.add(h);
  return () => { set.delete(h); if (!set.size) held.delete(viewer); };
}

/** Close this viewer's other live streams; waits briefly when something was closed. */
export async function claim(viewer) {
  const set = held.get(viewer);
  if (!set?.size) return false;
  for (const h of [...set]) { try { h.stop(); } catch { /* already gone */ } set.delete(h); }
  held.delete(viewer);
  await sleep(700);
  return true;
}

/** A live transcode/remux session counts as a held stream until it's stopped. */
export function holdSession(viewer, sessionId) {
  return hold(viewer, { stop: () => stopSession(sessionId) });
}

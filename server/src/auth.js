import crypto from 'node:crypto';
import { config } from './config.js';
import { getSetting } from './db.js';
import { deviceForToken } from './services/devices.js';

const COOKIE = 'lumen_session';
const secret = () => config.sessionSecret || getSetting('sessionSecret');
const sign = v => crypto.createHmac('sha256', secret()).update(v).digest('base64url');

export const authEnabled = () => Boolean(config.appPassword);

function parseCookies(header = '') {
  return Object.fromEntries(header.split(';').map(c => c.trim().split('=')).filter(p => p[0]).map(([k, ...v]) => [k, decodeURIComponent(v.join('='))]));
}

/** Device token from the Android TV app: "Authorization: Bearer …" (player) or the lumen_device cookie (WebView). */
export function deviceToken(req) {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.authorization || '');
  return m ? m[1].trim() : parseCookies(req.headers.cookie).lumen_device || null;
}

export function isAuthed(req) {
  const dt = deviceToken(req);
  if (dt && req.device === undefined) req.device = deviceForToken(dt, req.ip) || null;
  if (!authEnabled() || req.device) return true;
  const tok = parseCookies(req.headers.cookie)[COOKIE];
  if (!tok) return false;
  const [exp, sig] = tok.split('.');
  if (!exp || !sig || Number(exp) < Date.now()) return false;
  const expect = sign(exp);
  return sig.length === expect.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expect));
}

export function login(req, res) {
  const pw = String(req.body?.password || '');
  const a = crypto.createHash('sha256').update(pw).digest();
  const b = crypto.createHash('sha256').update(config.appPassword).digest();
  if (!authEnabled() || !crypto.timingSafeEqual(a, b)) return res.status(401).json({ error: 'Incorrect password' });
  const exp = String(Date.now() + 1000 * 60 * 60 * 24 * 30);
  const secure = req.secure || req.headers['x-forwarded-proto'] === 'https';
  res.setHeader('Set-Cookie', `${COOKIE}=${exp}.${sign(exp)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${60 * 60 * 24 * 30}${secure ? '; Secure' : ''}`);
  res.json({ ok: true });
}

export function logout(req, res) {
  res.setHeader('Set-Cookie', `${COOKIE}=; Path=/; HttpOnly; Max-Age=0`);
  res.json({ ok: true });
}

export function requireAuth(req, res, next) {
  if (isAuthed(req)) return next();
  res.status(401).json({ error: 'Unauthorized', lumenAuth: true });
}

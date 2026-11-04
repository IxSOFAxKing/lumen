// Login: Lumen always requires a username and password (like Sonarr/Radarr).
// Credentials come from LUMEN_USERNAME / LUMEN_PASSWORD (env), or are created on first start in the web UI
// and stored hashed (scrypt) in the database. Paired TV apps use their own device tokens.
import crypto from 'node:crypto';
import { config } from './config.js';
import { getSetting, setSetting } from './db.js';
import { deviceForToken } from './services/devices.js';

const COOKIE = 'lumen_session';
const secret = () => config.sessionSecret || getSetting('sessionSecret');
const sign = v => crypto.createHmac('sha256', secret()).update(v).digest('base64url');

const envUser = () => (process.env.LUMEN_USERNAME || '').trim();
const envPass = () => process.env.LUMEN_PASSWORD || config.appPassword || '';
/** Credentials are set through environment variables (they can't be changed in the UI). */
export const envManaged = () => Boolean(envPass());
const account = () => getSetting('account', null); // { username, salt, hash, ver }

export const authEnabled = () => true;
/** First start: no account yet — the web UI asks to create one. */
export const needsSetup = () => !envManaged() && !account()?.hash;
export const username = () => (envManaged() ? (envUser() || 'admin') : account()?.username || '');
/** Changes whenever the password changes, so old sessions stop working. */
const credVersion = () => (envManaged() ? crypto.createHash('sha256').update(`${username()}\n${envPass()}`).digest('hex').slice(0, 12) : String(account()?.ver || 0));

const hashPw = (pw, salt) => crypto.scryptSync(String(pw), salt, 64).toString('hex');
const same = (a, b) => { const x = Buffer.from(String(a)), y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };

function checkCredentials(user, pw) {
  user = String(user || '').trim();
  if (envManaged()) {
    const a = crypto.createHash('sha256').update(String(pw)).digest(), b = crypto.createHash('sha256').update(envPass()).digest();
    return same(user.toLowerCase(), username().toLowerCase()) && crypto.timingSafeEqual(a, b);
  }
  const acc = account();
  if (!acc?.hash) return false;
  return same(user.toLowerCase(), String(acc.username).toLowerCase()) && same(hashPw(pw, acc.salt), acc.hash);
}

function parseCookies(header = '') {
  return Object.fromEntries(header.split(';').map(c => c.trim().split('=')).filter(p => p[0]).map(([k, ...v]) => [k, decodeURIComponent(v.join('='))]));
}
export const cookies = req => parseCookies(req.headers.cookie);

/** Device token from the Android TV app: "Authorization: Bearer …" (player) or the lumen_device cookie (WebView). */
export function deviceToken(req) {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.authorization || '');
  return m ? m[1].trim() : parseCookies(req.headers.cookie).lumen_device || null;
}

export function isAuthed(req) {
  const dt = deviceToken(req);
  if (dt && req.device === undefined) req.device = deviceForToken(dt, req.ip) || null;
  if (req.device) return true;
  const tok = parseCookies(req.headers.cookie)[COOKIE];
  if (!tok || needsSetup()) return false;
  const [exp, ver, sig] = tok.split('.');
  if (!exp || !ver || !sig || Number(exp) < Date.now() || ver !== credVersion()) return false;
  return same(sig, sign(`${exp}.${ver}`));
}

function startSession(req, res) {
  const exp = String(Date.now() + 1000 * 60 * 60 * 24 * 30);
  const v = `${exp}.${credVersion()}`;
  const secure = req.secure || req.headers['x-forwarded-proto'] === 'https';
  res.setHeader('Set-Cookie', `${COOKIE}=${v}.${sign(v)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${60 * 60 * 24 * 30}${secure ? '; Secure' : ''}`);
}

// Slow down password guessing: after 5 failures from an address, wait a minute.
const failures = new Map(); // ip -> { n, until }
function throttled(ip) {
  const f = failures.get(ip);
  return f && f.n >= 5 && Date.now() < f.until;
}
function failed(ip) {
  const f = failures.get(ip) || { n: 0, until: 0 };
  if (Date.now() > f.until) f.n = 0;
  f.n++; f.until = Date.now() + 60000;
  failures.set(ip, f);
}

export function status(req, res) {
  res.json({ required: true, authed: isAuthed(req), setup: needsSetup(), username: isAuthed(req) ? username() : undefined, envManaged: envManaged() });
}

function validNew(user, pw) {
  user = String(user || '').trim();
  if (!/^[\w.@+-]{2,40}$/.test(user)) return 'Choose a username of 2–40 letters, numbers or . _ - @ +';
  if (String(pw || '').length < 6) return 'The password must be at least 6 characters';
  return null;
}

function saveAccount(user, pw) {
  const salt = crypto.randomBytes(16).toString('hex');
  setSetting('account', { username: String(user).trim(), salt, hash: hashPw(pw, salt), ver: (account()?.ver || 0) + 1 });
}

/** First start: create the login. Only possible while no account exists. */
export function setup(req, res) {
  if (!needsSetup()) return res.status(409).json({ error: 'An account already exists. Sign in instead.' });
  const { username: user, password } = req.body || {};
  const bad = validNew(user, password);
  if (bad) return res.status(400).json({ error: bad });
  saveAccount(user, password);
  startSession(req, res);
  res.json({ ok: true });
}

export function login(req, res) {
  if (needsSetup()) return res.status(409).json({ error: 'Create your account first.', setup: true });
  if (throttled(req.ip)) return res.status(429).json({ error: 'Too many attempts. Wait a minute and try again.' });
  const { username: user, password } = req.body || {};
  if (!checkCredentials(user, password)) { failed(req.ip); return res.status(401).json({ error: 'Incorrect username or password' }); }
  failures.delete(req.ip);
  startSession(req, res);
  res.json({ ok: true });
}

/** Change the username/password (signed in, current password required). */
export function changeAccount(req, res) {
  if (envManaged()) return res.status(400).json({ error: 'The login is set with LUMEN_USERNAME / LUMEN_PASSWORD in docker-compose.yml. Change it there.' });
  const { current, username: user, password } = req.body || {};
  if (!checkCredentials(username(), current)) return res.status(403).json({ error: 'Current password is incorrect' });
  const bad = validNew(user || username(), password || current);
  if (bad) return res.status(400).json({ error: bad });
  saveAccount(user || username(), password || current);
  startSession(req, res); // other browsers are signed out
  res.json({ ok: true });
}

export function logout(req, res) {
  res.setHeader('Set-Cookie', `${COOKIE}=; Path=/; HttpOnly; Max-Age=0`);
  res.json({ ok: true });
}

export function requireAuth(req, res, next) {
  if (isAuthed(req)) return next();
  res.status(401).json({ error: needsSetup() ? 'Create your Lumen account first' : 'Unauthorized', lumenAuth: true, setup: needsSetup() });
}

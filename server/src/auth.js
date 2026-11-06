// Login: a Lumen username and password (the owner's admin login, like Sonarr/Radarr), or "Sign in with Plex" for
// the Plex server's owner, Plex Home members and friends the server is shared with (see services/accounts.js).
// Credentials come from LUMEN_USERNAME / LUMEN_PASSWORD (env), or are created on first start in the web UI
// and stored hashed (scrypt) in the database. Paired TV apps use their own device tokens.
import crypto from 'node:crypto';
import { config } from './config.js';
import { getSetting, setSetting } from './db.js';
import { deviceForToken, issueAccountToken } from './services/devices.js';
import * as accounts from './services/accounts.js';
import * as plex from './services/plex.js';

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

/**
 * Who is making the request. Sets req.device (app device token), req.account (Plex account, or null for the
 * Lumen login and code-paired devices) and req.perms. Returns true when signed in.
 */
export function isAuthed(req) {
  if (req.authed !== undefined) return req.authed;
  req.authed = false; req.account = null; req.perms = null;
  const dt = deviceToken(req);
  if (dt && req.device === undefined) req.device = deviceForToken(dt, req.ip) || null;
  if (req.device) {
    if (req.device.plex_account) {
      const a = accounts.get(req.device.plex_account);
      if (!a || a.blocked) return false;
      req.account = a;
    }
    return grant(req);
  }
  const tok = parseCookies(req.headers.cookie)[COOKIE];
  if (!tok || needsSetup()) return false;
  const [exp, ver, sig] = tok.split('.');
  if (!exp || !ver || !sig || Number(exp) < Date.now() || !same(sig, sign(`${exp}.${ver}`))) return false;
  if (ver.startsWith('p-')) {
    // Plex session: the account must still exist and not be blocked.
    const a = accounts.get(ver.slice(2));
    if (!a || a.blocked) return false;
    req.account = a;
    return grant(req);
  }
  if (ver !== credVersion()) return false;
  return grant(req);
}
function grant(req) {
  req.authed = true;
  req.perms = accounts.permissions(req.account);
  if (req.account) accounts.touch(req.account);
  return true;
}

function startSession(req, res, account = null) {
  const exp = String(Date.now() + 1000 * 60 * 60 * 24 * 30);
  const v = `${exp}.${account ? `p-${account.plex_id}` : credVersion()}`;
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
  const authed = isAuthed(req);
  res.json({
    required: true, authed, setup: needsSetup(), envManaged: envManaged(),
    username: authed ? (req.account ? req.account.title : username()) : undefined,
    account: authed ? accounts.publicAccount(req.account) : undefined,
    perms: authed ? req.perms : undefined,
    plexLogin: plex.isConfigured() && !!plex.plexConf().machineId,
    plexServer: plex.plexConf().serverName || null,
  });
}

// ---------- Sign in with Plex ----------
/** Web: start a plex.tv sign-in (the page opens authUrl in a pop-up and polls /api/auth/plex/pin/:id). */
export async function plexPinStart(req, res, next) {
  try { res.json(await plex.createPin()); } catch (e) { next(e); }
}
/** Web: finish once the user approved the sign-in on plex.tv. */
export async function plexPinCheck(req, res, next) {
  try {
    const token = await plex.checkPin(req.params.id);
    if (!token) return res.json({ authorized: false });
    const a = await accounts.verify(token);
    startSession(req, res, a);
    res.json({ authorized: true, account: accounts.publicAccount(a) });
  } catch (e) { next(e); }
}
/** Apps: they sign in to Plex themselves and hand Lumen their plex.tv token; Lumen checks it and issues a device token. */
export async function plexToken(req, res, next) {
  if (throttled(req.ip)) return res.status(429).json({ error: 'Too many attempts. Wait a minute and try again.' });
  try {
    const { token, name, platform } = req.body || {};
    if (!token) return res.status(400).json({ error: 'token is required' });
    let a;
    try { a = await accounts.verify(String(token)); }
    catch (e) { if (e.status === 401 || e.status === 403) failed(req.ip); throw e; }
    const issued = issueAccountToken({ name, platform }, a.plex_id, req.ip);
    res.json({ token: issued.token, deviceId: issued.deviceId, account: accounts.publicAccount(a), perms: accounts.permissions(a) });
  } catch (e) { next(e); }
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

/** Only the owner: the Lumen login, code-paired devices, or the Plex server owner. */
export function requireAdmin(req, res, next) {
  if (req.perms?.admin) return next();
  res.status(403).json({ error: 'Only the server owner can change this.' });
}
export const requirePerm = name => (req, res, next) => {
  if (req.perms?.[name]) return next();
  res.status(403).json({ error: name === 'live' ? 'Live TV isn\'t enabled for your account.' : name === 'discover' ? 'Discover isn\'t enabled for your account.' : 'Your account can\'t change this.', perm: name });
};

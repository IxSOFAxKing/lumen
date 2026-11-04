// Parental lock: a PIN protects channel/VOD groups marked as locked.
import crypto from 'node:crypto';
import { getSetting, setSetting } from '../db.js';

const UNLOCK_HOURS = 4;
const secret = () => getSetting('sessionSecret');

export function pinEnabled() { return !!getSetting('parentalPin'); }

function hashPin(pin, salt = crypto.randomBytes(16).toString('hex')) {
  return { salt, hash: crypto.scryptSync(String(pin), salt, 32).toString('hex') };
}
export function checkPin(pin) {
  const stored = getSetting('parentalPin');
  if (!stored) return true;
  const { hash } = hashPin(pin, stored.salt);
  return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(stored.hash, 'hex'));
}
export function setPin(pin) {
  if (!pin) { setSetting('parentalPin', null); return; }
  if (!/^\d{4,8}$/.test(String(pin))) { const e = new Error('PIN must be 4–8 digits'); e.status = 400; throw e; }
  setSetting('parentalPin', hashPin(pin));
}

/** Short-lived unlock token returned after a correct PIN (sent back in the X-Lumen-Unlock header). */
export function issueToken() {
  const exp = String(Date.now() + UNLOCK_HOURS * 3600e3);
  return { token: `${exp}.${crypto.createHmac('sha256', secret()).update('unlock|' + exp).digest('base64url')}`, expires: Number(exp) };
}
export function tokenValid(tok) {
  if (!tok) return false;
  const [exp, sig] = String(tok).split('.');
  if (!exp || !sig || Number(exp) < Date.now()) return false;
  const expect = crypto.createHmac('sha256', secret()).update('unlock|' + exp).digest('base64url');
  return sig.length === expect.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expect));
}

/** True when locked content may be shown for this request. */
export const isUnlocked = req => !pinEnabled() || tokenValid(req.headers['x-lumen-unlock']);

/** SQL condition hiding rows whose category is locked (unless unlocked). alias = table alias with provider_id + category_id. */
export function lockClause(req, alias, kind) {
  if (isUnlocked(req)) return '1=1';
  return `(${alias}.category_id IS NULL OR ${alias}.category_id NOT IN (SELECT id FROM category_prefs WHERE provider_id = ${alias}.provider_id AND kind = '${kind}' AND locked = 1))`;
}

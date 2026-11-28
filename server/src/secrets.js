// Encryption of stored secrets (Plex tokens, the Overseerr key, IPTV passwords) in Lumen's database.
// The key lives in its own file next to the database (or LUMEN_SECRET_KEY), so a copy of lumen.db — or one of the
// nightly backups — doesn't give those secrets away by itself. Keep data/secret.key with your backups.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';

const PREFIX = 'enc1:';

function loadKey() {
  if (process.env.LUMEN_SECRET_KEY) return crypto.createHash('sha256').update(process.env.LUMEN_SECRET_KEY).digest();
  const file = path.join(config.dataDir, 'secret.key');
  try {
    const k = Buffer.from(fs.readFileSync(file, 'utf8').trim(), 'base64');
    if (k.length === 32) return k;
  } catch { /* first start */ }
  const k = crypto.randomBytes(32);
  fs.mkdirSync(config.dataDir, { recursive: true });
  fs.writeFileSync(file, k.toString('base64') + '\n', { mode: 0o600 });
  return k;
}
const KEY = loadKey();

export const isEncrypted = v => typeof v === 'string' && v.startsWith(PREFIX);

/** Encrypt a string (empty / null stay as they are; already-encrypted values are left alone). */
export function enc(v) {
  if (v === null || v === undefined || v === '' || isEncrypted(v)) return v;
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', KEY, iv);
  const ct = Buffer.concat([c.update(String(v), 'utf8'), c.final()]);
  return PREFIX + Buffer.concat([iv, c.getAuthTag(), ct]).toString('base64');
}

/** Decrypt a value written by enc(); plain values (from before encryption) pass through. */
export function dec(v) {
  if (!isEncrypted(v)) return v;
  try {
    const b = Buffer.from(v.slice(PREFIX.length), 'base64');
    const d = crypto.createDecipheriv('aes-256-gcm', KEY, b.subarray(0, 12));
    d.setAuthTag(b.subarray(12, 28));
    return Buffer.concat([d.update(b.subarray(28)), d.final()]).toString('utf8');
  } catch {
    console.error('[secrets] a stored secret could not be decrypted (was data/secret.key replaced?) — enter it again in Settings');
    return null;
  }
}

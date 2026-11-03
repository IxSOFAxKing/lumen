import { DatabaseSync } from 'node:sqlite';
import path from 'node:path';
import crypto from 'node:crypto';
import { config } from './config.js';

export const db = new DatabaseSync(path.join(config.dataDir, 'lumen.db'));
db.exec(`PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA foreign_keys = ON;`);

db.exec(`
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE IF NOT EXISTS providers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  type TEXT NOT NULL DEFAULT 'xtream',      -- xtream | m3u
  url TEXT NOT NULL,                         -- xtream server url or m3u url
  username TEXT, password TEXT,
  epg_url TEXT,                              -- optional override XMLTV url
  output TEXT DEFAULT 'ts',                  -- ts | m3u8
  user_agent TEXT,
  enabled INTEGER DEFAULT 1,
  account_info TEXT,
  last_sync INTEGER, last_epg_sync INTEGER,
  created_at INTEGER DEFAULT (unixepoch())
);

CREATE TABLE IF NOT EXISTS categories (
  provider_id INTEGER NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,          -- live | vod | series
  id TEXT NOT NULL,
  name TEXT NOT NULL,
  sort INTEGER DEFAULT 0,
  hidden INTEGER DEFAULT 0,
  PRIMARY KEY (provider_id, kind, id)
);

CREATE TABLE IF NOT EXISTS channels (
  provider_id INTEGER NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  id TEXT NOT NULL,             -- stream_id (xtream) or hash (m3u)
  num INTEGER,
  name TEXT NOT NULL,
  logo TEXT,
  category_id TEXT,
  epg_id TEXT,
  url TEXT,                     -- direct url for m3u
  archive INTEGER DEFAULT 0,    -- catch-up available
  archive_days INTEGER DEFAULT 0,
  hidden INTEGER DEFAULT 0,
  PRIMARY KEY (provider_id, id)
);
CREATE INDEX IF NOT EXISTS idx_channels_cat ON channels(provider_id, category_id);
CREATE INDEX IF NOT EXISTS idx_channels_epg ON channels(epg_id);

CREATE TABLE IF NOT EXISTS vod (
  provider_id INTEGER NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,            -- movie | series
  id TEXT NOT NULL,
  name TEXT NOT NULL,
  poster TEXT,
  category_id TEXT,
  rating REAL,
  year TEXT,
  ext TEXT,
  added INTEGER,
  PRIMARY KEY (provider_id, kind, id)
);
CREATE INDEX IF NOT EXISTS idx_vod_cat ON vod(provider_id, kind, category_id);

CREATE TABLE IF NOT EXISTS programmes (
  provider_id INTEGER NOT NULL,
  channel TEXT NOT NULL,          -- xmltv channel id (matches channels.epg_id)
  start INTEGER NOT NULL,         -- unix seconds
  stop INTEGER NOT NULL,
  title TEXT, sub_title TEXT, descr TEXT, category TEXT, icon TEXT, episode TEXT
);
CREATE INDEX IF NOT EXISTS idx_prog ON programmes(provider_id, channel, start);
CREATE INDEX IF NOT EXISTS idx_prog_stop ON programmes(stop);

CREATE TABLE IF NOT EXISTS favorites (
  provider_id INTEGER NOT NULL,
  channel_id TEXT NOT NULL,
  position INTEGER DEFAULT 0,
  PRIMARY KEY (provider_id, channel_id)
);

CREATE TABLE IF NOT EXISTS history (
  provider_id INTEGER NOT NULL,
  channel_id TEXT NOT NULL,
  watched_at INTEGER NOT NULL,
  PRIMARY KEY (provider_id, channel_id)
);

CREATE TABLE IF NOT EXISTS vod_progress (
  provider_id INTEGER NOT NULL,
  kind TEXT NOT NULL,
  id TEXT NOT NULL,
  position REAL, duration REAL,
  title TEXT, poster TEXT, parent_id TEXT,
  updated_at INTEGER,
  PRIMARY KEY (provider_id, kind, id)
);

CREATE TABLE IF NOT EXISTS recordings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_id INTEGER, channel_id TEXT, channel_name TEXT,
  title TEXT, descr TEXT,
  start INTEGER NOT NULL, stop INTEGER NOT NULL,
  status TEXT DEFAULT 'scheduled',   -- scheduled | recording | completed | failed | cancelled
  file TEXT, size INTEGER, error TEXT,
  created_at INTEGER DEFAULT (unixepoch())
);

CREATE TABLE IF NOT EXISTS reminders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_id INTEGER, channel_id TEXT, channel_name TEXT,
  title TEXT, start INTEGER NOT NULL
);
`);

const getStmt = db.prepare('SELECT value FROM settings WHERE key = ?');
const setStmt = db.prepare('INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value');

export function getSetting(key, fallback = null) {
  const row = getStmt.get(key);
  if (!row) return fallback;
  try { return JSON.parse(row.value); } catch { return row.value; }
}
export function setSetting(key, value) {
  setStmt.run(key, JSON.stringify(value));
}

export function tx(fn) {
  db.exec('BEGIN');
  try { const r = fn(); db.exec('COMMIT'); return r; }
  catch (e) { db.exec('ROLLBACK'); throw e; }
}

// Stable per-install identifiers / secrets
if (!getSetting('clientId')) setSetting('clientId', crypto.randomUUID());
if (!getSetting('sessionSecret')) setSetting('sessionSecret', crypto.randomBytes(32).toString('hex'));

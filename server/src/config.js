import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const env = process.env;

export const config = {
  port: Number(env.PORT || 8080),
  dataDir: env.DATA_DIR || path.resolve(__dirname, '../../data'),
  transcodeDir: env.TRANSCODE_DIR || '/tmp/lumen-transcode',
  recordingsDir: env.RECORDINGS_DIR || path.resolve(__dirname, '../../recordings'),
  webDir: env.WEB_DIR || path.resolve(__dirname, '../../web'),
  vendorDir: path.resolve(__dirname, '../node_modules'),
  ffmpeg: env.FFMPEG_PATH || 'ffmpeg',
  ffprobe: env.FFPROBE_PATH || 'ffprobe',
  appPassword: env.APP_PASSWORD || '',
  sessionSecret: env.SESSION_SECRET || '',
  // Defaults for hardware transcoding; can be overridden in Settings UI.
  hwAccel: env.HW_ACCEL || 'none', // none | vaapi | qsv | nvenc | videotoolbox
  vaapiDevice: env.VAAPI_DEVICE || '/dev/dri/renderD128',
  tz: env.TZ || 'UTC',
};

for (const dir of [config.dataDir, config.transcodeDir, config.recordingsDir]) {
  fs.mkdirSync(dir, { recursive: true });
}

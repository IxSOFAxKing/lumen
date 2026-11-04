# Lumen — Plex + IPTV in one web player

Lumen is a self-hosted web app that plays your **Plex library** and your **IPTV live TV** (Xtream Codes or M3U) in one place, with a TiviMate-style TV guide, favorites, catch-up, DVR and hardware transcoding. It runs in Docker and you use it from any modern browser.

## Features

**Plex**
- Sign in with your Plex account (PIN link) or enter a server URL and token
- Home hubs: Continue Watching / On Deck, Recently Added, Recently Released and the other hubs Plex provides, plus your playlists
- Per-library pages: Recommended hubs (including a "Most Popular" row), a full library grid with sort/genre/unwatched filters and infinite scroll, and Collections
- Detail pages for movies, shows, seasons and episodes: resume, play from the start, shuffle, trailers and extras, cast, related titles, mark watched/unwatched
- Playback: direct play when the browser supports the file, otherwise the Plex transcoder (or Lumen's own transcoder); audio track, subtitle, quality and version selection; **Skip Intro / Skip Credits** from Plex markers; **Up Next** autoplay; watch progress synced back to Plex

**IPTV (TiviMate-style)**
- Multiple providers: **Xtream Codes API** (live, movies, series, account expiry and connection count) and **M3U** playlists
- Live TV screen: groups, ★ favorites, recently watched, channel search, live preview with now/next and a scrollable per-channel EPG
- **TV Guide** grid: 8-hour window, day picker (past 7 days through next 7), now line, favorites/group filter, infinite scroll
- **Catch-up / timeshift** (Xtream `tv_archive`): watch past programmes, or restart one that's on now from the beginning
- **DVR**: record now, record from the guide, or schedule manual recordings, then play them back in the app
- **Reminders**: in-app pop-up (and an optional desktop notification) when a programme starts
- Player: channel up/down zapping, type a channel number, channel-list overlay, info banner with now/next and progress
- **Multi-View**: watch 1–4 channels at once and pick which one you hear
- Movies (VOD) and Series with resume and "next episode"
- Hide groups or channels; XMLTV EPG (gzip supported), with optional EPG URL override and fuzzy name matching for M3U

**Player and transcoding**
- MPEG-TS live streams play directly in the browser (mpegts.js); HLS plays with hls.js
- **Automatic fallback**: direct → remux (copy video, convert audio to AAC) → full transcode
- **Hardware transcoding**: Intel/AMD **VAAPI**, Intel **Quick Sync (QSV)**, **NVIDIA NVENC**. If the GPU pipeline fails, Lumen falls back to software automatically
- Deinterlacing for live TV, max resolution and bitrate settings, and a live list of active transcode sessions
- Picture-in-picture, fullscreen, playback speed, aspect/zoom, a stream-info overlay, and keyboard shortcuts

## Quick start

Lumen is published as a ready-made image at **`ghcr.io/ixsofaxking/lumen`** for `amd64` and `arm64`, so you deploy it like Sonarr or Radarr: one compose file, `docker compose pull`, done.

Create a folder (for example `~/lumen`) and save this as `docker-compose.yml`:

```yaml
services:
  lumen:
    image: ghcr.io/ixsofaxking/lumen:latest
    container_name: lumen
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      - PUID=1000                  # your user id   (run `id` on the host)
      - PGID=1000                  # your group id
      - TZ=America/New_York        # change me: your time zone (used by the TV guide and recordings)
      - APP_PASSWORD=changeme      # change me: login password (leave empty to disable login)
      - HW_ACCEL=none              # none | vaapi | qsv | nvenc (can also be changed in Settings)
    volumes:
      - ./config:/config           # database, settings and EPG cache
      - ./recordings:/recordings   # DVR recordings
    tmpfs:
      - /transcode:size=4g         # transcoding scratch space in RAM

    # --- Intel / AMD GPU (VAAPI or Quick Sync): uncomment these two lines ---
    # devices:
    #   - /dev/dri:/dev/dri

    # --- NVIDIA GPU (needs the NVIDIA Container Toolkit): uncomment this block ---
    # deploy:
    #   resources:
    #     reservations:
    #       devices:
    #         - driver: nvidia
    #           count: all
    #           capabilities: [gpu, video, compute, utility]
```

Start it from that folder:

```bash
docker compose up -d
```

Update to the latest version:

```bash
docker compose pull && docker compose up -d
```

Image tags: `latest` (newest build from `main`), version tags such as `1.0.0` / `1.0` for releases, and `sha-xxxxxxx` for a specific commit. Portainer, Unraid, Dockge and similar tools work with the same compose file.

### Building from source instead

```bash
git clone https://github.com/IxSOFAxKing/lumen.git && cd lumen
```

In `docker-compose.yml`, comment out the `image:` line and uncomment `build: .`, then:

```bash
docker compose up -d --build
```

### First-time setup

Open **http://SERVER-IP:8080**, then go to **Settings**:
1. **Plex** → *Sign in with Plex* (or connect manually with URL + token).
2. **IPTV Providers** → *Add provider* → Xtream Codes: server URL (e.g. `http://line.example.com:8080`), username and password. Channels, VOD, series and EPG sync automatically.
3. **Transcoding** → choose your hardware acceleration.

Set `APP_PASSWORD` in `docker-compose.yml` if the app is reachable by anyone other than you.

## Hardware transcoding

The image ships **jellyfin-ffmpeg**, which includes VAAPI, QSV (with the Intel media driver) and NVENC support.

### Intel (Quick Sync / VAAPI) or AMD (VAAPI)
Uncomment the `devices: - /dev/dri:/dev/dri` lines in your compose file (or add `-f docker-compose.intel.yml` when using the repo's files). This passes the GPU into the container, and Lumen automatically joins the group that owns it, so it works with any `PUID`/`PGID`. In **Settings → Transcoding**, pick **VAAPI** (Intel or AMD) or **Intel QSV**. The "Detected hardware" panel shows which render devices and encoders were found.

### NVIDIA
Install the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html) on the host, uncomment the NVIDIA `deploy:` block in your compose file (or add `-f docker-compose.nvidia.yml` when using the repo's files), then select **NVIDIA NVENC** in Settings.

### Plex content
By default Plex content is transcoded by **your Plex Media Server**. That handles subtitle burn-in and uses Plex's own hardware acceleration, which requires Plex Pass. If you don't have Plex Pass, set *Transcoder for Plex content* to **Lumen built-in** so Lumen's GPU pipeline does the work instead.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `PUID` / `PGID` | `0` (root) | User and group the app runs as; files in `/config` and `/recordings` are owned by them |
| `APP_PASSWORD` | *(empty)* | Require a password to use the app |
| `HW_ACCEL` | `none` | Default accel: `none`, `vaapi`, `qsv`, `nvenc` (also changeable in the UI) |
| `VAAPI_DEVICE` | `/dev/dri/renderD128` | Render node for VAAPI |
| `TZ` | `UTC` | Time zone for the guide and recordings |
| `PORT` | `8080` | HTTP port inside the container |
| `SESSION_SECRET` | auto | Override the cookie signing secret |
| `PLEX_DEBUG` | *(off)* | Set to `1` to log every request Lumen sends to Plex, with timing (for troubleshooting) |

Volumes: `/config` (SQLite database, settings, EPG), `/recordings` (DVR files), `/transcode` (scratch space; a RAM `tmpfs` by default).

## Keyboard shortcuts (player)

| Key | Action |
|---|---|
| Space / K | Play / pause |
| ← / → | Seek −10 s / +30 s |
| ↑ / ↓, PgUp / PgDn | Channel up / down (live), volume otherwise |
| 0–9 | Jump to a channel number |
| L | Channel list overlay |
| Enter | Show the channel info banner |
| C | Subtitles · F fullscreen · P picture-in-picture · M mute · I stream info · N next episode |
| Esc | Close the player |

## Tips & troubleshooting

- **Live channel shows audio but no picture, or won't start**: the stream probably uses HEVC video or AC3 audio. Lumen switches to remux and then transcode by itself. To skip the direct attempt, set *Live TV playback* to *Always remux* or *Always transcode*.
- **Catch-up starts at the wrong time**: adjust *Catch-up time offset* under **Settings → Playback & UI** to match your provider's time zone.
- **Provider connection limits**: the live preview, Multi-View tiles and recordings each use one provider connection. Check *Connections x/y* on the provider card.
- **Remote access**: put Lumen behind a reverse proxy with HTTPS (Caddy, Traefik, Nginx Proxy Manager) and set `APP_PASSWORD`.
- **No guide data**: open **Settings → IPTV Providers → EPG** to refresh it, or add an *EPG URL override* (XMLTV `.xml` or `.xml.gz`).

## Running without Docker (development)

Requires Node.js 22.13+ and ffmpeg on your PATH.
```bash
cd server && npm install && npm run dev
# open http://localhost:8080
```

## Architecture

```
server/            Node.js (Express 5, built-in node:sqlite)
  src/services/    plex.js (Plex API + plex.tv PIN login), iptv.js (Xtream/M3U sync),
                   epg.js (streaming XMLTV parser), transcoder.js (ffmpeg HLS sessions + HW accel),
                   recorder.js (DVR scheduler), proxy.js (signed stream proxy)
  src/routes/      /api/plex, /api/iptv, /api/stream
web/               No-build browser app (ES modules) + hls.js + mpegts.js
```

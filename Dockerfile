# syntax=docker/dockerfile:1
FROM node:22-bookworm-slim AS deps
WORKDIR /app/server
COPY server/package.json server/package-lock.json* ./
RUN npm ci --omit=dev 2>/dev/null || npm install --omit=dev && npm cache clean --force

FROM node:22-bookworm-slim
ARG TARGETARCH
ENV DEBIAN_FRONTEND=noninteractive

# jellyfin-ffmpeg: FFmpeg build with VAAPI, Intel QSV (oneVPL + bundled iHD driver), NVENC/NVDEC and AMF support.
# Falls back to Debian's ffmpeg (VAAPI + NVENC) if the Jellyfin repository is unavailable.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg tini \
 && install -d /etc/apt/keyrings \
 && ( curl -fsSL https://repo.jellyfin.org/jellyfin_team.gpg.key | gpg --dearmor -o /etc/apt/keyrings/jellyfin.gpg \
      && echo "deb [signed-by=/etc/apt/keyrings/jellyfin.gpg] https://repo.jellyfin.org/debian bookworm main" > /etc/apt/sources.list.d/jellyfin.list \
      && apt-get update \
      && apt-get install -y --no-install-recommends jellyfin-ffmpeg7 \
      && ln -sf /usr/lib/jellyfin-ffmpeg/ffmpeg /usr/local/bin/ffmpeg \
      && ln -sf /usr/lib/jellyfin-ffmpeg/ffprobe /usr/local/bin/ffprobe ) \
    || ( echo "jellyfin-ffmpeg unavailable, using Debian ffmpeg" \
      && rm -f /etc/apt/sources.list.d/jellyfin.list && apt-get update \
      && apt-get install -y --no-install-recommends ffmpeg ) \
 && (apt-get install -y --no-install-recommends mesa-va-drivers vainfo || true) \
 && apt-get purge -y gnupg && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=deps /app/server/node_modules ./server/node_modules
COPY server ./server
COPY web ./web

ENV NODE_ENV=production \
    PORT=8080 \
    DATA_DIR=/config \
    RECORDINGS_DIR=/recordings \
    TRANSCODE_DIR=/transcode \
    FFMPEG_PATH=ffmpeg \
    FFPROBE_PATH=ffprobe \
    NVIDIA_DRIVER_CAPABILITIES=compute,video,utility

VOLUME ["/config", "/recordings", "/transcode"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD curl -fsS http://127.0.0.1:${PORT}/api/health || exit 1
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "--no-warnings=ExperimentalWarning", "server/src/index.js"]

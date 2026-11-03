#!/bin/sh
# Lumen container entrypoint: optional PUID/PGID (like linuxserver.io images) + GPU group access.
set -e

PUID="${PUID:-0}"
PGID="${PGID:-0}"

for d in "${DATA_DIR:-/config}" "${RECORDINGS_DIR:-/recordings}" "${TRANSCODE_DIR:-/transcode}"; do
  mkdir -p "$d"
done

if [ "$PUID" = "0" ] && [ "$PGID" = "0" ]; then
  exec "$@"
fi

echo "[lumen] running as uid=$PUID gid=$PGID"
chown "$PUID:$PGID" "${DATA_DIR:-/config}" "${TRANSCODE_DIR:-/transcode}" 2>/dev/null || true
chown "$PUID:$PGID" "${RECORDINGS_DIR:-/recordings}" 2>/dev/null || true
# Fix ownership of existing files from earlier root runs (top level of /config only is cheap).
find "${DATA_DIR:-/config}" -maxdepth 1 ! -user "$PUID" -exec chown "$PUID:$PGID" {} + 2>/dev/null || true

# Give the app access to GPU devices by joining whatever groups own them.
GROUPS_LIST="$PGID"
for dev in /dev/dri/renderD* /dev/dri/card* /dev/nvidia*; do
  [ -e "$dev" ] || continue
  gid=$(stat -c '%g' "$dev")
  case ",$GROUPS_LIST," in *",$gid,"*) ;; *) GROUPS_LIST="$GROUPS_LIST,$gid" ;; esac
done

export HOME="${DATA_DIR:-/config}"
exec setpriv --reuid="$PUID" --regid="$PGID" --groups="$GROUPS_LIST" --inh-caps=-all -- "$@"

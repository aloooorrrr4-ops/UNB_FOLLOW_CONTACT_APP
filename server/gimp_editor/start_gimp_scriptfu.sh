#!/usr/bin/env bash
set -euo pipefail

HOST="${UNB_GIMP_HOST:-127.0.0.1}"
PORT="${UNB_GIMP_PORT:-10008}"
LOG="${UNB_GIMP_LOG:-/tmp/unb-gimp-scriptfu.log}"

GIMP_BIN="${GIMP_BIN:-}"
if [[ -z "$GIMP_BIN" ]]; then
  if command -v gimp-console >/dev/null 2>&1; then
    GIMP_BIN="$(command -v gimp-console)"
  elif command -v gimp-console-3.0 >/dev/null 2>&1; then
    GIMP_BIN="$(command -v gimp-console-3.0)"
  elif command -v gimp >/dev/null 2>&1; then
    GIMP_BIN="$(command -v gimp)"
  else
    echo "GIMP console binary not found" >&2
    exit 127
  fi
fi

exec "$GIMP_BIN" \
  --console-messages \
  --batch-interpreter=plug-in-script-fu-eval \
  --batch="(plug-in-script-fu-server 1 \"$HOST\" $PORT \"$LOG\")"

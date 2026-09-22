#!/usr/bin/env bash
set -euo pipefail
BASE="${1:-http://127.0.0.1:18083}"

echo "=== health ==="
curl --fail --show-error --silent "$BASE/health"
echo

echo "=== capabilities ==="
curl --fail --show-error --silent "$BASE/api/editor/capabilities"
echo

echo "=== systemd ==="
if command -v systemctl >/dev/null 2>&1; then
  systemctl --no-pager --full status unb-gimp-scriptfu.service || true
  systemctl --no-pager --full status unb-pro-editor-api.service || true
fi

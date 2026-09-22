#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${ROOT_DIR:-/opt/unb-pro-editor}"
ENGINE_DIR="$ROOT_DIR/gimp_editor"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo bash install_staging.sh" >&2
  exit 1
fi

echo "[1/7] Installing OS packages..."
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y gimp python3 python3-venv python3-pip curl

echo "[2/7] Verifying GIMP 3..."
GIMP_VERSION="$(gimp --version 2>/dev/null || gimp-console --version 2>/dev/null || true)"
echo "$GIMP_VERSION"
if ! grep -Eq 'GIMP (3|4)\.' <<<"$GIMP_VERSION"; then
  echo "GIMP 3.x is required. Installed version: $GIMP_VERSION" >&2
  exit 2
fi

echo "[3/7] Installing engine files..."
mkdir -p "$ENGINE_DIR" /var/lib/unb-pro-editor
cp "$SOURCE_DIR/app.py" "$ENGINE_DIR/app.py"
cp "$SOURCE_DIR/requirements.txt" "$ENGINE_DIR/requirements.txt"
cp "$SOURCE_DIR/start_gimp_scriptfu.sh" "$ENGINE_DIR/start_gimp_scriptfu.sh"
chmod 755 "$ENGINE_DIR/start_gimp_scriptfu.sh"

echo "[4/7] Creating virtual environment..."
python3 -m venv "$ENGINE_DIR/.venv"
"$ENGINE_DIR/.venv/bin/pip" install --upgrade pip wheel
"$ENGINE_DIR/.venv/bin/pip" install -r "$ENGINE_DIR/requirements.txt"

echo "[5/7] Installing systemd units..."
cp "$SOURCE_DIR/systemd/unb-gimp-scriptfu.service" /etc/systemd/system/
cp "$SOURCE_DIR/systemd/unb-pro-editor-api.service" /etc/systemd/system/
systemctl daemon-reload

echo "[6/7] Starting services..."
systemctl enable --now unb-gimp-scriptfu.service
sleep 3
systemctl enable --now unb-pro-editor-api.service
sleep 3

echo "[7/7] Health check..."
curl --fail --show-error --silent http://127.0.0.1:18085/health
echo
curl --fail --show-error --silent http://127.0.0.1:18085/api/editor/capabilities
echo
echo "UNB Pro Editor staging engine is listening on port 18085."

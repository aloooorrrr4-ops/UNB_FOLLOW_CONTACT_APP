#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${ROOT_DIR:-/opt/unb-pro-editor}"
ENGINE_DIR="$ROOT_DIR/gimp_editor"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="/etc/unb-pro-editor.env"
SERVICE_USER="unb-editor"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo bash install_staging.sh" >&2
  exit 1
fi

echo "[1/8] Installing OS packages..."
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-venv python3-pip curl openssl

echo "[2/8] Verifying GIMP 3..."
GIMP_VERSION="$(gimp --version 2>/dev/null || gimp-console --version 2>/dev/null || true)"
echo "$GIMP_VERSION"
if ! grep -Eq 'GIMP (3|4).' <<<"$GIMP_VERSION"; then
  echo "GIMP 3.x is required. Installed version: $GIMP_VERSION" >&2
  exit 2
fi

echo "[3/8] Creating unprivileged service account..."
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --home /var/lib/unb-pro-editor --shell /usr/sbin/nologin "$SERVICE_USER"
fi
mkdir -p "$ENGINE_DIR" /var/lib/unb-pro-editor
chown -R "$SERVICE_USER:$SERVICE_USER" "$ROOT_DIR" /var/lib/unb-pro-editor

echo "[4/8] Installing engine files..."
cp "$SOURCE_DIR/app.py" "$ENGINE_DIR/app.py"
cp "$SOURCE_DIR/requirements.txt" "$ENGINE_DIR/requirements.txt"
cp "$SOURCE_DIR/start_gimp_scriptfu.sh" "$ENGINE_DIR/start_gimp_scriptfu.sh"
chmod 755 "$ENGINE_DIR/start_gimp_scriptfu.sh"
chown -R "$SERVICE_USER:$SERVICE_USER" "$ROOT_DIR" /var/lib/unb-pro-editor

echo "[5/8] Creating virtual environment..."
if [[ ! -x "$ENGINE_DIR/.venv/bin/python" ]]; then
  python3 -m venv "$ENGINE_DIR/.venv"
fi
"$ENGINE_DIR/.venv/bin/pip" install --upgrade pip wheel
"$ENGINE_DIR/.venv/bin/pip" install -r "$ENGINE_DIR/requirements.txt"
chown -R "$SERVICE_USER:$SERVICE_USER" "$ENGINE_DIR/.venv"

echo "[6/8] Configuring editor authentication..."
if [[ ! -f "$ENV_FILE" ]]; then
  KEY="$(openssl rand -hex 32)"
  umask 077
  printf 'UNB_EDITOR_API_KEY=%s
' "$KEY" > "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"
chown root:root "$ENV_FILE"

echo "[7/8] Installing and starting services..."
cp "$SOURCE_DIR/systemd/unb-gimp-scriptfu.service" /etc/systemd/system/
cp "$SOURCE_DIR/systemd/unb-pro-editor-api.service" /etc/systemd/system/
systemctl daemon-reload
docker rm -f unb-stage-ai 2>/dev/null || true
systemctl disable --now unb-pro-editor-api.service 2>/dev/null || true
systemctl enable unb-gimp-scriptfu.service
systemctl restart unb-gimp-scriptfu.service
sleep 3
systemctl enable unb-pro-editor-api.service
systemctl restart unb-pro-editor-api.service
sleep 3

echo "[8/8] Health check..."
curl --fail --show-error --silent http://127.0.0.1:18083/health
echo
API_KEY="$(sed -n 's/^UNB_EDITOR_API_KEY=//p' "$ENV_FILE")"
curl --fail --show-error --silent   -H "X-UNB-Editor-Key: $API_KEY"   http://127.0.0.1:18083/api/editor/capabilities
echo
echo
echo "UNB Pro Editor is listening on port 18083."
echo "Authentication is enabled."
echo "To view the Android API key as root:"
echo "  sed -n 's/^UNB_EDITOR_API_KEY=//p' $ENV_FILE"

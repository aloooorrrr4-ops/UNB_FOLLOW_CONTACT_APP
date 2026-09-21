#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

mkdir -p /opt/unb-stage-ai/cache
mkdir -p /opt/unb-stage-ai/rembg
mkdir -p /opt/unb-stage-ai/easyocr

echo "Stopping older experimental AI containers to free RAM..."
docker rm -f unb-background 2>/dev/null || true
docker rm -f unb-facepaste 2>/dev/null || true
docker rm -f unb-stage-ai 2>/dev/null || true

echo "Building UNB staged AI service..."
docker build -t unb-stage-ai:latest .

echo "Starting service on port 18083..."
docker run -d \
  --name unb-stage-ai \
  --restart unless-stopped \
  --memory=3g \
  --cpus=2 \
  -e OMP_NUM_THREADS=2 \
  -e MKL_NUM_THREADS=2 \
  -p 18083:8000 \
  -v /opt/unb-stage-ai/cache:/root/.cache \
  -v /opt/unb-stage-ai/rembg:/root/.u2net \
  -v /opt/unb-stage-ai/easyocr:/root/.EasyOCR \
  unb-stage-ai:latest

echo "Waiting for API health..."
healthy=0
for i in $(seq 1 30); do
  if curl -fsS --max-time 5 http://127.0.0.1:18083/health >/tmp/unb_health.json 2>/dev/null; then
    healthy=1
    break
  fi
  sleep 4
done

echo "===== HEALTH ====="
if [ "$healthy" = "1" ]; then
  cat /tmp/unb_health.json
  echo
else
  echo "Health check did not become ready within 120 seconds."
fi

echo
echo "===== STATUS ====="
docker ps -a --filter name=unb-stage-ai

echo
echo "===== LOGS ====="
docker logs --tail 80 unb-stage-ai

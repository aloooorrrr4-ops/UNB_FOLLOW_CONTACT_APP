#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "Stopping old OCR container..."
docker rm -f unb-stage-ai 2>/dev/null || true

echo "Building lightweight OCR service..."
docker build -t unb-ocr-lite:latest .

echo "Starting lightweight OCR service on port 18083..."
docker run -d \
  --name unb-stage-ai \
  --restart unless-stopped \
  --memory=1g \
  --cpus=2 \
  -e OMP_NUM_THREADS=2 \
  -e MKL_NUM_THREADS=2 \
  -p 18083:8000 \
  unb-ocr-lite:latest

echo "Waiting for API..."
for i in $(seq 1 30); do
  if curl -fsS --max-time 5 http://127.0.0.1:18083/health; then
    echo
    echo "=== READY ==="
    exit 0
  fi
  sleep 4
done

echo "=== FAILED TO START ==="
docker logs --tail 100 unb-stage-ai
exit 1

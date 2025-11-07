#!/bin/bash

API_URL="http://localhost:8080/api/stream/hls/start"
INPUT_DIR="recordings"

idx=1

# ลูปเฉพาะไฟล์ .mp4 ชั้นเดียวเท่านั้น
for file in "$INPUT_DIR"/*.mp4; do
  if [ -f "$file" ]; then
    cam_name="cam${idx}"
    payload=$(printf '{"rtspUrl":"rtsp://127.0.0.1:8554/%s","streamName":"%s"}' "$cam_name" "$cam_name")

    echo "[API] Starting stream: $cam_name <= $(basename "$file")"
    curl -s -X POST "$API_URL" \
         -H "Content-Type: application/json" \
         -d "$payload" >/dev/null && echo "[OK] $cam_name started" || echo "[FAIL] $cam_name"

    idx=$((idx + 1))
    sleep 0.5
  fi
done

echo "[API] ✅ Done — started $((idx - 1)) streams from folder '$INPUT_DIR'"
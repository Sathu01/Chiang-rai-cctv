#!/bin/bash

API_URL="http://localhost:8080/api/stream/hls/start"

# ---- กำหนดลิงก์ไว้ใน array ----
rtsp_links=(
"rtsp://Police:PoliceCR1234$@183.89.209.110:4442/1/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4442/2/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4442/3/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4442/4/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4442/5/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4442/6/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4442/7/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4442/8/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4441/1/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4441/2/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4441/3/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4441/4/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4441/5/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4441/6/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4441/7/2"
"rtsp://Police:PoliceCR1234$@183.89.209.110:4441/8/2"

)

idx=1

# ---- ยิง API ทีละลิงก์ ----
for rtsp in "${rtsp_links[@]}"; do
  cam_name="cam${idx}"
  payload=$(printf '{"rtspUrl":"%s","streamName":"%s"}' "$rtsp" "$cam_name")

  echo "[API] Starting stream: $cam_name <= $rtsp"
  if curl -s -X POST "$API_URL" -H "Content-Type: application/json" -d "$payload" >/dev/null; then
    echo "[OK] $cam_name started"
  else
    echo "[FAIL] $cam_name"
  fi

  idx=$((idx + 1))
  sleep 0.5
done

echo "[API] ✅ Done — started $((idx - 1)) streams"

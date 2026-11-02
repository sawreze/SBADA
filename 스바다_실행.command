#!/bin/bash
# ===== 스바다 개발환경 실행: 서버 창 + iOS 시뮬레이터 앱 창을 각각 연다 =====
ROOT="$(cd "$(dirname "$0")" && pwd)"
LOG="$ROOT/logs/launch.log"; mkdir -p "$ROOT/logs"
exec > >(tee "$LOG") 2>&1
echo "[$(date +%H:%M:%S)] 시작: $ROOT"
echo "Xcode: $(xcode-select -p)"
ls /Applications/Xcode.app/Contents/Developer/Applications 2>&1
SIM="$(find /Applications/Xcode.app/Contents/Developer/Applications -maxdepth 1 -name 'Simulator.app' 2>/dev/null)"
echo "Simulator.app: ${SIM:-없음}"
# 1) 서버: 이미 켜져 있으면 건너뜀
if curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health; then
  echo "서버 이미 켜져 있음"
else
  osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT' && scripts/run-server.sh 2>&1 | tee logs/server.log\""
  echo "서버 창 열림 → 켜질 때까지 대기"
  for i in $(seq 1 60); do curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health && break; sleep 3; done
fi
curl -s -m 3 http://127.0.0.1:8097/api/health; echo
# 2) 시뮬레이터 창 + iOS 앱
[ -n "$SIM" ] && open "$SIM"
osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT' && scripts/run-ios.sh\""
echo "iOS 앱 창 열림"
echo "[$(date +%H:%M:%S)] DONE"

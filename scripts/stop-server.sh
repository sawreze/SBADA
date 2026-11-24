#!/usr/bin/env bash
# ===== 스바다 서버 종료: 메인(8097) · 결제(8098) · 물품(8099). 허브(Kafka)는 그대로 둔다(끄기: brew services stop kafka) =====
FOUND=0
for port in 8097 8098 8099; do
  PIDS="$(lsof -ti tcp:$port -sTCP:LISTEN 2>/dev/null)"
  [ -n "$PIDS" ] && { echo "포트 $port 서버 PID $PIDS 종료"; kill $PIDS; FOUND=1; }
done
[ "$FOUND" = 0 ] && echo "실행 중인 서버가 없습니다."
true

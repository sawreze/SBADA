#!/usr/bin/env bash
# ===== 8097 포트에서 실행 중인 스바다 서버 종료 =====
PIDS="$(lsof -ti tcp:8097 -sTCP:LISTEN 2>/dev/null)"
if [ -z "$PIDS" ]; then echo "실행 중인 서버가 없습니다."; else echo "서버 PID $PIDS 종료"; kill $PIDS; fi

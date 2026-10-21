#!/usr/bin/env bash
# ===== 전체 점검: 서버 테스트 -> 서버 실행(백그라운드) -> 로컬호스트 smoke 테스트 =====
# 서버는 계속 실행된다(종료: stop-server.sh). 서버 로그: logs/2-server.log
source "$(dirname "$0")/_common.sh"
echo "==== 1/3 서버 테스트 ===="
( cd "$ROOT/sbada-server" && chmod +x gradlew && ./gradlew test --console=plain > "$LOGS/1-test.log" 2>&1 ) \
  || { echo "테스트 실패 - logs/1-test.log 확인"; exit 1; }
grep -E "PASSED|FAILED|BUILD" "$LOGS/1-test.log"
echo; echo "==== 2/3 서버 실행 (포트 8097) ===="
if lsof -ti tcp:8097 -sTCP:LISTEN >/dev/null 2>&1; then echo "이미 실행 중입니다."; else
  ( cd "$ROOT/sbada-server" && nohup ./gradlew bootRun --console=plain --args="--spring.profiles.active=dev" > "$LOGS/2-server.log" 2>&1 & )
fi
for i in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health")" = "200" ] && break
  echo "서버 기다리는 중... $i"; sleep 5
done
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health")" = "200" ] || { echo "서버가 켜지지 않았습니다 - logs/2-server.log 확인"; exit 1; }
echo; echo "==== 3/3 smoke 테스트 ===="
"$(dirname "$0")/smoke-test.sh"

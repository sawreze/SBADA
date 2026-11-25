#!/bin/bash
# ===== 시연용 계정 만들기 (2026-11-25 사용자 요청: 만든 기능을 모든 경우까지 한 계정에서 보여 주기) =====
#  demo@sbada.test / sbada1234 (로컬 시연 전용) — 교환 20건이 경우별 상태로 들어 있다(받은 요청 · 추가요금 · 결제 대기 · 배송 · 완료 · 기간 만료 …)
#  상대 계정: neighbor1~3@sbada.test (같은 비밀번호). 모두 서울 강남구 역삼동.
#  다시 실행하면 예전 시연 계정은 '지난 시연' 으로 정리하고 깨끗하게 새로 만든다(물건 49개 + AI 가격 분석이라 몇 분 걸림).
#  서버가 꺼져 있거나 옛 코드면 여기서 켠다(메인은 dev 프로필 — 시연 도구가 dev 에서만 켜짐). Kafka(허브)가 필요하다.
#  설명: docs/handoff/10_시연계정.md     기록: logs/demo-account.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
"$ROOT/scripts/stop-stale.sh" >/dev/null 2>&1
if ! curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health; then
  echo "메인 서버 켜는 중(처음엔 1~2분) → logs/server.log"
  ( cd "$ROOT/sbada-server" && nohup ./gradlew bootRun --args='--spring.profiles.active=dev --spring.h2.console.enabled=false' > "$ROOT/logs/server.log" 2>&1 & )
  for i in $(seq 1 90); do curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health && break; sleep 2; done
  if ! curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health; then
    echo "메인 서버가 안 켜져요 → logs/server.log 를 확인해 주세요"; read -r -p "Enter 를 누르면 닫혀요"; exit 1
  fi
fi
"$ROOT/scripts/start-msa-bg.sh" --wait
python3 "$ROOT/scripts/make_demo_account.py" "$@" 2>&1 | tee "$ROOT/logs/demo-account.log"
open "$ROOT/docs/handoff/10_시연계정.md" 2>/dev/null
read -r -p "Enter 를 누르면 이 창이 닫혀요"

#!/bin/bash
# ===== 직접 손으로 확인하는 테스트 계정 만들기 (2026-11-25) =====
#  tester1@sbada.test · tester2@sbada.test · tester3@sbada.test / 비밀번호 sbada1234 (로컬 시연 전용) — 셋 다 서울 강남구 역삼동, 물건 2~3개씩, 지갑 10만 원(가상)
#  admin 과 달리 교환이 '바로 성사' 되지 않아서 요청 → 수락 → 결제 → 수령 확인을 직접 해 볼 수 있다.
#  서버(메인 · 물품 · 결제)가 켜져 있어야 한다 → 꺼져 있으면 먼저 스바다_실행.command
#  다시 실행해도 안전(계정은 그대로, 물건이 모자란 계정만 채움, 지갑은 10만 원으로).
#  처음 상태로 되돌리기: 터미널에서  ./테스트계정_만들기.command --새로
#  확인 순서: docs/handoff/09_직접_확인_체크리스트.md     기록: logs/test-accounts.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
"$ROOT/scripts/stop-stale.sh" >/dev/null 2>&1
if ! curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health; then
  echo "메인 서버가 꺼져 있어요 → 스바다_실행.command 를 먼저 실행해 주세요"; read -r -p "Enter 를 누르면 닫혀요"; exit 1
fi
"$ROOT/scripts/start-msa-bg.sh" --wait
python3 "$ROOT/scripts/make_test_accounts.py" "$@" 2>&1 | tee "$ROOT/logs/test-accounts.log"
open "$ROOT/docs/handoff/09_직접_확인_체크리스트.md" 2>/dev/null
read -r -p "Enter 를 누르면 이 창이 닫혀요"

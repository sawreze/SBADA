#!/bin/bash
# 금융 백엔드 샌드박스 테스트(실제 돈 X): 서버 두 개를 새 코드로 다시 켜고 → 결제 시나리오를 실제 API 로 끝까지 실행.
#  1원 인증 → 교환 추가 금액 → 안전결제(보관) → 수락 → 거래 완료(정산) → 출금 → 지갑 결제 · 취소 · 자동 환불 → 알림
# 기록: logs/sandbox-payment.log  (토큰 · 비밀번호 · 계좌번호 전체는 기록하지 않음)
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/sandbox-payment.log"; B=http://127.0.0.1:8097; P=http://127.0.0.1:8098
{
echo "[$(date +%H:%M:%S)] 시작"
for port in 8097 8098; do PID="$(lsof -ti tcp:$port -sTCP:LISTEN 2>/dev/null)"; [ -n "$PID" ] && { echo "포트 $port 종료(PID $PID)"; kill $PID; }; done
sleep 3
( cd "$ROOT/sbada-server" && chmod +x gradlew && nohup ./gradlew bootRun --console=plain --args="--spring.profiles.active=dev" > "$ROOT/logs/server.log" 2>&1 & )
for i in $(seq 1 80); do curl -s -o /dev/null -m 2 $B/api/health && break; sleep 3; done
curl -s -m 3 -o /dev/null $B/api/health && echo "메인 서버 OK" || { echo "메인 서버가 안 켜짐 → logs/server.log"; tail -40 "$ROOT/logs/server.log"; exit 1; }
grep -E "Successfully applied|Current version of schema" "$ROOT/logs/server.log" | tail -2
( cd "$ROOT/sbada-payment" && chmod +x gradlew && nohup ./gradlew bootRun --console=plain > "$ROOT/logs/payment.log" 2>&1 & )
for i in $(seq 1 80); do curl -s -o /dev/null -m 2 $P/api/payment/health && break; sleep 3; done
curl -s -m 3 $P/api/payment/health && echo || { echo "결제 서비스가 안 켜짐 → logs/payment.log"; tail -40 "$ROOT/logs/payment.log"; exit 1; }
grep -E "Successfully applied|Current version of schema" "$ROOT/logs/payment.log" | tail -2
python3 "$ROOT/scripts/sandbox_payment_test.py"
echo "[$(date +%H:%M:%S)] DONE (서버는 켜 둔 채로 끝남)"
} 2>&1 | tee "$L"
read -r -p "Enter 를 누르면 이 창이 닫혀요"

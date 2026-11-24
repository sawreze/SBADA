#!/usr/bin/env bash
# ===== MSA 뒷단을 백그라운드로 켠다: 허브(Kafka 9092) → 물품 서버(8099 · gRPC 9099) → 결제 서버(8098 · gRPC 9098) =====
#  메인 서버(8097 · gRPC 9097)는 각 실행 스크립트가 터미널 창으로 따로 켠다(로그를 눈으로 보려고).
#  이미 켜져 있는 것은 그대로 둔다. 기록: logs/item.log, logs/payment.log
#  사용: scripts/start-msa-bg.sh [--wait]   (--wait: 물품 · 결제 서버가 응답할 때까지 기다림)
source "$(dirname "$0")/_common.sh"
WAIT="$1"
up9092(){ nc -z 127.0.0.1 9092 >/dev/null 2>&1; }

# 1) 허브(Kafka). 없어도 서버는 켜진다 — 이벤트는 각 서버 outbox 표에 쌓였다가 Kafka 가 켜지면 보내진다
if up9092; then echo "허브(Kafka) OK"
elif command -v brew >/dev/null 2>&1 && brew list kafka >/dev/null 2>&1; then
  echo "허브(Kafka) 켜는 중…"; brew services start kafka >/dev/null 2>&1
  for i in $(seq 1 40); do up9092 && break; sleep 1; done
  up9092 && echo "허브(Kafka) OK" || echo "허브(Kafka)가 안 떠요 → 'Kafka_설치_실행.command' 로 확인(이벤트는 outbox 에 쌓여 있다가 나중에 보내져요)"
else
  echo "허브(Kafka)가 설치돼 있지 않아요 → 'Kafka_설치_실행.command' 를 한 번 실행해 주세요(그 전까지 알림 · 조회수 같은 이벤트는 대기)"
fi

# 2) 물품 서버 / 3) 결제 서버
start(){ # 이름 폴더 health주소 로그파일
  local name="$1" dir="$2" health="$3" log="$4"
  if curl -s -o /dev/null -m 2 "$health"; then echo "$name 이미 켜져 있음"; return; fi
  ( cd "$ROOT/$dir" && chmod +x gradlew && nohup ./gradlew bootRun --console=plain > "$LOGS/$log" 2>&1 & )
  echo "$name 켜는 중 → logs/$log"
}
start "물품 서버(8099)" sbada-item    http://127.0.0.1:8099/api/item-service/health item.log
start "결제 서버(8098)" sbada-payment http://127.0.0.1:8098/api/payment/health     payment.log

if [ "$WAIT" = "--wait" ]; then
  for pair in "물품 서버|http://127.0.0.1:8099/api/item-service/health|item.log" "결제 서버|http://127.0.0.1:8098/api/payment/health|payment.log"; do
    IFS='|' read -r n h l <<<"$pair"
    for i in $(seq 1 90); do curl -s -o /dev/null -m 2 "$h" && break; sleep 2; done
    if curl -s -o /dev/null -m 2 "$h"; then echo "  $n OK"; else echo "  $n 가 안 떠요 → logs/$l"; fi
  done
fi

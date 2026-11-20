#!/usr/bin/env bash
# 결제 서비스를 백그라운드로 켠다(이미 켜져 있으면 그대로). 기록: logs/payment.log
source "$(dirname "$0")/_common.sh"
if curl -s -o /dev/null -m 2 http://127.0.0.1:8098/api/payment/health; then echo "결제 서비스 이미 켜져 있음"; exit 0; fi
( cd "$ROOT/sbada-payment" && chmod +x gradlew && nohup ./gradlew bootRun --console=plain > "$LOGS/payment.log" 2>&1 & )
echo "결제 서비스 켜는 중(포트 8098) → logs/payment.log"

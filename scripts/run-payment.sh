#!/usr/bin/env bash
# ===== 스바다 결제 마이크로서비스 실행 (포트 8098, 시연용 — 실제 돈은 움직이지 않음). 종료: Ctrl+C =====
# 앱은 메인 서버(8097)로만 요청하고, 메인 서버가 /api/wallet/** · /api/payments/** 를 이 서비스로 넘긴다.
source "$(dirname "$0")/_common.sh"
cd "$ROOT/sbada-payment" && chmod +x gradlew
echo " 결제 서비스: http://127.0.0.1:8098/api/payment/health   DB: sbada-payment/data/payment"
./gradlew bootRun --console=plain

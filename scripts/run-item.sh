#!/usr/bin/env bash
# ===== 스바다 물품 서버 실행 (REST 8099 · gRPC 9099 · GraphQL /graphql). 종료: Ctrl+C =====
# 앱은 메인 서버(8097)로만 요청하고, 메인 서버가 물품 · 사진 · AI · 추천 · 찜 · /graphql 을 이 서버로 넘긴다.
# 처음 켜질 때 DB 가 비어 있으면 메인 서버의 예전 물품 데이터를 한 번 옮겨 온다(메인 서버가 켜져 있어야 함, 30초마다 재시도).
source "$(dirname "$0")/_common.sh"
cd "$ROOT/sbada-item" && chmod +x gradlew
echo " 물품 서버: http://127.0.0.1:8099/api/item-service/health   DB: sbada-item/data/item"
./gradlew bootRun --console=plain

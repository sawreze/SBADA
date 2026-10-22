#!/usr/bin/env bash
# ===== 서버 단위·통합 테스트 (메모리 DB, AI 미사용) =====
source "$(dirname "$0")/_common.sh"
cd "$ROOT/sbada-server" && chmod +x gradlew && ./gradlew test --console=plain
echo; echo "결과 보고서: $ROOT/sbada-server/build/reports/tests/test/index.html"

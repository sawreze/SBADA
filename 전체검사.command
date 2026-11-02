#!/bin/bash
# 서버 테스트(gradle test) + 앱 검사·테스트·웹 빌드. 기록: logs/full-check.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/full-check.log"
{
echo "[$(date +%H:%M:%S)] 서버 테스트"
cd "$ROOT/sbada-server" && ./gradlew test --console=plain 2>&1 | grep -E "FAILED|Test.*>|BUILD|tests completed|error:" | tail -40; echo "SERVER_TEST_EXIT=${PIPESTATUS[0]}"
echo "서버 테스트 결과 파일 수: $(ls build/test-results/test/*.xml 2>/dev/null | wc -l) · 실패: $(grep -l "<failure" build/test-results/test/*.xml 2>/dev/null | wc -l)"
echo "[$(date +%H:%M:%S)] 앱"
cd "$ROOT/sbada_app" && flutter pub get >/dev/null
flutter analyze; echo "ANALYZE_EXIT=$?"
flutter test 2>&1 | tail -5; echo "TEST_EXIT=${PIPESTATUS[0]}"
flutter build web --release --base-href /app/ --no-web-resources-cdn --dart-define=API_BASE_URL=http://127.0.0.1:8097 2>&1 | tail -3; echo "BUILD_EXIT=${PIPESTATUS[0]}"
echo "[$(date +%H:%M:%S)] DONE"
} > "$L" 2>&1

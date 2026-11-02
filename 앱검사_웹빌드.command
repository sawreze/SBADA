#!/bin/bash
# 앱 검사(analyze) · 테스트 · 웹 빌드를 한 번에. 기록: logs/check-web.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/check-web.log"
{
echo "[$(date +%H:%M:%S)] 시작"
cd "$ROOT/sbada_app" && flutter pub get >/dev/null
flutter analyze; echo "ANALYZE_EXIT=$?"
flutter test 2>&1 | tail -5; echo "TEST_EXIT=${PIPESTATUS[0]}"
flutter build web --release --base-href /app/ --no-web-resources-cdn --dart-define=API_BASE_URL=http://127.0.0.1:8097 2>&1 | tail -3; echo "BUILD_EXIT=${PIPESTATUS[0]}"
echo "[$(date +%H:%M:%S)] DONE"
} > "$L" 2>&1

#!/usr/bin/env bash
# ===== 앱을 웹으로 빌드 → 서버가 /app/ 로 내려 줌(브라우저 테스트 화면 http://127.0.0.1:8097/test.html) =====
source "$(dirname "$0")/_common.sh"
cd "$ROOT/sbada_app"
flutter build web --release --base-href /app/ --no-web-resources-cdn --dart-define=API_BASE_URL=http://127.0.0.1:8097
echo; echo "완료: 브라우저에서 http://127.0.0.1:8097/test.html 를 새로고침하세요."

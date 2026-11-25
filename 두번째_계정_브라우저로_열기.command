#!/bin/bash
# ===== 두 계정을 동시에 보여 주기: 시뮬레이터(demo) + 브라우저(상대 계정) (2026-11-25 사용자 요청) =====
#  시뮬레이터는 그대로 두고, 같은 맥 서버에 붙는 웹 앱을 브라우저로 연다 → 브라우저에서 neighbor1@sbada.test 로 로그인.
#  한쪽에서 교환 요청 · 수락 · 추가요금 요청을 하면 다른 쪽 채팅 카드 · 🔔 알림이 실시간(WebSocket)으로 바로 바뀐다.
#  웹 빌드가 앱 코드보다 오래됐으면 먼저 다시 빌드한다(1~2분). 토스 결제창은 앱(시뮬레이터) 쪽에서만 열린다.
#  기록: logs/second-screen.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
exec > >(tee "$ROOT/logs/second-screen.log") 2>&1
if ! curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health; then
  echo "서버가 꺼져 있어요 → 스바다_실행.command 를 먼저 실행해 주세요"; read -r -p "Enter 를 누르면 닫혀요"; exit 1
fi
WEB="$ROOT/sbada_app/build/web/main.dart.js"
NEWEST="$(find "$ROOT/sbada_app/lib" -name '*.dart' -newer "$WEB" 2>/dev/null | head -1)"
if [ ! -f "$WEB" ] || [ -n "$NEWEST" ]; then
  echo "웹 앱 빌드 중(앱 코드가 바뀜, 1~2분)…"
  cd "$ROOT/sbada_app" && flutter pub get >/dev/null && flutter build web --release --base-href /app/ --no-web-resources-cdn \
    --dart-define=API_BASE_URL=http://127.0.0.1:8097 2>&1 | tail -3
fi
URL="http://127.0.0.1:8097/app/"
if [ -d "/Applications/Google Chrome.app" ]; then
  # 시크릿 창: 브라우저에 남아 있던 다른 로그인과 섞이지 않게
  open -na "Google Chrome" --args --incognito --window-size=430,900 "$URL"
else
  open -a Safari "$URL"
fi
echo "브라우저에서 neighbor1@sbada.test / sbada1234 로 로그인하세요(시뮬레이터는 demo@sbada.test)."

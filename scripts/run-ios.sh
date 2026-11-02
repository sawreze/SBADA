#!/usr/bin/env bash
# ===== Mac: iOS 시뮬레이터에서 앱 실행 (Apple 계정 없어도 됨) =====
# 서버 주소(SBADA_SERVER)를 고른다:
#   ① Mac 에서 서버도 켠 경우(run-server.sh): 그대로 실행 → http://127.0.0.1:8097
#      (Mac 에는 학습한 AI 서버가 없어 가격 분석은 Google Gemma 또는 기본 계산)
#   ② Windows PC 서버(학습한 Gemma)를 쓰는 경우: SBADA_SERVER=http://PC의IP:8097 scripts/run-ios.sh
#      PC 서버가 같은 와이파이에서 접속을 받도록 열려 있어야 한다(지금 기본값은 PC 자신만 접속 가능)
# 처음 한 번: Xcode 설치 → Xcode 한 번 열어 추가 구성요소 설치 → sudo xcodebuild -runFirstLaunch
#            시뮬레이터 런타임이 없으면 Xcode > Settings > Components 에서 iOS 받기
# 카카오 로그인까지 쓰려면: sbada_app/ios/Flutter/Secrets.xcconfig.example → Secrets.xcconfig 로 복사 후 키 입력
source "$(dirname "$0")/_common.sh"
cd "$ROOT/sbada_app"
SERVER="${SBADA_SERVER:-http://127.0.0.1:8097}"
echo "서버: $SERVER"
flutter pub get
# 꺼져 있으면 아이폰 시뮬레이터를 하나 골라 켠다(Xcode 안의 Simulator 앱을 경로로 연다)
DEV_DIR="${DEVELOPER_DIR:-$(xcode-select -p)}"
if ! xcrun simctl list devices booted | grep -q "iPhone"; then
  UDID="$(xcrun simctl list devices available | grep -m1 -E '^ +iPhone' | grep -oE '[0-9A-F-]{36}')"
  if [ -z "$UDID" ]; then
    echo "아이폰 시뮬레이터가 없어요. Xcode > Settings > Components 에서 iOS 를 받은 뒤 다시 실행하세요."; exit 1
  fi
  echo "시뮬레이터 켜는 중: $(xcrun simctl list devices | grep "$UDID" | sed 's/^ *//')"
  xcrun simctl boot "$UDID" 2>/dev/null
fi
# Xcode 27 부터는 Simulator 앱 대신 'Device Hub' 앱이 시뮬레이터 화면을 보여 준다
SIMAPP="$(find /Applications/Xcode.app/Contents -maxdepth 8 -type d \( -iname '*Device*Hub*.app' -o -name 'Simulator.app' \) 2>/dev/null | head -1)"
[ -n "$SIMAPP" ] && open -a "$SIMAPP" || echo "시뮬레이터 화면: Xcode 메뉴 Window > Device Hub 에서 여세요"
# flutter 가 시뮬레이터를 알아볼 때까지 잠깐 기다림
for _ in 1 2 3 4 5 6 7 8 9 10; do
  DEVICE="$(xcrun simctl list devices booted | grep -m1 -E '^ +iPhone' | grep -oE '[0-9A-F-]{36}')"
  [ -n "$DEVICE" ] && flutter devices 2>/dev/null | grep -q "$DEVICE" && break
  sleep 3
done
if [ -z "$DEVICE" ]; then
  echo "iOS 시뮬레이터를 찾지 못했어요. Xcode 를 한 번 열어 추가 구성요소를 설치한 뒤 다시 실행하세요."; exit 1
fi
flutter run -d "$DEVICE" --dart-define=API_BASE_URL="$SERVER" "$@"

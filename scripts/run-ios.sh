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
open -a Simulator
flutter pub get
# 시뮬레이터가 뜰 때까지 잠깐 기다렸다가 iOS 기기를 골라 실행
for _ in 1 2 3 4 5 6 7 8 9 10; do
  DEVICE="$(flutter devices 2>/dev/null | grep -i 'simulator' | head -1 | awk -F'•' '{gsub(/ /,"",$2); print $2}')"
  [ -n "$DEVICE" ] && break
  sleep 3
done
if [ -z "$DEVICE" ]; then
  echo "iOS 시뮬레이터를 찾지 못했어요. Simulator 앱에서 아이폰을 하나 켠 뒤 다시 실행하세요."; exit 1
fi
flutter run -d "$DEVICE" --dart-define=API_BASE_URL="$SERVER" "$@"

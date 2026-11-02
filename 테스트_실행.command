#!/bin/bash
# ===== 스바다 테스트 한 번에: 서버 → Android 에뮬레이터 앱 → iPhone 시뮬레이터 앱 =====
# 기록: logs/test-launch.log (에뮬레이터 자체 기록 logs/emulator.log)
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
LOG="$ROOT/logs/test-launch.log"; exec > >(tee "$LOG") 2>&1
say() { echo "[$(date +%H:%M:%S)] $*"; }
term() { osascript -e "tell application \"Terminal\" to do script \"$1\"" >/dev/null; }
EMU="$ANDROID_SDK/emulator/emulator"; export PATH="$ANDROID_SDK/platform-tools:$PATH"
SDKM="$(ls "$ANDROID_SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1)"

say "1) 서버"
if curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health; then say "서버 이미 켜져 있음"
else
  term "cd '$ROOT' && scripts/run-server.sh 2>&1 | tee logs/server.log"
  for i in $(seq 1 60); do curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health && break; sleep 3; done
fi

say "2) Android 에뮬레이터"
"$EMU" -version 2>&1 | head -2; file "$EMU"; echo "sdkmanager: ${SDKM:-없음}"
AVD="$("$EMU" -list-avds 2>/dev/null | grep -v -i -E 'error|warning|INFO' | head -1)"; say "AVD: ${AVD:-없음}"
boot_emu() {  # $1 = gpu 모드
  say "에뮬레이터 켜는 중: $AVD (gpu=$1)"
  nohup "$EMU" -avd "$AVD" -gpu "$1" -netdelay none -netspeed full -no-snapshot >> "$ROOT/logs/emulator.log" 2>&1 &
  EPID=$!; disown
  for i in $(seq 1 100); do
    kill -0 $EPID 2>/dev/null || { say "에뮬레이터가 바로 꺼짐"; return 1; }
    [ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return 0; sleep 3
  done; return 1
}
: > "$ROOT/logs/emulator.log"
if [ -n "$AVD" ] && ! adb devices | grep -q "^emulator-.*device$"; then
  boot_emu auto || { tail -15 "$ROOT/logs/emulator.log"; boot_emu swiftshader_indirect; } || {
    tail -15 "$ROOT/logs/emulator.log"
    if [ -x "$SDKM" ]; then say "에뮬레이터 프로그램을 최신으로 업데이트 후 다시 시도"; yes | "$SDKM" --install emulator platform-tools >/dev/null; boot_emu swiftshader_indirect; fi; }
fi
ADEV="$(adb devices | awk '/^emulator-.*device$/{print $1; exit}')"; say "Android 기기: ${ADEV:-없음}"
if pgrep -f "flutter_tools.*run.*-d $ADEV" >/dev/null 2>&1; then say "Android 앱은 이미 실행 중(창 그대로 두기)"
elif [ -n "$ADEV" ]; then term "cd '$ROOT/sbada_app' && flutter run -d $ADEV --dart-define=API_BASE_URL=http://10.0.2.2:8097"; fi

say "3) iPhone 시뮬레이터"
xcodebuild -version | head -1
# Xcode 27 부터는 Simulator 앱이 없어지고 'Device Hub' 앱이 시뮬레이터 화면을 보여 준다
SIMAPP="$(find /Applications/Xcode.app/Contents /Applications -maxdepth 8 -type d \( -iname '*Device*Hub*.app' -o -name 'Simulator.app' \) 2>/dev/null | head -1)"
[ -z "$SIMAPP" ] && SIMAPP="$(mdfind 'kMDItemCFBundleIdentifier == "com.apple.iphonesimulator"' | head -1)"
say "Simulator 앱: ${SIMAPP:-없음}"
xcrun simctl list runtimes
# 가장 최신 iOS 런타임에 아이폰을 쓴다(옛 런타임 기기는 Xcode 27.2 에서 설치 도중 꺼짐)
RT="$(xcrun simctl list runtimes available | grep -E '^iOS' | tail -1 | grep -oE 'com\.apple\.CoreSimulator\.SimRuntime\.[A-Za-z0-9-]+')"
RTNAME="$(xcrun simctl list runtimes available | grep -E '^iOS' | tail -1 | awk '{print $1" "$2}')"; say "iOS 런타임: $RTNAME ($RT)"
UDID="$(xcrun simctl list devices available | awk -v r="-- $RTNAME --" '$0==r{f=1;next} /^--/{f=0} f && /iPhone/' | head -1 | grep -oE '[0-9A-F-]{36}')"
if [ -z "$UDID" ] && [ -n "$RT" ]; then
  TYPE="$(xcrun simctl list devicetypes | grep -m1 -E 'iPhone 17 Pro \(' | grep -oE 'com\.apple\.CoreSimulator\.SimDeviceType\.[A-Za-z0-9-]+')"
  [ -z "$TYPE" ] && TYPE="$(xcrun simctl list devicetypes | grep -E '^iPhone' | tail -1 | grep -oE 'com\.apple\.CoreSimulator\.SimDeviceType\.[A-Za-z0-9-]+')"
  UDID="$(xcrun simctl create "스바다 iPhone" "$TYPE" "$RT")"; say "새 아이폰 기기 만듦: $UDID"
fi
xcrun simctl shutdown all 2>/dev/null
# Xcode 를 27.2 로 업데이트한 뒤 예전 시뮬레이터 서비스가 남아 있으면 켠 아이폰이 곧바로 꺼진다 → 서비스를 새로 띄운다
pkill -f "flutter_tools.*run.*-d [0-9A-F-]\{36\}" 2>/dev/null
killall -9 com.apple.CoreSimulator.CoreSimulatorService 2>/dev/null; sleep 3
xcrun simctl boot "$UDID"; xcrun simctl bootstatus "$UDID" -b | tail -1
if [ -n "$SIMAPP" ]; then open -a "$SIMAPP"; else say "!! Device Hub 앱을 못 찾음 → Xcode 를 열고 메뉴 Window 에서 Device Hub(또는 Devices and Simulators)를 여세요"; open -a Xcode; fi
# 아이폰이 계속 켜져 있는지 30초 동안 지켜본다(꺼지면 기록에 남김)
for i in 1 2 3 4 5 6; do sleep 5; echo "  [$i] $(xcrun simctl list devices | grep "$UDID" | sed 's/^ *//')"; done
say "iPhone 기기: $UDID"
[ -n "$UDID" ] && term "cd '$ROOT/sbada_app' && flutter run -d $UDID --dart-define=API_BASE_URL=http://127.0.0.1:8097"
say "DONE (앱 빌드는 각 터미널 창에서 1~3분 걸려요)"

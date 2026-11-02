#!/bin/bash
# 아이폰 시뮬레이터 앱만 새로 빌드해서 다시 띄운다(서버 · 안드로이드는 그대로). 기록: logs/ios-rerun.log
# 화면에 보이는 시뮬레이터와 flutter 가 연결된 시뮬레이터가 달라 '바뀐 게 안 보일' 때 쓴다:
#  지금 켜져 있는 시뮬레이터를 모두 끄고 → iPhone Duo(최신 iOS) 하나만 켜서 → 그 기기에 flutter run
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/ios-rerun.log"; mkdir -p "$ROOT/logs"
{
echo "[$(date +%H:%M:%S)] 시작"
pkill -f "flutter_tools.*run -d [0-9A-F-]{36}" 2>/dev/null
RT="$(xcrun simctl list runtimes | grep -Eo 'com.apple.CoreSimulator.SimRuntime.iOS-[0-9-]+' | sort -V | tail -1)"
UDID="$(xcrun simctl list devices -j | python3 -c "
import json,sys; d=json.load(sys.stdin)['devices'].get('$RT',[])
ok=[x for x in d if x.get('isAvailable')]
pick=[x for x in ok if 'Duo' in x['name']] or [x for x in ok if 'iPhone' in x['name']]
print(pick[0]['udid'] if pick else '')")"
echo "런타임 $RT · 기기 $UDID"
[ -z "$UDID" ] && { echo "아이폰 기기를 못 찾음"; exit 1; }
xcrun simctl shutdown all 2>/dev/null
killall -9 com.apple.CoreSimulator.CoreSimulatorService 2>/dev/null; sleep 2
xcrun simctl boot "$UDID" 2>&1; xcrun simctl bootstatus "$UDID" -b 2>&1 | tail -1
SIMAPP="$(find /Applications/Xcode.app/Contents -maxdepth 8 -type d \( -iname '*Device*Hub*.app' -o -name 'Simulator.app' \) 2>/dev/null | head -1)"
[ -n "$SIMAPP" ] && open -a "$SIMAPP"
osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT/sbada_app' && flutter run -d $UDID --dart-define=API_BASE_URL=http://127.0.0.1:8097\"" >/dev/null
echo "[$(date +%H:%M:%S)] DONE — 새 터미널에서 빌드 중(1~3분). 시뮬레이터 창 왼쪽 목록에서 iPhone Duo 를 보세요"
} 2>&1 | tee "$L"

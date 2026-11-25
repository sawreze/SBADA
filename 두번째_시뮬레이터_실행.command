#!/bin/bash
# ===== 맥북 한 대에서 스바다 앱 두 개 동시에: 두 번째 아이폰 시뮬레이터 (2026-11-25 사용자 요청) =====
#  첫 번째 시뮬레이터(스바다_실행.command)에 앱이 뜬 뒤에 실행한다. 다른 기종의 아이폰 시뮬레이터를 하나 더 켜고,
#  첫 번째가 이미 만든 앱(build/ios/iphonesimulator/Runner.app)을 그대로 설치해 띄운다(다시 빌드하지 않아 빠름).
#  → 첫 번째는 demo@sbada.test, 두 번째는 neighbor1@sbada.test 로 로그인 → 요청 · 수락 · 추가요금이 서로 바로 보인다.
#  두 앱 모두 토스 테스트 결제창이 열린다. 기록: logs/second-sim.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
exec > >(tee "$ROOT/logs/second-sim.log") 2>&1
say(){ echo "[$(date +%H:%M:%S)] $*"; }
APP="$ROOT/sbada_app/build/ios/iphonesimulator/Runner.app"

if ! curl -s -o /dev/null -m 2 http://127.0.0.1:8097/api/health; then
  say "서버가 꺼져 있어요 → 스바다_실행.command 를 먼저 실행해 주세요"; read -r -p "Enter 를 누르면 닫혀요"; exit 1
fi

# 1) 앱: 첫 번째 시뮬레이터용으로 만든 것이 있으면 그대로, 없거나 앱 코드보다 오래됐으면 한 번 빌드
NEWER="$( [ -d "$APP" ] && find "$ROOT/sbada_app/lib" -name '*.dart' -newer "$APP/Info.plist" 2>/dev/null | head -1)"
if [ ! -d "$APP" ] || [ -n "$NEWER" ]; then
  say "시뮬레이터용 앱 빌드 중(2~4분)… (첫 번째 시뮬레이터 빌드가 끝난 뒤에 실행해야 서로 안 부딪혀요)"
  cd "$ROOT/sbada_app" && flutter build ios --simulator --debug --dart-define=API_BASE_URL=http://127.0.0.1:8097 2>&1 | tail -3
  [ -d "$APP" ] || { say "빌드 실패 → 위 기록을 캡처해 주세요"; read -r -p "Enter"; exit 1; }
fi
BUNDLE="$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")"

# 2) 두 번째 아이폰 시뮬레이터: 이미 켜진 아이폰이 2대 이상이면 두 번째 것, 아니면 다른 기종을 하나 켠다
read -r FIRST SECOND <<<"$(xcrun simctl list devices -j | python3 -c "
import json,sys
d=json.load(sys.stdin)['devices']
rt=[(k,v) for k,v in d.items() if 'iOS' in k]
booted=[x for _,v in rt for x in v if x.get('state')=='Booted' and 'iPhone' in x['name']]
first=booted[0] if booted else None
duo=[x for x in booted if 'Duo' in x['name']]
if duo:  # 2026-11-25: 켜져 있는 iPhone Duo 가 있으면 그것을 두 번째로
    rest=[x for x in booted if x['udid']!=duo[0]['udid']]
    print(rest[0]['udid'] if rest else '-', duo[0]['udid']); sys.exit()
if len(booted)>=2:
    print(first['udid'], booted[1]['udid']); sys.exit()
same=[v for k,v in rt if first and any(x['udid']==first['udid'] for x in v)]
pool=(same[0] if same else [x for _,v in rt for x in v])
cand=[x for x in pool if x.get('isAvailable') and 'iPhone' in x['name'] and x.get('state')!='Booted'
      and (not first or x['name']!=first['name'])]
print(first['udid'] if first else '-', cand[0]['udid'] if cand else '')
")"
[ -z "$SECOND" ] && { say "켤 수 있는 아이폰 시뮬레이터가 더 없어요 → Xcode > Window > Devices and Simulators 에서 하나 추가해 주세요"; read -r -p "Enter"; exit 1; }
say "두 번째 시뮬레이터: $(xcrun simctl list devices | grep "$SECOND" | sed 's/^ *//')"
xcrun simctl boot "$SECOND" 2>/dev/null
SIMAPP="$(find /Applications/Xcode.app/Contents -maxdepth 8 -type d \( -iname '*Device*Hub*.app' -o -name 'Simulator.app' \) 2>/dev/null | head -1)"
[ -n "$SIMAPP" ] && open -a "$SIMAPP"
xcrun simctl bootstatus "$SECOND" >/dev/null 2>&1

# 3) 설치 · 실행
xcrun simctl install "$SECOND" "$APP" && xcrun simctl launch "$SECOND" "$BUNDLE" >/dev/null \
  && say "완료! 두 번째 시뮬레이터에서 neighbor1@sbada.test / sbada1234 로 로그인하세요(첫 번째는 demo@sbada.test)." \
  || say "설치 · 실행 실패 → 이 창을 캡처해 주세요"
say "창 두 개를 나란히 놓으면 한쪽의 요청 · 수락이 다른 쪽에 바로 떠요."
read -r -p "Enter 를 누르면 이 창이 닫혀요"

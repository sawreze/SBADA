#!/bin/bash
# 지금 화면에 켜져 있는(booted) 아이폰 시뮬레이터에 스바다를 설치해 실행한다. 시뮬레이터는 끄지 않는다. 기록: logs/ios-here.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/ios-here.log"; mkdir -p "$ROOT/logs"
{
echo "[$(date +%H:%M:%S)] 시작"
UDID="$(xcrun simctl list devices booted -j | python3 -c "
import json,sys
d=json.load(sys.stdin)['devices']
b=[x for v in d.values() for x in v if x.get('state')=='Booted' and 'iPhone' in x['name']]
print(b[0]['udid'] if b else '')")"
[ -z "$UDID" ] && { echo "켜져 있는 아이폰 시뮬레이터가 없음 → 아이폰_앱_다시실행.command 를 쓰세요"; exit 1; }
echo "대상 기기 $UDID"
# 서버가 꺼져 있으면 켠다(새 코드로 켜져 있으면 그대로, 옛 코드면 끄고 다시)
"$ROOT/scripts/stop-stale.sh"
if ! curl -s -o /dev/null http://127.0.0.1:8097/actuator/health 2>/dev/null && ! lsof -iTCP:8097 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "서버 시작"; ( cd "$ROOT/sbada-server" && nohup ./gradlew bootRun --args='--spring.profiles.active=dev --spring.h2.console.enabled=false' > "$ROOT/logs/server.log" 2>&1 & )
fi
"$ROOT/scripts/start-msa-bg.sh"   # 허브(Kafka) + 물품 서버(8099) + 결제 서버(8098)
osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT/sbada_app' && flutter run -d $UDID --dart-define=API_BASE_URL=http://127.0.0.1:8097\"" >/dev/null
echo "[$(date +%H:%M:%S)] DONE — 새 터미널에서 빌드 중(1~3분). 끝나면 시뮬레이터에 '스바다' 앱이 뜹니다"
} 2>&1 | tee "$L"

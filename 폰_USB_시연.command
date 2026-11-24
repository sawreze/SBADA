#!/bin/bash
# ===== 시연: 내 휴대폰을 USB 로 맥에 꽂고 스바다 앱 실행 (2026-11-25) =====
# 메인 서버(8097) + 결제 서비스(8098, 가상 거래 · 실제 돈 이동 없음)를 새 코드로 켜고,
# USB 로 연결된 휴대폰을 찾아 앱을 설치 · 실행한다. 기록: logs/phone-usb.log
#
#  ▣ 안드로이드폰 (와이파이 필요 없음)
#    처음 한 번: 폰 설정 > 휴대전화 정보 > 소프트웨어 정보 > '빌드번호' 7번 톡톡 → 개발자 옵션 > USB 디버깅 켜기
#                USB 를 꽂으면 폰에 'USB 디버깅을 허용할까요?' → 항상 허용 체크 후 허용
#    adb reverse 로 폰의 127.0.0.1:8097 을 맥 서버로 이어 준다 → 케이블만 꽂혀 있으면 된다
#  ▣ 아이폰
#    처음 한 번은 '아이폰_실기기_실행.command' 의 준비(신뢰 · 개발자 모드 · 서명 팀)가 필요하다
#    아이폰은 USB 로 서버를 이어 주는 방법이 없어서 같은 와이파이로 맥 서버(http://맥IP:8097)에 붙는다
#
#  profile 모드로 설치한다(디버그보다 훨씬 부드럽고, 케이블을 뽑아도 앱이 폰에 남는다 —
#  단 안드로이드는 서버 연결이 USB 를 거치므로 시연 중에는 케이블을 꽂아 둔다)
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/phone-usb.log"; mkdir -p "$ROOT/logs"
exec > >(tee "$L") 2>&1
say(){ echo "[$(date +%H:%M:%S)] $*"; }
bye(){ read -r -p "Enter 를 누르면 이 창이 닫혀요"; exit "${1:-0}"; }

say "1) USB 로 연결된 휴대폰 찾기"
cd "$ROOT/sbada_app"
DEV="$(flutter devices --machine 2>/dev/null | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: d=[]
real=[x for x in d if not x.get('emulator') and (x.get('targetPlatform','').startswith('android') or x.get('targetPlatform')=='ios')]
real.sort(key=lambda x: 0 if x.get('targetPlatform','').startswith('android') else 1)
print((real[0]['id']+'|'+real[0]['name']+'|'+real[0]['targetPlatform']) if real else '')")"
if [ -z "$DEV" ]; then
  say "휴대폰이 안 보여요."
  say "  안드로이드: USB 디버깅을 켰는지, 폰에 뜬 'USB 디버깅 허용'을 눌렀는지, 충전 전용 케이블이 아닌지 확인"
  say "  아이폰: '이 컴퓨터를 신뢰' · 설정 > 개인정보 보호 및 보안 > 개발자 모드 확인"
  flutter devices; [ -x "$ADB" ] && "$ADB" devices
  bye 1
fi
ID="${DEV%%|*}"; REST="${DEV#*|}"; NAME="${REST%%|*}"; PLAT="${REST#*|}"
say "연결된 폰: $NAME ($PLAT)"

if [[ "$PLAT" == android* ]]; then
  KIND=android; HOST=127.0.0.1; BIND=127.0.0.1
else
  KIND=ios
  HOST="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)"
  [ -z "$HOST" ] && { say "아이폰은 와이파이로 서버에 붙어요 → 맥을 아이폰과 같은 와이파이에 연결해 주세요"; bye 1; }
  grep -q "DEVELOPMENT_TEAM = [A-Z0-9]" ios/Runner.xcodeproj/project.pbxproj \
    || { say "서명 팀이 아직 없어요 → '아이폰_실기기_실행.command' 를 먼저 한 번 실행해 주세요"; bye 1; }
  BIND=0.0.0.0
fi
API="http://$HOST:8097"

say "2) 서버 다시 켜기(새 코드 반영) — 메인 서버 + 결제 서비스(가상 거래)"
for port in 8097 8098; do PID="$(lsof -ti tcp:$port -sTCP:LISTEN 2>/dev/null)"; [ -n "$PID" ] && { say "  포트 $port 종료"; kill $PID; }; done
sleep 3
osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT/sbada-server' && ./gradlew bootRun --console=plain --args='--spring.profiles.active=dev --server.address=$BIND --spring.h2.console.enabled=false' 2>&1 | tee '$ROOT/logs/server.log'\"" >/dev/null
for i in $(seq 1 90); do curl -s -o /dev/null -m 2 "$API/api/health" && break; sleep 2; done
curl -s -o /dev/null -m 2 "$API/api/health" && say "  메인 서버 OK ($API)" || { say "  메인 서버가 안 떠요 → 새로 열린 터미널 창 · logs/server.log 확인"; bye 1; }
( cd "$ROOT/sbada-payment" && chmod +x gradlew && nohup ./gradlew bootRun --console=plain > "$ROOT/logs/payment.log" 2>&1 & )
for i in $(seq 1 90); do curl -s -o /dev/null -m 2 http://127.0.0.1:8098/api/payment/health && break; sleep 2; done
if curl -s -o /dev/null -m 2 http://127.0.0.1:8098/api/payment/health; then say "  결제 서비스 OK (시연 모드 · 가상 거래)"
else say "  결제 서비스가 안 떠요 → logs/payment.log (교환 · 채팅은 되고 결제만 503)"; fi

if [ "$KIND" = android ]; then
  say "3) USB 로 폰 ↔ 맥 서버 연결(adb reverse)"
  [ -x "$ADB" ] || command -v adb >/dev/null 2>&1 || { say "adb 가 없어요 → Android Studio 의 SDK Platform-Tools 설치 필요"; bye 1; }
  "$ADB" -s "$ID" reverse tcp:8097 tcp:8097 && "$ADB" -s "$ID" reverse --list
  say "  폰의 127.0.0.1:8097 → 맥 서버. 케이블을 뽑으면 끊기니 시연 중엔 꽂아 두세요"
else
  say "3) 아이폰은 와이파이로 연결: $API (앱이 '로컬 네트워크' 권한을 물으면 허용)"
fi

say "4) 폰에 설치 · 실행(새 터미널, 처음엔 3~6분)"
osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT/sbada_app' && flutter run --profile -d $ID --dart-define=API_BASE_URL=$API\"" >/dev/null
say "DONE — 앱이 켜지면 시연 계정으로 로그인하세요. 결제 화면은 전부 가상 거래(실제 돈 이동 없음)"
bye 0

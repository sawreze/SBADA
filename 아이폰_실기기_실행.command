#!/bin/bash
# ===== 내 아이폰(실제 기기)에 스바다 앱 설치 · 실행 =====
# 준비(처음 한 번):
#  1) 아이폰을 케이블로 맥에 연결 → 아이폰에 '이 컴퓨터를 신뢰' → 신뢰
#  2) 아이폰 설정 > 개인정보 보호 및 보안 > 개발자 모드 켜기(재시동)
#  3) 서명 팀: 이 스크립트가 Xcode 를 열어 주면 Runner > Signing & Capabilities > Team 에서 내 Apple ID(Personal Team) 고르기
#  4) 설치 후 처음 열 때 '신뢰하지 않는 개발자'가 뜨면: 아이폰 설정 > 일반 > VPN 및 기기 관리 > 내 Apple ID > 신뢰
# 앱은 와이파이로 맥 서버(http://맥IP:8097)에 붙는다 → 아이폰과 맥이 같은 와이파이여야 한다.
# 무료 Apple ID 로 설치한 앱은 7일 뒤 다시 설치해야 한다(Apple 규칙). 기록: logs/iphone.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/iphone.log"; mkdir -p "$ROOT/logs"
exec > >(tee "$L") 2>&1
say(){ echo "[$(date +%H:%M:%S)] $*"; }

IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)"
say "맥 와이파이 IP: ${IP:-없음}"
[ -z "$IP" ] && { say "와이파이에 연결해 주세요"; read -r -p "Enter 를 누르면 닫혀요"; exit 1; }

say "1) 연결된 아이폰 찾기"
cd "$ROOT/sbada_app"
DEV="$(flutter devices --machine 2>/dev/null | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: d=[]
p=[x for x in d if x.get('targetPlatform')=='ios' and not x.get('emulator')]
print((p[0]['id']+'|'+p[0]['name']) if p else '')")"
if [ -z "$DEV" ]; then
  say "아이폰이 안 보여요 → 케이블 연결 · '이 컴퓨터를 신뢰' · 개발자 모드를 확인하고 다시 실행해 주세요"
  flutter devices
  read -r -p "Enter 를 누르면 닫혀요"; exit 1
fi
ID="${DEV%%|*}"; say "아이폰: ${DEV#*|} ($ID)"

say "2) 서명 팀 확인"
if ! grep -q "DEVELOPMENT_TEAM = [A-Z0-9]" ios/Runner.xcodeproj/project.pbxproj; then
  say "서명 팀이 아직 없어요 → Xcode 를 엽니다."
  say "  왼쪽 Runner(파란 아이콘) > TARGETS: Runner > Signing & Capabilities > Team 에서 내 Apple ID 를 고르고"
  say "  (없으면 Add an Account… 로 Apple ID 로그인) Xcode 를 닫은 뒤 이 파일을 다시 실행해 주세요."
  open ios/Runner.xcworkspace
  read -r -p "Enter 를 누르면 닫혀요"; exit 0
fi
say "서명 팀 OK"

say "3) 서버를 와이파이에서도 접속되게(이미 그렇게 켜져 있으면 그대로)"
if curl -s -o /dev/null -m 2 "http://$IP:8097/api/health"; then say "서버 OK(와이파이)"; else
  "$ROOT/scripts/stop-server.sh"; sleep 2
  osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT/sbada-server' && ./gradlew bootRun --console=plain --args='--spring.profiles.active=dev --server.address=0.0.0.0 --spring.h2.console.enabled=false' 2>&1 | tee '$ROOT/logs/server.log'\"" >/dev/null
  for i in $(seq 1 90); do curl -s -o /dev/null -m 2 "http://$IP:8097/api/health" && break; sleep 2; done
  curl -s -o /dev/null -m 2 "http://$IP:8097/api/health" && say "서버 OK(와이파이)" || say "서버가 아직 안 떠요 — 새 터미널 창 확인"
fi

say "4) 아이폰에 설치 · 실행(새 터미널, 처음엔 3~6분). 앱이 '로컬 네트워크' 권한을 물으면 허용"
osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT/sbada_app' && flutter run -d $ID --dart-define=API_BASE_URL=http://$IP:8097\"" >/dev/null
say "DONE"

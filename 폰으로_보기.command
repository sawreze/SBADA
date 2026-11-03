#!/bin/bash
# ===== 내 휴대폰(같은 와이파이)으로 스바다 보기 =====
# 맥 서버를 와이파이에서도 접속되게 다시 켜고(0.0.0.0), 웹 화면을 '같은 서버 주소'로 빌드한 뒤,
# 휴대폰 카메라로 찍을 QR 코드를 맥 화면에 띄운다. 기록: logs/phone.log
#  - 아이폰 사파리로 열면 자동으로 iOS 디자인(리퀴드 글래스 탭바 · 펼쳐지는 화면 전환)
#  - 맥과 휴대폰이 같은 와이파이여야 한다. 처음에 맥이 "java 가 들어오는 연결을 허용할까요?" 물으면 '허용'
#  - H2 콘솔은 끈 채로 켠다(와이파이의 다른 기기가 DB 화면에 들어오지 못하게)
#  - 다 보고 나면 이 창을 닫고 '스바다_실행.command' 로 원래처럼(맥에서만 접속) 켜면 된다
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/phone.log"; mkdir -p "$ROOT/logs"
exec > >(tee "$L") 2>&1
say(){ echo "[$(date +%H:%M:%S)] $*"; }

IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)"
[ -z "$IP" ] && { say "와이파이 IP 를 못 찾았어요(와이파이 연결 확인)"; exit 1; }
URL="http://$IP:8097/app/"
say "맥 IP: $IP"

say "1) 웹 화면 빌드(같은 서버 주소 사용)"
cd "$ROOT/sbada_app" && flutter build web --release --base-href /app/ --no-web-resources-cdn \
  --dart-define=API_BASE_URL=ORIGIN 2>&1 | tail -2
say "BUILD_EXIT=${PIPESTATUS[0]}"

say "2) 서버를 와이파이에서도 접속되게 다시 켜기"
"$ROOT/scripts/stop-server.sh"; sleep 2
osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT/sbada-server' && ./gradlew bootRun --console=plain --args='--spring.profiles.active=dev --server.address=0.0.0.0 --spring.h2.console.enabled=false' 2>&1 | tee '$ROOT/logs/server.log'\"" >/dev/null
for i in $(seq 1 90); do curl -s -o /dev/null -m 2 "http://$IP:8097/api/health" && break; sleep 2; done
if curl -s -o /dev/null -m 2 "http://$IP:8097/api/health"; then say "서버 OK: $URL"; else say "서버가 아직 안 떠요 — 새 터미널 창을 확인"; fi

say "3) 휴대폰용 QR 코드"
Q="$ROOT/logs/phone.html"
cat > "$Q" <<HTML
<!doctype html><meta charset="utf-8"><title>스바다 · 폰으로 보기</title>
<body style="font-family:-apple-system,sans-serif;text-align:center;padding:40px;background:#111;color:#fff">
<h2>휴대폰 카메라로 찍어 주세요</h2><div id="q" style="display:inline-block;padding:16px;background:#fff;border-radius:16px"></div>
<p style="font-size:22px;margin-top:20px">$URL</p><p style="color:#aaa">맥과 같은 와이파이 · 로그인: admin / sbada1234</p>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>new QRCode(document.getElementById('q'),{text:'$URL',width:280,height:280});</script></body>
HTML
open "$Q"
say "DONE  주소: $URL"

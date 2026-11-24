#!/bin/bash
# 1순위 점검을 한 번에: 서버를 새 코드로 다시 켜고(DB V16 · 결제 게이트웨이 · 30일 로그인) → 결제 서비스(8098) 켜기
# → 결제 흐름 실제 호출(로그인 · 지갑 · 계좌 등록 · 출금 · 추가 요금) → 켜져 있는 아이폰 시뮬레이터에 스바다 실행.
# 기록: logs/priority1.log  (비밀값 · 토큰은 기록하지 않음)
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/priority1.log"; B=http://127.0.0.1:8097
{
echo "[$(date +%H:%M:%S)] 시작"
# 1) 예전 서버 · 결제 서비스 끄기
for p in 8097 8098; do P="$(lsof -ti tcp:$p -sTCP:LISTEN 2>/dev/null)"; [ -n "$P" ] && { echo "포트 $p 종료(PID $P)"; kill $P; }; done
sleep 3
# 2) 새 코드로 서버 켜기
( cd "$ROOT/sbada-server" && chmod +x gradlew && nohup ./gradlew bootRun --console=plain --args="--spring.profiles.active=dev" > "$ROOT/logs/server.log" 2>&1 & )
for i in $(seq 1 60); do curl -s -o /dev/null -m 2 $B/api/health && break; sleep 3; done
curl -s -m 3 $B/api/health >/dev/null && echo "서버 OK" || { echo "서버가 안 켜짐 → logs/server.log"; tail -30 "$ROOT/logs/server.log"; exit 1; }
grep -E "Current version of schema|Successfully applied|Migrating schema" "$ROOT/logs/server.log" | tail -3
# 3) 결제 서비스 켜기
( cd "$ROOT/sbada-payment" && chmod +x gradlew && nohup ./gradlew bootRun --console=plain > "$ROOT/logs/payment.log" 2>&1 & )
for i in $(seq 1 60); do curl -s -o /dev/null -m 2 http://127.0.0.1:8098/api/payment/health && break; sleep 3; done
curl -s -m 3 http://127.0.0.1:8098/api/payment/health && echo || { echo "결제 서비스가 안 켜짐 → logs/payment.log"; tail -30 "$ROOT/logs/payment.log"; exit 1; }
# 4) 결제 흐름 호출(게이트웨이 8097 경유)
tok() { curl -s -H 'Content-Type: application/json' --data-binary "@$ROOT/scripts/json/login-$1.json" $B/api/auth/login | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'; }
uid() { curl -s -H "Authorization: Bearer $1" $B/api/me | python3 -c "import json,sys; d=json.load(sys.stdin); print((d.get('user') or d).get('id'))"; }
T1="$(tok minji)"; T2="$(tok junho)"
[ ${#T1} -gt 20 ] && [ ${#T2} -gt 20 ] && echo "로그인 OK(minji · junho)" || { echo "로그인 실패"; exit 1; }
U2="$(uid "$T2")"; echo "junho id=$U2"
c() { local n="$1"; shift; local out; out="$(curl -s -w ' HTTP%{http_code}' "$@")"; echo "== $n → ${out: -7}"; echo "${out% HTTP*}" | python3 -c "import json,sys
try:
  d=json.load(sys.stdin)
  for k in ('message','balance','code','accountMasked','bankName','fee','total'):
    if k in d: print('   ',k,'=',d[k])
  if 'transaction' in d: t=d['transaction']; print('    거래:',t.get('typeLabel'),t.get('amount'),'수수료',t.get('fee'))
except Exception as e: print('   (JSON 아님)')"; }
A=(-H "Authorization: Bearer $T1" -H 'Content-Type: application/json')
c "지갑 GET /api/wallet" "${A[@]}" $B/api/wallet
c "계좌 등록(카카오뱅크)" "${A[@]}" -d '{"bankCode":"KAKAOBANK","accountNumber":"3333-01-'$((RANDOM%9000000+1000000))'","holder":"민지"}' $B/api/wallet/accounts
ACC="$(curl -s "${A[@]}" $B/api/wallet | python3 -c "import json,sys; a=json.load(sys.stdin)['accounts']; print(a[-1]['id'] if a else '')")"
c "출금 10,000원" "${A[@]}" -d "{\"accountId\":$ACC,\"amount\":10000}" $B/api/wallet/withdraw
c "수수료 미리보기 10,000원" "${A[@]}" "$B/api/payments/quote?amount=10000"
c "추가 요금 10,000원 → junho(카카오페이)" "${A[@]}" -d "{\"toUserId\":$U2,\"amount\":10000,\"method\":\"KAKAOPAY\"}" $B/api/payments/extra
c "junho 지갑" -H "Authorization: Bearer $T2" $B/api/wallet
sleep 4
echo "== 알림(아웃박스 → 메인 서버) minji:"; curl -s -H "Authorization: Bearer $T1" $B/api/notifications | python3 -c "import json,sys; [print('   ',n['type'],n['title']) for n in json.load(sys.stdin)['notifications'] if n['type']=='WALLET'][:3]"
echo "== 계좌번호가 DB 에 평문으로 있는지(없어야 정상):"; strings "$ROOT/sbada-payment/data/payment.mv.db" 2>/dev/null | grep -cE "3333-?01-?[0-9]{7}" || true
# 4-2) 앱 코드 검사(하단바 리퀴드 글래스 변경 포함)
( cd "$ROOT/sbada_app" && flutter analyze 2>&1 | tail -3; flutter test test/glass_test.dart test/apple_ui_test.dart test/wallet_test.dart 2>&1 | tail -2 )
# 5) 켜져 있는 아이폰 시뮬레이터에 스바다 실행(없으면 iPhone Duo 를 켬)
UDID="$(xcrun simctl list devices booted -j | python3 -c "
import json,sys; d=json.load(sys.stdin)['devices']
b=[x for v in d.values() for x in v if x.get('state')=='Booted' and 'iPhone' in x['name']]; print(b[0]['udid'] if b else '')")"
if [ -z "$UDID" ]; then
  UDID="$(xcrun simctl list devices available -j | python3 -c "
import json,sys; d=json.load(sys.stdin)['devices']; a=[x for v in d.values() for x in v if 'iPhone' in x['name']]
p=[x for x in a if 'Duo' in x['name']] or a; print(p[0]['udid'] if p else '')")"
  xcrun simctl boot "$UDID" 2>&1 | tail -1
fi
SIMAPP="$(find /Applications/Xcode.app/Contents -maxdepth 8 -type d \( -iname '*Device*Hub*.app' -o -name 'Simulator.app' \) 2>/dev/null | head -1)"; [ -n "$SIMAPP" ] && open -a "$SIMAPP"
pkill -f "flutter_tools.*run -d $UDID" 2>/dev/null
osascript -e "tell application \"Terminal\" to do script \"cd '$ROOT/sbada_app' && flutter run -d $UDID --dart-define=API_BASE_URL=http://127.0.0.1:8097\"" >/dev/null
echo "시뮬레이터 $UDID 에 스바다 빌드 시작(새 터미널 창, 1~3분)"
echo "[$(date +%H:%M:%S)] DONE"
} 2>&1 | tee "$L"
read -r -p "Enter 를 누르면 이 창이 닫혀요"

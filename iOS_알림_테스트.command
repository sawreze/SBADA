#!/bin/bash
# 아이폰 시뮬레이터에 APNs 알림을 흉내 내 보낸다(xcrun simctl push — Apple 키 없이도 됨). 기록: logs/apns-test.log
# 1) 교환 요청 알림 → 누르면 알림함의 '받은 요청' 칸
# 2) 6초 뒤 채팅 알림 → 누르면 채팅 탭
# 앱을 홈 화면으로 내려 두고(⌘⇧H) 실행하면 배너가 잘 보인다(앱이 켜져 있어도 위에 배너가 뜬다).
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/apns-test.log"; mkdir -p "$ROOT/logs"
{
echo "[$(date +%H:%M:%S)] 시작"
UDID="$(xcrun simctl list devices booted | grep -Eo '[0-9A-F-]{36}' | head -1)"
if [ -z "$UDID" ]; then echo "켜진 아이폰 시뮬레이터가 없어요 → 테스트_실행.command 먼저"; echo DONE; exit 1; fi
echo "시뮬레이터 $UDID"
T="$(mktemp -d)"
cat > "$T/exchange.apns" <<'J'
{"Simulator Target Bundle":"com.sbada.sbada",
 "aps":{"alert":{"title":"새 교환 요청이 왔어요","body":"하늘님이 내 '자전거 전조등'과 바꾸고 싶어 해요"},"sound":"default","thread-id":"EXCHANGE_REQUESTED"},
 "type":"EXCHANGE_REQUESTED","refType":"EXCHANGE","refId":"1"}
J
cat > "$T/chat.apns" <<'J'
{"Simulator Target Bundle":"com.sbada.sbada",
 "aps":{"alert":{"title":"하늘","body":"내일 저녁 7시에 역 앞에서 바꿀까요?"},"sound":"default","thread-id":"CHAT_MESSAGE"},
 "type":"CHAT_MESSAGE","refType":"CHAT","refId":"1"}
J
xcrun simctl push "$UDID" com.sbada.sbada "$T/exchange.apns"; echo "EXCHANGE_EXIT=$?"
sleep 6
xcrun simctl push "$UDID" com.sbada.sbada "$T/chat.apns"; echo "CHAT_EXIT=$?"
echo "[$(date +%H:%M:%S)] DONE"
} 2>&1 | tee "$L"

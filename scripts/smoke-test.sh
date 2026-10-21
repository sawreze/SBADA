#!/usr/bin/env bash
# ===== 터미널 + curl 로 로컬호스트 API 확인 (run-server.sh 로 서버를 먼저 켠다) =====
source "$(dirname "$0")/_common.sh"
J="$(dirname "$0")/json"; OUT="$(dirname "$0")/smoke-result.txt"
req() { echo; echo "== $1" >> "$OUT"; shift; curl -s -w "\nHTTP %{http_code}\n" "$@" >> "$OUT" 2>&1; }
echo "스바다 로컬호스트 점검 $(date '+%Y-%m-%d %H:%M:%S')" > "$OUT"
req "1. 서버 상태 GET /api/health" "$BASE/api/health"
echo >> "$OUT"; echo "== 2. 공개 목록 GET /api/items  (기대: 200)" >> "$OUT"
curl -s -o /dev/null -w "HTTP %{http_code}\n" "$BASE/api/items" >> "$OUT"
req "3. 토큰 없이 보호 API GET /api/me  (기대: 401 AUTH_REQUIRED)" "$BASE/api/me"
TOKEN="$(curl -s -H 'Content-Type: application/json' --data-binary "@$J/login-minji.json" "$BASE/api/auth/login" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')"
if [ -z "$TOKEN" ] || [ "${#TOKEN}" -lt 20 ]; then echo "== 4. 로그인 실패: 토큰을 받지 못했습니다" >> "$OUT"; cat "$OUT"; exit 1; fi
echo >> "$OUT"; echo "== 4. 로그인 POST /api/auth/login : 200, accessToken 받음(값은 숨김)" >> "$OUT"
AUTH=(-H "Authorization: Bearer $TOKEN")
echo >> "$OUT"; echo "== 5. 내 정보 GET /api/me  (기대: 200)" >> "$OUT"
curl -s -o /dev/null -w "HTTP %{http_code}\n" "${AUTH[@]}" "$BASE/api/me" >> "$OUT"
req "6. AI 추천 피드 GET /api/feed?limit=2" "${AUTH[@]}" "$BASE/api/feed?limit=2"
echo >> "$OUT"; echo "== 7. 받은 교환 요청 GET /api/exchanges  (기대: 200)" >> "$OUT"
curl -s -o /dev/null -w "HTTP %{http_code}\n" "${AUTH[@]}" "$BASE/api/exchanges" >> "$OUT"
req "8. 안 읽은 알림 GET /api/notifications/unread-count" "${AUTH[@]}" "$BASE/api/notifications/unread-count"
req "9. 가격을 몰래 넣은 등록 POST /api/items  (기대: 400 UNKNOWN_FIELD)" "${AUTH[@]}" -H "Content-Type: application/json" --data-binary "@$J/item-with-price.json" "$BASE/api/items"
req "10. 변조 토큰으로 공개 GET /api/items  (기대: 401 TOKEN_INVALID)" -H "Authorization: Bearer abc.def.ghi" "$BASE/api/items"
req "11. AI 상태 GET /api/ai/status" "$BASE/api/ai/status"
cat "$OUT"; echo; echo "결과 파일: $OUT"

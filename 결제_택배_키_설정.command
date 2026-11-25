#!/bin/bash
# ===== 토스페이먼츠 테스트 키 · 스마트택배 API 키를 이 맥에 넣기 (2026-11-25) =====
#  - 키는 화면에 안 보이고, Git 에서 빠지는 파일(application-local.properties)에만 저장된다. 채팅에 붙여 넣지 마세요.
#    · 토스 클라이언트 키(test_ck_ · test_gck_) · 시크릿 키(test_sk_ · test_gsk_) → sbada-payment/application-local.properties
#    · 스마트택배 API 키                                                     → sbada-server/application-local.properties
#  - 지금은 테스트 키만 받는다(live_ 로 시작하는 키는 실제 돈이 움직여서 거절).
#  - 빈칸으로 Enter 를 누르면 그 키는 건너뛴다(이미 넣은 값 유지). 다 넣은 뒤 스바다_실행.command 로 서버를 다시 켜면 적용.
ROOT="$(cd "$(dirname "$0")" && pwd)"
PAY="$ROOT/sbada-payment/application-local.properties"
MAIN="$ROOT/sbada-server/application-local.properties"
put(){ # 파일 키 값
  local f="$1" k="$2" v="$3"
  touch "$f"; chmod 600 "$f"
  grep -v "^$k=" "$f" > "$f.tmp" 2>/dev/null; echo "$k=$v" >> "$f.tmp"; mv "$f.tmp" "$f"; chmod 600 "$f"
}
ask(){ # 안내 → 숨겨서 읽기
  echo; echo "$1"; read -rs V; echo; V="$(printf '%s' "$V" | tr -d '[:space:]')"
}

echo "== 1) 토스페이먼츠 (developers.tosspayments.com → 로그인 → API 키 → 테스트 키)"
ask "클라이언트 키(test_ck_… 또는 test_gck_…)를 붙여 넣고 Enter (⌘V, 화면에 안 보여요 / 건너뛰려면 그냥 Enter)"
if [ -n "$V" ]; then
  case "$V" in
    test_ck_*|test_gck_*) put "$PAY" sbada.toss.client-key "$V"; echo "  [OK] 클라이언트 키 저장(${#V}자)";;
    live_*) echo "  [거절] live_ 키는 실제 돈이 움직여요. 지금은 테스트 키만 넣어 주세요.";;
    *) echo "  [거절] test_ck_ / test_gck_ 로 시작하지 않아요. 키를 다시 확인해 주세요.";;
  esac
fi
ask "시크릿 키(test_sk_… 또는 test_gsk_…)를 붙여 넣고 Enter (건너뛰려면 그냥 Enter)"
if [ -n "$V" ]; then
  case "$V" in
    test_sk_*|test_gsk_*) put "$PAY" sbada.toss.secret-key "$V"; echo "  [OK] 시크릿 키 저장(${#V}자)";;
    live_*) echo "  [거절] live_ 키는 실제 돈이 움직여요. 지금은 테스트 키만 넣어 주세요.";;
    *) echo "  [거절] test_sk_ / test_gsk_ 로 시작하지 않아요. 키를 다시 확인해 주세요.";;
  esac
fi

echo; echo "== 2) 스마트택배 (tracking.sweettracker.co.kr → 회원가입 → API KEY 관리 → 프리 등급으로 키 만들기)"
ask "스마트택배 API 키를 붙여 넣고 Enter (건너뛰려면 그냥 Enter)"
if [ -n "$V" ]; then
  if printf '%s' "$V" | grep -Eq '^[A-Za-z0-9_-]{10,80}$'; then put "$MAIN" sbada.tracking.sweettracker-key "$V"; echo "  [OK] 택배 키 저장(${#V}자)"
  else echo "  [거절] 키 모양이 이상해요(영문 · 숫자 10자 이상). 다시 확인해 주세요."; fi
fi

echo
cd "$ROOT" && for f in "$PAY" "$MAIN"; do
  [ -f "$f" ] || continue
  if git check-ignore -q "$f" 2>/dev/null || grep -qx 'application-local.properties' .gitignore 2>/dev/null; then echo "[OK] Git 제외: ${f#$ROOT/}"
  else echo "[주의] ${f#$ROOT/} 가 Git 제외인지 확인하세요 — 커밋하지 마세요"; fi
done
echo "현재 들어 있는 키(값은 안 보여 줌):"
for f in "$PAY" "$MAIN"; do [ -f "$f" ] && grep -oE '^sbada\.(toss|tracking)\.[a-z-]+' "$f" | sed 's/^/  · /'; done
echo; echo "다음: 스바다_실행.command 로 서버를 다시 켜면 적용돼요."
read -r -p "Enter 를 누르면 닫혀요"

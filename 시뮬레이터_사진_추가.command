#!/bin/bash
# ===== 시뮬레이터 사진 앨범에 사진 넣기 (2026-11-26 사용자 요청) =====
#  시연 중 '사진 찍어 올리기'를 보여 주려면 시뮬레이터 사진 앱에 사진이 있어야 한다.
#  demo_photos/ 폴더에 넣은 사진(jpg · png · heic)을 켜져 있는 iPhone 17 Pro Max 시뮬레이터(시연 계정 쪽)
#  사진 앱에 모두 추가한다. 다른 기종에 넣으려면: SIM_NAME="iPhone Duo" ./시뮬레이터_사진_추가.command
#  demo_photos/ 의 사진은 개인 사진일 수 있어 깃허브에 올리지 않는다(.gitignore).
ROOT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$ROOT/logs" "$ROOT/demo_photos"
exec > >(tee "$ROOT/logs/add-photo.log") 2>&1
say(){ echo "[$(date +%H:%M:%S)] $*"; }
SIM_NAME="${SIM_NAME:-iPhone 17 Pro Max}"

UDID=$(xcrun simctl list devices booted | grep "$SIM_NAME (" \
  | grep -oE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' | head -1)
if [ -z "$UDID" ]; then
  say "켜져 있는 '$SIM_NAME' 시뮬레이터가 없어요 → 스바다_실행.command 로 먼저 켜 주세요"
  read -r -p "Enter 를 누르면 닫혀요"; exit 1
fi

shopt -s nullglob nocaseglob
PHOTOS=("$ROOT"/demo_photos/*.{jpg,jpeg,png,heic})
if [ ${#PHOTOS[@]} -eq 0 ]; then
  say "demo_photos 폴더가 비어 있어요 → 사진을 넣고 다시 실행해 주세요"
  open "$ROOT/demo_photos"
  read -r -p "Enter 를 누르면 닫혀요"; exit 1
fi

xcrun simctl addmedia "$UDID" "${PHOTOS[@]}" \
  && say "완료! $SIM_NAME 사진 앱에 ${#PHOTOS[@]}장 추가했어요" \
  || say "추가 실패 — 위 메시지를 확인해 주세요"
sleep 3

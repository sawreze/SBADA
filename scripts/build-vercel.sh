#!/usr/bin/env bash
# ===== Vercel 에 올릴 웹 화면 만들기 → deploy/vercel 폴더(이 폴더를 Vercel 이 그대로 내보냄) =====
#  사용법: scripts/build-vercel.sh [서버주소]   예) scripts/build-vercel.sh https://sbada-server.onrender.com
#  서버 주소를 안 적으면 Render 기본 주소. Render 에서 받은 주소가 다르면 그 주소로 다시 실행 → 커밋 · 푸시
source "$(dirname "$0")/_common.sh"
SERVER="${1:-https://sbada-server.onrender.com}"
cd "$ROOT/sbada_app"
flutter build web --release --base-href / --no-web-resources-cdn --dart-define=API_BASE_URL="$SERVER" || { echo "[실패] 웹 빌드 오류. 위 메시지를 확인하세요."; exit 1; }
# vercel.json 은 남기고 나머지를 새 빌드로 바꾼다
find "$ROOT/deploy/vercel" -mindepth 1 -maxdepth 1 ! -name vercel.json -exec rm -rf {} +
cp -R build/web/. "$ROOT/deploy/vercel/"
echo; echo "완료: deploy/vercel 에 웹 화면을 만들었어요(서버 주소 $SERVER). git add · commit · push 하면 Vercel 이 다시 배포해요."

#!/usr/bin/env bash
# ===== Flutter 앱 처음 준비: android 폴더 채우기(기존 파일 유지) + 패키지 + 분석 + 테스트 =====
source "$(dirname "$0")/_common.sh"
cd "$ROOT/sbada_app"
flutter create --org com.sbada --project-name sbada --platforms android .
flutter pub get && flutter analyze && flutter test

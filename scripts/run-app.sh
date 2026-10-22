#!/usr/bin/env bash
# ===== 에뮬레이터에서 앱 실행. 서버(run-server.sh)를 먼저 켠다 =====
# 에뮬레이터가 꺼져 있으면: flutter emulators  →  flutter emulators --launch <이름>
source "$(dirname "$0")/_common.sh"
cd "$ROOT/sbada_app"
flutter devices
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8097 "$@"

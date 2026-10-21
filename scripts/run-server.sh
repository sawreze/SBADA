#!/usr/bin/env bash
# ===== 스바다 서버 실행 (dev 프로필: H2 콘솔 켜짐). 종료: Ctrl+C =====
source "$(dirname "$0")/_common.sh"
java -version
cd "$ROOT/sbada-server" && chmod +x gradlew
echo; echo " 주소: http://127.0.0.1:8097/   (에뮬레이터: http://10.0.2.2:8097)"
echo " H2 콘솔: http://127.0.0.1:8097/h2-console  JDBC URL: jdbc:h2:file:./data/sbada  User: sa"; echo
./gradlew bootRun --console=plain --args="--spring.profiles.active=dev"

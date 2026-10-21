#!/usr/bin/env bash
# 맥(macOS)·리눅스 공통 설정. 다른 .sh 스크립트가 source 로 읽는다.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/logs"; mkdir -p "$LOGS"
BASE="${BASE:-http://127.0.0.1:8097}"
# JDK 21 찾기: JAVA_HOME 이 없으면 macOS 의 java_home 으로 21 버전을 고른다
if [ -z "$JAVA_HOME" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"; export JAVA_HOME
fi
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
# Android SDK (Android Studio 기본 설치 위치)
ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_SDK/platform-tools/adb"; command -v adb >/dev/null 2>&1 && ADB="$(command -v adb)"

#!/bin/bash
# ===== 자동 깃허브 올리기 끄기 (2026-11-26) =====
#  자동_깃허브_올리기_켜기.command 가 등록한 5분마다 올리기를 멈춘다. git 연결과 파일은 그대로 둔다.
LABEL="com.sbada.autopush"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1
rm -f "$PLIST"
echo "자동 올리기를 껐어요. 다시 켜려면 자동_깃허브_올리기_켜기.command"
sleep 3

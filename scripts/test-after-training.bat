@echo off
chcp 65001 > nul
rem ===== 학습이 끝나길 기다렸다가 자동 점검(학습한 AI · API · flutter · 에뮬레이터 앱 실행 · 오류 로그) =====
rem  finetune-and-apply.bat 과 같이 켜 두면 됨. 결과: logs\test-after-training.txt
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0test-after-training.ps1"
pause

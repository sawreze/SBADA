@echo off
chcp 65001 > nul
rem ===== 시연 도우미: 다른 사용자(준호)가 민지의 물건에 '바로 바꾸기'를 보낸다 =====
rem 에뮬레이터에 민지로 로그인해 둔 상태에서 실행하면, 알림 표시줄에 '교환 요청이 왔어요' 푸시가 뜬다.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0demo-send-request.ps1" %*

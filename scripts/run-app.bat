@echo off
chcp 65001 > nul
rem ===== 에뮬레이터에서 앱 실행. 서버(run-server.bat)를 먼저 켠다 =====
cd /d "%~dp0..\sbada_app"
call flutter devices
call flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8097 %*

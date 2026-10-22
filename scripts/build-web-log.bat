@echo off
chcp 65001 > nul
rem ===== 앱을 웹으로 빌드(build-web.bat 과 같음) + 출력을 logs\build-web.log 에 남긴다 =====
cd /d "%~dp0.."
if not exist logs mkdir logs
cd /d "%~dp0..\sbada_app"
call flutter build web --release --base-href /app/ --no-web-resources-cdn --dart-define=API_BASE_URL=http://127.0.0.1:8097 > "%~dp0..\logs\build-web.log" 2>&1
echo exit=%errorlevel% >> "%~dp0..\logs\build-web.log"
timeout /t 3 > nul

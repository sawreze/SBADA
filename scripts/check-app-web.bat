@echo off
chcp 65001 > nul
rem ===== 앱 검사(flutter analyze · test) + 웹 빌드(test.html 용). 결과 logs\check-app-web.log =====
cd /d "%~dp0..\sbada_app"
set "LOG=%~dp0..\logs\check-app-web.log"
echo [analyze] > "%LOG%"
call flutter analyze >> "%LOG%" 2>&1
echo analyze-exit=%errorlevel% >> "%LOG%"
echo [test] >> "%LOG%"
call flutter test >> "%LOG%" 2>&1
echo test-exit=%errorlevel% >> "%LOG%"
echo [build web] >> "%LOG%"
call flutter build web --release --base-href /app/ --no-web-resources-cdn --dart-define=API_BASE_URL=http://127.0.0.1:8097 >> "%LOG%" 2>&1
echo build-exit=%errorlevel% >> "%LOG%"
echo DONE >> "%LOG%"
timeout /t 3 > nul

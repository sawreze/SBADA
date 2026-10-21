@echo off
chcp 65001 > nul
rem ===== server tests + flutter pub get / analyze / test. Results: logs\check-*.log, logs\check-all.done =====
set ROOT=%~dp0..
set LOGS=%ROOT%\logs
if not exist "%LOGS%" mkdir "%LOGS%"
del /q "%LOGS%\check-all.done" 2>nul
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"

echo ==== 1/2 server tests ====
cd /d "%ROOT%\sbada-server"
call gradlew.bat test --console=plain > "%LOGS%\check-server.log" 2>&1
set SERVER_EXIT=%ERRORLEVEL%

echo ==== 2/2 flutter pub get / analyze / test ====
cd /d "%ROOT%\sbada_app"
call flutter pub get > "%LOGS%\check-app.log" 2>&1
call flutter analyze >> "%LOGS%\check-app.log" 2>&1
set ANALYZE_EXIT=%ERRORLEVEL%
call flutter test >> "%LOGS%\check-app.log" 2>&1
set TEST_EXIT=%ERRORLEVEL%

echo SERVER_EXIT=%SERVER_EXIT% ANALYZE_EXIT=%ANALYZE_EXIT% TEST_EXIT=%TEST_EXIT% > "%LOGS%\check-all.done"
type "%LOGS%\check-all.done"

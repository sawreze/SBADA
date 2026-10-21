@echo off
chcp 65001 > nul
rem ===== stop server on 8097, start it again (new code, new keys). Log: logs\2-server.log =====
set ROOT=%~dp0..
set LOGS=%ROOT%\logs
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8097" ^| findstr LISTENING') do taskkill /PID %%p /F
timeout /t 3 > nul
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
start "sbada-server" /min cmd /c "cd /d %ROOT%\sbada-server && call gradlew.bat bootRun --console=plain --args=--spring.profiles.active=dev > %LOGS%\2-server.log 2>&1"

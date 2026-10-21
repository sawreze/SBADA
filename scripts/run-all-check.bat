@echo off
chcp 65001 > nul
rem ===== 스바다 전체 점검: 서버 테스트 -> 서버 실행 -> 로컬호스트 smoke 테스트 =====
rem 결과는 sbada\logs 폴더에 남는다. 서버는 "sbada-server" 창에서 계속 실행된다(stop-server.bat 로 종료).
set ROOT=%~dp0..
set LOGS=%ROOT%\logs
if not exist "%LOGS%" mkdir "%LOGS%"
del /q "%LOGS%\run-all.done" 2>nul
echo [%DATE% %TIME%] start > "%LOGS%\run-all.log"

if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
java -version >> "%LOGS%\run-all.log" 2>&1

echo.
echo ==== 1/3 서버 테스트 (JUnit + MockMvc + WebSocket) ====
cd /d "%ROOT%\sbada-server"
call gradlew.bat test --console=plain > "%LOGS%\1-test.log" 2>&1
set TEST_EXIT=%ERRORLEVEL%
echo TEST_EXIT=%TEST_EXIT% >> "%LOGS%\run-all.log"
type "%LOGS%\1-test.log" | findstr /i /c:"PASSED" /c:"FAILED" /c:"BUILD"
if not "%TEST_EXIT%"=="0" (
  echo 테스트 실패 - logs\1-test.log 를 확인하세요
  echo FAIL_TEST > "%LOGS%\run-all.done"
  goto :end
)

echo.
echo ==== 2/3 서버 실행 (포트 8097) ====
netstat -ano | findstr ":8097" | findstr LISTENING > nul && (
  echo 이미 8097 포트에서 서버가 실행 중입니다. >> "%LOGS%\run-all.log"
  goto :wait
)
start "sbada-server" /min cmd /c "cd /d "%ROOT%\sbada-server" && call gradlew.bat bootRun --console=plain --args=--spring.profiles.active=dev > "%LOGS%\2-server.log" 2>&1"
start "sbada-server-log" powershell -NoProfile -Command "Start-Sleep 3; Get-Content -Wait -Path '%LOGS%\2-server.log'"

:wait
set /a N=0
:loop
set CODE=
for /f %%c in ('curl -s -o nul -w "%%{http_code}" http://127.0.0.1:8097/api/health') do set CODE=%%c
if "%CODE%"=="200" goto :up
set /a N+=1
if %N% GEQ 60 (
  echo 서버가 5분 안에 켜지지 않았습니다 - logs\2-server.log 확인
  echo FAIL_SERVER >> "%LOGS%\run-all.log"
  echo FAIL_SERVER > "%LOGS%\run-all.done"
  goto :end
)
echo 서버 기다리는 중... %N%
timeout /t 5 /nobreak > nul
goto :loop

:up
echo SERVER_UP after %N% tries >> "%LOGS%\run-all.log"
echo.
echo ==== 3/3 로컬호스트 smoke 테스트 (cmd + curl) ====
call "%ROOT%\scripts\smoke-test.bat" > "%LOGS%\3-smoke.log" 2>&1
copy /y "%ROOT%\scripts\smoke-result.txt" "%LOGS%\smoke-result.txt" > nul
type "%ROOT%\scripts\smoke-result.txt"
echo OK > "%LOGS%\run-all.done"

:end
echo [%DATE% %TIME%] end >> "%LOGS%\run-all.log"
echo.
echo 끝. 결과 폴더: %LOGS%
pause

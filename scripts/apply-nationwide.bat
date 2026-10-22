@echo off
chcp 65001 > nul
rem ===== 전국 상품 500개 적용: AI 사진 생성 → DB 백업 → 서버 테스트 → 서버 재시작(V9 · V10) → 앱 점검 =====
rem  더블클릭 한 번이면 끝까지 진행(약 40~60분). 기록: logs\apply-nationwide.log, 점검 결과 logs\test-after-training.txt
if "%~1"==":main" goto main
cd /d "%~dp0.."
if not exist logs mkdir logs
set "PYTHONUTF8=1"
set "PYTHONUNBUFFERED=1"
set "HF_HUB_DISABLE_SYMLINKS_WARNING=1"
call "%~f0" :main 2>&1 | powershell -NoProfile -Command "$OutputEncoding=[Text.Encoding]::UTF8; $input | Tee-Object -FilePath logs\apply-nationwide.log"
echo.
echo 끝. 기록: logs\apply-nationwide.log
pause
exit /b

rem 아래는 위에서 자기 자신을 ":main" 인자로 다시 불러 실행(출력을 로그로 남기려고)
:main
cd /d "%~dp0.."
set "ROOT=%CD%"
echo [%date% %time%] 시작
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PY=%ROOT%\training\.venv\Scripts\python.exe"

echo [1/5] GPU 비우기(학습한 AI 서버 잠깐 끄기)
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='python.exe'\" | Where-Object { $_.CommandLine -like '*serve_lora*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; 'stopped ' + $_.ProcessId }"
timeout /t 3 > nul

echo [2/5] 상품 사진 500장 만들기(SDXL-Turbo, 처음이면 모델 약 7GB 다운로드)
"%PY%" -c "import diffusers" > nul 2>&1 || "%PY%" -m pip install diffusers
"%PY%" -u training\make_photos.py
if not exist sbada-server\src\main\resources\db\migration\V10__nationwide_photos.sql (echo 오류: 사진 · V10 생성 실패 & exit /b 1)

echo [3/5] 서버 끄고 DB 백업
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8097" ^| findstr LISTENING') do taskkill /PID %%p /F > nul
timeout /t 5 > nul
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%t"
if not exist sbada-server\data\backup mkdir sbada-server\data\backup
if exist sbada-server\data\sbada.mv.db copy /y sbada-server\data\sbada.mv.db sbada-server\data\backup\sbada-before-v9-%TS%.mv.db > nul && echo  백업: data\backup\sbada-before-v9-%TS%.mv.db

echo [4/5] 서버 테스트(JUnit)
cd /d "%ROOT%\sbada-server"
call gradlew.bat test --console=plain > "%ROOT%\logs\nation-server-test.log" 2>&1
if errorlevel 1 (echo  !! 서버 테스트 실패 - logs\nation-server-test.log 확인) else (echo  서버 테스트 통과)
cd /d "%ROOT%"

echo [5/5] 서버 다시 켜기(V9 · V10 적용) + 학습한 AI 서버
call scripts\restart-server-finetuned.bat
powershell -NoProfile -Command "try { $r = Invoke-RestMethod -TimeoutSec 20 'http://127.0.0.1:8097/api/items?size=1'; 'items API OK' } catch { 'items API 실패: ' + $_ }"
echo  앱 점검(지도 좌표 반영된 앱 빌드 · 설치 · 실행 · 오류 로그) 시작
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test-after-training.ps1
echo [%date% %time%] 전부 끝
exit /b

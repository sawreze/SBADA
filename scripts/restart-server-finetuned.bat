@echo off
chcp 65001 > nul
rem ===== 학습한 스바다 Gemma(LoRA)로 서버 다시 켜기 =====
rem  1) training\serve_lora.py 를 띄움(Ollama 와 같은 형식, http://127.0.0.1:11435, 기록 logs\serve-lora.log)
rem  2) 8097 서버를 끄고, 환경 변수로 AI 제공자를 ollama(=학습한 모델)로 바꿔 다시 켬(기록 logs\2-server.log)
rem     application-local.properties(키 파일)는 건드리지 않는다. 환경 변수가 설정 파일보다 우선한다.
rem  되돌리기: scripts\restart-server.bat (환경 변수 없이 켜져서 원래 설정 = Google Gemma)
set "ROOT=%~dp0.."
set "LOGS=%ROOT%\logs"
if not exist "%LOGS%" mkdir "%LOGS%"
if not exist "%ROOT%\training\out-final\lora\adapter_config.json" (echo 오류: 학습 결과가 없어요. training\finetune-and-apply.bat 먼저 & exit /b 1)

echo  - 학습한 AI 서버 켜는 중(모델 불러오기 약 1분)
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='python.exe'\" | Where-Object { $_.CommandLine -like '*serve_lora*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
start "sbada-ai" /min cmd /c "cd /d %ROOT% && set PYTHONUTF8=1&& set PYTHONUNBUFFERED=1&& training\.venv\Scripts\python.exe -u training\serve_lora.py > %LOGS%\serve-lora.log 2>&1"
set /a N=0
:wait_ai
timeout /t 5 > nul
curl -s -o nul -w "%%{http_code}" http://127.0.0.1:11435/api/tags 2>nul | findstr "200" > nul && goto ai_ok
set /a N+=1
if %N% lss 60 goto wait_ai
echo  !! 학습한 AI 서버가 5분 안에 안 켜졌어요. logs\serve-lora.log 확인
exit /b 1
:ai_ok
echo  - 학습한 AI 서버 준비 완료 (http://127.0.0.1:11435)

echo  - 스바다 서버(8097)를 학습한 AI 설정으로 다시 켜는 중
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8097" ^| findstr LISTENING') do taskkill /PID %%p /F > nul
timeout /t 3 > nul
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
set "SBADA_AI_PROVIDER=ollama"
set "SBADA_AI_OLLAMA_URL=http://127.0.0.1:11435"
set "SBADA_AI_OLLAMA_MODEL=sbada-gemma-lora"
set "SBADA_AI_TIMEOUT_SECONDS=95"
start "sbada-server" /min cmd /c "cd /d %ROOT%\sbada-server && call gradlew.bat bootRun --console=plain --args=--spring.profiles.active=dev > %LOGS%\2-server.log 2>&1"
set /a N=0
:wait_srv
timeout /t 5 > nul
netstat -ano | findstr ":8097" | findstr LISTENING > nul && goto srv_ok
set /a N+=1
if %N% lss 36 goto wait_srv
echo  !! 서버가 3분 안에 안 켜졌어요. logs\2-server.log 확인
exit /b 1
:srv_ok
timeout /t 5 > nul
echo  - 서버 AI 상태:
curl -s http://127.0.0.1:8097/api/ai/status
echo.
echo  완료: 이제 앱에서 사진을 올리면 학습한 모델이 가격을 매겨요(한 번에 30~80초).
exit /b 0

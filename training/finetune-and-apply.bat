@echo off
chcp 65001 > nul
rem ===== 스바다: 학습(사용자 가격표 100개 + 상품 사진 50장) → 학습한 AI 띄우기 → 서버가 그 AI 를 쓰게 전환 =====
rem  더블클릭 한 번이면 끝까지 혼자 진행(약 1시간). 기록: logs\finetune-final.log
rem  되돌리기(Google Gemma 로): scripts\restart-server.bat
if "%~1"==":main" goto main
cd /d "%~dp0.."
if not exist logs mkdir logs
set "PYTHONUTF8=1"
set "PYTHONUNBUFFERED=1"
set "HF_HUB_DISABLE_SYMLINKS_WARNING=1"
call "%~f0" :main 2>&1 | powershell -NoProfile -Command "$OutputEncoding=[Text.Encoding]::UTF8; $input | Tee-Object -FilePath logs\finetune-final.log"
echo.
echo 끝. 기록: logs\finetune-final.log
pause
exit /b

rem 아래는 위에서 자기 자신을 ":main" 인자로 다시 불러 실행(출력을 로그로 남기려고)
:main
cd /d "%~dp0.."
echo [%date% %time%] 시작
echo [0/3] 돌고 있던 이전 학습 끄기(GPU 비우기)
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='python.exe'\" | Where-Object { $_.CommandLine -like '*train_vision*' -or $_.CommandLine -like '*train_qlora*' -or $_.CommandLine -like '*serve_lora*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; 'stopped ' + $_.ProcessId }"
timeout /t 5 > nul
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist training\data\train.jsonl java -Dfile.encoding=UTF-8 training\MakeDataset.java .
if not exist training\data\user_prices.json (echo 오류: training\data\user_prices.json 이 없어요 & exit /b 1)
if not exist training\.venv\Scripts\python.exe (echo 오류: 먼저 training\setup.bat 을 실행하세요 & exit /b 1)
training\.venv\Scripts\python.exe -c "import PIL, torchvision" > nul 2>&1 || (
  training\.venv\Scripts\python.exe -m pip install pillow
  training\.venv\Scripts\python.exe -m pip install torchvision --index-url https://download.pytorch.org/whl/cu126
)
if exist training\out-final\DONE del training\out-final\DONE
echo [1/3] 학습 (학습 전 평가 → 학습 → 학습 후 평가)
training\.venv\Scripts\python.exe -u training\train_final.py
if not exist training\out-final\DONE (echo 오류: 학습이 끝나지 않았어요. 위 기록 확인 & exit /b 1)
echo [2/3] 학습한 AI 띄우기 + [3/3] 서버 전환
call scripts\restart-server-finetuned.bat
echo [%date% %time%] 전부 끝
exit /b

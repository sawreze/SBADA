@echo off
chcp 65001 > nul
rem ===== 스바다 Gemma '사진 → 가격' QLoRA 학습 =====
rem  실제 상품 사진 + 가격(시연 상품 50개 + training\photos\prices.csv 추가분)으로 사진 가격 오차를 줄인다
rem  학습 전 평가 → 학습 → 학습 후 평가(오차 비교). 결과 training\out-vision\lora, 기록 logs\qlora-vision.log
if "%~1"==":main" goto main
cd /d "%~dp0.."
if not exist logs mkdir logs
set "PYTHONUTF8=1"
set "PYTHONUNBUFFERED=1"
set "HF_HUB_DISABLE_SYMLINKS_WARNING=1"
call "%~f0" :main 2>&1 | powershell -NoProfile -Command "$OutputEncoding=[Text.Encoding]::UTF8; $input | Tee-Object -FilePath logs\qlora-vision.log"
echo.
echo 끝. 기록: logs\qlora-vision.log
pause
exit /b

rem 아래는 위에서 자기 자신을 ":main" 인자로 다시 불러 실행(출력을 로그로 남기려고)
:main
cd /d "%~dp0.."
echo [%date% %time%] vision train 시작
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist training\data\train.jsonl java -Dfile.encoding=UTF-8 training\MakeDataset.java .
if not exist training\.venv\Scripts\python.exe (echo 오류: 먼저 training\setup.bat 을 실행하세요 & exit /b 1)
rem 사진 학습에 필요한 패키지(처음 한 번만 설치): Pillow(사진 읽기) · torchvision(Gemma 사진 전처리)
training\.venv\Scripts\python.exe -c "import PIL, torchvision" > nul 2>&1 || (
  echo 사진 학습용 패키지 설치 중 ^(처음 한 번, 몇 분^)...
  training\.venv\Scripts\python.exe -m pip install pillow
  training\.venv\Scripts\python.exe -m pip install torchvision --index-url https://download.pytorch.org/whl/cu126
)
training\.venv\Scripts\python.exe -c "import torch, torchvision, PIL; print('torch', torch.__version__, 'torchvision', torchvision.__version__, 'cuda', torch.cuda.is_available())"
training\.venv\Scripts\python.exe -u training\train_vision.py
echo [%date% %time%] vision train 끝 (종료 코드 %errorlevel%)
exit /b

@echo off
chcp 65001 > nul
rem ===== 스바다 Gemma QLoRA 학습 준비(최초 1회) =====
rem  필요: Python 3.12 또는 3.11 (명령 프롬프트에서 py install 3.12, 설치 때 "Add python.exe to PATH" 체크), NVIDIA 드라이버(RTX 3060 OK)
rem  결과: training\.venv  (이 폴더 안에만 설치 → 다른 프로그램에 영향 없음)
rem  기록: logs\qlora-setup.log  (10~20분, 몇 GB 다운로드)
cd /d "%~dp0"
if not exist "..\logs" mkdir "..\logs"
set "LOG=%~dp0..\logs\qlora-setup.log"
echo 설치 중... 10~20분 걸려요. 진행 기록: logs\qlora-setup.log
call :main > "%LOG%" 2>&1
type "%LOG%" | findstr /i "python GPU 오류 ERROR 완료 먼저"
echo.
echo 끝. 전체 기록: logs\qlora-setup.log
pause
exit /b

:main
echo [%date% %time%] setup 시작
set "PY="
rem PyTorch(CUDA)는 Python 3.12 / 3.11 이 가장 안전(3.15 는 PyTorch 가 없음) → 3.12 → 3.11 순서로 찾는다
where py > nul 2>&1 && py -3.12 --version > nul 2>&1 && set "PY=py -3.12"
if not defined PY (where py > nul 2>&1 && py -3.11 --version > nul 2>&1 && set "PY=py -3.11")
if not defined PY (
  echo 오류: Python 3.12 또는 3.11 이 없어요. 지금 깔린 버전:
  python --version
  echo 먼저 명령 프롬프트에서  py install 3.12   를 실행한 뒤 setup.bat 을 다시 실행하세요.
  exit /b 1
)
%PY% --version
%PY% -c "import sys; print('python 경로:', sys.executable)"
rem 예전에 다른 버전(예: 3.15)으로 만든 .venv 는 지우고 다시 만든다
if exist .venv\Scripts\python.exe (
  .venv\Scripts\python.exe -c "import sys; sys.exit(0 if sys.version_info[:2] in ((3,11),(3,12)) else 1)" || (echo 기존 .venv 버전이 맞지 않아 다시 만듭니다 & rmdir /s /q .venv)
)
if not exist .venv\Scripts\python.exe %PY% -m venv .venv
if not exist .venv\Scripts\python.exe (echo 오류: .venv 를 만들지 못했어요 & exit /b 1)
set "VPY=%~dp0.venv\Scripts\python.exe"
"%VPY%" -m pip install --upgrade pip
rem PyTorch(CUDA 12.6 빌드) → 학습 라이브러리
"%VPY%" -m pip install torch --index-url https://download.pytorch.org/whl/cu126
"%VPY%" -m pip install -r requirements.txt
"%VPY%" -c "import torch; print('torch', torch.__version__, 'cuda', torch.version.cuda); print('GPU:', torch.cuda.get_device_name(0) if torch.cuda.is_available() else '없음(학습 불가)')"
"%VPY%" -c "import transformers, peft, bitsandbytes; print('transformers', transformers.__version__, 'peft', peft.__version__, 'bitsandbytes', bitsandbytes.__version__)"
echo [%date% %time%] setup 완료
echo 다음: Hugging Face 에서 Gemma 4 사용 동의 후  huggingface-cli login  →  train.bat
exit /b

@echo off
chcp 65001 > nul
rem ===== 스바다 Gemma QLoRA 학습 준비(최초 1회) =====
rem  필요: Python 3.11 (python.org, 설치 때 "Add python.exe to PATH" 체크), NVIDIA 드라이버(RTX 3060 OK)
rem  결과: training\.venv  (이 폴더 안에만 설치 → 다른 프로그램에 영향 없음)
cd /d "%~dp0"
where python > nul 2>&1 || (echo [먼저] Python 3.11 을 설치해 주세요: https://www.python.org/downloads/ & exit /b 1)
python -m venv .venv
call .venv\Scripts\activate
python -m pip install --upgrade pip
rem PyTorch(CUDA 12.4 빌드) → 학습 라이브러리
pip install torch --index-url https://download.pytorch.org/whl/cu124
pip install -r requirements.txt
python -c "import torch; print('GPU:', torch.cuda.get_device_name(0) if torch.cuda.is_available() else '없음(학습 불가)')"
echo.
echo 다음: Hugging Face 에서 Gemma 4 사용 동의 후  huggingface-cli login  →  train.bat

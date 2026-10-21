@echo off
chcp 65001 > nul
rem ===== 스바다 Gemma QLoRA 학습 =====
rem  1) 데이터 다시 만들기(상품 · 프롬프트가 바뀌었을 때)  2) 학습(RTX 3060 기준 대략 20~40분)  3) 결과 training\out
cd /d "%~dp0.."
java -Dfile.encoding=UTF-8 training\MakeDataset.java . || exit /b 1
call training\.venv\Scripts\activate || (echo 먼저 training\setup.bat 을 실행하세요 & exit /b 1)
python training\train_qlora.py --model google/gemma-4-E4B-it %* 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath logs\qlora-train.log"
echo.
echo 다음: training\README.md 의 '4. Ollama 로 앱에 연결'

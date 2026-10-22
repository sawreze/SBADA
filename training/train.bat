@echo off
chcp 65001 > nul
rem ===== 스바다 Gemma QLoRA 학습 =====
rem  1) 데이터 다시 만들기  2) 학습 전 평가 → 학습(RTX 3060 기준 20~40분) → 학습 후 평가 → 합치기  3) 결과 training\out
rem  기록: logs\qlora-train.log (전부)
if "%~1"==":main" goto main
cd /d "%~dp0.."
if not exist logs mkdir logs
set "PYTHONUTF8=1"
set "PYTHONUNBUFFERED=1"
call "%~f0" :main 2>&1 | powershell -NoProfile -Command "$OutputEncoding=[Text.Encoding]::UTF8; $input | Tee-Object -FilePath logs\qlora-train.log"
echo.
echo 끝. 기록: logs\qlora-train.log   다음: training\README.md 의 '4. Ollama 로 앱에 연결'
pause
exit /b

rem 아래는 위에서 자기 자신을 ":main" 인자로 다시 불러 실행(출력을 로그로 남기려고)
:main
cd /d "%~dp0.."
echo [%date% %time%] train 시작
rem java 가 PATH 에 없으면 서버 스크립트와 같은 JDK 21 을 쓴다
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [1/2] 학습 데이터 만들기 (MakeDataset.java)
java -Dfile.encoding=UTF-8 training\MakeDataset.java . || (echo 오류: 데이터 만들기 실패 & exit /b 1)
if not exist training\.venv\Scripts\python.exe (echo 오류: 먼저 training\setup.bat 을 실행하세요 & exit /b 1)
echo [2/2] 학습 시작 (처음이면 모델 약 16GB 다운로드부터)
training\.venv\Scripts\python.exe -u training\train_qlora.py --model google/gemma-4-E4B-it
echo [%date% %time%] train 끝 (종료 코드 %errorlevel%)
exit /b

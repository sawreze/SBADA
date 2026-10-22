@echo off
chcp 65001 > nul
rem ===== Vercel 에 올릴 웹 화면 만들기 → deploy\vercel 폴더(이 폴더를 Vercel 이 그대로 내보냄) =====
rem  사용법: build-vercel.bat [서버주소]   예) build-vercel.bat https://sbada-server.onrender.com
rem  서버 주소를 안 적으면 Render 기본 주소. Render 에서 받은 주소가 다르면 그 주소로 다시 실행 → 커밋 · 푸시
set SERVER=%~1
if "%SERVER%"=="" set SERVER=https://sbada-server.onrender.com
cd /d "%~dp0..\sbada_app"
call flutter build web --release --base-href / --no-web-resources-cdn --dart-define=API_BASE_URL=%SERVER%
if errorlevel 1 (
  echo [실패] 웹 빌드 오류. 위 메시지를 확인하세요.
  exit /b 1
)
robocopy build\web ..\deploy\vercel /MIR /XF vercel.json /NFL /NDL /NJH /NJS > nul
echo.
echo 완료: deploy\vercel 에 웹 화면을 만들었어요(서버 주소 %SERVER%). git add · commit · push 하면 Vercel 이 다시 배포해요.

@echo off
chcp 65001 > nul
rem ===== 스바다 앱을 웹으로 빌드한다(브라우저 테스트 화면 http://127.0.0.1:8097/test.html 용) =====
rem  결과: sbada_app\build\web  →  서버가 /app/ 주소로 내려 준다(WebAppConfig). 서버 재시작은 필요 없다.
rem  --no-web-resources-cdn : 인터넷 없이도 열리게 렌더링 파일까지 같이 넣는다
cd /d "%~dp0..\sbada_app"
call flutter build web --release --base-href /app/ --no-web-resources-cdn --dart-define=API_BASE_URL=http://127.0.0.1:8097
if errorlevel 1 (
  echo [실패] 웹 빌드 오류. 위 메시지를 확인하세요.
  exit /b 1
)
echo.
echo 완료: 브라우저에서 http://127.0.0.1:8097/test.html 를 새로고침하세요.

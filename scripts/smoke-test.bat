@echo off
chcp 65001 > nul
rem ===== cmd 로 로컬호스트 API 확인 (run-server.bat 으로 서버를 먼저 켠다) =====
rem Windows 10 이상에 들어 있는 curl.exe 를 쓴다. 한글 JSON 은 json 폴더의 UTF-8 파일로 보낸다.
set BASE=http://127.0.0.1:8097
set J=%~dp0json
set OUT=%~dp0smoke-result.txt
echo 스바다 로컬호스트 점검 %DATE% %TIME% > "%OUT%"

echo.>> "%OUT%"
echo == 1. 서버 상태 GET /api/health >> "%OUT%"
curl -s -w "\nHTTP %%{http_code}\n" %BASE%/api/health >> "%OUT%" 2>&1

echo.>> "%OUT%"
echo == 2. 공개 목록 GET /api/items  (기대: 200) >> "%OUT%"
curl -s -o nul -w "HTTP %%{http_code}\n" %BASE%/api/items >> "%OUT%" 2>&1

echo.>> "%OUT%"
echo == 3. 토큰 없이 보호 API GET /api/me  (기대: 401 AUTH_REQUIRED) >> "%OUT%"
curl -s -w "\nHTTP %%{http_code}\n" %BASE%/api/me >> "%OUT%" 2>&1

curl -s -H "Content-Type: application/json" --data-binary "@%J%\login-minji.json" %BASE%/api/auth/login > "%TEMP%\sbada-login.json"
set TOKEN=
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "(Get-Content -Raw '%TEMP%\sbada-login.json' | ConvertFrom-Json).accessToken"`) do set TOKEN=%%T
echo.>> "%OUT%"
if "%TOKEN%"=="" (
  echo == 4. 로그인 실패: 토큰을 받지 못했습니다 >> "%OUT%"
  type "%OUT%"
  exit /b 1
)
echo == 4. 로그인 POST /api/auth/login : 200, accessToken 받음(값은 숨김) >> "%OUT%"

echo.>> "%OUT%"
echo == 5. 내 정보 GET /api/me  (기대: 200) >> "%OUT%"
curl -s -o nul -w "HTTP %%{http_code}\n" -H "Authorization: Bearer %TOKEN%" %BASE%/api/me >> "%OUT%" 2>&1

echo.>> "%OUT%"
echo == 6. AI 추천 피드 GET /api/feed?limit=2 >> "%OUT%"
curl -s -w "\nHTTP %%{http_code}\n" -H "Authorization: Bearer %TOKEN%" "%BASE%/api/feed?limit=2" >> "%OUT%" 2>&1

echo.>> "%OUT%"
echo == 7. 받은 교환 요청 GET /api/exchanges  (기대: 200) >> "%OUT%"
curl -s -o nul -w "HTTP %%{http_code}\n" -H "Authorization: Bearer %TOKEN%" %BASE%/api/exchanges >> "%OUT%" 2>&1

echo.>> "%OUT%"
echo == 8. 안 읽은 알림 GET /api/notifications/unread-count >> "%OUT%"
curl -s -w "\nHTTP %%{http_code}\n" -H "Authorization: Bearer %TOKEN%" %BASE%/api/notifications/unread-count >> "%OUT%" 2>&1

echo.>> "%OUT%"
echo == 9. 가격을 몰래 넣은 등록 POST /api/items  (기대: 400 UNKNOWN_FIELD) >> "%OUT%"
curl -s -w "\nHTTP %%{http_code}\n" -H "Authorization: Bearer %TOKEN%" -H "Content-Type: application/json" --data-binary "@%J%\item-with-price.json" %BASE%/api/items >> "%OUT%" 2>&1

echo.>> "%OUT%"
echo == 10. 변조 토큰으로 공개 GET /api/items  (기대: 401 TOKEN_INVALID) >> "%OUT%"
curl -s -w "\nHTTP %%{http_code}\n" -H "Authorization: Bearer abc.def.ghi" %BASE%/api/items >> "%OUT%" 2>&1

echo.>> "%OUT%"
echo == 11. AI 상태 GET /api/ai/status >> "%OUT%"
curl -s -w "\nHTTP %%{http_code}\n" %BASE%/api/ai/status >> "%OUT%" 2>&1

type "%OUT%"
echo.
echo 결과 파일: %OUT%

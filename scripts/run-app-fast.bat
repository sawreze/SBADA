@echo off
chcp 65001 > nul
rem ===== 부드럽게 실행: profile 모드(미리 컴파일된 코드라 웹 테스트 화면처럼 매끄럽다) =====
rem 더블클릭 한 번이면: 서버가 꺼져 있으면 켜고 → 에뮬레이터가 없으면 켜고 → 앱을 profile 로 빌드 · 설치 · 실행
rem 차이: 핫 리로드(r) 안 됨. 코드를 고치면 이 파일을 다시 실행. 첫 빌드는 1~3분
rem 기록: logs\run-app-fast.log
setlocal EnableDelayedExpansion
set "ROOT=%~dp0.."
set "LOGS=%ROOT%\logs"
if not exist "%LOGS%" mkdir "%LOGS%"
set "LOG=%LOGS%\run-app-fast.log"
set "SDK=%LOCALAPPDATA%\Android\Sdk"
if defined ANDROID_HOME set "SDK=%ANDROID_HOME%"
if defined ANDROID_SDK_ROOT set "SDK=%ANDROID_SDK_ROOT%"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
echo [%date% %time%] run-app-fast 시작 > "%LOG%"

rem ---- 1) 서버 ----
netstat -ano | findstr ":8097" | findstr LISTENING > nul
if errorlevel 1 (
  echo [1/3] 서버가 꺼져 있어서 켭니다 ^(restart-server.bat, 약 20~40초^)
  echo [1/3] server start >> "%LOG%"
  call "%~dp0restart-server.bat"
  for /l %%i in (1,1,60) do (
    netstat -ano | findstr ":8097" | findstr LISTENING > nul && goto server_ok
    timeout /t 2 > nul
  )
  echo  !! 서버가 2분 안에 안 켜졌어요. logs\2-server.log 확인
  echo [1/3] server FAILED >> "%LOG%"
) else (
  echo [1/3] 서버 켜져 있음
)
:server_ok
echo [1/3] server ok >> "%LOG%"

rem ---- 2) 에뮬레이터 ----
"%ADB%" devices | findstr /r "emulator-[0-9]*.*device$" > nul
if errorlevel 1 (
  set "AVD="
  for /f "delims=" %%a in ('"%EMU%" -list-avds 2^>nul') do if not defined AVD set "AVD=%%a"
  if not defined AVD (
    echo  !! 에뮬레이터^(AVD^)가 없어요. Android Studio ^> Device Manager 에서 하나 만드세요
    echo [2/3] no AVD >> "%LOG%"
    pause
    exit /b 1
  )
  echo [2/3] 에뮬레이터 !AVD! 켜는 중 ^(GPU 가속, 빠른 부팅^)
  echo [2/3] start avd !AVD! >> "%LOG%"
  start "emulator" "%EMU%" -avd "!AVD!" -gpu host -no-boot-anim
  "%ADB%" wait-for-device
  for /l %%i in (1,1,90) do (
    for /f "delims=" %%b in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do if "%%b"=="1" goto boot_ok
    timeout /t 2 > nul
  )
) else (
  echo [2/3] 에뮬레이터 켜져 있음
)
:boot_ok
echo [2/3] emulator ok >> "%LOG%"
rem 화면 전환 애니메이션을 줄여 조작이 더 가볍게(되돌리기: 1)
"%ADB%" shell settings put global window_animation_scale 0.5 > nul 2>&1
"%ADB%" shell settings put global transition_animation_scale 0.5 > nul 2>&1

rem ---- 3) 앱 (profile) ----
echo [3/3] 앱 빌드 · 설치 · 실행 ^(profile^). 첫 빌드는 1~3분. 이 창은 닫지 마세요 ^(닫아도 앱은 에뮬레이터에 남음^)
cd /d "%ROOT%\sbada_app"
call flutter pub get >> "%LOG%" 2>&1
call flutter run --profile --dart-define=API_BASE_URL=http://10.0.2.2:8097 %* 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%LOG%' -Append"

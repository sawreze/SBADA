@echo off
chcp 65001 > nul
rem ===== 에뮬레이터가 느린 이유 점검: 가속(하이퍼바이저) · 시스템 이미지 · GPU · RAM · 해상도 =====
rem 결과: logs\emulator-check.txt (Claude 에게 "에뮬레이터 점검했어" 라고 말하면 읽어서 고칠 점을 알려 줌)
set "LOG=%~dp0..\logs\emulator-check.txt"
if not exist "%~dp0..\logs" mkdir "%~dp0..\logs"
call :main > "%LOG%" 2>&1
type "%LOG%"
echo.
echo 저장: %LOG%
pause
exit /b

:main
set "SDK=%LOCALAPPDATA%\Android\Sdk"
if defined ANDROID_HOME set "SDK=%ANDROID_HOME%"
if defined ANDROID_SDK_ROOT set "SDK=%ANDROID_SDK_ROOT%"
echo ===== 1) 가속 확인 (emulator -accel-check) =====
if exist "%SDK%\emulator\emulator.exe" ("%SDK%\emulator\emulator.exe" -accel-check) else (echo emulator.exe 없음: %SDK%)
echo.
echo ===== 2) CPU · RAM =====
wmic cpu get Name,NumberOfCores,NumberOfLogicalProcessors /value 2>nul | findstr "="
wmic computersystem get TotalPhysicalMemory /value 2>nul | findstr "="
echo.
echo ===== 3) 에뮬레이터(AVD)별 설정 =====
for /d %%D in ("%USERPROFILE%\.android\avd\*.avd") do (
  echo --- %%~nD
  findstr /i /b "abi.type image.sysdir.1 tag.id hw.gpu.mode hw.gpu.enabled hw.ramSize vm.heapSize hw.cpu.ncore hw.lcd.width hw.lcd.height hw.lcd.density fastboot.forceColdBoot fastboot.forceFastBoot" "%%D\config.ini"
)
echo.
echo ===== 4) 켜져 있는 기기 =====
if exist "%SDK%\platform-tools\adb.exe" ("%SDK%\platform-tools\adb.exe" devices -l) else (echo adb.exe 없음)
exit /b

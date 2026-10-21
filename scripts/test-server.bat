@echo off
chcp 65001 > nul
rem ===== 서버 단위·통합 테스트 (메모리 DB, AI 미사용) =====
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0..\sbada-server"
call gradlew.bat test --console=plain
echo.
echo 결과 보고서: %CD%\build\reports\tests\test\index.html

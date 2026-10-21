@echo off
chcp 65001 > nul
rem ===== 스바다 서버 실행 (dev 프로필: H2 콘솔 켜짐) =====
rem JDK 21 경로가 다르면 아래 줄을 자기 PC 경로로 바꾸거나, JAVA_HOME 을 미리 설정한다.
if "%JAVA_HOME%"=="" if exist "C:\dev\jdk-21\bin\java.exe" set "JAVA_HOME=C:\dev\jdk-21"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"
java -version
cd /d "%~dp0..\sbada-server"
echo.
echo  주소: http://127.0.0.1:8097/   (에뮬레이터: http://10.0.2.2:8097)
echo  H2 콘솔: http://127.0.0.1:8097/h2-console  JDBC URL: jdbc:h2:file:./data/sbada  User: sa
echo  종료: Ctrl+C
echo.
call gradlew.bat bootRun --console=plain --args="--spring.profiles.active=dev"

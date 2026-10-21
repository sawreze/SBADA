@echo off
chcp 65001 > nul
rem ===== 8097 포트에서 실행 중인 스바다 서버 종료 =====
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8097" ^| findstr LISTENING') do (
  echo 서버 PID %%p 종료
  taskkill /PID %%p /F
)
echo 완료
timeout /t 3 > nul

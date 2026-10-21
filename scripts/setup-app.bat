@echo off
chcp 65001 > nul
rem ===== Flutter 앱 처음 준비: android 폴더 생성(기존 파일은 덮어쓰지 않음) + 패키지 받기 =====
cd /d "%~dp0..\sbada_app"
call flutter create --org com.sbada --project-name sbada --platforms android .
call flutter pub get
call flutter analyze
call flutter test

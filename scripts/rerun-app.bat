@echo off
chcp 65001 > nul
rem ===== rebuild + reinstall the app on the running emulator (needed after adding packages like webview_flutter) =====
cd /d "%~dp0..\sbada_app"
call flutter pub get
call flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8097

@echo off
chcp 65001 > nul
rem ===== source + trained LoRA backup to the handoff folder (secret files excluded). log: logs\backup.log =====
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0backup-to-handoff.ps1" > "%~dp0..\logs\backup.log" 2>&1
type "%~dp0..\logs\backup.log"

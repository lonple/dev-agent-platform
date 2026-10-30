@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Starting DAP (backend :8200, frontend :8201, agent :8202)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-dev.ps1"
if errorlevel 1 pause

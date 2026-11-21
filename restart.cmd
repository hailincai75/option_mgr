@echo off
rem Double-click to restart Option Manager (backend and frontend). See restart.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart.ps1" %*
pause

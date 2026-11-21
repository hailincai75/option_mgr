@echo off
rem Double-click to stop Option Manager. See stop.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
pause

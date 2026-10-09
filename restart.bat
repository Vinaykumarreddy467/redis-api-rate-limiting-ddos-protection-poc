@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  restart.bat - restart RateGuard after code changes.
rem  Stops the console and backend (Redis keeps running), rebuilds the backend from the
rem  current source and starts everything again.
rem ---------------------------------------------------------------------------
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-project.ps1" -KeepRedis
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-project.ps1" %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" pause
exit /b %CODE%

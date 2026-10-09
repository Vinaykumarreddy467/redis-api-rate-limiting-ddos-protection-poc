@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  stop.bat - stop RateGuard: Angular console, backend, then the Redis container.
rem  Redis data is never deleted. Use  stop.bat -KeepRedis  to leave Redis running.
rem  Only processes and containers belonging to this project are touched.
rem ---------------------------------------------------------------------------
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-project.ps1" %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" pause
exit /b %CODE%

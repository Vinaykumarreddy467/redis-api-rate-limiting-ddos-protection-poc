@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  start.bat - start or restart RateGuard (Redis, Spring Boot backend, Angular console).
rem  Safe to run any time: it restarts this project's backend and console from the current
rem  source and reuses the Redis container, so stored policies are kept.
rem  Requires RATELIMIT_ADMIN_USER and RATELIMIT_ADMIN_PASSWORD to be set.
rem  Options are passed through, e.g.  start.bat -NoBrowser
rem ---------------------------------------------------------------------------
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-project.ps1" %*
set "CODE=%ERRORLEVEL%"
rem Keep the window open on failure so the error can be read when the file was double-clicked.
if not "%CODE%"=="0" pause
exit /b %CODE%

@echo off
setlocal
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js not found in PATH.
  echo.
  echo Install Node.js 22+ and try again, or run: npm run preview
  pause
  exit /b 1
)

echo ============================================
echo   Stepboy preview
echo   Browser will open automatically.
echo   Keep this window open; close it to stop.
echo ============================================
echo.

node dev-server.mjs --open

if errorlevel 1 (
  echo.
  echo Server exited with an error.
  pause
)

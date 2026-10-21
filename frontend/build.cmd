@echo off
REM  Production build with Node 22 (portable install at D:\program\app\nodejs22).
setlocal
set "NODE_DIR=D:\program\app\nodejs22"

if not exist "%NODE_DIR%\node.exe" (
  echo [build] ERROR: Node 22 not found at %NODE_DIR%
  echo [build] See node22.cmd for the download link.
  exit /b 1
)

set "PATH=%NODE_DIR%;%PATH%"
call npm run build -- %*
exit /b %ERRORLEVEL%

@echo off
REM  Start the Vite dev server with Node 22 (portable install at D:\program\app\nodejs22).
REM  Extra args are forwarded to vite, e.g.  dev.cmd --port 5173
setlocal
set "NODE_DIR=D:\program\app\nodejs22"

if not exist "%NODE_DIR%\node.exe" (
  echo [dev] ERROR: Node 22 not found at %NODE_DIR%
  echo [dev] See node22.cmd for the download link.
  exit /b 1
)

set "PATH=%NODE_DIR%;%PATH%"
call npm run dev -- %*
exit /b %ERRORLEVEL%

@echo off
REM ---------------------------------------------------------------------------
REM  Start an interactive cmd session pinned to Node 22 (portable install).
REM  The machine-wide Node 16 on PATH is left untouched.
REM
REM  Usage:  node22.cmd            -> opens a shell where `node -v` is v22
REM ---------------------------------------------------------------------------
setlocal
set "NODE_DIR=D:\program\app\nodejs22"

if not exist "%NODE_DIR%\node.exe" (
  echo [node22] ERROR: Node 22 not found at %NODE_DIR%
  echo [node22] Get it from https://nodejs.org/dist/v22.23.3/node-v22.23.3-win-x64.zip
  echo [node22] and extract the folder to %NODE_DIR%
  exit /b 1
)

set "PATH=%NODE_DIR%;%PATH%"
echo [node22] Active Node: %NODE_DIR%
cmd /k

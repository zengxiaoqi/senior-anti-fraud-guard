@echo off
chcp 65001 >nul
setlocal

REM ------------------------------------------------------------
REM  Sync WeChat MP server-domain list with the host in config.js
REM
REM  NOTE: this file is ASCII-ONLY on purpose.
REM  cmd.exe reads a .bat with the CURRENT code page until chcp
REM  takes effect; any UTF-8 multi-byte text before/around that
REM  switch gets mis-decoded and then parsed as a bogus command.
REM  Chinese comments in a .bat here always blow up.
REM
REM  Prereq: a Chrome debug session logged in to mp.weixin.qq.com
REM          Start it with:  open-wx-console.bat
REM ------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"

set "NODE="
where node >nul 2>&1
if not errorlevel 1 set "NODE=node"

if not defined NODE (
    for /d %%D in ("C:\Users\nanre\.workbuddy\binaries\node\versions\*") do (
        if exist "%%~D\node.exe" set "NODE=%%~D\node.exe"
    )
)

if not defined NODE (
    echo [ERROR] node.exe not found in PATH nor in managed versions dir.
    pause
    exit /b 1
)

cd /d "%SCRIPT_DIR%"
"%NODE%" sync-wx-domain.js

echo.
pause

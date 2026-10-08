@echo off
chcp 65001 >nul
setlocal

REM ------------------------------------------------------------
REM  Switch domain + upload the miniprogram in one shot.
REM  Run from any directory - paths are derived from %~dp0.
REM
REM    scripts\mp-publish\publish.bat
REM    scripts\mp-publish\publish.bat guard.example.com 1.0.3
REM
REM  ASCII-ONLY on purpose (see note in sync-wx-domain.bat):
REM  cmd decodes this file with the code page active at read time,
REM  so multi-byte text before chcp takes effect gets mis-decoded
REM  and parsed as a bogus command.
REM
REM  NOTE: with Cloudflare Tunnel the domain is FIXED, so the %1
REM  argument is almost never needed anymore - omit it and the
REM  recorded fixed hostname is used.
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

REM Clear proxies: miniprogram-ci picks them up and egresses from a
REM foreign IP, which WeChat rejects with "invalid ip".
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "http_proxy="
set "https_proxy="
set "ALL_PROXY="
set "all_proxy="

if not defined MP_CI_MODULES set "MP_CI_MODULES=D:\mp-ci\node_modules"

if "%~2"=="" ( set "VER=1.0.1" ) else ( set "VER=%~2" )

cd /d "%SCRIPT_DIR%"

echo.
echo [1/2] Writing domain into config.js ...
"%NODE%" set-domain.js %1
if errorlevel 1 goto fail

echo.
echo [2/2] Uploading version %VER% ...
"%NODE%" --dns-result-order=ipv4first --require "%SCRIPT_DIR%force_ipv4.js" upload.js %VER% %3
if errorlevel 1 goto fail

echo.
echo ============================================================
echo  Code uploaded. If the domain changed, also sync the WeChat
echo  backend server-domain list:
echo     scripts\mp-publish\sync-wx-domain.bat   (admin QR scan once)
echo ============================================================
echo.
pause
exit /b 0

:fail
echo.
echo Failed - see the error output above.
pause
exit /b 1

@echo off
chcp 65001 >nul
setlocal

REM ------------------------------------------------------------
REM  Launch a Chrome instance with a FIXED remote debugging port
REM  so scripts/mp-publish/sync-wx-domain.js can drive it.
REM
REM  ASCII-ONLY on purpose (see note in sync-wx-domain.bat).
REM
REM  Why a dedicated --user-data-dir:
REM    Chrome ignores --remote-debugging-port when another instance
REM    already holds that profile, and reuses a random ephemeral port
REM    (that is where the old ws://127.0.0.1:49930/... came from -
REM    dead as soon as the session ended). A separate profile keeps
REM    port 9222 deterministic and keeps your normal Chrome untouched.
REM    Login to mp.weixin.qq.com persists in this profile.
REM ------------------------------------------------------------

set "CHROME=C:\Program Files\Google\Chrome\Application\chrome.exe"
set "PROFILE=D:\mp-ci\chrome-profile"
set "PORT=9222"

if not exist "%CHROME%" (
    echo [ERROR] Chrome not found: %CHROME%
    echo Set CHROME env var or edit this file.
    pause
    exit /b 1
)

if not exist "%PROFILE%" mkdir "%PROFILE%" >nul 2>&1

echo Starting Chrome on debug port %PORT% ...
start "" "%CHROME%" ^
    --remote-debugging-port=%PORT% ^
    --user-data-dir="%PROFILE%" ^
    --no-first-run ^
    --no-default-browser-check ^
    https://mp.weixin.qq.com/

echo.
echo Waiting for the debugging endpoint ...
set "READY=0"
for /l %%i in (1,1,30) do (
    curl -s -f "http://127.0.0.1:%PORT%/json/version" >nul 2>&1
    if not errorlevel 1 (
        set "READY=1"
        goto :ready
    )
    timeout /t 1 >nul
)

:ready
if "%READY%"=="1" (
    echo Debug endpoint is up on port %PORT%.
) else (
    echo [WARN] Endpoint did not answer yet - Chrome may still be booting.
)

echo.
echo NEXT:
echo   1. In the Chrome window that just opened, log in to mp.weixin.qq.com
echo      (scan QR; this profile remembers the login afterwards).
echo   2. Then run:  sync-wx-domain.bat
echo.
pause

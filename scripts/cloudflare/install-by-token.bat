@echo off
REM ------------------------------------------------------------
REM  Install a remote-managed Cloudflare Tunnel from a token.
REM  No cert.pem needed - all login happens in the cloud.
REM
REM  Why: on this machine the browser->127.0.0.1 callback is hijacked
REM  by the system proxy, so "cloudflared tunnel login" can never hand
REM  cert.pem back. The token flow sidesteps that completely.
REM
REM  Usage (AS ADMINISTRATOR):
REM    scripts\cloudflare\install-by-token.bat eyJhIjoiXXXX...
REM
REM  Admin is required only to register the service. cloudflared itself
REM  needs no privileges - without elevation, use the scheduled-task
REM  path (see scripts\cloudflare\run-tunnel-headless.bat).
REM
REM  ASCII ONLY on purpose. cmd.exe decodes batch bytes with GBK until a
REM  "chcp 65001" runs; Chinese lines above it decode into garbage and
REM  become bogus commands. Never put Chinese in this file.
REM ------------------------------------------------------------

set "CF=D:\cloudflared\cloudflared.exe"
set "SVC=Cloudflared"
set "TOKEN=%~1"

echo.
echo ==== Cloudflare Tunnel install by token ====
echo.

if "%TOKEN%"=="" (
    echo [ERROR] Missing token argument.
    echo.
    echo   Usage: %~nx0 ^<tunnel-token^>
    echo   Source: Zero Trust ^> Networks ^> Tunnels ^> create ^> copy install command
    echo.
    pause
    exit /b 1
)

net session >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Not elevated. Right-click, then Run as administrator.
    pause
    exit /b 1
)

if not exist "%CF%" (
    echo [ERROR] Not found: %CF%
    pause
    exit /b 1
)

echo [1/4] Stopping legacy cpolar tunnel ...
taskkill /IM cpolar.exe /F >nul 2>&1
if errorlevel 1 ( echo        no running cpolar, skipped ) else ( echo        cpolar stopped )

echo [2/4] Checking for existing service ...
sc query "%SVC%" >nul 2>&1
if not errorlevel 1 (
    echo       found one, uninstalling first ...
    net stop "%SVC%" >nul 2>&1
    "%CF%" service uninstall >nul 2>&1
    echo       uninstalled.
) else (
    echo       none found.
)

echo [3/4] Installing service ...
"%CF%" service install "%TOKEN%"
if errorlevel 1 (
    echo [ERROR] Install failed. Token invalid or expired?
    pause
    exit /b 1
)

echo [4/4] Enabling autostart and starting ...
sc config "%SVC%" start= auto >nul 2>&1
sc failure "%SVC%" reset= 0 actions= restart/60000/restart/60000/restart/60000 >nul 2>&1
REM 2182 = "The requested service has already been started".
REM cloudflared's service install usually starts the service itself, so this
REM is SUCCESS. Treating every non-zero rc as failure made the installer
REM report "[WARN] failed to start" on a perfectly healthy install.
net start "%SVC%" >"%TEMP%\cf-start.txt" 2>&1
set START_RC=%errorlevel%
type "%TEMP%\cf-start.txt"
if "%START_RC%"=="0" goto svc_started
findstr /C:"2182" "%TEMP%\cf-start.txt" >nul 2>&1
if not errorlevel 1 goto svc_started
echo [WARN] Could not start service (rc=%START_RC%). Check token and network.
pause
exit /b 1
:svc_started

REM Confirm it really reached Running before declaring victory.
ping -n 4 127.0.0.1 >nul 2>&1
sc query "%SVC%" >"%TEMP%\cf-svc.txt" 2>&1
findstr /C:"RUNNING" "%TEMP%\cf-svc.txt" >nul 2>&1
if errorlevel 1 (
    echo [WARN] Service is not in RUNNING state - output above shows why.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  Cloudflare Tunnel service is up.
echo  Next: add a Public Hostname for this tunnel in Zero Trust:
echo        Subdomain: guard    Domain: chataifree.eu.org
echo        Service  : HTTP 127.0.0.1:3000
echo ============================================================
echo.
pause
exit /b 0

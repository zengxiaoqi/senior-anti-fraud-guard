@echo off
REM ------------------------------------------------------------
REM  Install/reinstall Cloudflare Tunnel as a Windows service using
REM  cert.pem plus a local config.yml (CLI-managed tunnel).
REM
REM  Prefer install-by-token.bat unless you specifically need local
REM  CLI management or advanced ingress knobs such as readTimeout.
REM
REM  Usage (AS ADMINISTRATOR):
REM    scripts\cloudflare\install-service.bat
REM
REM  ASCII ONLY on purpose - see note in install-by-token.bat.
REM ------------------------------------------------------------

set "CF=D:\cloudflared\cloudflared.exe"
set "CONF=D:\cloudflared\config.yml"
set "SVC=Cloudflared"

echo.
echo ==== Cloudflare Tunnel service install ====
echo.

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
if not exist "%CONF%" (
    echo [ERROR] Not found: %CONF%
    echo        Run scripts\cloudflare\init-tunnel.bat first.
    pause
    exit /b 1
)

REM Service runs as SYSTEM, whose home is NOT the user profile,
REM so credentials-file must be an absolute path.
findstr /C:"__FIXED_HOSTNAME__" "%CONF%" >nul 2>&1
if not errorlevel 1 (
    echo [ERROR] config.yml still has the __FIXED_HOSTNAME__ placeholder.
    pause
    exit /b 1
)
findstr /C:"credentials-file:" "%CONF%" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] config.yml lacks credentials-file.
    echo        SYSTEM has a different home dir; relative paths break.
    pause
    exit /b 1
)
echo [1/5] Config sanity check passed.

echo [2/5] Stopping legacy cpolar tunnel ...
taskkill /IM cpolar.exe /F >nul 2>&1
if errorlevel 1 ( echo        no running cpolar, skipped ) else ( echo        cpolar stopped )

echo [3/5] Checking for existing service ...
sc query "%SVC%" >nul 2>&1
if not errorlevel 1 (
    echo       found one, reinstalling ...
    net stop "%SVC%" >nul 2>&1
    "%CF%" service uninstall >nul 2>&1
) else (
    echo       none found.
)

echo [4/5] Installing service ...
"%CF%" service install --config "%CONF%"
if errorlevel 1 (
    echo [ERROR] Install failed.
    pause
    exit /b 1
)

echo [5/5] Enabling autostart and starting ...
sc config "%SVC%" start= auto >nul 2>&1
sc failure "%SVC%" reset= 0 actions= restart/60000/restart/60000/restart/60000 >nul 2>&1
net start "%SVC%"
if errorlevel 1 (
    echo [WARN] Installed but failed to start.
    echo        Check credentials path and tunnel ownership.
    echo        Debug: "%CF%" tunnel list
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  Service installed and started.
echo  Status: sc query %SVC%
echo ============================================================
echo.
pause
exit /b 0

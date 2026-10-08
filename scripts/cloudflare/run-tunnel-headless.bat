@echo off
REM ------------------------------------------------------------
REM  Run the Cloudflare Tunnel WITHOUT admin rights.
REM
REM  Intended to be CALLED, not double-clicked: it has no pause so it
REM  cannot hang an unattended Task Scheduler run.
REM
REM  Used by start-server-bg.bat so Task Scheduler owns the process tree
REM  (same pattern cpolar relied on). No service registration needed.
REM
REM  The token is read from a file OUTSIDE the repo, never inline:
REM    D:\cloudflared\tunnel-token.txt   (single line)
REM
REM  One-token-in-one-place rule: never copy the token into this file or
REM  into the repository. It stays outside version control on purpose.
REM
REM  ASCII ONLY on purpose - see note in install-by-token.bat.
REM ------------------------------------------------------------

set "CF=D:\cloudflared\cloudflared.exe"
set "TOKENFILE=D:\cloudflared\tunnel-token.txt"

if not exist "%TOKENFILE%" (
    echo [run-tunnel] ERROR token file missing: %TOKENFILE%
    exit /b 1
)
if not exist "%CF%" (
    echo [run-tunnel] ERROR not found: %CF%
    exit /b 1
)

set /p TOKEN=<"%TOKENFILE%"
if "%TOKEN%"=="" (
    echo [run-tunnel] ERROR token file is empty: %TOKENFILE%
    exit /b 1
)

echo [run-tunnel] starting cloudflared (foreground) ...
"%CF%" --no-autoupdate tunnel run --token %TOKEN%
echo [run-tunnel] cloudflared exited with code %errorlevel% at %date% %time%
exit /b 0

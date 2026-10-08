@echo off
REM ------------------------------------------------------------
REM  Resident startup script invoked by Task Scheduler / watchdog.
REM  Unlike start-server.bat it never kills and never pauses, so it is
REM  safe to run unattended.
REM
REM  Responsibilities:
REM    1. keep node server.js listening on 3000
REM    2. own the Cloudflare tunnel so the process tree survives
REM
REM  Tunnel ownership: cloudflared must run in the FOREGROUND here so
REM  Task Scheduler owns the process tree (same trick cpolar used). If
REM  cloudflared already runs - owned by the Windows service or by an
REM  earlier task instance - we hand ownership over and exit instead of
REM  spawning a duplicate that would fight over the same tunnel.
REM
REM  NOTE the ordering bug this fixes: the previous version exited early
REM  when port 3000 was already listening, so the tunnel section was
REM  never reached. Backend up + tunnel down would never be repaired.
REM
REM  ASCII ONLY on purpose - see scripts/cloudflare/install-by-token.bat.
REM ------------------------------------------------------------

set "ROOT=%~dp0"
cd /d "%ROOT%"

REM ---- 1. watchdog (backend keepalive; has its own duplicate guard) ----
start "" /b powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%scripts\watchdog-server.ps1"

REM ---- 2. backend ----
netstat -ano | findstr ":3000 .*LISTENING" >nul 2>&1
if errorlevel 1 (
    echo [start-server-bg] starting node server.js ...
    start "" /b node server.js >> server.log 2>> server.err.log
) else (
    echo [start-server-bg] port 3000 already listening, skip node.
)

REM ---- 3. optional DNS self-check for the fixed tunnel hostname ----
if "%TUNNEL_HOST%"=="" goto dns_done
nslookup %TUNNEL_HOST% >"%TEMP%\cftunnel-probe.txt" 2>&1
findstr /C:"Address" "%TEMP%\cftunnel-probe.txt" >nul 2>&1
if errorlevel 1 (
    echo [start-server-bg] WARNING: %TUNNEL_HOST% did not resolve.
) else (
    echo [start-server-bg] tunnel DNS %TUNNEL_HOST% resolves OK.
)
:dns_done

REM ---- 4. own the tunnel (foreground) ----
tasklist /FI "IMAGENAME eq cloudflared.exe" 2>nul | findstr /I "cloudflared.exe" >nul 2>&1
if not errorlevel 1 (
    echo [start-server-bg] cloudflared already running, ownership kept elsewhere.
    goto done
)

if not exist "%ROOT%scripts\cloudflare\run-tunnel-headless.bat" goto done
echo [start-server-bg] taking ownership of cloudflared tunnel (foreground) ...
call "%ROOT%scripts\cloudflare\run-tunnel-headless.bat"

:done
echo [start-server-bg] done at %date% %time% >> server.log
exit /b 0

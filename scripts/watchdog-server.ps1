# ------------------------------------------------------------
# Watchdog for Senior Anti-Fraud Guard backend + Cloudflare Tunnel.
# Runs as a hidden scheduled task at user logon.
# Every 30s: if port 3000 is not listening   -> start node server.js
#            if Cloudflared service stopped  -> start it again
#
# Encoding: only COMMENTS below are Chinese; no Chinese appears in code, string
# literals or log output, so this script executes identically with or without a
# BOM under PowerShell 5.1. Verified, not assumed.
# The line endings DO matter: this file must stay CRLF (repo .gitattributes
# pins eol=crlf for *.ps1), otherwise cmd-launched copies silently mis-parse.
# ------------------------------------------------------------

$ErrorActionPreference = "SilentlyContinue"

# Idempotency guard: exit if another watchdog instance is already running.
#
# 判据必须是「以 -File 参数执行本脚本」，不能退化成「命令行提到过本脚本名」。
# 后者会把任何提及此文件的进程都算成实例 —— 包括运维/诊断时随手敲的
# Get-CimInstance 查询、编辑器/IDE 的索引进程、乃至 -Command 里内联本文件的
# 诊断命令。后果是真实的：这些进程会让本脚本静默 exit 0，watchdog 根本没起来，
# 而日志里连一行记录都没有 —— 看起来像"启动了但端口没人管"。
#
# 正则要求 -File 与路径相邻（start-server-bg.bat 与计划任务都是这么拉起的）：
#   start "" /b powershell ... -File "%ROOT%scripts\watchdog-server.ps1"
# 保留 (?i) 以兼容 powershell / PowerShell 的大小写差异。
$myPid = $PID
$dup = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.ProcessId -ne $myPid -and
                   $_.CommandLine -match '(?i)-File\s+"?[^"]*watchdog-server\.ps1' }
if ($dup) { exit 0 }

$Root     = "D:\project\github\senior-anti-fraud-guard"
$SvcName  = "Cloudflared"
$Log      = "D:\project\github\senior-anti-fraud-guard\watchdog.log"

function Write-Log([string]$Msg) {
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $Msg" | Out-File -FilePath $Log -Append -Encoding utf8
}

# Same message repeated every 30s = log spam (once logged 864 lines while the
# tunnel was down for 7 hours). Log once, then stay silent until it changes.
$script:lastProbe = ""

# Log $msg only when it differs from the previous probe result.
function Write-Probe([string]$Msg) {
    if ($Msg -ne $script:lastProbe) {
        Write-Log $Msg
        $script:lastProbe = $Msg
    }
}

Write-Log "=== watchdog started (pid $PID) ==="

while ($true) {
    try {
        # ---- backend: node server.js on port 3000 ----
        $listener = Get-NetTCPConnection -State Listen -LocalPort 3000 -ErrorAction SilentlyContinue
        if (-not $listener) {
            Write-Log "port 3000 not listening -> starting node server.js"
            Start-Process -FilePath "cmd.exe" `
                -ArgumentList "/c node server.js >> server.log 2>> server.err.log" `
                -WorkingDirectory $Root -WindowStyle Hidden
            Start-Sleep -Seconds 6
            $check = Get-NetTCPConnection -State Listen -LocalPort 3000 -ErrorAction SilentlyContinue
            if ($check) { Write-Log "backend started OK" }
            else {
                # Repeated failures must not spam the log (one line per30s forever).
                if ($script:lastMsg -ne "backend-fail") {
                    Write-Log "WARNING: backend still not listening after start attempt"
                    $script:lastMsg = "backend-fail"
                }
            }
        }

        # ---- tunnel: Cloudflared Windows service ----
        # Ownership moved from cpolar (foreground in start-server-bg.bat, which
        # churned a NEW random domain on every restart) to a Cloudflare named
        # tunnel owned by the Cloudflared service: fixed hostname, survives
        # reboot, auto-restarts on crash. No domain churn means the old
        # "double ownership" hazard is gone, so restarting here is safe.
        $svc = Get-Service -Name $SvcName -ErrorAction SilentlyContinue
        if (-not $svc) {
            Write-Probe "WARNING: Cloudflared service not installed (run scripts\cloudflare\install-service.bat)"
        } elseif ($svc.Status -ne 'Running') {
            Write-Probe "Cloudflared service is $($svc.Status) -> starting"
            Start-Service -Name $SvcName -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 5
            $again = Get-Service -Name $SvcName -ErrorAction SilentlyContinue
            Write-Probe "Cloudflared service now $($again.Status)"
        } else {
            # Clear the dedup slot so a later failure gets logged again.
            $script:lastProbe = ""
        }

        # ---- end-to-end probe: public hostname -> local 3000 -----
        # Hostname source: scripts/cloudflare/hostname.txt (single source of truth).
        # Do NOT rely on $env:TUNNEL_HOST — it was never actually set, and setting a
        # Machine environment variable needs admin. Reading the file needs nothing.
        $hostFile = Join-Path $Root "scripts\cloudflare\hostname.txt"
        $pubHost = $env:TUNNEL_HOST
        if (-not $pubHost -and (Test-Path $hostFile)) {
            $pubHost = (Get-Content $hostFile -Raw -ErrorAction SilentlyContinue).Trim()
        }
        if ($pubHost) {
            try {
                $r = Invoke-WebRequest -Uri "https://$pubHost/api/health" -TimeoutSec 15 `
                     -UseBasicParsing -ErrorAction Stop
                Write-Probe "probe https://$pubHost/api/health -> $($r.StatusCode)"
            } catch {
                Write-Probe "probe https://$pubHost/api/health FAILED: $($_.Exception.Message)"
            }
        }
    }
    catch {
        Write-Log "ERROR: $($_.Exception.Message)"
    }

    Start-Sleep -Seconds 30
}

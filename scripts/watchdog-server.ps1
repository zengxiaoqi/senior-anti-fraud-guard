# ------------------------------------------------------------
# Watchdog for Senior Anti-Fraud Guard backend + cpolar tunnel.
# Runs as a hidden scheduled task at user logon.
# Every 30s: if port 3000 is not listening -> start node server.js
#            if cpolar.exe is not running  -> start cpolar tunnel
# ASCII only, no encoding issues.
# ------------------------------------------------------------

$ErrorActionPreference = "SilentlyContinue"

$Root     = "D:\project\github\senior-anti-fraud-guard"
$CpolarExe  = "D:\cpolar-tunnel\cpolar.exe"
$CpolarDir  = "D:\cpolar-tunnel"
$Log      = "D:\project\github\senior-anti-fraud-guard\watchdog.log"

function Write-Log([string]$Msg) {
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $Msg" | Out-File -FilePath $Log -Append -Encoding utf8
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
            else { Write-Log "WARNING: backend still not listening after start attempt" }
        }

        # ---- tunnel: cpolar.exe ----
        $cp = Get-Process -Name cpolar -ErrorAction SilentlyContinue
        if (-not $cp) {
            Write-Log "cpolar not running -> restarting tunnel"
            # cpolar must connect directly; clear proxy vars
            Remove-Item Env:HTTP_PROXY  -ErrorAction SilentlyContinue
            Remove-Item Env:HTTPS_PROXY -ErrorAction SilentlyContinue
            Remove-Item Env:http_proxy  -ErrorAction SilentlyContinue
            Remove-Item Env:https_proxy -ErrorAction SilentlyContinue
            Remove-Item Env:ALL_PROXY   -ErrorAction SilentlyContinue
            Remove-Item Env:all_proxy   -ErrorAction SilentlyContinue
            Start-Process -FilePath $CpolarExe `
                -ArgumentList "http 3000 --log=D:\cpolar-tunnel\cpolar.log" `
                -WorkingDirectory $CpolarDir -WindowStyle Hidden
        }
    }
    catch {
        Write-Log "ERROR: $($_.Exception.Message)"
    }

    Start-Sleep -Seconds 30
}

<#
  Phase 0 real-device verification: keepalive (R1) + permission state.

  Why this script exists
  ---------------------
  Whether the guard survives on Chinese OEM ROMs cannot be proven by reading code.
  MIUI / HarmonyOS / ColorOS / OriginOS each kill background work differently.
  So we measure it instead: install, reboot, stay away for N minutes, then read
  how often the app managed to wake itself up. That number IS the answer.

  Design notes
  ------------
  - Heartbeat is read from the app's SharedPreferences via `run-as`, which works
    on a debuggable build. No root needed. If it fails we fall back to logcat.
  - ASCII only on purpose: PowerShell 5.1 mis-decodes UTF-8 scripts without a BOM,
    and a mangled script is worse than an ugly one.

  Usage
  -----
    powershell -ExecutionPolicy Bypass -File scripts\verify-keepalive.ps1
    powershell -ExecutionPolicy Bypass -File scripts\verify-keepalive.ps1 -WaitMinutes 60
    powershell -ExecutionPolicy Bypass -File scripts\verify-keepalive.ps1 -Serial ABC123
    powershell -ExecutionPolicy Bypass -File scripts\verify-keepalive.ps1 -SkipReboot
#>
[CmdletBinding()]
param(
    [string]   $Serial      = '',
    [int]      $WaitMinutes = 35,
    [switch]   $SkipReboot,
    [switch]   $KeepInstalled
)

$ErrorActionPreference = 'Continue'
$PKG    = 'com.antifraud.guard'
$APK    = Join-Path (Split-Path $PSScriptRoot -Parent) 'android\app\build\outputs\apk\debug\app-debug.apk'
$TAG    = 'verify-keepalive'

# ── locate adb ────────────────────────────────────────────────────────────────
function Find-Adb {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($p in @(
        'D:\Android\platform-tools\adb.exe',
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    )) { if (Test-Path $p) { return $p } }
    return $null
}

$adb = Find-Adb
if (-not $adb) {
    Write-Host "[$TAG] FATAL: adb not found. Set PATH or pass -AdbPath." -ForegroundColor Red
    exit 2
}
Write-Host "[$TAG] adb = $adb"

function Invoke-Adb {
    param([string[]]$AdbArgs, [int]$TimeoutSec = 120)
    $o = New-TemporaryFile; $e = New-TemporaryFile
    try {
        $p = Start-Process -FilePath $adb -ArgumentList $AdbArgs -NoNewWindow -PassThru `
             -RedirectStandardOutput $o -RedirectStandardError $e
        if (-not $p.WaitForExit($TimeoutSec * 1000)) {
            try { $p.Kill() } catch {}
            return @{ code = -1; out = 'TIMEOUT'; err = '' }
        }
        return @{
            code = $p.ExitCode
            out  = (Get-Content $o.FullName -Raw -ErrorAction SilentlyContinue)
            err  = (Get-Content $e.FullName -Raw -ErrorAction SilentlyContinue)
        }
    } finally {
        Remove-Item $o.FullName, $e.FullName -ErrorAction SilentlyContinue
    }
}

$A = @()   # optional -s <serial> prefix
function Adb { param([string[]]$AdbArgs, [int]$TimeoutSec = 120) Invoke-Adb ($A + $AdbArgs) $TimeoutSec }
function AdbShell {
    param([string]$Cmd, [int]$TimeoutSec = 120)
    $r = Adb @('shell', $Cmd) $TimeoutSec
    if ($null -eq $r.out) { return '' }
    return ($r.out -replace "`r", '')
}

# ── resolve device ────────────────────────────────────────────────────────────
$list = Invoke-Adb @('devices') 60
$devices = @()
if ($list.out) {
    $devices = @($list.out -split "`n" |
        Select-String -Pattern '^\s*(\S+)\s+device\b' |
        ForEach-Object { ($_ -split '\s+')[0] })
}

if ($Serial) {
    if ($devices -notcontains $Serial) {
        Write-Host "[$TAG] FATAL: device '$Serial' not attached." -ForegroundColor Red
        Write-Host "[$TAG] attached: $($devices -join ', ')" -ForegroundColor Yellow
        exit 2
    }
    $A = @('-s', $Serial)
    $target = $Serial
} else {
    if ($devices.Count -eq 0) {
        Write-Host ''
        Write-Host "[$TAG] NO DEVICE ATTACHED." -ForegroundColor Red
        Write-Host ''
        Write-Host '  Connect a phone over USB and either:'
        Write-Host '    1) accept the RSA fingerprint prompt on the phone screen, or'
        Write-Host '    2) enable USB debugging first (Settings > About > tap Build number 7x)'
        Write-Host ''
        Write-Host '  An emulator is NOT usable here: this host has VT-x / SLAT disabled'
        Write-Host '  and no system images are installed (it is a nested VM).'
        Write-Host ''
        exit 2
    }
    if ($devices.Count -gt 1) {
        Write-Host "[$TAG] FATAL: multiple devices attached; pass -Serial. Got: $($devices -join ', ')" -ForegroundColor Red
        exit 2
    }
    $A = @('-s', $devices[0])
    $target = $devices[0]
}

$model = (AdbShell 'getprop ro.product.model').Trim()
$brand = (AdbShell 'getprop ro.product.brand').Trim()
$rel   = (AdbShell 'getprop ro.build.version.release').Trim()
$sdk   = (AdbShell 'getprop ro.build.version.sdk').Trim()
$miui  = (AdbShell 'getprop ro.miui.ui.version.name').Trim()
$harmony = (AdbShell 'getprop ro.build.version.emui').Trim()
$fingerprint = (AdbShell 'getprop ro.product.model').Trim()

Write-Host ''
Write-Host "=== target ==="
Write-Host "  serial : $target"
Write-Host "  model  : $brand $model  |  Android $rel (API $sdk)"
if ($miui)    { Write-Host "  rom    : MIUI $miui" }
if ($harmony) { Write-Host "  rom    : EMUI/Harmony $harmony" }
Write-Host ''

function Section($t) { Write-Host "=== $t ===" }

# ── baseline: clear stale heartbeat ───────────────────────────────────────────
Section 'preflight'
if (-not (Test-Path $APK)) {
    Write-Host "[$TAG] FATAL: APK not found: $APK" -ForegroundColor Red
    exit 2
}
Write-Host "apk: $APK"

$pkgPresent = (AdbShell "pm path $PKG")
if ($pkgPresent -match '^package:') {
    Write-Host "uninstalling previous build (to get a clean heartbeat baseline)"
    Adb @('uninstall', $PKG) | Out-Null
}

Write-Host "installing..."
$ins = Adb @('install', '-r', '-t', '-g', $APK) 300
Write-Host ($ins.out -replace "`r", '')
if ($ins.out -notmatch 'Success') {
    Write-Host "[$TAG] FATAL: install failed" -ForegroundColor Red
    exit 3
}

# Best-effort runtime grants. Notification-listener access and the call-screening
# ROLE cannot be granted over adb -- those must be toggled by hand on the phone,
# which is exactly what we want the script to tell you to do.
Write-Host 'granting runtime permissions (best effort)...'
foreach ($perm in @(
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_BACKGROUND_LOCATION',
    'android.permission.READ_PHONE_STATE',
    'android.permission.RECORD_AUDIO',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.VIBRATE'
)) {
    $r = Adb @('shell', "pm grant $PKG $perm") 60
    if ($r.code -eq 0) { Write-Host "  granted  $perm" }
    else               { Write-Host "  skipped  $perm  (user must grant manually)" }
}

# ── clear log buffer so we only see this run ─────────────────────────────────
Adb @('logcat', '-c') | Out-Null

# ── reboot ───────────────────────────────────────────────────────────────────
if (-not $SkipReboot) {
    Section 'reboot'
    Write-Host 'rebooting device... (boot receiver should restore the guard)'
    Adb @('reboot') | Out-Null
    Write-Host 'waiting for boot_completed...'
    $bootOk = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 5
        $bc = (AdbShell 'getprop sys.boot_completed').Trim()
        if ($bc -eq '1') { $bootOk = $true; break }
    }
    if (-not $bootOk) {
        Write-Host "[$TAG] FATAL: device did not finish booting in 5 min" -ForegroundColor Red
        exit 4
    }
    Write-Host 'booted. NOT touching the app -- that is the whole point of this test.'
} else {
    Write-Host "[$TAG] -SkipReboot: opening the app once so services start"
    Adb @('shell', "monkey -p $PKG -c android.intent.category.LAUNCHER 1") | Out-Null
    Start-Sleep -Seconds 8
}

# ── observe ──────────────────────────────────────────────────────────────────
Section "observing for $WaitMinutes minutes (do not open the app)"
$t0 = Get-Date
$samples = @()
for ($m = 1; $m -le $WaitMinutes; $m++) {
    Start-Sleep -Seconds 60

    # heartbeat prefs via run-as (debug build => works without root)
    $prefs = ''
    $rp = Adb @('shell', "run-as $PKG cat /data/data/$PKG/shared_prefs/guard_keepalive.xml") 60
    if ($null -ne $rp.out) { $prefs = $rp.out -replace "`r", '' }

    $gap = -1; $cnt = 0
    if ($prefs -match 'name="last_heartbeat_at">(\d+)<') {
        $ts = [int64]$Matches[1]
        if ($ts -gt 0) { $gap = [int](([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $ts) / 60000) }
    }
    if ($prefs -match 'name="heartbeat_count">(\d+)<') { $cnt = [int]$Matches[1] }

    $svc = (AdbShell "dumpsys activity services $PKG" | Select-String -Pattern 'ServiceRecord' | Measure-Object).Count
    $samples += [pscustomobject]@{ minute = $m; gap = $gap; count = $cnt; services = $svc }
    Write-Host ("  +{0,3} min   gap={1,4} min   beats={2,3}   serviceRecords={3}" -f $m, $gap, $cnt, $svc)
}

# ── collect final state ──────────────────────────────────────────────────────
Section 'final state'

$prefs = (Adb @('shell', "run-as $PKG cat /data/data/$PKG/shared_prefs/guard_keepalive.xml") 60).out
$prefs = ($prefs -replace "`r", '') -split "`n" | Where-Object { $_ -match 'name=' }

$jobs    = AdbShell "dumpsys jobscheduler | grep -A3 $PKG"
$svcDump = AdbShell "dumpsys activity services $PKG"
$fgRunning = $svcDump -match 'ForegroundGuardService'
$locRunning = $svcDump -match 'LocationGuardService'

# battery optimisation exemption
$battery = (AdbShell "dumpsys deviceidle whitelist | grep $PKG").Trim()
$batteryExempt = [bool]$battery

# exact alarm capability (informational only; keepalive uses inexact alarms)
$exactAlarms = (AdbShell "cmd appops get $PKG SCHEDULE_EXACT_ALARM").Trim()

$notifListener = ((AdbShell "settings get secure enabled_notification_listeners") -split ' ' | Where-Object { $_ -match $PKG }) -ne ''
$callScreening = ((AdbShell "cmd role get-role-holders android.app.role.CALL_SCREENING") -split "`n" | Where-Object { $_ -match $PKG }) -ne ''

Write-Host ''
Write-Host '--- alarm / job ---'
Write-Host ($jobs.Trim())
Write-Host ''
Write-Host '--- services ---'
Write-Host "ForegroundGuardService running : $fgRunning"
Write-Host "LocationGuardService  running : $locRunning"
Write-Host ''
Write-Host '--- system switches ---'
Write-Host "battery optimisation exempt  : $batteryExempt"
Write-Host "exact alarm allowed          : $exactAlarms"
Write-Host "notification listener on     : $notifListener"
Write-Host "call screening role held     : $callScreening"
Write-Host ''
Write-Host '--- heartbeat prefs ---'
Write-Host ($prefs -join "`n")

# ── verdict ──────────────────────────────────────────────────────────────────
$last = $samples | Select-Object -Last 1
$gap = $last.gap
$verdict = 'FAIL'
$note = ''
if (-not $prefs) {
    $verdict = 'UNKNOWN'
    $note = 'could not read heartbeat prefs (run-as failed). Check logcat for GuardKeepAlive.'
} elseif ($gap -lt 0) {
    $verdict = 'FAIL'
    $note = 'no heartbeat recorded at all -- keepalive never fired. Likely autostart / battery whitelist off.'
} elseif ($gap -le 25) {
    $verdict = 'PASS'
    $note = "gap $gap min is within the expected 10-25 min window."
} elseif ($gap -le 40) {
    $verdict = 'WARN'
    $note = "gap $gap min is longer than expected; the OS is likely throttling but not killing us."
} else {
    $verdict = 'FAIL'
    $note = "gap $gap min means the OS/OEM killed background work. Autostart + battery whitelist + vendor background switch all need to be ON."
}

Write-Host ''
Write-Host '============================ VERDICT ============================'
Write-Host "  device            : $brand $model / Android $rel (API $sdk)"
Write-Host "  wait time         : $WaitMinutes min"
Write-Host "  final heartbeat gap: $gap min"
Write-Host "  heartbeats total   : $($last.count)"
Write-Host "  KEEPALIVE VERDICT  : $verdict"
Write-Host "  $note"
Write-Host '================================================================'

Write-Host ''
Write-Host 'Manual toggles that CANNOT be done over adb (verify on the phone):'
if (-not $batteryExempt) {
    Write-Host '  [ ] battery optimisation -> OFF for this app'
    Write-Host '        Settings > Apps > this app > Battery > Unrestricted'
    Write-Host '        (or use the in-app "Guard health" panel button 1)'
}
if (-not $notifListener) {
    Write-Host '  [ ] notification access -> ON'
    Write-Host '        Settings > Notifications > Device & app notifications > this app'
    Write-Host '        (needed for large-payment detection)'
}
if (-not $callScreening) {
    Write-Host '  [ ] incoming call screening role -> ON'
    Write-Host '        Settings > Apps > Default apps > Incoming call screening > pick this app'
    Write-Host '        (without it onScreenCall is never called: call-duration watch is dead)'
}
Write-Host ''
Write-Host 'Then paste the VERDICT block plus the heartbeat interval back into'
Write-Host 'docs/superpowers/plans/2026-10-07-hardening-roadmap.md (Phase 0 section)'
Write-Host 'so R1 can be closed with data instead of a guess.'
Write-Host ''
Write-Host "logs: adb logcat -d -s GuardKeepAlive:V GuardKeepAliveRx:V GuardStarter:V GuardBootReceiver:V"

if (-not $KeepInstalled) {
    Write-Host ''
    $ans = Read-Host "Uninstall $PKG from device? (y/N)"
    if ($ans -match '^[yY]') { Adb @('uninstall', $PKG) | Out-Null; Write-Host 'uninstalled' }
}
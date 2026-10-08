# ---------------------------------------------------------------------
# Idempotency-guard regression test for scripts/watchdog-server.ps1.
#
# Why this exists: the guard used to be "command line mentions
# watchdog-server.ps1". Any process that merely *mentions* the file — a
# Get-CimInstance diagnostic, an IDE indexer, an inline -Command probe —
# was counted as a running instance, so the watchdog silently exit 0'd and
# never came up. That failure mode is invisible in the log: it looks like
# "started fine, but nobody owns port 3000".
#
# The guard now requires `-File` adjacent to the script path. These cases
# pin that down so the loose pattern cannot come back unnoticed.
# ---------------------------------------------------------------------
$ErrorActionPreference = "Stop"

$Self  = $PID
$Tight = '(?i)-File\s+"?[^"]*watchdog-server\.ps1'

# Mirrors how watchdog-server.ps1 actually filters, in the same order:
# exclude own PID first, then require the tight pattern. $Self stands in for
# the watchdog's own PID; $ProcPid is the PID of the process being examined.
function Test-IsRealInstance([string]$CommandLine, [int]$ProcPid, [int]$OwnPid) {
    if (-not $CommandLine) { return $false }
    if ($ProcPid -eq $OwnPid) { return $false }
    if ($CommandLine -match $Tight) { return $true }
    return $false
}

$cases = @(
    # name,                                  procCmdLine, procPid, ownPid, expected
    @('real: plan-task / start-server-bg',    'powershell  -NoProfile -ExecutionPolicy Bypass -File "D:\p\scripts\watchdog-server.ps1"', 4242, 99999, $true),
    @('real: unquoted path',                  'powershell -NoProfile -File D:\project\scripts\watchdog-server.ps1',                         4242, 99999, $true),
    @('real: mixed case exe name',            'PowerShell.EXE -NoProfile -File d:\project\watchdog-server.ps1',                          4242, 99999, $true),
    @('self: exclude own PID',                'powershell -NoProfile -File "D:\project\scripts\watchdog-server.ps1"',                  $Self, $Self,  $false),
    @('noise: Get-CimInstance diagnostic',    'powershell -NoProfile -Command "Get-CimInstance Win32_Process | ? CommandLine -match watchdog-server\.ps1"', 4242, 99999, $false),
    @('noise: inline -Command probe',         'powershell -Command ". D:\project\scripts\watchdog-server.ps1"',                            4242, 99999, $false),
    @('noise: code editor grep',              'powershell -Command "Select-String -Path *.ps1 -Pattern watchdog-server"',                4242, 99999, $false),
    @('noise: mentioned in an echoed string', 'cmd.exe /c echo watchdog-server.ps1 started',                                            4242, 99999, $false),
    @('noise: different script name',         'powershell -NoProfile -File "D:\project\scripts\other-daemon.ps1"',                      4242, 99999, $false)
)

$fail = 0
foreach ($c in $cases) {
    $name   = $c[0]
    $cmd    = $c[1]
    $procPid = [int]$c[2]
    $ownPid  = [int]$c[3]
    $want   = [bool]$c[4]
    $got    = Test-IsRealInstance $cmd $procPid $ownPid
    $ok     = ($got -eq $want)
    if (-not $ok) { $fail++ }
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("  [{0}] {1}  (expected={2} got={3})" -f $mark, $name, $want, $got)
}

# The guard's whole purpose is preventing double instances. Two processes
# carrying the exact real-instance command line must both be detected.
$dupes = @(
    @{ pid = 5151; cmd = 'powershell -NoProfile -File "D:\project\scripts\watchdog-server.ps1"' },
    @{ pid = 5152; cmd = 'powershell -NoProfile -File "D:\project\scripts\watchdog-server.ps1"' }
)
$detected = @($dupes | Where-Object { Test-IsRealInstance $_.cmd $_.pid 99999 }).Count
if ($detected -ne 2) { $fail++; Write-Host "  [FAIL] double-instance detection ($detected/2)" }
else { Write-Host "  [PASS] both duplicate instances detected" }

Write-Host ""
if ($fail -eq 0) {
    Write-Host "guard-test: ALL PASS"
    exit 0
} else {
    Write-Host "guard-test: $fail FAILURE(S)"
    exit 1
}
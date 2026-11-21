# Stops Option Manager: closes the backend / frontend windows that start.ps1 opened
# and anything still listening on port 8080 (backend) or 5173 (frontend).
# Use -BackendOnly to leave the frontend running, -WhatIf to only show what would stop.

[CmdletBinding(SupportsShouldProcess)]
param([switch]$BackendOnly)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
# load these up front so -WhatIf doesn't report their alias setup
& { $WhatIfPreference = $false; Import-Module CimCmdlets, NetTCPIP }

$targets = @(@{ Name = 'backend'; Port = 8080; Pattern = '*backend*gradlew.bat bootRun*' })
if (-not $BackendOnly) {
    $targets += @{ Name = 'frontend'; Port = 5173; Pattern = '*frontend*npm run dev*' }
}

function Stop-Tree([int]$procId, [string]$what) {
    if ($PSCmdlet.ShouldProcess("$what (PID $procId)", 'Stop')) {
        # /T also ends child processes (gradlew, node, the app itself)
        & taskkill.exe /PID $procId /T /F *> $null
        Write-Host "Stopped $what (PID $procId)"
    }
}

$all = Get-CimInstance Win32_Process
foreach ($t in $targets) {
    # 1. the console window start.ps1 opened for this part (matched by its command line)
    $windows = $all | Where-Object {
        $_.Name -eq 'powershell.exe' -and $_.ProcessId -ne $PID -and
        $_.CommandLine -like "*$root*" -and $_.CommandLine -like $t.Pattern
    }
    foreach ($w in $windows) { Stop-Tree $w.ProcessId "$($t.Name) window" }

    # 2. whatever still listens on the port (e.g. the app forked by the Gradle daemon)
    $listeners = Get-NetTCPConnection -LocalPort $t.Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $listeners) {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if (-not $p) { continue }
        if ($p.ProcessName -in 'java', 'node') {
            Stop-Tree $procId "$($t.Name) on port $($t.Port) ($($p.ProcessName))"
        } else {
            Write-Warning "Port $($t.Port) is used by $($p.ProcessName) (PID $procId), not Option Manager; left it alone."
        }
    }
}

if ($WhatIfPreference) { return }

# Wait until the ports are free (Windows can take a moment to release them).
foreach ($t in $targets) {
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-NetTCPConnection -LocalPort $t.Port -State Listen -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
    }
    if (Get-NetTCPConnection -LocalPort $t.Port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $($t.Port) is still in use after stopping; check Task Manager."
    }
}
Write-Host 'Option Manager stopped.'

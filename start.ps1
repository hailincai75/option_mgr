# Starts Option Manager for everyday use: backend (port 8080, your real database
# in backend/data/) and frontend (port 5173), each in its own window, then opens
# the browser. Close the two windows to stop the app.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Test-Jdk21([string]$jdkHome) {
    if (-not $jdkHome -or -not (Test-Path (Join-Path $jdkHome 'bin\java.exe'))) { return $false }
    $release = Join-Path $jdkHome 'release'
    if (-not (Test-Path $release)) { return $false }
    return [bool](Select-String -Path $release -Pattern 'JAVA_VERSION="21' -Quiet)
}

# Find a JDK 21: OPTIONMGR_JAVA_HOME, then JAVA_HOME, then common install folders.
$candidates = @($env:OPTIONMGR_JAVA_HOME, $env:JAVA_HOME)
foreach ($dir in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft", "$env:USERPROFILE\.gradle\jdks")) {
    if (Test-Path $dir) { $candidates += (Get-ChildItem $dir -Directory | ForEach-Object FullName) }
}
$jdk = $candidates | Where-Object { Test-Jdk21 $_ } | Select-Object -First 1
if (-not $jdk) {
    throw 'JDK 21 not found. Install it, or set OPTIONMGR_JAVA_HOME to its folder.'
}

foreach ($port in 8080, 5173) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use. Stop whatever is running there (e.g. an E2E run) and try again."
    }
}

if (-not (Test-Path (Join-Path $root 'frontend\node_modules'))) {
    Write-Host 'Installing frontend dependencies (first run only)...'
    Push-Location (Join-Path $root 'frontend'); npm install; Pop-Location
}

Write-Host "Using JDK: $jdk"
$backendCmd = "`$env:JAVA_HOME='$jdk'; `$env:SPRING_PROFILES_ACTIVE=''; Set-Location '$root\backend'; .\gradlew.bat bootRun"
Start-Process powershell -ArgumentList '-NoExit', '-Command', $backendCmd -WindowStyle Normal
$frontendCmd = "Set-Location '$root\frontend'; npm run dev"
Start-Process powershell -ArgumentList '-NoExit', '-Command', $frontendCmd -WindowStyle Normal

function Wait-Http([string]$url, [string]$name) {
    Write-Host "Waiting for the $name to start..."
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 3
        try { $ok = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 $url).StatusCode -eq 200 } catch { $ok = $false }
    } until ($ok -or (Get-Date) -gt $deadline)
    if (-not $ok) { Write-Host "The $name did not respond within 5 minutes; check its window for errors." }
    return $ok
}

# The first start after a dependency change can take a couple of minutes on both sides.
if ((Wait-Http 'http://localhost:8080/api/health' 'backend') -and (Wait-Http 'http://localhost:5173/' 'frontend')) {
    Start-Process 'http://localhost:5173'
    Write-Host 'Option Manager is running at http://localhost:5173'
}

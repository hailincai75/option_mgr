# Restarts Option Manager: stop.ps1, then start.ps1 (new windows, no new browser tab).
# -BackendOnly restarts only the backend and keeps the frontend running.

param([switch]$BackendOnly)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'stop.ps1') -BackendOnly:$BackendOnly
& (Join-Path $PSScriptRoot 'start.ps1') -BackendOnly:$BackendOnly -NoBrowser

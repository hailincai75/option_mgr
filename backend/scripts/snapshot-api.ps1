# Saves the raw JSON of every read endpoint of the app into a directory, so two runs can be compared byte by byte
# (FEAT-033 migration rehearsal: M1 version before the upgrade vs. the new version after it).
# Read-only: only GET requests. Windows PowerShell 5.1 compatible.
#
#   .\snapshot-api.ps1 -BaseUrl http://localhost:8080 -OutDir C:\temp\before            # M1 version (no account)
#   .\snapshot-api.ps1 -BaseUrl http://localhost:8081 -OutDir C:\temp\after -AccountId 1 # new version
#   git diff --no-index C:\temp\before C:\temp\after                                    # must print nothing
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$AccountId = ''
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$headers = @{}
if ($AccountId -ne '') { $headers['X-Account-Id'] = $AccountId }

function Save([string]$path, [string]$file) {
    $r = Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $path) -Headers $headers -Method Get
    [IO.File]::WriteAllBytes((Join-Path $OutDir $file), $r.RawContentStream.ToArray())
    $text = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    return ($text | ConvertFrom-Json)
}

function SavePaged([string]$path, [string]$name) {
    $page = 1
    do {
        $sep = if ($path.Contains('?')) { '&' } else { '?' }
        $body = Save ($path + $sep + "page=$page&size=100") ("$name-page$page.json")
        $page++
    } while ($page -le $body.totalPages)
    return $page - 1
}

Save '/api/cash/summary' 'cash-summary.json' | Out-Null
SavePaged '/api/cash/ledger' 'cash-ledger' | Out-Null
$tradePages = SavePaged '/api/option-trades' 'trades'
for ($p = 1; $p -le $tradePages; $p++) {
    $list = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "trades-page$p.json") | ConvertFrom-Json
    foreach ($t in $list.items) { Save "/api/option-trades/$($t.id)" "trade-$($t.id).json" | Out-Null }
}
Save '/api/option-positions' 'positions.json' | Out-Null
SavePaged '/api/closed-trades' 'closed-trades' | Out-Null
Save '/api/pnl-summary' 'pnl-summary.json' | Out-Null
Save '/api/dashboard' 'dashboard.json' | Out-Null
SavePaged '/api/audit-log' 'audit-log' | Out-Null
Write-Host "Saved to $OutDir"

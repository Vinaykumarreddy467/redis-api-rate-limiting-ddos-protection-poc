<#
  project-common.ps1 - shared helpers for start-project.ps1 and stop-project.ps1.
  Dot-source it:  . (Join-Path $PSScriptRoot 'project-common.ps1')

  A process counts as "ours" only when its command line contains this project's folder, so a
  different Java or Node program on port 8080/4200 is never touched.
  Note: never assign to $pid in PowerShell; it is a read-only automatic variable. Use $procId.
#>

$ProjectRoot  = Split-Path -Parent $PSScriptRoot
$BackendDir   = Join-Path $ProjectRoot 'redis-rate-limit-poc'
$FrontendDir  = Join-Path $ProjectRoot 'frontend'
$StateDir     = Join-Path $ProjectRoot '.run-state'
$BackendPort  = 8080
$FrontendPort = 4200
$RedisPort    = 6379
$RedisContainers = @('ratelimit-redis', 'ratelimit-poc-redis')

function Write-Step([string] $text)  { Write-Host $text -ForegroundColor Cyan }
function Write-Ok([string] $text)    { Write-Host "  $text" -ForegroundColor Green }
function Write-Note([string] $text)  { Write-Host "  $text" -ForegroundColor Yellow }
function Write-Fail([string] $text)  { Write-Host "  $text" -ForegroundColor Red }

function Test-Http([string] $uri) {
    try {
        $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 3
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400)
    } catch {
        return $false
    }
}

function Wait-Http([string] $uri, [int] $seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (Test-Http $uri) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Get-PortOwnerIds([int] $port) {
    return @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
}

function Get-CommandLine([int] $procId) {
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
    if ($cim) { return [string] $cim.CommandLine }
    return ''
}

function Test-ProjectProcess([int] $procId, [string] $expectedName) {
    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $proc -or $proc.ProcessName -ne $expectedName) { return $false }
    $commandLine = Get-CommandLine $procId
    if ($commandLine.IndexOf($ProjectRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    # A backend started by hand with a relative path ("java -jar target\...jar") is still recognisable by its
    # jar name, which is unique to this project.
    return ($expectedName -eq 'java' -and $commandLine -match 'redis-rate-limit-poc-[0-9][^\s"]*\.jar')
}

function Save-ProcessRecord([string] $name, [System.Diagnostics.Process] $process) {
    New-Item -ItemType Directory -Path $StateDir -Force | Out-Null
    @{ pid = $process.Id; processName = $process.ProcessName } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $StateDir "$name.json") -Encoding ASCII
}

<#
  Stops this project's process for one service: the PID recorded by start-project.ps1 plus anything
  listening on the service port whose command line points into this project. Returns $false only when
  the port is still held by something we were not allowed to stop.
#>
function Stop-ProjectService([string] $name, [string] $expectedName, [int] $port) {
    $targets = New-Object System.Collections.Generic.List[int]
    $recordPath = Join-Path $StateDir "$name.json"

    if (Test-Path -LiteralPath $recordPath) {
        try {
            $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
            $recordedId = [int] $record.pid
            if (Test-ProjectProcess $recordedId $expectedName) { $targets.Add($recordedId) }
        } catch {
            Write-Note "Ignoring unreadable $name record."
        }
        Remove-Item -LiteralPath $recordPath -Force -ErrorAction SilentlyContinue
    }

    foreach ($ownerId in (Get-PortOwnerIds $port)) {
        if ($targets.Contains([int] $ownerId)) { continue }
        if (Test-ProjectProcess $ownerId $expectedName) {
            $targets.Add([int] $ownerId)
        } else {
            $owner = Get-Process -Id $ownerId -ErrorAction SilentlyContinue
            $ownerName = if ($owner) { $owner.ProcessName } else { 'unknown' }
            Write-Note "Port $port is used by $ownerName (PID $ownerId), which is not part of this project; leaving it alone."
        }
    }

    if ($targets.Count -eq 0) {
        Write-Ok "$name is not running."
        return $true
    }

    foreach ($targetId in $targets) {
        & taskkill.exe /PID $targetId /T /F *> $null
        if ($LASTEXITCODE -eq 0) { Write-Ok "Stopped $name (PID $targetId)." }
        else { Write-Fail "Could not stop $name (PID $targetId)." }
    }

    for ($i = 0; $i -lt 15; $i++) {
        if ((Get-PortOwnerIds $port).Count -eq 0) { return $true }
        Start-Sleep -Seconds 1
    }
    Write-Fail "Port $port is still in use after stopping $name."
    return $false
}

function Find-RedisContainer {
    foreach ($candidate in $RedisContainers) {
        & docker inspect $candidate *> $null
        if ($LASTEXITCODE -eq 0) { return $candidate }
    }
    return $null
}

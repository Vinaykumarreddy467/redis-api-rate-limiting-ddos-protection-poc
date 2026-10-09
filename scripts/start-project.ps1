<#
  start-project.ps1 - start (or restart) the RateGuard POC on Windows.

  Always gives you a fresh run of the current source:
    1. Redis     - reuses the project container (data is kept) or creates it with docker compose.
    2. Backend   - stops this project's running backend first (it locks the jar), rebuilds, starts.
    3. Frontend  - stops this project's dev server, then starts a new one.
  Safe to run repeatedly. Never touches processes that do not belong to this project.
  Credentials are never stored here: RATELIMIT_ADMIN_USER / RATELIMIT_ADMIN_PASSWORD must be set.
#>
param(
    [switch] $NoBrowser
)

# 'Continue', not 'Stop': in Windows PowerShell 5.1 a native tool writing to stderr (docker prints progress
# there) would otherwise abort the script even on success. Every native call checks $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'project-common.ps1')

function Fail([string] $message) {
    Write-Fail $message
    Write-Host ''
    Write-Host 'RateGuard did not start. Fix the problem above and run start.bat again.' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'RateGuard - starting Redis, backend and Angular console' -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"

# --- 0/4 Pre-flight -----------------------------------------------------------
Write-Step '[0/4] Checks'
if ([string]::IsNullOrWhiteSpace($env:RATELIMIT_ADMIN_USER) -or [string]::IsNullOrWhiteSpace($env:RATELIMIT_ADMIN_PASSWORD)) {
    Fail 'Set RATELIMIT_ADMIN_USER and RATELIMIT_ADMIN_PASSWORD before starting (see README).'
}
foreach ($tool in @('java', 'mvn', 'node', 'npm', 'docker')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        Fail "Required tool '$tool' was not found on PATH."
    }
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Fail 'Docker is installed but not running. Start Docker Desktop and try again.'
}
New-Item -ItemType Directory -Path $StateDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $BackendDir 'logs') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $FrontendDir 'logs') -Force | Out-Null
Write-Ok 'Tools, Docker and admin credentials are available.'

# --- 1/4 Redis ----------------------------------------------------------------
Write-Step '[1/4] Redis'
$redisName = Find-RedisContainer
if ($null -eq $redisName) {
    Push-Location $BackendDir
    try {
        & docker compose up -d redis
        if ($LASTEXITCODE -ne 0) { Fail 'docker compose could not start Redis.' }
    } finally {
        Pop-Location
    }
    $redisName = Find-RedisContainer
    if ($null -eq $redisName) { Fail 'Redis container was not found after docker compose up.' }
    Write-Ok "Created Redis container '$redisName'."
} else {
    $running = & docker inspect -f '{{.State.Running}}' $redisName 2>$null
    if ($running -ne 'true') {
        & docker start $redisName *> $null
        if ($LASTEXITCODE -ne 0) { Fail "Could not start Redis container '$redisName'." }
        Write-Ok "Started Redis container '$redisName'."
    } else {
        Write-Ok "Redis container '$redisName' is already running."
    }
}
$redisReady = $false
for ($i = 0; $i -lt 30; $i++) {
    $ping = & docker exec $redisName redis-cli PING 2>$null
    if ($LASTEXITCODE -eq 0 -and "$ping" -match 'PONG') { $redisReady = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $redisReady) { Fail "Redis container '$redisName' did not answer PING within 30 seconds." }
Set-Content -LiteralPath (Join-Path $StateDir 'redis.txt') -Value $redisName -Encoding ASCII
Write-Ok "Redis ready (existing data kept)."

# --- 2/4 Backend: stop, build, start -------------------------------------------
Write-Step '[2/4] Backend'
if (-not (Stop-ProjectService 'backend' 'java' $BackendPort)) {
    Fail "Port $BackendPort is busy with another program. Free it and try again."
}
Write-Host '  Building from current source (tests skipped; run mvn test separately)...'
Push-Location $BackendDir
try {
    $buildLog = Join-Path $StateDir 'build.log'
    & mvn -B -q -DskipTests package *> $buildLog
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $buildLog -Tail 25 | ForEach-Object { Write-Host "    $_" }
        Fail "Maven build failed. Full log: $buildLog"
    }
} finally {
    Pop-Location
}
$jar = Get-ChildItem -LiteralPath (Join-Path $BackendDir 'target') -Filter 'redis-rate-limit-poc-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { Fail 'Build finished but no application jar was found in redis-rate-limit-poc\target.' }

$backendLog = Join-Path $BackendDir 'logs\backend.log'
$backend = Start-Process -FilePath (Get-Command java).Source `
    -ArgumentList @('-jar', ('"{0}"' -f $jar.FullName)) `
    -WorkingDirectory $BackendDir `
    -RedirectStandardOutput $backendLog -RedirectStandardError (Join-Path $BackendDir 'logs\backend-err.log') `
    -WindowStyle Hidden -PassThru -ErrorAction SilentlyContinue
if (-not $backend) { Fail 'Could not launch java for the backend.' }
Save-ProcessRecord 'backend' $backend
if (-not (Wait-Http "http://localhost:$BackendPort/actuator/health" 90)) {
    Get-Content -LiteralPath $backendLog -Tail 30 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" }
    Fail "Backend did not become healthy within 90 seconds. Log: $backendLog"
}
Write-Ok "Backend healthy at http://localhost:$BackendPort (PID $($backend.Id))."

# --- 3/4 Frontend: stop, install if needed, start ------------------------------
Write-Step '[3/4] Angular console'
if (-not (Stop-ProjectService 'frontend' 'node' $FrontendPort)) {
    Fail "Port $FrontendPort is busy with another program. Free it and try again."
}
if (-not (Test-Path -LiteralPath (Join-Path $FrontendDir 'node_modules'))) {
    Write-Host '  Installing frontend dependencies (first run only)...'
    Push-Location $FrontendDir
    try {
        if (Test-Path -LiteralPath (Join-Path $FrontendDir 'package-lock.json')) { & npm ci *> $null } else { & npm install *> $null }
        if ($LASTEXITCODE -ne 0) { Fail 'Installing frontend dependencies failed. Run npm install in frontend\ to see why.' }
    } finally {
        Pop-Location
    }
}
$ngCli = Join-Path $FrontendDir 'node_modules\@angular\cli\bin\ng.js'
if (-not (Test-Path -LiteralPath $ngCli)) { Fail "Angular CLI not found at $ngCli. Run npm install in frontend\." }
$frontendLog = Join-Path $FrontendDir 'logs\console.log'
$frontend = Start-Process -FilePath (Get-Command node).Source `
    -ArgumentList @(('"{0}"' -f $ngCli), 'serve', '--host', '127.0.0.1', '--proxy-config', 'proxy.conf.json') `
    -WorkingDirectory $FrontendDir `
    -RedirectStandardOutput $frontendLog -RedirectStandardError (Join-Path $FrontendDir 'logs\console-err.log') `
    -WindowStyle Hidden -PassThru -ErrorAction SilentlyContinue
if (-not $frontend) { Fail 'Could not launch node for the Angular console.' }
Save-ProcessRecord 'frontend' $frontend
if (-not (Wait-Http "http://127.0.0.1:$FrontendPort/" 120)) {
    Get-Content -LiteralPath $frontendLog -Tail 30 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" }
    Fail "Angular console did not become ready within 120 seconds. Log: $frontendLog"
}
Write-Ok "Angular console ready at http://localhost:$FrontendPort/ (PID $($frontend.Id))."

# --- 4/4 Done -----------------------------------------------------------------
Write-Step '[4/4] Running'
Write-Host ''
Write-Host 'RateGuard is running.' -ForegroundColor Green
Write-Host "  Console : http://localhost:$FrontendPort/"
Write-Host "  Backend : http://localhost:$BackendPort/   (health: /actuator/health)"
Write-Host "  Redis   : $redisName on port $RedisPort"
Write-Host "  Logs    : redis-rate-limit-poc\logs\backend.log, frontend\logs\console.log"
Write-Host '  Restart : restart.bat (or start.bat)   Stop: stop.bat'
if (-not $NoBrowser) { Start-Process "http://localhost:$FrontendPort/" | Out-Null }
exit 0

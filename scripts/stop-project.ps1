<#
  stop-project.ps1 - stop the RateGuard POC on Windows.

  Stops this project's Angular dev server and Spring Boot backend, then its Redis container.
  Redis data is never deleted (the container is only stopped). Use -KeepRedis to leave Redis running.
  Idempotent, and never touches processes or containers that do not belong to this project.
#>
param(
    [switch] $KeepRedis
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'project-common.ps1')

$problems = 0
Write-Host ''
Write-Host 'RateGuard - stopping project services' -ForegroundColor Cyan

Write-Step '[1/3] Angular console'
if (-not (Stop-ProjectService 'frontend' 'node' $FrontendPort)) { $problems++ }

Write-Step '[2/3] Backend'
if (-not (Stop-ProjectService 'backend' 'java' $BackendPort)) { $problems++ }

Write-Step '[3/3] Redis'
$redisState = Join-Path $StateDir 'redis.txt'
if ($KeepRedis) {
    Write-Ok 'Left Redis running (-KeepRedis).'
} elseif (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Note 'Docker is not on PATH; Redis was not checked.'
} else {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Note 'Docker is not running, so Redis is already down.'
    } else {
        $redisName = Find-RedisContainer
        if ($null -eq $redisName) {
            Write-Ok 'No project Redis container exists.'
        } else {
            $running = & docker inspect -f '{{.State.Running}}' $redisName 2>$null
            if ($running -eq 'true') {
                & docker stop $redisName *> $null
                if ($LASTEXITCODE -eq 0) { Write-Ok "Stopped Redis container '$redisName' (data kept)." }
                else { Write-Fail "Could not stop Redis container '$redisName'."; $problems++ }
            } else {
                Write-Ok "Redis container '$redisName' is already stopped."
            }
        }
    }
    Remove-Item -LiteralPath $redisState -Force -ErrorAction SilentlyContinue
}

Write-Host ''
if ($problems -eq 0) {
    Write-Host 'RateGuard stopped. Redis data was not deleted.' -ForegroundColor Green
    exit 0
}
Write-Host 'RateGuard stop finished with problems; see the messages above.' -ForegroundColor Yellow
exit 1

# Runs the collector locally over plain HTTP on loopback only.
# This is a datatype intake service for debug builds, not a public endpoint.
# For the deployed HTTPS service see README.md, section "Deploy".
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root "packages\backend"
$dataDir = Join-Path $env:TEMP "gboard-collector-local"

$env:COLLECTOR_ADDR = "127.0.0.1:8080"
$env:COLLECTOR_DATA_DIR = $dataDir
$env:COLLECTOR_TTL = "720h"
if (-not $env:COLLECTOR_TOKEN) {
    $env:COLLECTOR_TOKEN = "localdev"
}

Write-Host "Collector (local HTTP only) on http://127.0.0.1:8080"
Write-Host "Data dir: $dataDir"
Write-Host "Token: $($env:COLLECTOR_TOKEN)"
Write-Host "Health: curl -H 'X-Collector-Token: $($env:COLLECTOR_TOKEN)' http://127.0.0.1:8080/healthz"

Push-Location $backend
try {
    go run ./cmd/collector
}
finally {
    Pop-Location
}


# Publish the two model packages to Hugging Face.
# Run once:  hf auth login
# Then:      powershell -ExecutionPolicy Bypass -File tools/publish-models.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Write-Host 'Checking Hugging Face login...'
hf auth whoami
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Not logged in. Run "hf auth login" first.'
}

$models = @(
    @{ Dir = 'releases/omnivox';   Repo = 'giofolf/omnivox' },
    @{ Dir = 'releases/omniglide'; Repo = 'giofolf/omniglide' }
)

foreach ($m in $models) {
    $dir = Join-Path $root $m.Dir
    Write-Host "Publishing $dir -> $($m.Repo)"
    Push-Location $dir
    try {
        hf upload $m.Repo .
        if ($LASTEXITCODE -ne 0) { Write-Error "Upload failed for $($m.Repo)" }
    } finally {
        Pop-Location
    }
}

Write-Host 'Done.'

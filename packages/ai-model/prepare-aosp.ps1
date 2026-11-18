# Prepares the AOSP LatinIME + Whisper build by copying the model into res/raw.
# The model is large (141 MB) and kept out of git; this script copies it from the app assets.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$src = Join-Path $root 'packages\app\src\main\assets\models\ggml-base.bin'
$dst = Join-Path $root 'packages\app\aosp-base\java\res\raw\ggml_base.bin'

if (-not (Test-Path $src)) {
    Write-Error "Model not found: $src. Run packages/ai-model/fetch-model.ps1 first."
}
New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
Copy-Item -Path $src -Destination $dst -Force
Write-Host "Model copied: $dst ($([math]::Round((Get-Item $dst).Length/1MB)) MB)"

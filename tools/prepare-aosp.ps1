# Prepara o build do AOSP LatinIME + Whisper copiando o modelo para res/raw.
# O modelo e grande (141MB) e fica fora do git; este script copia doAssets do app.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'app\src\main\assets\models\ggml-base.bin'
$dst = Join-Path $root 'aosp-base\java\res\raw\ggml_base.bin'

if (-not (Test-Path $src)) {
    Write-Error "Modelo nao encontrado: $src. Rode tools/fetch-model.ps1 primeiro."
}
New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
Copy-Item -Path $src -Destination $dst -Force
Write-Host "Modelo copiado: $dst ($([math]::Round((Get-Item $dst).Length/1MB)) MB)"

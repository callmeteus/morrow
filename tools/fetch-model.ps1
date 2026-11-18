# Download the on-device Whisper model (ggml-base.bin, ~141 MB) into app assets.
# Run from the repo root:  powershell -ExecutionPolicy Bypass -File tools/fetch-model.ps1
$ErrorActionPreference = "Stop"
$asset = Join-Path $PSScriptRoot "..\app\src\main\assets\models\ggml-base.bin"
$asset = [System.IO.Path]::GetFullPath($asset)
New-Item -ItemType Directory -Force -Path (Split-Path $asset) | Out-Null
$urls = @(
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
    "https://huggingface.co/ggml-org/whisper.cpp/resolve/main/ggml-base.bin"
)
foreach ($u in $urls) {
    Write-Host "Trying $u"
    try {
        Invoke-WebRequest -Uri $u -OutFile $asset -UseBasicParsing -TimeoutSec 900
        $len = (Get-Item $asset).Length
        if ($len -gt 100MB) { Write-Host "OK: $len bytes -> $asset"; exit 0 }
        Write-Host "Too small ($len), trying next source..."
    } catch {
        Write-Host "Failed: $($_.Exception.Message)"
    }
}
throw "Could not download ggml-base.bin from any source."

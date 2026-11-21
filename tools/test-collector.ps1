# End-to-end smoke test for the dataset collector.
# Boots the real Go binary on loopback, sends every event kind the Android app
# emits, and verifies the JSONL output and the uploaded WAV.
# All artifacts stay inside this project under docs/evidence/collector-run.
#
# IMPORTANT: keep this file pure ASCII. PowerShell 5.1 reads BOM-less files as
# the system ANSI code page, which mangles accented literals. Accented values
# are built from [char] codes and every file write uses explicit UTF-8.
$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root "packages\app\backend"
$evidence = Join-Path $root "docs\evidence\collector-run"
$report = Join-Path $root "docs\evidence\collector_e2e.md"

Remove-Item -Recurse -Force $evidence -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

$env:COLLECTOR_ADDR = "127.0.0.1:18081"
$env:COLLECTOR_DATA_DIR = $evidence
$env:COLLECTOR_TTL = "720h"
$env:COLLECTOR_TOKEN = "e2etoken"

$acute = [char]0x00E1                 # a with acute -> "ola" becomes "ola" + acute
$atilde = [char]0x00E3                # a with tilde -> "n" + tilde + "o"
$expectedCorrected = "ol" + $acute + " mundo"
$expectedDecoded = "n" + $atilde + "o"

$lines = @()
function Log($s) { Write-Host $s; $script:lines += $s }
function WriteUtf8($path, $text) {
    [System.IO.File]::WriteAllText($path, $text, $script:utf8NoBom)
}

Push-Location $backend
$p = $null
try {
    $p = Start-Process -FilePath "go" -ArgumentList "run","./cmd/collector" -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 4

    $authHeader = "X-Collector-Token: e2etoken"
    $base = "http://127.0.0.1:18081"
    $session = "sess-e2e-001"

    # 1. healthz (open) and auth enforcement
    $health = curl.exe -s -w "`n%{http_code}" "$base/healthz"
    Log "healthz -> $health"
    $noauth = curl.exe -s -o NUL -w "%{http_code}" "$base/v1/export"
    Log "export without token -> $noauth (expect 401)"

    # 2. Build a minimal 1s 16kHz mono 16-bit WAV (44-byte header + silence).
    $wav = Join-Path $evidence "sample.wav"
    $fs = [System.IO.File]::Open($wav, [System.IO.FileMode]::Create)
    $bw = New-Object System.IO.BinaryWriter($fs)
    $dataLen = 16000 * 2
    foreach ($b in [byte[]][char[]]"RIFF") { $bw.Write($b) }
    $bw.Write([int](36 + $dataLen))
    foreach ($b in [byte[]][char[]]"WAVEfmt ") { $bw.Write($b) }
    $bw.Write([int]16); $bw.Write([int16]1); $bw.Write([int16]1)
    $bw.Write([int]16000); $bw.Write([int]32000); $bw.Write([int16]2); $bw.Write([int16]16)
    foreach ($b in [byte[]][char[]]"data") { $bw.Write($b) }
    $bw.Write([int]$dataLen)
    $bw.Write((New-Object byte[] $dataLen))
    $bw.Flush(); $bw.Close()

    # 3. Upload voice via multipart (meta JSON + audio WAV).
    $meta = @{
        id = "voice-e2e-001"; sessionId = $session; ts = 1700000000000
        transcriptRaw = "ola mundo"; transcriptCleaned = "ola mundo"
        transcriptCorrected = $expectedCorrected; inferMs = 1234; rms = 0.05
        durationMs = 1000; model = "ggml-tiny-q8_0-bin"; locale = "pt"
    } | ConvertTo-Json -Compress
    $metaFile = Join-Path $evidence "voice-meta.json"
    WriteUtf8 $metaFile $meta
    $voiceResp = curl.exe -s -w "`n%{http_code}" -H $authHeader `
        -F "meta=<$metaFile" -F "audio=@$wav" "$base/v1/voice"
    $voiceCode = ($voiceResp -split "`n")[-1]
    Log "POST /v1/voice -> $voiceCode (expect 201)"

    # 4. Swipe event.
    $swipe = @{
        id = "sw-e2e-001"; sessionId = $session; ts = 1700000000100
        trail = "nao"; decoded = $expectedDecoded; candidates = @($expectedDecoded, "nada")
        kb = "1080x400"; points = @(@(0.10,0.20,1700000000050),@(0.20,0.25,1700000000075))
    } | ConvertTo-Json -Compress -Depth 6
    $swipeFile = Join-Path $evidence "swipe.json"
    WriteUtf8 $swipeFile $swipe
    $swipeCode = curl.exe -s -o NUL -w "%{http_code}" -H $authHeader -H "Content-Type: application/json" `
        --data-binary "@$swipeFile" "$base/v1/swipe"
    Log "POST /v1/swipe -> $swipeCode (expect 201)"

    # 5. All 7 corrector event kinds, sharing sessionId + monotonic seq.
    $kinds = @(
        @{ kind="asr_word";            input="ola";   candidate=$expectedCorrected;  editDistance=1; frequency=5000; accepted=$true;  voiceId="voice-e2e-001" },
        @{ kind="suggestion_selected"; index=0; selected=$expectedDecoded; latencyMs=420; accepted=$true },
        @{ kind="suggestion_ignored";  input=$expectedDecoded; latencyMs=610; accepted=$false },
        @{ kind="suggestion_missed";   input="zebra"; latencyMs=300; accepted=$false },
        @{ kind="fallback_generic";    shown=@("de","que","nao") },
        @{ kind="backspace_burst";     prefix="err"; accepted=$false },
        @{ kind="swipe_failure";       swipeId="sw-e2e-001"; input="nao"; candidate=$expectedDecoded; accepted=$false }
    )
    $seq = 0
    foreach ($k in $kinds) {
        $seq++
        $k.id = "corr-e2e-$seq"
        $k.sessionId = $session
        $k.ts = 1700000000200 + $seq
        $k.seq = $seq
        if ($k.kind -eq "asr_word") { $k.source = "voice" }
        elseif ($k.kind -eq "swipe_failure") { $k.source = "swipe" }
        else { $k.source = "keyboard" }
        $body = $k | ConvertTo-Json -Compress -Depth 6
        $bodyFile = Join-Path $evidence "body-$seq.json"
        WriteUtf8 $bodyFile $body
        $code = curl.exe -s -o NUL -w "%{http_code}" -H $authHeader -H "Content-Type: application/json" `
            --data-binary "@$bodyFile" "$base/v1/correction"
        Log "POST /v1/correction kind=$($k.kind) -> $code (expect 201)"
    }

    # 6. Export and verify. Capture curl bytes to a file and decode as UTF-8
    # explicitly: the PowerShell 5.1 pipeline uses the console code page,
    # which mangles non-ASCII bytes from native commands.
    $exportFile = Join-Path $evidence "export.json"
    curl.exe -s -o $exportFile -H $authHeader "$base/v1/export"
    $export = [System.IO.File]::ReadAllText($exportFile, (New-Object System.Text.UTF8Encoding($false, $true)))
    $data = $export | ConvertFrom-Json
    Log "export voice=$($data.voice.Count) swipe=$($data.swipe.Count) correction=$($data.correction.Count)"

    $voiceRow = $data.voice[0]
    Log "voice: raw='$($voiceRow.transcriptRaw)' clean='$($voiceRow.transcriptCleaned)' corrected='$($voiceRow.transcriptCorrected)' inferMs=$($voiceRow.inferMs) audioPath=$($voiceRow.audioPath)"
    Log "swipe: trail='$($data.swipe[0].trail)' decoded='$($data.swipe[0].decoded)' points=$($data.swipe[0].points.Count)"
    $actualKinds = ($data.correction | ForEach-Object { $_.kind }) -join ","
    Log "correction kinds: $actualKinds"

    $uploadedWav = Join-Path $evidence "audio\voice-e2e-001.wav"
    $wavBytes = if (Test-Path $uploadedWav) { (Get-Item $uploadedWav).Length } else { 0 }
    Log "uploaded WAV bytes: $wavBytes"

    # Assertions (case-sensitive, codepoint-safe)
    $fail = @()
    if ($noauth -ne "401") { $fail += "no-auth export was not 401" }
    if ($voiceCode -ne "201") { $fail += "voice upload not 201" }
    if ($swipeCode -ne "201") { $fail += "swipe upload not 201" }
    if ($data.voice.Count -ne 1) { $fail += "voice count != 1" }
    if ($data.swipe.Count -ne 1) { $fail += "swipe count != 1" }
    if ($data.correction.Count -ne 7) { $fail += "correction count != 7" }
    if ($voiceRow.transcriptCorrected -cne $expectedCorrected) { $fail += "corrected text mismatch" }
    if ($data.swipe[0].decoded -cne $expectedDecoded) { $fail += "swipe decoded mismatch" }
    if ($wavBytes -lt 44) { $fail += "WAV too small" }
    $uniq = @($data.correction | ForEach-Object { $_.kind } | Sort-Object -Unique).Count
    if ($uniq -ne 7) { $fail += "expected 7 unique kinds, got $uniq" }
    $seqs = $data.correction | ForEach-Object { [int]$_.seq } | Sort-Object
    if (($seqs -join ",") -ne "1,2,3,4,5,6,7") { $fail += "seq not monotonic" }
    $sessions = @($data.correction | ForEach-Object { $_.sessionId } | Sort-Object -Unique)
    if ($sessions.Count -ne 1 -or $sessions[0] -cne $session) { $fail += "sessionId not shared" }

    if ($fail.Count -gt 0) {
        Log "RESULT: FAIL"
        foreach ($f in $fail) { Log "  - $f" }
        exit 1
    }
    Log "RESULT: PASS"
}
finally {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    # `go run` spawns a child collector.exe that outlives the parent; kill it
    # and wait until the port is free before returning.
    for ($i = 0; $i -lt 20; $i++) {
        $kids = Get-Process -Name "collector" -ErrorAction SilentlyContinue
        if (-not $kids) { break }
        $kids | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 200
    }
    Pop-Location
}

$header = @(
    "# Dataset collector end-to-end test",
    "",
    "Run at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') by tools/test-collector.ps1.",
    "Real Go server on 127.0.0.1:18081, data under docs/evidence/collector-run/.",
    "",
    "## Output",
    "",
    '```'
) + $lines + @('```', '')
WriteUtf8 $report ($header -join "`n")
Write-Host "Report: $report"


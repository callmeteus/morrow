# Proof on the device (emulator), 100% local STT

AVD: `gtv`, Pixel 5, Android 14 (API 34), x86_64, `google_apis`, GPU SwiftShader.
Model and library identical to those embedded in the APK (`ggml-base.bin`, `libwhisper.so`).
Input: `pt16.wav`, espeak-ng pt-BR TTS, 16 kHz mono, 6,35 s
(target text: *"O teclado local reconhece a minha voz. O ditado por voz funciona
sem internet."*).

The test is the file-based self-test of `SetupActivity` (path identical to
dictation: `Wav → VoiceEngine.transcribe → DictionaryCorrector`), triggered by
intent without UI interaction:

```
adb shell am start -n dev.gti.gboard/.SetupActivity --ez wavtest 1
```

## Round 1, network available

```
I VoiceEngine:    whisper context ready (threads=4)
I VoiceEngine:    VoiceEngine ready (threads=4)
I SetupActivity:  WAV-SELFTEST: engine ready (dict_size=2934)
I SetupActivity:  WAV self-test file=pt16.wav audio_s=6.35 infer_ms=159443 rtf=25.10
                  raw="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
                  corrected="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
```

## Round 2, AIRPLANE MODE (with no network interface other than `lo`)

```
I SetupActivity:  WAV-SELFTEST: engine ready (dict_size=2934)
I SetupActivity:  WAV self-test file=pt16.wav audio_s=6.35 infer_ms=155385 rtf=24.46
                  raw="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
                  corrected="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
```

Network confirmation (empty = only loopback active):

```
$ adb shell "ip -o link show | grep -Ei up | grep -Evi 'lo:|dummy'"
$ adb shell settings get global airplane_mode_on
1
```

## Reading the results

- **Complete pipeline ran on the device**: native library loaded, model
  initialized, WAV transcribed, dictionary applied. Without a network (round 2), it
  is therefore not Google's STT.
- **Transcription quality**: the audio is *synthetic* (robotic TTS voice,
  150 wpm), which Whisper `base` recognizes imperfectly, as expected. The
  dictation keywords (teclado, local, voz, funciona) appear; the final
  segment degrades (`sem internet` → `ser inteira-nage`), typical of ASR on
  synthetic voice + a small model.
- **Latency**: `infer_ms≈155 s` (RTF ≈ 24) is an artifact of the software-based
  x86_64 **emulator** (weak, shared CPU). On real hardware, with the **same**
  model, the host measurement gives **RTF ≈ 0,23** (see
  `docs/VERIFICATION.md` §2). The emulator serves to prove *it works offline*,
  not to measure latency.

Raw log from round 2: `device_selftest_airplane.log`.

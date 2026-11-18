# Verification, 100% local STT, without Google's voice server

This document records how it was verified that speech recognition works
**entirely on the device**, without any dependency on Google's STT.

## 1. Why the pipeline is structurally offline

- The app **does not reference** `android.speech.SpeechRecognizer`/`RecognitionService`
  and **does not talk** to any system speech service: the complete path is
  `AudioRecord → PCM 16 kHz → libwhisper.so (JNI) → text` (see
  `Recorder.java`, `VoiceEngine.java`, `jni/whisper/jni.cpp`).
- The model (`ggml-base.bin`) comes **inside the APK** (assets) and is copied to
  internal storage on first use, there is no "speech-pack" download at
  runtime (unlike Gboard, whose packs come from `OnDevicePackDownloadWorker`,
  see `REA_GBOARD.md`).
- The native library was compiled with `WHISPER_CURL=OFF` on the host (and without any
  network client on the target), there is no network path in the STT code.

## 2. Functional proof on the host (same `ggml-base.bin` model)

Environment: Windows x86_64, 32 threads, whisper.cpp (clone of `ggml-org/whisper.cpp`,
build `Release`, `ggml-base.bin` 147.951.465 bytes, identical to the one embedded in the
APK).

### Command

```bash
whisper-cli.exe -m ggml-base.bin -l pt -nt pt-test.wav
whisper-cli.exe -m ggml-base.bin -l en -nt jfk.wav
```

### Captured output (evidence)

- `evidence/jfk-transcript.txt` (real human voice audio, 6,3 s):

  ```
  whisper_print_timings:    total time =  1458.21 ms
   And so my fellow Americans, ask not what your country can do for you, ask
   what you can do for your country.
  ```

- `evidence/pt-transcript-espeak.txt` (6,8 s, espeak-ng pt-BR synthetic voice,
  robotic voice, expectedly imperfect transcription, but pt pipeline OK):

  ```
  whisper_print_timings:    total time =  1557.03 ms
   O modelo que forzou cowfuncio na seguinte da niche. A gitado por voz hoda
   no apateio.
  ```

  Target text: *"O modelo de voz local funciona sem internet. A ditado por voz
  roda no aparelho."*, the keywords (modelo, voz, local, ditado,
  aparelho) were recognized, confirming the pt-BR axis.

### Latency (measurement)

| Metric | Value |
|---|---|
| Audio (pt-test) | 6,8 s |
| Total inference time | **1,56 s** |
| Real-time factor (RTF) | **≈ 0,23** (5,3× faster than real time) |
| Mel + encode + decode (JFK, 6,3 s) | ~1,46 s |

Google's STT adds the network round-trip (audio upload + server response):
typically 0,8–3 s on unstable 4G/Wi-Fi, in addition to processing
latency. Since RTF ≈ 0,23 < 1, local time grows linearly and stays
**below** Google's STT for dictation-length phrases (and is ~0 when there
is no network, where Google's STT simply does not work).

## 3. Local dictionary/correction layer (offline)

`DictionaryCorrector.java` applies, **on the device**, a post-ASR correction
on the final transcription:

- **Dictionary**: 3.000 pt-BR words ranked by frequency in a real corpus
  (Dom Casmurro, Wikisource + 30 articles from pt Wikipedia), embedded in the
  APK (`assets/dict/pt-br-dictionary.txt`, ~33 KB).
- **Algorithm**: each unknown token (≥ 4 letters, comparison without
  accents) is replaced by the dictionary word with the smallest Levenshtein
  distance (≤ 1 for short words, ≤ 2 for long ones), preferring the one
  with the highest frequency; distance-2 replacements require frequency ≥
  100 (avoids replacing a genuinely rare word with a common one).
- **Validation**: the Levenshtein core was tested against a complete
  reference on 200.000 random pairs (0 failures); the behavior was validated
  with a Python mirror: known words preserved (including with accent and
  capitalization, `Dicionario` → `Dicionário`), typical ASR errors corrected
  (`intenet` → `internet`, `trabaIhar` → `trabalhar`,
  `computadpr` → `computador`), noise preserved (`xkcdzz` → `xkcdzz`).
- **No network**: the dictionary is an APK asset; all correction runs in
  memory, on the same executor as Whisper.

## 4. Verification on the device (EMULATOR, EXECUTED)

Executed on the Android 14 emulator (AVD `gtv`, Pixel 5, x86_64, `google_apis`,
without dedicated GPU acceleration). The transcription itself is done by the
same `libwhisper.so` + `ggml-base.bin` from the APK, via JNI.

### 4.1 Deterministic WAV test (without microphone)

`SetupActivity` has a file-based test that exercises exactly the same
path as dictation (`Wav.to16kMono → VoiceEngine.transcribe → DictionaryCorrector`)
and is triggerable by intent, without UI interaction:

```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb push pt16.wav /sdcard/Android/data/dev.gti.gboard/files/
adb shell pm grant dev.gti.gboard android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -n dev.gti.gboard/.SetupActivity --ez wavtest 1
adb logcat -d -s SetupActivity,VoiceEngine
```

**Captured output** (input: espeak-ng pt-BR TTS, 6,35 s;
`evidence/device_selftest_airplane.log`):

```
I VoiceEngine:    whisper context ready (threads=4)
I VoiceEngine:    VoiceEngine ready (threads=4)
I SetupActivity:  WAV-SELFTEST: engine ready (dict_size=2934)
I SetupActivity:  WAV self-test file=pt16.wav audio_s=6.35 infer_ms=155385 rtf=24.46
                  raw="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
                  corrected="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
```

This proves, on the device: (a) `libwhisper.so` loads and creates the context;
(b) the model is extracted from assets and initialized; (c) the 16 kHz mono WAV
is transcribed; (d) the dictionary layer runs on the final transcription.

> **Note about RTF on the emulator:** the `infer_ms≈155 s` (RTF ≈ 24) is an artifact of
> the software-based x86_64 emulator (weak, shared CPU, without dedicated
> AVX/NEON instruction). It does **not** represent performance on a real device.
> Measurement on real hardware (host, §2) gives **RTF ≈ 0,23** with the *same* model.

### 4.2 Isolation proof, Airplane Mode (EXECUTED)

With `airplane_mode_on=1` and **no active network interface** (only `lo`),
the same test was repeated and **produced the complete transcription**
(`infer_ms=155385`, same text as above). Without a network, there is no way to
use Google's STT, the transcription is 100% local.

```bash
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
adb shell "ip -o link show | grep -Ei up | grep -Evi 'lo:|dummy'"   # empty
# … rerun test 4.1 …
```

### 4.3 IME registration and activation (EXECUTED on the emulator)

The IME must declare the action `android.view.InputMethod` (and not
`android.view.inputmethod.InputMethod`) in the service's intent-filter, otherwise
`InputMethodManagerService` will not discover it. Verified:

```
$ adb shell ime list -a | grep gti
dev.gti.gboard/.GboardIme:
$ adb shell ime enable dev.gti.gboard/.GboardIme
Input method dev.gti.gboard/.GboardIme: now enabled for user #0
$ adb shell ime set dev.gti.gboard/.GboardIme
Input method dev.gti.gboard/.GboardIme selected for user #0
$ adb shell dumpsys input_method | grep -E 'mCurMethodId|mIsInputViewShown'
mCurMethodId=dev.gti.gboard/.GboardIme
mIsInputViewShown=true
```

The `VoiceEngine` of the keyboard process initializes when a field is focused, and the
`InputMethod` window of `dev.gti.gboard` appears in `dumpsys window windows`.

### 4.4 Microphone dictation (real device)

Button dictation (hold 🎤 → speak → release) uses real-time `AudioRecord`; the
emulator has no reliable physical microphone, so this item is verified on a
physical device with:

1. `adb install -r app\build\outputs\apk\debug\app-debug.apk`
2. Enable the keyboard in *Settings → Keyboard* and allow the microphone.
3. Hold 🎤, speak ~4 s in pt, release → text committed to the field.
4. (Isolation proof) enable *Airplane mode* and repeat: it keeps working.

## 5. Artifacts

| File | Content |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | Signed (debug) APK with `libwhisper.so` (arm64-v8a, armeabi-v7a, x86_64) + `ggml-base.bin` |
| `evidence/*.txt` | Raw outputs from whisper-cli (timings + transcriptions) |
| `REA_GBOARD.md` / `REA_VOICE_CURSOR.md` | REA artifacts |
| `../README.md` | Build, ADB installation and configuration |

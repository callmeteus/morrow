# REA, Gboard for Android (voice / STT stack)

Reverse-engineering analysis of the **Gboard** APK
(`com.google.android.inputmethod.latin`), performed with the REA workflow:
obtain the shipped binary → decompile → map the voice/STT integration points →
identify the exact replacement surface used by `gboard-local-voice`.

> Method note (REA): the target is an **obfuscated proprietary binary**
> (R8/ProGuard, `defpackage.*` class names). Findings below are labeled
> **Observed** (directly present in the decompiled code) or **Inferred**
> (behavior expected from that code). The Gboard binary is Google's
> proprietary work, this analysis documents *where* the STT plugs in so the
> behavior can be reimplemented independently, which is what
> `gboard-local-voice/` does (clean-room, no Gboard code copied).

## 0. Acquisition and toolchain (artifacts)

| Step | Detail |
|------|--------|
| Source | Gboard release APK (~69.5 MB, `application/vnd.android.package-archive`), mirror `androidapks.com` |
| Validation | ZIP central directory lists `AndroidManifest.xml`, `META-INF/services/io.grpc.*` (gRPC present in the app) |
| Decompiler | **jadx 1.5.6** (`jadx --no-res -d out gboard.apk`), JDK 17 |
| Output | 21,448 Java files (116 classes partially failed, normal at this size) |
| Key packages | `com.google.android.apps.inputmethod.libs.{voiceime,hmmvoice,genaivoice,agenticdictation}`, `com.google.android.libraries.inputmethod.voice.smartdictation.*`, `defpackage.*` (obfuscated core) |

## 1. The Gboard voice stack (Observed)

```
                        Gboard dictation pipeline (from the APK)
┌────────────────────────────────────────────────────────────────────────┐
│ 1. CAPTURE   defpackage.aaqe                                            │
│              new AudioRecord(6 /*VOICE_RECOGNITION*/, 16000,           │
│                               16 /*PCM_16BIT*/, 2 /*STEREO*/,          │
│                               max(getMinBufferSize(16000,16,2),32000)) │
│ 2. LIFECYCLE sap interface (obf.), implemented by AgenticDictationIme │
│              onVoiceStart / onListeningStop / abortComposing /         │
│              finishComposing  (Observed method strings in               │
│              AgenticDictationIme.java)                                  │
│ 3. PROCESSORS                                                                  │
│              HmmVoiceInputProcessor (hmmvoice), on-device HMM LM for   │
│              voice input; SmartDictation service (smartdictation):      │
│              NativeFormatterImpl (JNI, dictation_jni) with              │
│              capitalization / punctuation / spoken-punctuation /        │
│              suffix-command model buffers; SpeechAlternativesTrie       │
│              (spelling alternatives); NativeEmojiNluHandler             │
│ 4. BACKENDS                                                                  │
│              • OnDevicePackDownloadWorker (voiceime), auto-downloads  │
│                per-language "speech-packs" from Google servers          │
│              • Network STT via gRPC/protobuf (io.grpc services in       │
│                AndroidManifest META-INF), the actual acoustic model    │
│                for dictation runs on Google's servers                   │
│ 5. TTS     android.speech.tts.TextToSpeech (read-back; not STT)         │
└────────────────────────────────────────────────────────────────────────┘
```

### Evidence map (decompiled file → finding)

| Decompiled file | Observed evidence |
|-----------------|-------------------|
| `defpackage/aaqe.java` | Mic capture: `AudioRecord(6, 16000, 16, 2, …)` → **16 kHz PCM 16-bit** (same rate this project standardizes on) |
| `com/…/libs/agenticdictation/AgenticDictationIme.java` | Dictation IME implementing `sap`; log strings `onVoiceStart`, `onListeningStop`, `abortComposing`, `finishComposing` |
| `com/…/libs/hmmvoice/HmmVoiceInputProcessor.java` | Implements `otj` (input-processor) + `sap`; holds language model `sas` and recognizer `ngw` |
| `com/…/libs/voiceime/backend/ondevice/OnDevicePackDownloadWorker.java` | `ImeListenableWorker("ondevice_pack_download_work")`; fetches `speech-packs` by `language_tag` → on-device data is **downloaded from Google**, not bundled |
| `com/…/voice/smartdictation/service/formatter/NativeFormatterImpl.java` | Extends JNI class (`dictation_jni`); models: `capitalizationModelBuffer`, `punctuationModelBuffer`, `spokenPunctuationModelBuffer`, `suffixCommandModelBuffer` → post-ASR **punctuation/capitalization** |
| `com/…/voice/smartdictation/service/spelling/SpeechAlternativesTrie.java` | Trie of spoken-word alternatives (dictionary/correction layer) |
| `META-INF/services/io.grpc.*` (APK) | gRPC stack bundled → network speech backend |

## 2. Where the STT decision happens (Inferred + Observed mix)

1. The mic key in the Gboard UI triggers the `sap` lifecycle (`onVoiceStart`).
2. `aaqe` starts `AudioRecord` at 16 kHz; audio frames flow to the voice
   backend.
3. **Inferred**: frames are chunked and sent over the bundled gRPC stack to
   Google's speech service (the acoustic model is server-side); `OnDevicePack
   DownloadWorker`'s "speech-packs" supply on-device *language* data (HMM LM,
   formatters), not the acoustic model.
4. Recognized text returns to the IME, passes the SmartDictation formatters
   (punctuation, capitalization, alternatives) and is committed to the
   `InputConnection`.

The **replacement point** for a fully local STT is therefore:

```
[16 kHz PCM from AudioRecord]  ──▶  LOCAL ASR (this project: whisper.cpp)
                                   instead of
                                   [gRPC upload → Google speech servers]
```

Everything around it (lifecycle, capture rate, commit path, post-processing)
is standard IME API and is reimplemented in `gboard-local-voice`:

| Gboard (Observed) | gboard-local-voice (this repo) |
|---|---|
| `AudioRecord(VOICE_RECOGNITION, 16000, PCM_16BIT, stereo)` | `Recorder.java`, 16 kHz PCM 16-bit mono (Whisper's native input) |
| `sap` lifecycle (`onVoiceStart`/`onListeningStop`) | `DictationController` (LISTENING/TRANSCRIBING state machine, press-and-hold mic) |
| gRPC → Google speech servers | `libwhisper.so` (JNI, `whisper_full`), **on-device** |
| SmartDictation formatters (punct/caps models) | Whisper emits text with punctuation + casing directly (multilingual base model); alternatives/dictionary hook is the documented extension point |
| `OnDevicePackDownloadWorker` (network) | Model shipped **inside the APK assets** (`ggml-base.bin`), no runtime download, no Google endpoint |

## 3. Residual unknowns

- Exact gRPC method/protobuf schema of the network STT (fully obfuscated;
  `io.grpc` provider files confirm the transport, not the contract).
- Whether the latest releases run a small on-device acoustic model for
  low-latency partials before the server pass (the `speech-packs` mechanism
  is consistent with that; the pack contents are downloaded at runtime).

Neither unknown affects the deliverable: the replacement point (local ASR in
place of the network pass) is the same in both cases, and `gboard-local-voice`
replaces the *entire* acoustic pass locally.

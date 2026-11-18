# REA, Voice Cursor (`cursor-voice`)

Reverse-engineering analysis of the **Voice Cursor** reference app, recovered from
this repository's git history (`Plataformas/cursor-voice`, commit `e2bfc134`).
Goal: understand exactly what "Voice Cursor quality" means for speech
recognition, so the on-device Android keyboard can replicate it **without**
Google's speech service.

> Method (REA): the target is a shipped source tree (Electron + Python sidecar),
> not an opaque binary, so the analysis reads the actual source and the runtime
> data flow rather than decompiling. Findings are labeled **Observed** (present
> in the code) vs **Inferred** (behavior we expect from that code).

## 1. What it is

An Electron desktop app that adds a "voice cursor", push-to-talk dictation and
an assistant, on top of the desktop. It is **not** an Android keyboard; it is
the behavioral/UX reference for how the voice feature should *feel*.

```
Electron main process                Python sidecar (FastAPI, localhost:16842)
┌─────────────────────────────┐      ┌──────────────────────────────────┐
│ hotkey.ts  (globalShortcut) │      │ app.py                           │
│ ipc.ts     (IPC bridge)     │      │  /stt  → faster-whisper (base)   │
│ Recorder/Agent/ChatClient   │  HTTP │  /tts  → Kokoro (onnx)          │
│ ScreenCapture / WebSearch   │ ───▶ │  /health /engines                │
└──────────────┬──────────────┘      └──────────────────────────────────┘
               │ (renderer: React)
┌──────────────▼──────────────┐
│ App.tsx / Orb.tsx / realtime│  captures mic, base64-encodes WAV,
│ SettingsView / i18n         │  POSTs to /stt, renders live text
└─────────────────────────────┘
```

## 2. The speech model (the part we replicate)

**Observed**, `sidecar/app.py`:
```python
_whisper = WhisperModel("base", device="cpu", compute_type="int8")
...
segments, info = model.transcribe(path, language=req.language)
text = " ".join(s.text for s in segments).strip()
```
- Engine: **faster-whisper**, model size **`base`**, **CPU**, **int8** quantization.
- Input: an audio file (`wav`/`webm`/`mp3`/`ogg`/`m4a`/`flac`), base64 in the body.
- Output: `{ "text": <transcript>, "language": <detected> }`.
- Language is passed through from the client (`language=req.language`), so
  Portuguese is first-class (`LANG_CODE` maps `pt → pt-br`).

**Inferred**, `base`/int8 on CPU is the deliberate tradeoff: fast enough for
interactive dictation, accurate enough to feel "smart". This is the quality bar
to match.

**Observed**, `sidecar/requirements.txt`: `faster-whisper>=1.1.0`,
`onnxruntime>=1.20.0` (TTS only), `kokoro-onnx>=0.4.0` (TTS only).

> The TTS path (Kokoro) is **out of scope** for a keyboard, dictation only
> needs STT. We replicate the STT model family and the interaction model, not TTS.

## 3. Interaction model (the "dynamic" to preserve)

**Observed**, `src/main/hotkey.ts` registers a **global shortcut**
(`globalShortcut.register(accelerator, callback)`); `SidecarClient.ts` exposes
`transcribe(audioBase64, format, language)`. The renderer (`App.tsx`,
`realtime.ts`) drives a capture→transcribe loop and shows text as it arrives.

**Inferred interaction** (the Gboard-like feel we reproduce):
1. **Push-to-talk**, press the voice control to start capturing; release to
   stop and commit. (Gboard's mic uses press-and-hold.)
2. **Live transcript**, text appears while/after speaking, not just at the end.
3. **Local, instant**, no cloud round-trip, so latency is bounded by on-device
   inference.

## 4. Porting decision → `gboard-local-voice`

| Voice Cursor (reference)            | gboard-local-voice (this project)                  |
|-------------------------------------|----------------------------------------------------|
| faster-whisper `base` int8 (CPU)    | **whisper.cpp** `ggml-base` (same model family)    |
| Python FastAPI sidecar (localhost)  | **JNI → libwhisper.so** inside the app (no sidecar)|
| Electron global hotkey              | **IME mic key, press-and-hold**                    |
| base64 WAV over HTTP                | **16 kHz PCM in-process** (no serialization)       |
| Kokoro TTS (out of scope)           |,                                                  |

**Why whisper.cpp:** it is the same Whisper model family as faster-whisper
(`ggml-base.bin` ≙ the `base` weights), runs fully **on-device**, needs no
server, and ships a first-class Android build (we adapt the upstream
`examples/whisper.android` CMake + JNI). This preserves "Voice Cursor quality"
while making the entire path local and Google-independent.

## 5. Key files (evidence map)

| File | Role |
|------|------|
| `sidecar/app.py` | STT endpoint (`/stt`) + faster-whisper `base`/int8 |
| `sidecar/requirements.txt` | engine versions (faster-whisper, onnxruntime) |
| `src/main/hotkey.ts` | global push-to-talk shortcut |
| `src/main/services/SidecarClient.ts` | HTTP client → `/stt` |
| `src/renderer/realtime.ts`, `App.tsx` | capture→transcribe→live text loop |
| `src/main/services/Agent.ts`, `ChatClient.ts` | assistant layer (not replicated) |

## 6. Residual unknowns

- Exact desktop audio capture format/sampling rate (renderer side), we standardize
  on **16 kHz mono** (Whisper's native rate) regardless.
- Whether the reference used any VAD/partial streaming, we approximate "live"
  with periodic partial transcriptions (see `DictationController`).

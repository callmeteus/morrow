---
license: mit
language:
- multilingual
- pt
- en
pipeline_tag: automatic-speech-recognition
library_name: whisper.cpp
tags:
- whisper
- offline
- on-device
- android
- asr
---

# OmniVox Lite

**OmniVox Lite** is the local speech model used by a Gboard-style Android keyboard with offline dictation: an offline, on-device Whisper engine for an Android keyboard. It runs entirely on the phone through `whisper.cpp` + JNI, with no network calls and no dependency on Google's speech service.

The base weights are the standard `ggml-base` model from [ggerganov/whisper.cpp](https://github.com/ggerganov/whisper.cpp) (OpenAI Whisper base, MIT). This release packages them together with the project's local correction dictionaries so the whole offline pipeline can be reproduced in one download. The `base` model is multilingual, so Omnivox is not tied to a single language; Portuguese and English are the languages exercised by the keyboard.

## Contents

| File | Purpose |
| --- | --- |
| `ggml-base.bin` | Whisper base weights in ggml format (~141 MB) |
| `common-words.txt` | High-frequency common words for the local corrector |
| `pt-br-dictionary.txt` | pt-BR frequency dictionary (~3k words) |
| `pt-br-lm.txt` | Local unigram/bigram language model used by the glide decoder |

## Usage

```bash
hf download giofolf/omnivox-lite ggml-base.bin --local-dir .
```

In the keyboard, the model is copied into the app assets and loaded through `VoiceEngine.java`:

```
AudioRecord -> 16 kHz PCM -> libwhisper.so (JNI) -> transcript -> local dictionary correction
```

## Training / adaptation

OmniVox Lite uses the unmodified Whisper `base` checkpoint. Adaptation is handled at runtime by the local dictionary corrector (`DictionaryCorrector.java` in the source repo), which replaces low-confidence ASR words with high-frequency dictionary candidates using accent-insensitive edit distance.

## License

Whisper base weights and whisper.cpp are MIT licensed (Copyright OpenAI / Georgi Gerganov). The correction dictionaries are part of the keyboard project (MIT). See the source repository for full notices.

## Credits

- OpenAI Whisper
- ggerganov/whisper.cpp
- Keyboard project contributors

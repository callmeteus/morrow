---
license: mit
language:
- multilingual
- pt
- en
pipeline_tag: text-generation
library_name: pytorch
tags:
- swipe
- gesture
- keyboard
- on-device
- android
---

# Omniglide

**Omniglide** is the neural swipe-typing model used by a Gboard-style Android keyboard with offline dictation. It decodes a finger gesture across an Android QWERTY keyboard into the word the user intended, replacing AOSP's geometric gesture fallback (which had no language model and produced garbage). Omniglide is multilingual: it was trained on real Portuguese (pt-BR / pt-PT) gestures from HeliBoard and real English gestures from FUTO.

## Model

`swipe_model.bin` (~2 MB) is a compact neural network trained on gesture traces represented as normalized `[x, y, t]` points, with a word-frequency vocabulary built from the same corpus. It runs on-device through `SwipeJni.cpp` in the keyboard, so decoding stays fully offline.

## Training data

| Source | Language | Use |
| --- | --- | --- |
| HeliBoard gesture corpus | pt-BR / pt-PT | Portuguese traces |
| FUTO gesture corpus | English | English traces |

The raw gesture data and training scripts live in `packages/ai-model/gesture-training/` in the source repository. A local usage log (`UsageLog.java`) records offered candidates, the user's committed word, and any subsequent corrections, providing the pairs needed to retrain the model as the app is used.

## Usage

```bash
hf download gtihq/omniglide swipe_model.bin --local-dir .
```

Place `swipe_model.bin` in the keyboard's `res/raw/` directory (the Gradle build copies it from `packages/ai-model/`). The model is loaded by `SwipeDecoder.java` via JNI.

## License

MIT. The vendored OpenSwipe engine is MIT licensed. See the source repository for full notices.

## Credits

- HeliBoard gesture contributors
- FUTO keyboard contributors
- Keyboard project contributors

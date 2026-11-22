# Product

## What this product is

Morrow is an Android keyboard with fully on-device voice dictation. Speech recognition runs through whisper.cpp on the device. The keyboard also decodes swipe gestures with a local neural model and ranks suggestions from a local dictionary. No network round-trip is required for dictation, swipe typing or correction.

The repository holds two Gradle builds: the setup and launcher app under `packages/app/` and the IME under `packages/app/aosp-base/`. The vendored engines, trained models and gesture training scripts live in `packages/ai-model/`.

## Who uses it

People who want dictation and swipe typing in Portuguese on an Android device without sending input to a remote service. The user enables the keyboard in system settings, grants microphone access and holds the microphone key to dictate.

## What it does

- Dictates in Portuguese by default. The language is configurable and the base model is multilingual.
- Corrects unknown recognition results with a local pt-BR frequency dictionary.
- Decodes swipe traces into dictionary words with a local model.
- Shows a suggestion strip with the typed word first and the corrector's best candidate in the centre.
- Offers emoji, GIF search, clipboard history, a translation panel, one-handed mode and a tools menu.
- Logs typed keys, swipe traces and dictation results to a local JSONL file for later training. Nothing is uploaded.
- Supports English, Portuguese, Brazilian Portuguese and Spanish locales.

## Models

Two trained artifacts are published under the `gtihq` account on Hugging Face: OmniVox Lite for speech recognition and Omniglide for swipe decoding. The Forgejo build downloads the Whisper model before building and copies it into `res/raw/`.

## Tone of voice

Plain and direct. Describe what the user sees and does. Name the on-device behavior and the privacy property. Do not use marketing language.

## Open points from the roadmap

- Ship the model as a download on first run instead of relying on the build-time copy.
- Remove remaining Gboard and Google wording from code and documentation.
- Provide a landing page.
- Move the project to the `gtihq` organization.

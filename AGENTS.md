# Morrow

A Gboard-style Android keyboard with on-device Whisper dictation, neural swipe
typing and a persistent user lexicon. The keyboard package is
`com.android.inputmethod.latin`; the setup/launcher app is `dev.gti.gboard`.

## Layout

- `packages/app/` owns its own Gradle build (AGP 8.5.2, Gradle 8.9, JDK 17,
  NDK 28.2.13676358, CMake 3.22.1). Run Gradle from inside this directory.
- `packages/app/aosp-base/` is an independent Gradle build for the IME.
- `packages/ai-model/` holds the vendored engines, trained models and gesture
  training scripts. Release binaries are published to Hugging Face and kept
  out of git.

## CI / Forgejo builds

Builds run on the self-hosted Forgejo instance, not on the local machine.

- Forgejo UI: `https://forgejo.tail375d47.ts.net`
- API: `http://100.87.110.100:3010/`
- Workflow: `.forgejo/workflows/build.yml`
- Runner: self-hosted Forgejo Actions runner with `runs-on: docker`
- Build image: `mingc/android-build-box:latest` (JDK 17 + Android SDK/NDK/ CMake)

The workflow downloads the Whisper model from
`https://huggingface.co/giofolf/omnivox` before building, copies it into
`res/raw/`, builds both APKs and uploads them as the `morrow-apks` artifact.

Trigger a build with `workflow_dispatch` in the Forgejo UI, or by pushing to
`master`. After the run finishes, download the APKs from the Actions artifacts
and install them on the device with:

```bash
adb install -r packages/app/build/outputs/apk/debug/morrow-debug.apk
adb install -r packages/app/aosp-base/build/outputs/apk/debug/LatinIME-debug.apk
adb shell ime set com.android.inputmethod.latin/.LatinIME
```

Infrastructure conventions for Forgejo (secrets, runners, GitOps webhook) live
in `Pacotes/gti-infra/AGENTS.md` and `Pacotes/gti-infra/docs/GITOPS.md`. Do not
duplicate them here.

## Conventions

- Docs and code comments in English.
- Commit messages in English with conventional prefixes.
- No em-dash character in any file.
- The first suggestion is always the word the user produced; the centre
  suggestion is the corrector's best candidate; only the word that will be
  committed gets the heavy font weight.

## Training must be published to Hugging Face

- Every training run, once finished, MUST be uploaded to Hugging Face before the task is
  reported as done. This is mandatory, whether the run passed acceptance or not.
- Upload the final weights or adapter, the export in the delivery format, the model card
  (settings, data sources and hours, metrics table, acceptance verdict, license and attribution
  of the base model and of every dataset), and the training log summary.
- Visibility: upload as private by default. Make a repository public only when the run passed
  its acceptance rule and the user said so. A failed run is still uploaded (private, card says
  FAIL) so the result and the numbers are never lost.
- Repositories: `giofolf/omnivox-pro` (Parakeet TDT, repo ai-personal-assistant) and
  `giofolf/omnivox-lite` (Whisper base, repo morrow). Use a new revision or a clearly named
  folder per run; never overwrite a published run.
- Never put tokens in the repository or in logs. Use the logged-in `hf` CLI or `HF_TOKEN` from
  the environment. Ask the user to run `hf auth login` if it is missing.
- Do not delete anything local until the upload is verified (file list and sizes match on the Hub).
- After the upload is verified, delete the exported weight files from the local disk: the ONNX
  external data (`*.onnx.data`), the `.onnx` graphs and, for OmniVox Lite, the ggml `.bin` files.
  Keep the small files (adapters, `vocab.txt`, configs, hashes, logs, reports) and the base
  checkpoint. Record the Hub revision and the freed size in the run report.

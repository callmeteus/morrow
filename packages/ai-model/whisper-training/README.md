# OmniVox Lite training preparation

OmniVox Lite is the Whisper base 74M model exported to ggml and bundled with
Morrow. This folder will hold its fine-tuning scripts. Nothing is implemented
here yet: the training script comes later. This README only records how the
fine-tune will consume the shared dataset store managed by the
`speech-datasets` package in `Pacotes/speech-datasets`.

## Shared store variables

The three variables below are documented in `../.env.example`. They point both
OmniVox Lite and OmniVox Pro at the same store and the same scripts.

| Variable | Default | Meaning |
| --- | --- | --- |
| `SPEECH_DATASETS_DIR` | `C:/Users/Gio/training-work/data` | Root of the shared store with the audio, manifests, splits and state. |
| `SPEECH_DATASETS_TOOLS` | Unset | Absolute path of the `speech-datasets` checkout. Add it to `PYTHONPATH` before running a module. |
| `SPEECH_DATASETS_MAX_GB` | `25` | Working-set cap in GiB, checked before every shard download. |

`HF_HOME` is set to `$SPEECH_DATASETS_DIR/hf-cache` by the tools unless it is
already set. Legacy audio and manifests from earlier runs stay where they are
and are discovered read only.

## How the fine-tune will read the store

The shared package prepares shards and writes canonical JSONL manifests with
`audio_filepath`, `duration`, `text`, `lang`, `source`, `license`,
`license_url` and `speaker_id`. The split command groups by source and speaker
with a seed, so the same data can be used by both models:

```bash
python -m speech_datasets split --name omnivox-lite --seed 42 --validation-ratio 0.1
```

That writes `splits/omnivox-lite/train.jsonl`, `val.jsonl` and `summary.json`
under the store. A later script in this folder will read those two files,
convert them to the Hugging Face Seq2Seq format expected by Whisper, and run the
fine-tune with the local Whisper base checkpoint. The resulting weights will be
exported to ggml with whisper.cpp's converter and published for OmniVox Lite.

No training script, model or dataset is downloaded or committed in this step.
The store, registry and license rules live in the `speech-datasets` repository.

# Shared speech datasets

OmniVox Lite (Whisper base 74M, ggml) and OmniVox Pro (Parakeet 600M, ONNX int8)
train from one dataset store. The store never lives inside either repository.
The canonical tool checkout is `Pacotes/speech-datasets` and the registry at
`registry/sources.json` is the single source of truth for upstream facts and
license decisions.

## Environment variables

Set these before using the shared tools, as shown in
`packages/ai-model/.env.example`:

- `SPEECH_DATASETS_DIR`: store root, default `C:/Users/Gio/training-work/data`.
- `SPEECH_DATASETS_TOOLS`: absolute path of the speech-datasets checkout.
- `SPEECH_DATASETS_MAX_GB`: working-set cap, default `25`.

`HF_HOME` is set to `$SPEECH_DATASETS_DIR/hf-cache` unless it already exists.
Nothing downloads a dataset, model or tool in this repository, and no port or
database is touched.

## Store layout

```
$SPEECH_DATASETS_DIR/
  hf-cache/                    Hugging Face cache, deleted after each source
  audio/<source>/<shard>/*.wav converted audio, deleted after training uses it
  manifests/<source>_<shard>.jsonl
  splits/<split-name>/train.jsonl, val.jsonl, summary.json
  models/                      base checkpoints, not datasets, optional
  state.json                   what was prepared or deleted, for resume
```

The legacy folders `audio`, `audio-run2`, `manifests` and `manifests-run2` stay
where they are. Tools read them only for discovery and never move or delete
them. Canonical manifest lines carry the source, license and SPDX license URL,
so every training clip stays traceable.

## Commands

From the speech-datasets checkout, or with it on `PYTHONPATH`:

```bash
python -m speech_datasets list
python -m speech_datasets prepare --source cetuc --shard 0
python -m speech_datasets delete-audio --source cetuc --shard 0
python -m speech_datasets split --name omnivox-lite --seed 42
```

`split` writes the `train.jsonl` and `val.jsonl` that a later OmniVox Lite
script will convert to the Hugging Face Seq2Seq format and export to ggml with
whisper.cpp's converter.

## License rule

The registry marks each source as `train`, `evaluation` or `refused`. CC0, MIT,
Apache and CC BY sources may be used for training. Any license carrying NC, ND
or SA is evaluation only and is refused for training, even when a mirror claims
a permissive license, because the upstream restriction wins.

CC BY sources require attribution. That attribution must go in the
`gtihq/omnivox-lite` model card. The SPDX license URL on every manifest line makes
the required credits easy to collect for the card. Other model cards follow the
same rule for the sources they consume.

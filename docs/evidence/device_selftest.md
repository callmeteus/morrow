# Prova no dispositivo (emulador), STT 100% local

AVD: `gtv`, Pixel 5, Android 14 (API 34), x86_64, `google_apis`, GPU SwiftShader.
Modelo e lib idênticos aos embutidos no APK (`ggml-base.bin`, `libwhisper.so`).
Entrada: `pt16.wav`, TTS espeak-ng pt-BR, 16 kHz mono, 6,35 s
(texto-alvo: *"O teclado local reconhece a minha voz. O ditado por voz funciona
sem internet."*).

O teste é o self-test por arquivo do `SetupActivity` (caminho idêntico à
ditação: `Wav → VoiceEngine.transcribe → DictionaryCorrector`), acionado por
intent sem interação de UI:

```
adb shell am start -n dev.gti.gboard/.SetupActivity --ez wavtest 1
```

## Rodada 1, rede disponível

```
I VoiceEngine:    whisper context ready (threads=4)
I VoiceEngine:    VoiceEngine ready (threads=4)
I SetupActivity:  WAV-SELFTEST: engine ready (dict_size=2934)
I SetupActivity:  WAV self-test file=pt16.wav audio_s=6.35 infer_ms=159443 rtf=25.10
                  raw="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
                  corrected="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
```

## Rodada 2, MODO AVIÃO (sem nenhuma interface de rede além de `lo`)

```
I SetupActivity:  WAV-SELFTEST: engine ready (dict_size=2934)
I SetupActivity:  WAV self-test file=pt16.wav audio_s=6.35 infer_ms=155385 rtf=24.46
                  raw="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
                  corrected="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
```

Confirmação de rede (vazio = só loopback ativo):

```
$ adb shell "ip -o link show | grep -Ei up | grep -Evi 'lo:|dummy'"
$ adb shell settings get global airplane_mode_on
1
```

## Leitura dos resultados

- **Pipeline completa executou no dispositivo**: lib nativa carregou, modelo
  inicializou, WAV transcrito, dicionário aplicado. Sem rede (rodada 2), logo
  não é STT do Google.
- **Qualidade da transcrição**: o áudio é *sintético* (voz robótica de TTS,
  150 wpm), o que o Whisper `base` reconhece de forma imperfeita, esperado.
  As palavras-chave do ditado (teclado, local, voz, funciona) aparecem; o
  trecho final degrada (`sem internet` → `ser inteira-nage`), típico de
  ASR em voz sintética + modelo pequeno.
- **Latência**: `infer_ms≈155 s` (RTF ≈ 24) é artefato do **emulador**
  x86_64 via software (CPU fraca e compartilhada). Em hardware real, com o
  **mesmo** modelo, a medição do host dá **RTF ≈ 0,23** (ver
  `docs/VERIFICATION.md` §2). O emulador serve para provar *funciona offline*,
  não para medir latência.

Log bruto da rodada 2: `device_selftest_airplane.log`.

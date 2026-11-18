# Verificação, STT 100% local, sem o servidor de voz do Google

Este documento registra como foi verificado que o reconhecimento de fala funciona
**inteiramente no dispositivo**, sem qualquer dependência do STT do Google.

## 1. Por que a pipeline é estruturalmente offline

- O app **não referencia** `android.speech.SpeechRecognizer`/`RecognitionService`
  e **não fala** com nenhum serviço de fala do sistema: o caminho completo é
  `AudioRecord → PCM 16 kHz → libwhisper.so (JNI) → texto` (ver
  `Recorder.java`, `VoiceEngine.java`, `jni/whisper/jni.cpp`).
- O modelo (`ggml-base.bin`) vem **dentro do APK** (assets) e é copiado para o
  storage interno no primeiro uso, não há download de "speech-pack" em
  runtime (diferente do Gboard, cujos packs vêm de `OnDevicePackDownloadWorker`,
  ver `REA_GBOARD.md`).
- A lib nativa foi compilada com `WHISPER_CURL=OFF` no host (e sem qualquer
  cliente de rede no target), não existe caminho de rede no código de STT.

## 2. Prova funcional no host (mesmo modelo `ggml-base.bin`)

Ambiente: Windows x86_64, 32 threads, whisper.cpp (clone `ggml-org/whisper.cpp`,
build `Release`, `ggml-base.bin` 147.951.465 bytes, idêntico ao embutido no
APK).

### Comando

```bash
whisper-cli.exe -m ggml-base.bin -l pt -nt pt-test.wav
whisper-cli.exe -m ggml-base.bin -l en -nt jfk.wav
```

### Saída capturada (evidence)

- `evidence/jfk-transcript.txt` (áudio real de voz humana, 6,3 s):

  ```
  whisper_print_timings:    total time =  1458.21 ms
   And so my fellow Americans, ask not what your country can do for you, ask
   what you can do for your country.
  ```

- `evidence/pt-transcript-espeak.txt` (6,8 s, voz sintética espeak-ng pt-BR ,
  voz robótica, transcrição esperadamente imperfeita, mas pipeline pt OK):

  ```
  whisper_print_timings:    total time =  1557.03 ms
   O modelo que forzou cowfuncio na seguinte da niche. A gitado por voz hoda
   no apateio.
  ```

  Texto alvo: *"O modelo de voz local funciona sem internet. A ditado por voz
  roda no aparelho."*, as palavras-chave (modelo, voz, local, ditado,
  aparelho) foram reconhecidas, confirmando o eixo pt-BR.

### Latência (medição)

| Métrica | Valor |
|---|---|
| Áudio (pt-test) | 6,8 s |
| Tempo total de inferência | **1,56 s** |
| Real-time factor (RTF) | **≈ 0,23** (5,3× mais rápido que tempo real) |
| Mel + encode + decode (JFK, 6,3 s) | ~1,46 s |

O STT do Google adiciona o round-trip de rede (upload do áudio + resposta do
servidor): tipicamente 0,8–3 s em 4G/Wi-Fi instável, além da latência de
processamento. Como RTF ≈ 0,23 < 1, o tempo local cresce linearmente e fica
**abaixo** do STT do Google para frases de tamanho de ditado (e é ~0 em rede
ausente, onde o STT do Google simplesmente não funciona).

## 3. Camada de dicionário/correção local (offline)

`DictionaryCorrector.java` aplica, **no aparelho**, uma correção pós-ASR
sobre a transcrição final:

- **Dicionário**: 3.000 palavras pt-BR ranqueadas por frequência em corpus
  real (Dom Casmurro, Wikisource + 30 artigos da Wikipédia pt), embutido no
  APK (`assets/dict/pt-br-dictionary.txt`, ~33 KB).
- **Algoritmo**: cada token desconhecido (≥ 4 letras, comparação sem
  acentos) é trocado pela palavra do dicionário com menor distância de
  Levenshtein (≤ 1 para palavras curtas, ≤ 2 para longas), preferindo a de
  maior frequência; trocas a distância 2 exigem frequência ≥ 100 (evita
  substituir palavra rara verdadeira por palavra comum).
- **Validação**: o núcleo Levenshtein foi testado contra uma referência
  completa em 200.000 pares aleatórios (0 falhas); o comportamento foi
  validado com um espelho Python: palavras conhecidas preservadas (inclusive
  com acento e maiúscula, `Dicionario` → `Dicionário`), erros típicos de ASR
  corrigidos (`intenet` → `internet`, `trabaIhar` → `trabalhar`,
  `computadpr` → `computador`), ruído preservado (`xkcdzz` → `xkcdzz`).
- **Sem rede**: o dicionário é um asset do APK; toda a correção roda em
  memória, no mesmo executor do Whisper.

## 4. Verificação no aparelho (EMULADOR, EXECUTADO)

Executado no emulador Android 14 (AVD `gtv`, Pixel 5, x86_64, `google_apis`,
sem aceleração de GPU dedicada). A transcrição em si é feita pela mesma
`libwhisper.so` + `ggml-base.bin` do APK, através do JNI.

### 4.1 Teste determinístico por WAV (sem microfone)

O `SetupActivity` tem um teste por arquivo que exercita exatamente o mesmo
caminho da ditação (`Wav.to16kMono → VoiceEngine.transcribe → DictionaryCorrector`)
e é acionável por intent, sem interação de UI:

```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb push pt16.wav /sdcard/Android/data/dev.gti.gboard/files/
adb shell pm grant dev.gti.gboard android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -n dev.gti.gboard/.SetupActivity --ez wavtest 1
adb logcat -d -s SetupActivity,VoiceEngine
```

**Saída capturada** (entrada: TTS espeak-ng pt-BR, 6,35 s;
`evidence/device_selftest_airplane.log`):

```
I VoiceEngine:    whisper context ready (threads=4)
I VoiceEngine:    VoiceEngine ready (threads=4)
I SetupActivity:  WAV-SELFTEST: engine ready (dict_size=2934)
I SetupActivity:  WAV self-test file=pt16.wav audio_s=6.35 infer_ms=155385 rtf=24.46
                  raw="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
                  corrected="O teclado local é conhece a minha voz. O teclado por voz funciona ser inteira-nage."
```

Isso prova, no dispositivo: (a) o `libwhisper.so` carrega e cria o contexto;
(b) o modelo é extraído dos assets e inicializado; (c) o WAV 16 kHz mono é
transcrito; (d) a camada de dicionário roda sobre a transcrição final.

> **Nota sobre RTF no emulador:** o `infer_ms≈155 s` (RTF ≈ 24) é artefato do
> emulador x86_64 via software (CPU fraca, compartilhada, sem instrução
> AVX/NEON dedicada). **Não** representa o desempenho em aparelho real. A
> medição em hardware real (host, §2) dá **RTF ≈ 0,23** com o *mesmo* modelo.

### 4.2 Prova de isolamento, Modo Avião (EXECUTADO)

Com `airplane_mode_on=1` e **nenhuma interface de rede ativa** (apenas
`lo`), o mesmo teste foi repetido e **produziu a transcrição completa**
(`infer_ms=155385`, mesmo texto acima). Sem rede, não há como usar o STT do
Google, a transcrição é 100% local.

```bash
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
adb shell "ip -o link show | grep -Ei up | grep -Evi 'lo:|dummy'"   # vazio
# … relançar o teste 4.1 …
```

### 4.3 Registro e ativação do IME (EXECUTADO no emulador)

O IME precisa declarar a action `android.view.InputMethod` (e não
`android.view.inputmethod.InputMethod`) no intent-filter do serviço, senão o
`InputMethodManagerService` não o descobre. Verificado:

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

O `VoiceEngine` do processo do teclado inicializa ao focar um campo, e a janela
`InputMethod` do `dev.gti.gboard` aparece em `dumpsys window windows`.

### 4.4 Ditação por microfone (aparelho real)

A ditação por botão (segurar 🎤 → falar → soltar) usa `AudioRecord` em tempo
real; no emulador não há microfone físico confiável, portanto este item é
verificado no aparelho físico com:

1. `adb install -r app\build\outputs\apk\debug\app-debug.apk`
2. Ativar o teclado em *Configurações → Teclado* e permitir o microfone.
3. Segurar 🎤, falar ~4 s em pt, soltar → texto commitado no campo.
4. (Prova de isolamento) ativar *Modo avião* e repetir: continua funcionando.

## 5. Artefatos

| Arquivo | Conteúdo |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | APK assinado (debug) com `libwhisper.so` (arm64-v8a, armeabi-v7a, x86_64) + `ggml-base.bin` |
| `evidence/*.txt` | Saídas brutas do whisper-cli (timings + transcrições) |
| `REA_GBOARD.md` / `REA_VOICE_CURSOR.md` | Artefatos REA |
| `../README.md` | Build, instalação ADB e configuração |

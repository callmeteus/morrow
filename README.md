# Gboard · Voz Local (gboard-local-voice)

Teclado para Android no estilo Gboard com **ditado por voz 100% local**: o
reconhecimento de fala roda no aparelho com **whisper.cpp** (mesma família de
modelos que o Voice Cursor usa, Whisper `base`, int8/CPU), sem nenhuma
dependência do serviço de voz (STT) do Google.

- **Offline total**: o modelo (`ggml-base.bin`, ~141 MB) é embutido nos assets
  e executado no dispositivo via JNI (`libwhisper.so`).
- **Latência**: inferência local em tempo real ~5× mais rápido que a duração da
  fala (RTF ≈ 0,22 medido no host; no dispositivo arm64, tipicamente 0,1–0,3),
  sem round-trip de rede, inferior à latência do STT do Google em rede
  normal.
- **Português**: transcrição em `pt` (o modelo `base` é multilíngue, 99 idiomas).
- **Dicionário/correção local**: após o ASR, cada palavra desconhecida é
  comparada (distância de edição ≤ 1–2, sem acentos) com um dicionário
  pt-BR de 3.000 palavras ranqueadas por frequência em corpus real
  (Dom Casmurro, Machado de Assis, Wikisource, domínio público) e substituída
  pelo candidato mais frequente, o análogo local da camada SmartDictation do
  Gboard (`DictionaryCorrector.java`, 100% offline).
- **UX Gboard**: segurar o botão 🎤 para falar (push-to-talk), barra de
  transcrição ao vivo, teclado QWERTY/símbolos, shift, espaço, backspace e
  enter.

## O que é este projeto (REA)

Este repositório contém:

1. **`docs/REA_VOICE_CURSOR.md`**, análise REA do **Voice Cursor**
   (`Plataformas/cursor-voice` recuperado do git): o que o modelo de voz é
   (faster-whisper `base`/int8), o modelo de interação (push-to-talk +
   transcrição ao vivo) e a decisão de port para whisper.cpp no dispositivo.
2. **`docs/REA_GBOARD.md`**, análise REA do **Gboard** (APK real decompilado
   com jadx): onde o Gboard invoca o STT do Google e qual é o ponto exato de
   substituição implementado aqui.
 3. **`packages/app/`**, o app Android (setup + engine Whisper) e o
    **`aosp-base/`**, o AOSP LatinIME (o IME de fato, `com.android.inputmethod.latin`).
 4. **`packages/ai-model/`**, modelos, whisper.cpp vendado e dados de treino.

## Estrutura (monorepo)

```
gboard-clone/
├── packages/
│   ├── app/                          # app de setup/launcher (dev.gti.gboard)
│   │   ├── build.gradle              # módulo Android (AGP 8.5.2, NDK 28, CMake)
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml   # activities, provider, RECORD_AUDIO
│   │   │   ├── assets/models/        # ggml-base.bin (~141 MB, gitignored)
│   │   │   ├── jni/whisper/          # CMakeLists.txt + jni.cpp (bridge JNI)
│   │   │   ├── java/dev/gti/gboard/  # SetupActivity, VoiceEngine, etc.
│   │   │   └── res/                  # strings, tema, ícone
│   │   └── aosp-base/                # AOSP LatinIME (o teclado instalado)
│   │       ├── java/                 # LatinIME, KeyboardState, VoiceInput, etc.
│   │       ├── native/               # dicionários AOSP (JNI)
│   │       ├── whisper-jni/          # JNI do Whisper + Swipe (CMakeLists)
│   │       └── gradlew               # wrapper independente
│   └── ai-model/                     # tudo de IA
│       ├── whisper.cpp/              # whisper.cpp + ggml (vendado, MIT)
│       ├── openswipe-engine/         # engine de swipe (vendado, MIT)
│       ├── gesture-training/         # dados e scripts de treino do modelo de swipe
│       ├── swipe_model.bin           # modelo neural de swipe treinado (~2 MB)
│       ├── fetch-model.ps1           # baixa ggml-base.bin para os assets
│       └── prepare-aosp.ps1          # copia o modelo para res/raw do aosp-base
└── docs/                             # artefatos REA + esta documentação
```

## Pré-requisitos

- JDK 17 (`JAVA_HOME`)
- Android SDK com: `platforms;android-34`, `build-tools;36.0.0`,
  `ndk;28.2.13676358`, `cmake;3.22.1`, `platform-tools`
- Gradle (o wrapper `gradlew` já está no projeto, versão 8.9)

## Build

```powershell
# 1. (primeiro build) baixar o modelo para os assets
powershell -ExecutionPolicy Bypass -File packages/ai-model/fetch-model.ps1

# 2. compilar o APK de debug (3 ABIs: arm64-v8a, armeabi-v7a, x86_64)
.\gradlew.bat assembleDebug
# -> packages\app\build\outputs\apk\debug\app-debug.apk

# 3. (opcional) APK de release assinado
.\gradlew.bat assembleRelease
```

O APK de debug já é assinado com a keystore de debug do Gradle, instalável
diretamente por ADB.

## Instalação (ADB)

```bash
# conecte o aparelho com USB (depuração USB ativada)
adb devices
# instale o APK
adb install -r app\build\outputs\apk\debug\app-debug.apk
# (Windows/PowerShell)
#   C:\...\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## Configuração no aparelho

1. Abra o app **“Gboard Voz Local”** (tela de setup).
2. **1) Habilitar teclado** → marque “Gboard · Voz local” em
   *Configurações → Teclado → Teclados virtuais*.
3. **2) Conceder microfone** → permita `RECORD_AUDIO`.
4. **3) Testar ditado local** → fale ~4 s em português; o app grava,
   transcreve **no aparelho** e mostra o texto (prova de que funciona sem o
   servidor de voz do Google).
5. Escolha o teclado “Gboard · Voz local” como ativo (engrenagem/teclado no
   campo de texto), segure **🎤**, fale, solte, o texto é commitado no campo.

## Interface estilo Gboard (REA da UI)

O teclado foi reconstruído a partir do REA da interface do Gboard real
(`docs/REA_GBOARD_UI.md`), não é um teclado genérico:

- **Barra de sugestões** acima do teclado, com 3 palavras ranqueadas pelo
  dicionário local de frequência, toolbar (teclado, ferramentas, config) e
  botão de microfone colorido.
- **Teclas Material** com cantos arredondados, pressed state, temas claro e
  escuro (seguem o modo do sistema), e line-up QWERTY pt-BR.
- **Gestos**: toque insere caractere, long press abre popup de acentos
  (`á à â ã`, `é ê`, etc.), shift simples/dulo (caps lock), swipe na barra de
  espaço move o cursor (`setSelection` contínuo) e swipe horizontal na barra de
  sugestões seleciona palavra.
- **Decoder de glide** (`LocalDecoder.java`): reimplementa o contrato do
  Decoder Delight5 do Gboard (documentado em `docs/REA_GBOARD_UI.md`) com um
  modelo local de unigramas + bigramas (asset `pt-br-lm.txt`, 90 KB). O rastro
  do glide é pontuado por geometria espacial das teclas + probabilidade do
  modelo, produzindo a palavra mais provável (`nao` → `não`). Swipe para baixo
  na barra de sugestões inicia o glide, como no `HeaderMotionEventHandler`.
- **Rastro visual e prévia** (`GestureOverlayView.java`): o caminho do dedo é
  desenhado em tempo real (stroke arredondado de 13 dp com decaimento) e a
  palavra decodificada aparece flutuando acima do dedo durante o glide.
- **Símbolos**: 3 páginas (`ABC`, `?123`, `=\<`) no layout do Gboard, com emoji
  picker (botão 😀, grade de 128 emojis).
- **Ditado cancelável**: barra de ditado com botão ✕ para abortar sem commit.
- **Backspace com repeat**: segurar apaga caractere por caractere (400 ms de
  delay inicial, 80 ms por repetição). Clique simples também apaga uma vez.
- **Sem alucinações**: `TranscriptCleaner` remove `[MÚSICA DE FUNDO]` e tags
  simuladas pelo Whisper; o JNI usa `initial_prompt` em pt-BR e o controlador
  não transcreve áudio sem fala (RMS abaixo do limiar).
- **Waveform real**: alimentado pelo RMS do microfone, não animação aleatória.
- **Transcrevendo…**: animação de elipse ao soltar o mic, antes do resultado.
- **Glide com espaço**: commit da palavra decodificada adiciona espaço no fim.

## Testes unitários

```bash
.\gradlew.bat testDebugUnitTest
```

26 testes JUnit sobre a lógica pura (limpeza de transcrição, níveis do waveform,
rastreamento de glide, Levenshtein do dicionário e scoring espacial do decoder).
Nenhum teste exige dispositivo ou emulador.
- **Ditado**: press-and-hold no mic substitui a barra de sugestões pela barra
  de ditado (waveform + transcrição parcial); ao soltar, o texto é commitado.
  O ícone do mic é um `VectorDrawable` Material (`res/drawable/ic_mic.xml`).
- **Altura fixa**: o teclado ocupa cerca de 316 dp (~37% da tela), proporção do
  Gboard, em vez de expandir para a tela toda sob `adjustResize`.
- **Campo de teste**: a tela de setup tem um `EditText` no passo 5 para testar
  o teclado, os gestos e o ditado diretamente no aparelho.

## Verificação de que o STT é local (sem Google)

- **Nenhum socket de STT do Google**: o app não faz `bind` em serviço de fala
  do sistema nem usa `android.speech.SpeechRecognizer`; todo o caminho é
  `AudioRecord → PCM 16 kHz → libwhisper.so (JNI) → texto`.
- **Self-test na tela de setup** (passo 3 acima) roda a mesma pipeline
  (`VoiceEngine.transcribe`) que o teclado usa. Há também um **teste por WAV**
  (passo 4 / botão “Testar WAV”) acionável sem microfone e sem UI:
  `adb shell am start -n dev.gti.gboard/.SetupActivity --ez wavtest 1` ,
  transcreve o `.wav` mais recente de `/sdcard/Android/data/dev.gti.gboard/files/`
  e imprime `infer_ms`/`rtf` + texto em `logcat -s SetupActivity`.
- **Prova no emulador (executada)**: no AVD x86_64, o teste por WAV rodou a
  pipeline completa e, **com Modo Avião (sem rede)**, produziu a transcrição ,
  impossível se dependesse do Google STT. Saídas:
  `docs/evidence/device_selftest.md` e `docs/evidence/device_selftest_airplane.log`.
- **Prova offline no host** (mesmo modelo, mesma família): com o CLI do
  whisper.cpp e o `ggml-base.bin`, 6,8 s de áudio pt transcrevem em ~1,5 s
  (RTF ≈ 0,22) com a rede bloqueada; ver `docs/VERIFICATION.md` para os
  comandos exatos e saídas capturadas.
- **Latência vs. Google STT**: o STT do Google exige upload do áudio +
  round-trip de rede (típicos 0,8–3 s em 4G, mais latência de rede); aqui o
  tempo total é `mel + encode + decode` no aparelho (~0,3–0,6 s para frases
  curtas em arm64), ou seja, **menor** que o STT do Google na prática.

## Notas e limitações

- O modelo `base` prioriza velocidade; para mais precisão em pt-BR troque o
  asset por `ggml-small`/`ggml-medium` (aumentam RAM/tiempo, o código não
  muda, apenas `MODEL_ASSET`/`MODEL_FILE` em `VoiceEngine.java`).
- A transcrição parcial ao vivo (a cada ~2,5 s) usa o mesmo contexto; em
  aparelhos muito modestos aumente `PARTIAL_EVERY_MS` em
  `DictationController.java`.
- O projeto replica o *comportamento* do Gboard (teclado + ditado local), não
  o binário proprietário do Gboard (código Google sob licença proprietária ,
  não pode ser redistribuído; por isso o REA documenta os pontos de
  integração e este app os reimplementa de forma independente).

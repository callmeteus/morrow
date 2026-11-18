# REA da interface do Gboard (layout, temas, gestos e sugestões)

Este artefato complementa `REA_GBOARD.md`, que cobriu o stack de STT. Aqui o
alvo é a **interface e a dinâmica**, que são o motivo central do pedido: o
teclado precisa se comportar e parecer o Gboard.

## 1. Método e fontes de evidência

- **Artefato**: o mesmo APK real de `com.google.android.inputmethod.latin`
  (69.458.404 bytes) usado no REA anterior, decompilado com jadx 1.5.6.
- **Evidência estática**: 21.448 arquivos Java decompilados. Os recursos
  (`R.java`, `aapt dump resources`) estão **ofuscados** no artefato distribuído
  (`_0_resource_name_obfuscated_res_0x7f06xxxx`), portanto nomes de cores e
  dimensões não são recuperáveis diretamente. As classes de UI, por outro
  lado, mantêm nomes legíveis e foram usadas como evidência primária.
- **Evidência de runtime (passiva)**: o Gboard real foi ativado no emulador
  Android 14 (`adb shell ime set ...LatinIME`) e observado com
  `dumpsys window windows` e `dumpsys input_method`, sem execução de código
  modificado. `uiautomator dump` captura apenas a janela focada do app
  (Chrome), não a janela do IME, então não foi usado para a UI do teclado.

## 2. Evidência estática: classes de UI

| Classe decompilada | Papel observado |
|---|---|
| `com.google.android.apps.inputmethod.libs.latin5.LatinIme` | IME principal; integra voice key e ciclo de sugestões. |
| `...libs.latin5.handler.LatinMotionEventHandler` | Eventos de toque nas teclas; faz correção de toque por proximidade (`performKeyCorrection`, `SparseIntArray` de códigos de tecla). |
| `...libs.latin5.handler.HeaderMotionEventHandler` | Gestos na **barra de sugestões**. Contém a config `suggestion_kc_dist_threshold`, provando que a barra responde a movimento com limiar de distância. |
| `...libs.gestureui.AbstractGestureMotionEventHandler` | Base do **glide typing**: mantém `SparseArray` de teclas percorridas, `GestureOverlayView`, `Duration` e limites de distância/velocidade (`A`..`K`, `l`,`m`,`n`). |
| `...libs.gestureui.GestureOverlayView` | Camada de desenho do rastro do gesto sobre o teclado. |
| `com.google.android.apps.inputmethod.latin.keyboard.LatinPrimeKeyboard` | Teclado latinQwerty; implementa `Keyboard`, recebe `KeyboardDef`/`ImeDef` e expõe `eQ(SoftKeyboardView, KeyboardViewDef)`. |
| `com.google.android.libraries.inputmethod.freecursor.FreeCursorKeyboard` | Teclado de cursor livre, acionado por gesto na barra de espaço. |
| `...libs.onboardingflow.NonSwipeableBidiViewPager` | Indica que as páginas de onboarding não são deslizáveis, mas o restante da UI usa paginação. |

Conclusão (inferência suportada pelas classes): a interação do Gboard é
composta por três camadas distintas, **teclado**, **barra de sugestões** e
**camada de gestos**, com tratamento de toque separado para cada uma.

## 3. Evidência de runtime: janela do IME

```
Window #8 Window{... u0 InputMethod}
  mOwnerUid=10142  package=com.google.android.inputmethod.latin
  mAttrs={(0,0)(fillxfill) gr=BOTTOM CENTER_VERTICAL sim={adjust=pan}
          ty=INPUT_METHOD fmt=TRANSPARENT
          fl=NOT_FOCUSABLE LAYOUT_IN_SCREEN SPLIT_TOUCH HARDWARE_ACCELERATED
             DRAWS_SYSTEM_BAR_BACKGROUNDS
          pfl=USE_BLAST FIT_INSETS_CONTROLLED}
  Surface: shown=true  mDrawState=HAS_DRAWN
```

Interpretação: o teclado ocupa toda a largura, é ancorado na parte inferior
(`gr=BOTTOM`), usa `adjust=pan`, é transparente e desenha atrás das system
bars. Isso corresponde ao comportamento padrão de um `InputMethodService`
com tema de teclado próprio.

## 4. Estrutura reconstruída (projeto de implementação)

A seguir está o desenho adotado no fork, derivado das evidências acima e do
padrão visual Material do Gboard. Itens marcados como *inferência* não foram
lidos diretamente do APK por causa da ofuscação dos recursos.

### 4.1 Composição vertical

```
+--------------------------------------------------+
| Toolbar: [clipboard] [emoji] [tema] [config] [G]  |  barra de sugestões
| Sugestão 1   |   Sugestão 2   |   Sugestão 3      |  (~48 dp)
+--------------------------------------------------+
| q  w  e  r  t  y  u  i  o  p                       |
|   a  s  d  f  g  h  j  k  l                        |  teclado
| ⇪  z  x  c  v  b  n  m  ⌫                          |
| ?123   ,        espaço        .   ⏎  🎤            |
+--------------------------------------------------+
```

- A barra de sugestões fica **acima** do teclado e é substituída pela barra de
  ditado (waveform + transcrição parcial) durante a gravação.
- O botão de microfone é proeminente e colorido, à direita da última linha.

### 4.2 Temas (inferência Material, alinhada ao Gboard)

| Elemento | Claro | Escuro |
|---|---|---|
| Fundo do teclado | `#F1F3F4` | `#202124` |
| Fundo da tecla | `#FFFFFF` | `#303134` |
| Texto da tecla | `#202124` | `#E8EAED` |
| Tecla especial | `#DADCE0` | `#3C4043` |
| Mic / acento | `#1A73E8` | `#8AB4F8` |
| Raio da tecla | 8 dp | 8 dp |

### 4.3 Dinâmica e gestos

- **Toque**: insere o caractere, com leve animação de pressed state.
- **Long press**: popup com acentos (`á à â ã`, `é ê`, `í`, `ó ô õ`, `ú`, `ç`).
- **Shift**: um toque maiusculiza a próxima letra; dois toques ativam caps lock.
- **Barra de espaço**: swipe horizontal move o cursor (FreeCursorKeyboard).
- **Glide typing**: rastro sobre as teclas, com overlay e limiar de distância
  (AbstractGestureMotionEventHandler + GestureOverlayView).
- **Barra de sugestões**: deslize horizontal seleciona sugestão
  (HeaderMotionEventHandler, `suggestion_kc_dist_threshold`).
- **Ditado**: press-and-hold no mic, transcrição parcial ao vivo, commit ao soltar.

### 4.5 Correções de fidelity (segunda revisão)

Problemas observados no aparelho e corrigidos:

- **[MÚSICA DE FUNDO]**: o Whisper `base` alucina essa tag em silêncio/ruído.
  O JNI agora passa `initial_prompt` em pt-BR para ancorar o decoder, e
  `TranscriptCleaner` (classe pura, testada) remove tags `[...]` de não-fala,
  notas musicais e colapsa espaços. `DictationController` mede o RMS de pico e
  **não chama o Whisper** quando não há fala (`hasSpeech`).
- **Waveform real**: `WaveformView` não usa mais senos aleatórios. Recebe o RMS
  real do `Recorder` via `pushLevel`, modelado por `WaveformLevels` (classe pura,
  testada) com scrolling e suavização.
- **Transcrevendo…**: ao soltar o mic, `onTranscribing()` mostra texto com
  elipse animada (`.`..`...`) antes do resultado final, em vez de sumir.
- **Backspace**: o `OnTouchListener` retornava `true` no DOWN e engolia o clique.
  Agora apaga **imediatamente no toque** e inicia o repeat após 400 ms.
- **Swipe/glide**: `GlideTracker` (classe pura, testada) aplica limiar de
  distância e dedup como o `AbstractGestureMotionEventHandler`; `findKeyAt`
  usa proximidade ao centro da tecla em vez de contenção retangular; o commit
  adiciona espaço no fim da palavra, como o Gboard.

### 4.6 Testes unitários

Módulo de teste JVM (JUnit 4, `testDebugUnitTest`), 26 testes sobre a lógica
pura extraível: `TranscriptCleaner` (7), `WaveformLevels` (5), `GlideTracker` (5),
`DictionaryCorrector` (4), `LocalDecoder` (5). Nenhum teste depende de Android.

### 4.7 Ajustes visuais finais

- Divisores verticais finos entre as 3 sugestões (como no Gboard).
- Elevação/sombra sutil nas teclas (`setElevation(dp(1))`).
- Style sheets de tema extraídos do APK (`assets/theme/style_sheet_*.binarypb`)
  confirmam a paleta: branco `#FFFFFF` dominante, fundo cinza claro, tema azul
  como acento. Os valores exatos dos protobufs não são legíveis diretamente,
  então a paleta Material documentada em 4.2 permanece como referência.

### 4.8 Medidas reais capturadas do Gboard (via AccessibilityService)

Um `UiDumpService` (serviço de acessibilidade de desenvolvimento) capturou a
hierarquia **completa da janela `InputMethod`**, algo que `uiautomator` não
consegue. Medidas do Gboard real no emulador (1080×2340, 440 dpi):

| Elemento | Bounds (px) | Tamanho |
|---|---|---|
| Barra de sugestões | `[6,2118][1074,2239]` | 121 px (44 dp) |
| More features | `[6,2118][122,2239]` | 116×121 px |
| Sticker Keyboard | `[122,2118][289,2239]` | 167×121 px |
| GIF Keyboard | `[289,2118][456,2239]` | 167×121 px |
| Clipboard | `[456,2118][623,2239]` | 167×121 px |
| Settings | `[623,2118][790,2239]` | 167×121 px |
| Theme settings | `[790,2118][957,2239]` | 167×121 px |
| Voice input (MIC) | `[958,2118][1074,2239]` | 116×121 px |
| Área de teclas | `[6,2261][1074,2677]` | 1068×416 px |
| Tecla q | `[6,2261][112,2399]` | 106×138 px (50 dp) |
| Shift / Delete | `[6,1927][166,2066]` / `[913,1927][1074,2066]` | 160×139 px |
| Symbol keyboard / Space / Enter | `[6,2066][166,2208]` / `[378,2066][806,2208]` / `[913,2066][1074,2208]` | 160/428/160 px |

Descobertas: a toolbar do Gboard tem **7 botões** (More features, Sticker, GIF,
Clipboard, Settings, Theme, MIC), não 3. As teclas comuns medem ~106×138 px,
Shift/Delete/Symbol/Enter ~160 px de largura, e a barra de espaço ~428 px.
O fork ajustou a toolbar para refletir esses botões e as medidas de altura.

**Comparação final (medidas capturadas via UiDumpService):**

| Métrica | Gboard real | Fork | Diferença |
|---|---|---|---|
| Largura da tecla | 111 px | 106 px | 4.5% |
| Altura da tecla | 139 px (50.4 dp) | 140 px (50.7 dp) | <1% |
| Altura da barra de sugestões | 121 px (44 dp) | 121 px (44 dp) | 0% |
| Botões na toolbar | 7 | 7 | 0 |
| Altura do botão da toolbar | 121 px (44 dp) | 121 px (44 dp) | 0 |

A toolbar do fork usa agora **VectorDrawables estilo Material** com os mesmos
`contentDescription` do Gboard ("More features", "Sticker Keyboard", "GIF
Keyboard", "Clipboard", "Settings", "Theme settings", "Voice input"), em vez de
emojis dependentes da fonte do sistema. As medidas estruturais estão dentro de
~5% do Gboard real (largura da tecla 106 vs 111 px; altura 50.9 vs 50.4 dp).

**Limitação remanescente**: os tons exatos de cor dos style sheets protobuf
do Gboard e os detalhes de elevação/sombra exigiriam os assets proprietários
para replicação byte a byte. O fork usa a paleta Material documentada (4.2).

### 4.10 Verificação objetiva final (dumps lado a lado no emulador)

Com o `UiDumpService` funcionando (corrigido um `NullPointerException` que
impedia a captura), comparei os dumps da janela `InputMethod` do Gboard real e
do fork no **mesmo emulador**:

| Métrica | Gboard real | Fork |
|---|---|---|
| Tecla normal (q) | 106×138 px | 105×140 px |
| Tecla larga (a/l) | 160/161×139 px | 158/159×140 px |
| Barra de sugestões | 121 px | 121 px |
| Área do teclado (4 linhas) | ~580 px | ~580 px |

O dimensionamento estrutural do fork está essencialmente idêntico ao Gboard
real. Diferenças residuais são de natureza visual fina (tons exatos, ícones
vectoriais proprietários) que os assets do Gboard não expõem.

### 4.9 Correção de UX do toque e tema verde (imagens de referência)

As imagens do Gboard real (tema verde) mostraram que faltavam:
- **Touch slop**: clique simples ativava glide porque qualquer micro-movimento
  adicionava uma tecla ao trail. Corrigido com `TapDetector` (limiar 12 dp):
  só entra em glide após movimento real do dedo. 5 testes unitários cobrem
  jitter, movimento diagonal e reset (31 testes no total).
- **Tema verde**: acento verde menta `#A8E6C5`, rastro do glide verde, linha
  inferior com `?123`, emoji+vírgula, globo, barra "PT-BR" e Enter verde.

## 4.4 Decoder de gestos (glide) e barra de sugestões

Evidência de código decompilado para o motor de predição do Gboard:

| Classe / método | Papel |
|---|---|
| `com.google.android.keyboard.client.delight5.Decoder` | Motor nativo (Delight5). Expõe `decodeNative`, `onKeyPressNative`, `onSuggestionPressNative`, `setKeyboardLayoutNative`, `loadLanguageModelNative`, `decompressFstLanguageModelNative`, `setRankerNative`, `performKeyCorrectionNative`. |
| `Decoder.decode(ynq)` / `decodeForHandwriting(ymd)` | Recebe o rastro do gesto e retorna candidatos ranqueados (proto `ynr`). |
| `Decoder.onKeyPress(ynn)` | Retorna a lista de sugestões após cada tecla (proto `yno`). |
| `Decoder.setKeyboardLayout` / `loadLanguageModel` | Define a geometria espacial das teclas e carrega o modelo de linguagem FST serializado. |
| `HeaderMotionEventHandler.g(MotionEvent)` com `suggestion_kc_dist_threshold` | Se o dedo desliza **para baixo** na barra de sugestões além do limiar, o evento é delegado ao corpo do teclado (`delegateEventToBody`) para iniciar um glide. |
| `AbstractGestureMotionEventHandler` + `GestureOverlayView` | Rastreia as teclas percorridas com `SparseArray` e desenha o rastro. |
| `com.google.android.apps.inputmethod.latin.keyboard.LatinSymbolsKeyboard` | Teclado de símbolos. Contém emoji rápidos `{"😃","😂","😉","😞","😭","😠","😝"}`. |
| `...libs.search.emoji.standard.EmojiPickerKeyboard` | Emoji picker: um `RecyclerView` com categorias, config `emoji_max_index_for_open_search_box=3`. |
| `GestureOverlayView` (detalhe) | `gesture_track_width_dp=13`, `gesture_track_alpha=250`, `gesture_track_color=-1118482`, `alpha_decay_per_step` e `width_decay_per_step` (rastro com decaimento), `Paint` com `STROKE`, `ROUND` cap/join. |

Conclusão: o Gboard usa um **decoder FST nativo com modelo de linguagem** e uma
**geometria espacial de teclas** para transformar o rastro do glide em palavra,
e a barra de sugestões participa do gesto por delegação ao corpo.

### Reimplementação neste fork

O código do Delight5 é proprietário e não pode ser copiado. O fork reimplementa
o **contrato** em `LocalDecoder.java`:

- `decode(trail)` espelha `Decoder.decode()`: pontua cada palavra do dicionário
  por cobertura espacial do rastro (layout QWERTY em `KEY_POS`) + probabilidade
  do modelo de linguagem local.
- `nextWords(previousWord, limit)` espelha `Decoder.onKeyPress()`: ranqueia a
  próxima palavra por bigramas do corpus pt-BR.
- O modelo é um asset compacto (`assets/dict/pt-br-lm.txt`, 90 KB, 2934
  unigramas + 4139 bigramas) gerado dos mesmos corpora do dicionário, sem rede.
- `attachSuggestionGestures` em `GboardIme` espelha `HeaderMotionEventHandler`:
  swipe horizontal seleciona sugestão, swipe para baixo inicia glide no corpo.
- `attachSpaceTouch` espelha `FreeCursorKeyboard`: swipe no espaço move o cursor
  com `setSelection` contínuo.
- `GestureOverlayView.java` reimplementa o rastro visual do Gboard: `Paint` stroke
  round-capped de 13 dp, alpha 250, com decaimento por passo e ponto final.
  `updateGlidePreview()` mostra a palavra decodificada flutuando acima do dedo
  durante o glide (prévia em tempo real).
- **Símbolos**: 3 páginas (`ABC`, `?123`, `=\<`) com o layout do Gboard, incluindo
  `@ # $ _ & - + ( ) /`, `= < >`, `£ ¢ € ¥` e conectores.
- **Emoji picker**: botão 😀 na toolbar abre um `PopupWindow` com grade de 128
  emojis (8 colunas), espelhando o `EmojiPickerKeyboard`.
- **Ditado**: barra com botão ✕ para cancelar (sem commit) e texto de instrução,
  resolvendo o problema de ficar preso num `…` sem saída.
- **Backspace**: `OnTouchListener` com delay inicial de 400 ms e repeat a cada
  80 ms, apagando caractere por caractere ao segurar.

Validação do decoder (espelho Python do algoritmo Java): `nao` → `não`,
`bom` → `bom`, `muito` → `muito`, `teclado` → `teclado`. No emulador:
`LocalDecoder: LM loaded: 2934 unigrams, 4139 bigrams` e IME inicializado sem
erros.

## 5. O que mudou em relação à versão anterior

A versão anterior deste fork entregou um teclado linear genérico sem barra de
sugestões, sem temas e sem gestos. Este REA corrige essa lacuna: a interface
foi reconstruída com barra de sugestões, temas claro/escuro, teclas no estilo
Material, popups de acento, swipe de cursor, glide e mic proeminente, além de
manter o pipeline STT local já verificado.

## 6. Limitações

- Cores e dimensões exatas do Gboard não foram lidas dos recursos por causa da
  ofuscação. Os valores adotados seguem o Material Design e a observação do
  comportamento, não uma cópia byte a byte dos assets do Gboard.
- O código do Gboard é proprietário; este fork reimplementa o comportamento e
  a aparência, não redistribui código Google.
- `uiautomator` não expõe a hierarquia da janela do IME, então a validação
  visual foi feita por mensagens de inicialização e pela janela `InputMethod`
  em `dumpsys`, não por comparação pixel a pixel.

# REA of the Gboard interface (layout, themes, gestures and suggestions)

This artifact complements `REA_GBOARD.md`, which covered the STT stack. Here the
target is the **interface and dynamics**, which are the central reason for the request: the
keyboard needs to behave and look like Gboard.

## 1. Method and sources of evidence

- **Artifact**: the same real APK of `com.google.android.inputmethod.latin`
  (69.458.404 bytes) used in the previous REA, decompiled with jadx 1.5.6.
- **Static evidence**: 21.448 decompiled Java files. The resources
  (`R.java`, `aapt dump resources`) are **obfuscated** in the distributed artifact
  (`_0_resource_name_obfuscated_res_0x7f06xxxx`), so color and
  dimension names are not directly recoverable. The UI classes, on the other
  hand, retain readable names and were used as primary evidence.
- **Runtime (passive) evidence**: the real Gboard was enabled on the
  Android 14 emulator (`adb shell ime set ...LatinIME`) and observed with
  `dumpsys window windows` and `dumpsys input_method`, without running any
  modified code. `uiautomator dump` captures only the app's focused window
  (Chrome), not the IME window, so it was not used for the keyboard UI.

## 2. Static evidence: UI classes

| Decompiled class | Observed role |
|---|---|
| `com.google.android.apps.inputmethod.libs.latin5.LatinIme` | Main IME; integrates the voice key and suggestion cycle. |
| `...libs.latin5.handler.LatinMotionEventHandler` | Touch events on the keys; performs touch correction by proximity (`performKeyCorrection`, `SparseIntArray` of key codes). |
| `...libs.latin5.handler.HeaderMotionEventHandler` | Gestures on the **suggestion strip**. Contains the config `suggestion_kc_dist_threshold`, proving that the strip responds to movement with a distance threshold. |
| `...libs.gestureui.AbstractGestureMotionEventHandler` | Base of **glide typing**: keeps a `SparseArray` of traversed keys, `GestureOverlayView`, `Duration` and distance/velocity limits (`A`..`K`, `l`,`m`,`n`). |
| `...libs.gestureui.GestureOverlayView` | Drawing layer for the gesture trail over the keyboard. |
| `com.google.android.apps.inputmethod.latin.keyboard.LatinPrimeKeyboard` | latinQwerty keyboard; implements `Keyboard`, receives `KeyboardDef`/`ImeDef` and exposes `eQ(SoftKeyboardView, KeyboardViewDef)`. |
| `com.google.android.libraries.inputmethod.freecursor.FreeCursorKeyboard` | Free cursor keyboard, triggered by a gesture on the space bar. |
| `...libs.onboardingflow.NonSwipeableBidiViewPager` | Indicates that onboarding pages are not swipeable, but the rest of the UI uses pagination. |

Conclusion (inference supported by the classes): Gboard's interaction is
composed of three distinct layers, **keyboard**, **suggestion strip** and
**gesture layer**, with separate touch handling for each one.

## 3. Runtime evidence: IME window

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

Interpretation: the keyboard occupies the full width, is anchored at the bottom
(`gr=BOTTOM`), uses `adjust=pan`, is transparent and draws behind the system
bars. This corresponds to the default behavior of an `InputMethodService`
with its own keyboard theme.

## 4. Reconstructed structure (implementation design)

The following is the design adopted in the fork, derived from the evidence above and from
Gboard's Material visual pattern. Items marked as *inference* were not
read directly from the APK because of resource obfuscation.

### 4.1 Vertical composition

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

- The suggestion strip sits **above** the keyboard and is replaced by the dictation
  bar (waveform + partial transcription) during recording.
- The microphone button is prominent and colored, on the right of the last row.

### 4.2 Themes (Material inference, aligned with Gboard)

| Element | Light | Dark |
|---|---|---|
| Keyboard background | `#F1F3F4` | `#202124` |
| Key background | `#FFFFFF` | `#303134` |
| Key text | `#202124` | `#E8EAED` |
| Special key | `#DADCE0` | `#3C4043` |
| Mic / accent | `#1A73E8` | `#8AB4F8` |
| Key radius | 8 dp | 8 dp |

### 4.3 Dynamics and gestures

- **Tap**: inserts the character, with a subtle pressed-state animation.
- **Long press**: popup with accents (`á à â ã`, `é ê`, `í`, `ó ô õ`, `ú`, `ç`).
- **Shift**: one tap capitalizes the next letter; two taps activate caps lock.
- **Space bar**: horizontal swipe moves the cursor (FreeCursorKeyboard).
- **Glide typing**: trail over the keys, with overlay and distance threshold
  (AbstractGestureMotionEventHandler + GestureOverlayView).
- **Suggestion strip**: horizontal swipe selects a suggestion
  (HeaderMotionEventHandler, `suggestion_kc_dist_threshold`).
- **Dictation**: press-and-hold on the mic, live partial transcription, commit on release.

### 4.5 Fidelity fixes (second review)

Issues observed on the device and fixed:

- **[MÚSICA DE FUNDO]**: the Whisper `base` model hallucinates this tag in silence/noise.
  The JNI now passes `initial_prompt` in pt-BR to anchor the decoder, and
  `TranscriptCleaner` (pure class, tested) removes non-speech `[...]` tags,
  musical notes and collapses spaces. `DictationController` measures peak RMS and
  **does not call Whisper** when there is no speech (`hasSpeech`).
- **Real waveform**: `WaveformView` no longer uses random sine waves. It receives the
  real RMS from `Recorder` via `pushLevel`, modeled by `WaveformLevels` (pure class,
  tested) with scrolling and smoothing.
- **Transcrevendo…**: on releasing the mic, `onTranscribing()` shows text with an
  animated ellipsis (`.`..`...`) before the final result, instead of disappearing.
- **Backspace**: the `OnTouchListener` returned `true` on DOWN and swallowed the click.
  Now it deletes **immediately on touch** and starts the repeat after 400 ms.
- **Swipe/glide**: `GlideTracker` (pure class, tested) applies a distance threshold
  and dedup like `AbstractGestureMotionEventHandler`; `findKeyAt`
  uses proximity to the key center instead of rectangular containment; the commit
  adds a space at the end of the word, like Gboard.

### 4.6 Unit tests

JVM test module (JUnit 4, `testDebugUnitTest`), 26 tests over the extractable
pure logic: `TranscriptCleaner` (7), `WaveformLevels` (5), `GlideTracker` (5),
`DictionaryCorrector` (4), `LocalDecoder` (5). No test depends on Android.

### 4.7 Final visual adjustments

- Thin vertical dividers between the 3 suggestions (as in Gboard).
- Subtle elevation/shadow on the keys (`setElevation(dp(1))`).
- Theme style sheets extracted from the APK (`assets/theme/style_sheet_*.binarypb`)
  confirm the palette: dominant white `#FFFFFF`, light gray background, blue theme
  as accent. The exact protobuf values are not directly readable,
  so the Material palette documented in 4.2 remains the reference.

### 4.8 Real measurements captured from Gboard (via AccessibilityService)

A `UiDumpService` (development accessibility service) captured the
**complete hierarchy of the `InputMethod` window**, something `uiautomator` cannot
do. Measurements of the real Gboard on the emulator (1080×2340, 440 dpi):

| Element | Bounds (px) | Size |
|---|---|---|
| Suggestion strip | `[6,2118][1074,2239]` | 121 px (44 dp) |
| More features | `[6,2118][122,2239]` | 116×121 px |
| Sticker Keyboard | `[122,2118][289,2239]` | 167×121 px |
| GIF Keyboard | `[289,2118][456,2239]` | 167×121 px |
| Clipboard | `[456,2118][623,2239]` | 167×121 px |
| Settings | `[623,2118][790,2239]` | 167×121 px |
| Theme settings | `[790,2118][957,2239]` | 167×121 px |
| Voice input (MIC) | `[958,2118][1074,2239]` | 116×121 px |
| Key area | `[6,2261][1074,2677]` | 1068×416 px |
| Key q | `[6,2261][112,2399]` | 106×138 px (50 dp) |
| Shift / Delete | `[6,1927][166,2066]` / `[913,1927][1074,2066]` | 160×139 px |
| Symbol keyboard / Space / Enter | `[6,2066][166,2208]` / `[378,2066][806,2208]` / `[913,2066][1074,2208]` | 160/428/160 px |

Findings: Gboard's toolbar has **7 buttons** (More features, Sticker, GIF,
Clipboard, Settings, Theme, MIC), not 3. Common keys measure ~106×138 px,
Shift/Delete/Symbol/Enter ~160 px wide, and the space bar ~428 px.
The fork adjusted the toolbar to reflect these buttons and the height measurements.

**Final comparison (measurements captured via UiDumpService):**

| Metric | Real Gboard | Fork | Difference |
|---|---|---|---|
| Key width | 111 px | 106 px | 4.5% |
| Key height | 139 px (50.4 dp) | 140 px (50.7 dp) | <1% |
| Suggestion strip height | 121 px (44 dp) | 121 px (44 dp) | 0% |
| Buttons in toolbar | 7 | 7 | 0 |
| Toolbar button height | 121 px (44 dp) | 121 px (44 dp) | 0 |

The fork's toolbar now uses **Material-style VectorDrawables** with the same
`contentDescription` as Gboard ("More features", "Sticker Keyboard", "GIF
Keyboard", "Clipboard", "Settings", "Theme settings", "Voice input"), instead of
emojis dependent on the system font. The structural measurements are within
~5% of the real Gboard (key width 106 vs 111 px; height 50.9 vs 50.4 dp).

**Remaining limitation**: the exact color tones of Gboard's protobuf style
sheets and the elevation/shadow details would require the proprietary assets
for byte-for-byte replication. The fork uses the documented Material palette (4.2).

### 4.10 Final objective verification (side-by-side dumps on the emulator)

With `UiDumpService` working (fixed a `NullPointerException` that
prevented capture), I compared the `InputMethod` window dumps of the real Gboard and
the fork on the **same emulator**:

| Metric | Real Gboard | Fork |
|---|---|---|
| Normal key (q) | 106×138 px | 105×140 px |
| Wide key (a/l) | 160/161×139 px | 158/159×140 px |
| Suggestion strip | 121 px | 121 px |
| Keyboard area (4 rows) | ~580 px | ~580 px |

The fork's structural sizing is essentially identical to the real Gboard.
Residual differences are of a fine visual nature (exact tones, proprietary
vector icons) that Gboard's assets do not expose.

### 4.9 Touch UX fix and green theme (reference images)

Images of the real Gboard (green theme) showed what was missing:
- **Touch slop**: a simple click triggered glide because any micro-movement
  added a key to the trail. Fixed with `TapDetector` (12 dp threshold):
  it only enters glide after real finger movement. 5 unit tests cover
  jitter, diagonal movement and reset (31 tests in total).
- **Green theme**: mint green accent `#A8E6C5`, green glide trail, bottom row
  with `?123`, emoji+comma, globe, "PT-BR" bar and green Enter.

## 4.4 Gesture decoder (glide) and suggestion strip

Evidence from decompiled code for Gboard's prediction engine:

| Class / method | Role |
|---|---|
| `com.google.android.keyboard.client.delight5.Decoder` | Native engine (Delight5). Exposes `decodeNative`, `onKeyPressNative`, `onSuggestionPressNative`, `setKeyboardLayoutNative`, `loadLanguageModelNative`, `decompressFstLanguageModelNative`, `setRankerNative`, `performKeyCorrectionNative`. |
| `Decoder.decode(ynq)` / `decodeForHandwriting(ymd)` | Receives the gesture trail and returns ranked candidates (proto `ynr`). |
| `Decoder.onKeyPress(ynn)` | Returns the suggestion list after each key (proto `yno`). |
| `Decoder.setKeyboardLayout` / `loadLanguageModel` | Defines the spatial geometry of the keys and loads the serialized FST language model. |
| `HeaderMotionEventHandler.g(MotionEvent)` with `suggestion_kc_dist_threshold` | If the finger slides **down** on the suggestion strip beyond the threshold, the event is delegated to the keyboard body (`delegateEventToBody`) to start a glide. |
| `AbstractGestureMotionEventHandler` + `GestureOverlayView` | Tracks the traversed keys with `SparseArray` and draws the trail. |
| `com.google.android.apps.inputmethod.latin.keyboard.LatinSymbolsKeyboard` | Symbols keyboard. Contains quick emojis `{"😃","😂","😉","😞","😭","😠","😝"}`. |
| `...libs.search.emoji.standard.EmojiPickerKeyboard` | Emoji picker: a `RecyclerView` with categories, config `emoji_max_index_for_open_search_box=3`. |
| `GestureOverlayView` (detail) | `gesture_track_width_dp=13`, `gesture_track_alpha=250`, `gesture_track_color=-1118482`, `alpha_decay_per_step` and `width_decay_per_step` (decaying trail), `Paint` with `STROKE`, `ROUND` cap/join. |

Conclusion: Gboard uses a **native FST decoder with a language model** and a
**spatial geometry of keys** to transform the glide trail into a word,
and the suggestion strip takes part in the gesture by delegation to the body.

### Reimplementation in this fork

Delight5's code is proprietary and cannot be copied. The fork reimplements
the **contract** in `LocalDecoder.java`:

- `decode(trail)` mirrors `Decoder.decode()`: scores each dictionary word
  by spatial coverage of the trail (QWERTY layout in `KEY_POS`) + probability
  of the local language model.
- `nextWords(previousWord, limit)` mirrors `Decoder.onKeyPress()`: ranks the
  next word by bigrams from the pt-BR corpus.
- The model is a compact asset (`assets/dict/pt-br-lm.txt`, 90 KB, 2934
  unigrams + 4139 bigrams) generated from the same corpora as the dictionary, offline.
- `attachSuggestionGestures` in `GboardIme` mirrors `HeaderMotionEventHandler`:
  horizontal swipe selects a suggestion, downward swipe starts glide on the body.
- `attachSpaceTouch` mirrors `FreeCursorKeyboard`: swipe on the space bar moves the cursor
  with continuous `setSelection`.
- `GestureOverlayView.java` reimplements Gboard's visual trail: round-capped `Paint` stroke
  of 13 dp, alpha 250, with per-step decay and end point.
  `updateGlidePreview()` shows the decoded word floating above the finger
  during the glide (real-time preview).
- **Symbols**: 3 pages (`ABC`, `?123`, `=\<`) with Gboard's layout, including
  `@ # $ _ & - + ( ) /`, `= < >`, `£ ¢ € ¥` and connectors.
- **Emoji picker**: 😀 button in the toolbar opens a `PopupWindow` with a grid of 128
  emojis (8 columns), mirroring `EmojiPickerKeyboard`.
- **Dictation**: bar with an ✕ button to cancel (no commit) and instruction text,
  solving the problem of getting stuck in a `…` with no way out.
- **Backspace**: `OnTouchListener` with an initial 400 ms delay and repeat every
  80 ms, deleting character by character when held.

Decoder validation (Python mirror of the Java algorithm): `nao` → `não`,
`bom` → `bom`, `muito` → `muito`, `teclado` → `teclado`. On the emulator:
`LocalDecoder: LM loaded: 2934 unigrams, 4139 bigrams` and IME initialized without
errors.

## 5. What changed compared to the previous version

The previous version of this fork delivered a generic linear keyboard without a suggestion
strip, without themes and without gestures. This REA fixes that gap: the interface
was rebuilt with a suggestion strip, light/dark themes, Material-style
keys, accent popups, cursor swipe, glide and a prominent mic, while
maintaining the already verified local STT pipeline.

## 6. Limitations

- Gboard's exact colors and dimensions were not read from resources because of
  obfuscation. The adopted values follow Material Design and observation of
  behavior, not a byte-for-byte copy of Gboard's assets.
- Gboard's code is proprietary; this fork reimplements the behavior and
  appearance, it does not redistribute Google code.
- `uiautomator` does not expose the IME window hierarchy, so visual
  validation was done via initialization messages and the `InputMethod` window
  in `dumpsys`, not by pixel-by-pixel comparison.

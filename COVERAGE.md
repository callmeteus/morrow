# Morrow test coverage

## How to read this document

Items are grouped by area, then by file or route. Each item is one test case, tagged with its kind:

- `[happy]` valid input, expected flow.
- `[sad]` invalid input, failing dependency, domain error.
- `[edge]` limits, empty, null, concurrency, cache, timing.
- `[security]` auth, spoofing, injection, replay, leaks.
`[x]` means the test exists. `[ ]` means it is still missing.
## 1. Transcript cleanup

### `packages/app/src/test/java/dev/gti/gboard/TranscriptCleanerTest.java`

- [x] [happy] Removes Portuguese music hallucination tag
- [x] [happy] Removes English music hallucination tag
- [x] [happy] Removes music notes and collapses whitespace
- [x] [happy] Keeps legitimate bracketed text
- [x] [edge] Music-only transcript becomes empty speech
- [x] [edge] Silence detection threshold distinguishes speech from noise
- [x] [edge] Null and empty transcript become empty string
## 2. Local dictionary correction

### `packages/app/src/test/java/dev/gti/gboard/DictionaryCorrectorTest.java`

- [x] [happy] Fold is accent-insensitive and lowercase
- [x] [happy] Bounded Levenshtein returns exact and near-edit distances
- [x] [edge] Levenshtein respects the distance bound
- [x] [edge] Levenshtein is symmetric
## 3. Glide decoding

### `packages/app/src/test/java/dev/gti/gboard/LocalDecoderTest.java`

- [x] [happy] Exact trail matches a word
- [x] [happy] A word longer than the trail matches by prefix
- [x] [edge] One-key overshoot is allowed
- [x] [edge] Unrelated word is rejected as hallucination
- [x] [edge] More than one-key overshoot is rejected
- [x] [happy] Key geometry contains QWERTY letters only
## 4. Suggestion ranking

### `packages/app/src/test/java/dev/gti/gboard/SuggestionEngineTest.java`

- [x] [happy] Empty prefix returns common words
- [x] [happy] Prefix filters common words first
- [x] [happy] Dictionary words rank after common words
- [x] [happy] Accent-insensitive matching
- [x] [edge] No match returns empty
## 5. Waveform levels

### `packages/app/src/test/java/dev/gti/gboard/WaveformLevelsTest.java`

- [x] [happy] Push shifts bars and keeps fixed size
- [x] [edge] Loud audio clamps to one
- [x] [edge] Silent audio stays at zero
- [x] [happy] Reset flattens all bars
- [x] [edge] Non-positive bar count is rejected
## 6. Gesture input

### `packages/app/src/test/java/dev/gti/gboard/TapDetectorTest.java`

- [x] [happy] Tiny jitter is still a tap
- [x] [happy] Movement beyond slop activates glide
- [x] [happy] Diagonal movement uses Euclidean distance
- [x] [edge] Up resets detector state
- [x] [edge] Non-positive slop is rejected

### `packages/app/src/test/java/dev/gti/gboard/GlideTrackerTest.java`

- [x] [happy] Adds distinct keys in order
- [x] [edge] Ignores repeated key
- [x] [edge] Ignores movement below threshold
- [x] [happy] Clear resets path
- [x] [edge] Ignores null and empty key

### `packages/app/src/test/java/dev/gti/gboard/GlideKeyResolverTest.java`

- [x] [happy] Tracks keys in order
- [x] [edge] Ignores repeated same key
- [x] [edge] Ignores points between keys
- [x] [happy] Reset clears path

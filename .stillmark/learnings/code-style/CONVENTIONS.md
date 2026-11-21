# Code style conventions

Observed in the project's own code. The vendored engines under `packages/ai-model/` and the AOSP IME base under `packages/app/aosp-base/` are excluded.

## Java

- Setup and launcher app package: `dev.gti.gboard`.
- IME package: `com.android.inputmethod.latin`.
- Four-space indentation.
- Class and method comments use Javadoc-style blocks. Public methods of pure logic classes have short descriptions.
- Pure logic is separated into small classes and covered by unit tests: cleanup, correction, decoding, ranking, waveform, gesture.
- Test classes end in `Test`, use JUnit 4 (`org.junit.Test`, `org.junit.Assert`) and live under `packages/app/src/test/java/dev/gti/gboard`.
- Test method names describe the behavior in lowerCamelCase, for example `removesPortugueseMusicHallucination`.
- Comments use plain English.

## Native and build

- The JNI bridge for the setup app lives under `packages/app/src/main/jni/whisper` and is built with CMake.
- The IME has its own Gradle build and its own JNI directory under `packages/app/aosp-base/`.
- Gradle runs from inside `packages/app/`, with wrapper version 8.9, Android Gradle Plugin 8.5.2, NDK 28.2.13676358 and CMake 3.22.1.
- Release binaries stay out of git and are published to Hugging Face.

## Documentation and commits

- Docs and code comments are written in English.
- Commit messages are written in English with conventional prefixes.
- The em-dash character is not used.

## Naming

- Constants are upper snake case, for example `KEY_ROW_H` and `PARTIAL_EVERY_MS`.
- Color values are defined inline in the keyboard implementation rather than in resources, except the launcher background and setup theme.

## Open questions

- No formatter configuration was found. Indentation and brace style are inferred from the existing Java files.

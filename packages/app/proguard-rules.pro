# The on-device Whisper engine is native; keep the JNI bridge intact.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class dev.gti.gboard.** { *; }

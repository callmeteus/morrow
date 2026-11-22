package com.android.inputmethod.latin;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure, unit-testable post-ASR cleanup. Whisper (the "base" model) hallucinates
 * non-speech tags such as [MÚSICA DE FUNDO], [MUSIC], ♪ on silence/noise or on
 * synthetic audio. This strips those artifacts, collapses whitespace and
 * decides whether a transcription carries real speech at all.
 *
 * No Android dependencies so it can be tested on the JVM.
 */
public final class TranscriptCleaner {
    private TranscriptCleaner() {}

    /** Bracketed tags Whisper emits for non-speech, e.g. [MÚSICA DE FUNDO]. */
    private static final Pattern BRACKET_TAG = Pattern.compile(
            "\\[[^\\]]{0,40}\\]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Standalone music notes / onomatopoeia produced on silence. */
    private static final Pattern MUSIC_TOKENS = Pattern.compile(
            "(?i)(?:^|\\s)(?:[♪♫♩]+|mm+|hmm+|hm+)(?=\\s|$)");

    private static final String[] KNOWN_TAGS = {
        "music", "música", "musica", "background music", "música de fundo",
        "musica de fundo", "applause", "aplausos", "silence", "silêncio",
        "noise", "ruído", "audio", "áudio", "speech", "fala", "som", "sound",
    };

    /**
     * Removes hallucinated non-speech tags and normalizes whitespace.
     */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw;
        // Remove bracketed tags, but only when the whole bracket looks like a
        // known non-speech tag (avoid dropping legitimately bracketed words).
        java.util.regex.Matcher m = BRACKET_TAG.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group();
            String inner = tag.substring(1, tag.length() - 1)
                    .trim().toLowerCase(Locale.ROOT);
            if (isNonSpeechTag(inner)) {
                m.appendReplacement(sb, " ");
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(tag));
            }
        }
        m.appendTail(sb);
        s = sb.toString();
        s = MUSIC_TOKENS.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** True when a bracket's content is a known non-speech label. */
    static boolean isNonSpeechTag(String inner) {
        String t = inner.toLowerCase(Locale.ROOT).trim();
        for (String k : KNOWN_TAGS) {
            if (t.equals(k) || t.contains(k)) return true;
        }
        // A bracket made only of music-note glyphs is also non-speech.
        if (t.matches("[♪♫♩\\s]+")) return true;
        return false;
    }

    /**
     * True if the cleaned text is empty or only non-speech artifacts. The UI
     * uses this to avoid committing "[MÚSICA DE FUNDO]" as if it were dictation.
     */
    public static boolean isEmptySpeech(String cleaned) {
        return cleaned == null || cleaned.trim().isEmpty();
    }

    /**
     * RMS threshold below which audio is considered silence/noise. Feeding such
     * audio to Whisper is what triggers the [MÚSICA DE FUNDO] hallucination.
     */
    public static boolean hasSpeech(float rms) {
        return rms >= 0.012f;
    }
}

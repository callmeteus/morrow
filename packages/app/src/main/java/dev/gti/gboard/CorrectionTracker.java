package dev.gti.gboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure state machine for corrector / suggestion frustration signals. No Android
 * dependency so it can be unit tested on the JVM. It records what the
 * suggestion strip showed, what the user selected or typed, and backspace
 * bursts, emitting one self-contained {@link Event} per signal.
 */
public final class CorrectionTracker {
    static final int BACKSPACE_BURST_COUNT = 3;
    static final long BACKSPACE_BURST_WINDOW_MS = 1500L;

    /** An immutable event, convertible to a map for JSON serialization. */
    public static final class Event {
        final String id;
        final String sessionId;
        final long seq;
        final long ts;
        final String kind;
        final String source;
        String prefix = "";
        List<String> shown = new ArrayList<>();
        String selected = "";
        int index = -1;
        String input = "";
        String candidate = "";
        int editDistance = -1;
        int frequency = -1;
        boolean accepted;
        long latencyMs = -1;
        String voiceId = "";
        String swipeId = "";

        Event(String sessionId, long seq, long ts, String kind, String source) {
            this.id = UUID.randomUUID().toString();
            this.sessionId = sessionId;
            this.seq = seq;
            this.ts = ts;
            this.kind = kind;
            this.source = source;
        }

        public String kind() { return kind; }
        public long seq() { return seq; }
        public String sessionId() { return sessionId; }

        /** Field map with only the attributes relevant to this event kind. */
        public Map<String, Object> toFields() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("sessionId", sessionId);
            m.put("seq", seq);
            m.put("ts", ts);
            m.put("kind", kind);
            m.put("source", source);
            if (!prefix.isEmpty()) m.put("prefix", prefix);
            if (!shown.isEmpty()) m.put("shown", shown);
            if (!selected.isEmpty()) m.put("selected", selected);
            if (index >= 0) m.put("index", index);
            if (!input.isEmpty()) m.put("input", input);
            if (!candidate.isEmpty()) m.put("candidate", candidate);
            if (editDistance >= 0) m.put("editDistance", editDistance);
            if (frequency >= 0) m.put("frequency", frequency);
            m.put("accepted", accepted);
            if (latencyMs >= 0) m.put("latencyMs", latencyMs);
            if (!voiceId.isEmpty()) m.put("voiceId", voiceId);
            if (!swipeId.isEmpty()) m.put("swipeId", swipeId);
            return m;
        }
    }

    /** The outcome of confirming a word: events to post and whether the
     *  pending glide state should be cleared. Pure data, no Android dependency. */
    public static final class WordCommit {
        public final List<Event> events;
        public final boolean clearPendingSwipe;

        WordCommit(List<Event> events, boolean clearPendingSwipe) {
            this.events = events;
            this.clearPendingSwipe = clearPendingSwipe;
        }
    }

    private final String sessionId;
    private long seq = 0;
    private String prefix = "";
    private List<String> shown = new ArrayList<>();
    private long shownAtMs = -1;
    private final Deque<Long> backspaces = new ArrayDeque<>();

    public CorrectionTracker(String sessionId) {
        this.sessionId = sessionId == null ? UUID.randomUUID().toString() : sessionId;
    }

    public String sessionId() { return sessionId; }

    /** Records the words currently displayed in the suggestion strip. */
    public void onSuggestionsShown(String prefix, List<String> shown, long nowMs) {
        this.prefix = prefix == null ? "" : prefix;
        this.shown = shown == null ? new ArrayList<>() : new ArrayList<>(shown);
        this.shownAtMs = nowMs;
    }

    /** The strip showed only the generic fallback words ("de que nao"). */
    public Event onFallbackGeneric(long nowMs) {
        Event e = next(nowMs, "fallback_generic", "keyboard");
        e.prefix = prefix;
        e.shown = new ArrayList<>(shown);
        return e;
    }

    /** The user tapped suggestion {@code index} in the strip. */
    public Event onSuggestionSelected(int index, long nowMs) {
        Event e = next(nowMs, "suggestion_selected", "keyboard");
        e.prefix = prefix;
        e.shown = new ArrayList<>(shown);
        e.index = index;
        if (index >= 0 && index < shown.size()) {
            e.selected = shown.get(index);
        }
        e.latencyMs = latency(nowMs);
        e.accepted = true;
        return e;
    }

    /**
     * The user committed a typed word with space/enter. If the word was in the
     * strip it was ignored; otherwise it is a coverage miss.
     */
    public Event onTypedCommit(String typedWord, long nowMs) {
        String word = typedWord == null ? "" : typedWord;
        if (word.isEmpty()) return null;
        boolean inStrip = containsFolded(shown, word);
        Event e = next(nowMs, inStrip ? "suggestion_ignored" : "suggestion_missed", "keyboard");
        e.prefix = prefix;
        e.shown = new ArrayList<>(shown);
        e.input = word;
        e.latencyMs = latency(nowMs);
        return e;
    }

    /**
     * One decision point for confirming a word. Combines the typed commit with
     * the pending-glide rewrite check, so every caller (space, enter, suggestion
     * tap) gets the same events without repeating the sequence.
     */
    public WordCommit onWordCommitted(String typedWord, String pendingWord,
                                      String pendingSwipeId, long nowMs) {
        String typed = typedWord == null ? "" : typedWord;
        if (typed.isEmpty()) return null;
        List<Event> events = new ArrayList<>();
        Event commit = onTypedCommit(typed, nowMs);
        if (commit != null) events.add(commit);
        boolean pending = pendingSwipeId != null && !pendingSwipeId.isEmpty();
        boolean rewritten = pending && !typed.isEmpty()
                && !WordFold.fold(typed).equals(WordFold.fold(pendingWord == null ? "" : pendingWord));
        if (rewritten) {
            events.add(onSwipeFailure(pendingSwipeId, pendingWord, typed, nowMs));
        }
        return new WordCommit(events, pending);
    }

    /** Records a backspace; emits a burst event when the threshold is crossed. */
    public Event onBackspace(long nowMs) {
        pruneBackspaces(nowMs);
        backspaces.addLast(nowMs);
        if (backspaces.size() < BACKSPACE_BURST_COUNT) return null;
        // Clear so one continuous hold produces a single signal, not one per key.
        backspaces.clear();
        Event e = next(nowMs, "backspace_burst", "keyboard");
        e.prefix = prefix;
        e.shown = new ArrayList<>(shown);
        return e;
    }

    /** Whisper token corrected by DictionaryCorrector. */
    public Event onAsrCorrection(String raw, String replacement, int distance,
                                 int frequency, String voiceId, long nowMs) {
        Event e = next(nowMs, "asr_word", "voice");
        e.input = raw == null ? "" : raw;
        e.candidate = replacement == null ? "" : replacement;
        e.editDistance = distance;
        e.frequency = frequency;
        e.accepted = true;
        if (voiceId != null) e.voiceId = voiceId;
        return e;
    }

    /** A glide was committed and the user immediately rewrote the word. */
    public Event onSwipeFailure(String swipeId, String decoded, String typed,
                                long nowMs) {
        Event e = next(nowMs, "swipe_failure", "swipe");
        if (swipeId != null) e.swipeId = swipeId;
        e.input = decoded == null ? "" : decoded;
        e.candidate = typed == null ? "" : typed;
        e.accepted = false;
        return e;
    }

    /** Clears transient strip/backspace state; keeps session and seq monotonic. */
    public void reset() {
        prefix = "";
        shown = new ArrayList<>();
        shownAtMs = -1;
        backspaces.clear();
    }

    // --- helpers -------------------------------------------------------------

    private Event next(long nowMs, String kind, String source) {
        return new Event(sessionId, ++seq, nowMs, kind, source);
    }

    private long latency(long nowMs) {
        return shownAtMs < 0 ? -1 : Math.max(0, nowMs - shownAtMs);
    }

    private void pruneBackspaces(long nowMs) {
        while (!backspaces.isEmpty()
                && nowMs - backspaces.peekFirst() > BACKSPACE_BURST_WINDOW_MS) {
            backspaces.removeFirst();
        }
    }

    private static boolean containsFolded(List<String> words, String target) {
        String t = WordFold.fold(target);
        for (String w : words) {
            if (WordFold.fold(w).equals(t)) return true;
        }
        return false;
    }
}

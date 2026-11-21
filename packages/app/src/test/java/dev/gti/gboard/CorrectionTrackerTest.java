package dev.gti.gboard;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CorrectionTrackerTest {
    private static final String SESSION = "sess-1";

    @Test
    public void wordCommitDecidesTypedAndSwipeFailureEvents() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("na", Arrays.asList("não", "nada"), 1000L);

        // A typed word that differs from a pending glide produces two events.
        // "não" and "nao" fold to the same word, so use a genuinely different one.
        CorrectionTracker.WordCommit commit = t.onWordCommitted(
                "errado", "nao", "sw-1", 1200L);
        assertNotNull(commit);
        assertEquals(2, commit.events.size());
        assertEquals("suggestion_missed", commit.events.get(0).kind());
        assertEquals("swipe_failure", commit.events.get(1).kind());
        assertEquals("sw-1", commit.events.get(1).toFields().get("swipeId"));
        assertTrue(commit.clearPendingSwipe);

        // No pending glide: only the typed-commit event, no clearing needed.
        CorrectionTracker.WordCommit plain = t.onWordCommitted("nada", "", null, 1300L);
        assertEquals(1, plain.events.size());
        assertFalse(plain.clearPendingSwipe);

        // Empty word emits nothing.
        assertNull(t.onWordCommitted("", "nao", "sw-1", 1400L));
    }

    @Test
    public void sameGlideWordDoesNotEmitSwipeFailure() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        // The typed word matches the glide, so no failure event; the pending
        // glide is still consumed by the commit.
        CorrectionTracker.WordCommit commit = t.onWordCommitted(
                "não", "não", "sw-1", 1000L);
        assertEquals(1, commit.events.size());
        assertTrue(commit.clearPendingSwipe);
    }

    @Test
    public void suggestionSelectedIndexZeroAndTwo() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("na", Arrays.asList("não", "nada", "nao"), 1000L);
        CorrectionTracker.Event e0 = t.onSuggestionSelected(0, 1100L);
        assertEquals("suggestion_selected", e0.kind());
        assertEquals(0, (int) (Integer) e0.toFields().get("index"));
        assertEquals("não", e0.toFields().get("selected"));
        assertEquals(100L, (long) (Long) e0.toFields().get("latencyMs"));

        CorrectionTracker.Event e2 = t.onSuggestionSelected(2, 1200L);
        assertEquals(2, (int) (Integer) e2.toFields().get("index"));
        assertEquals("nao", e2.toFields().get("selected"));
    }

    @Test
    public void typedWordInStripIsIgnored() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("nao", Arrays.asList("não", "nada"), 1000L);
        CorrectionTracker.Event e = t.onTypedCommit("não", 1500L);
        assertNotNull(e);
        assertEquals("suggestion_ignored", e.kind());
        assertEquals("não", e.toFields().get("input"));
        assertTrue(((List<?>) e.toFields().get("shown")).contains("não"));
    }

    @Test
    public void typedWordAbsentFromStripIsMissed() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("xy", Arrays.asList("xylophone"), 1000L);
        CorrectionTracker.Event e = t.onTypedCommit("zebra", 1500L);
        assertNotNull(e);
        assertEquals("suggestion_missed", e.kind());
        assertEquals("zebra", e.toFields().get("input"));
    }

    @Test
    public void emptyTypedWordEmitsNothing() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("", Arrays.asList("de", "que"), 1000L);
        assertNull(t.onTypedCommit("", 1500L));
        assertNull(t.onTypedCommit(null, 1500L));
    }

    @Test
    public void backspaceBurstWithinWindow() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("err", Arrays.asList("erro"), 1000L);
        assertNull(t.onBackspace(1000L));
        assertNull(t.onBackspace(1100L));
        CorrectionTracker.Event burst = t.onBackspace(1200L);
        assertNotNull(burst);
        assertEquals("backspace_burst", burst.kind());
        // The next backspace must not immediately emit a second burst.
        assertNull(t.onBackspace(1250L));
    }

    @Test
    public void backspacesOutsideWindowDoNotBurst() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        assertNull(t.onBackspace(1000L));
        assertNull(t.onBackspace(1100L));
        // More than 1.5 s after the first two: the window is pruned.
        assertNull(t.onBackspace(3000L));
    }

    @Test
    public void fallbackAndAsrAndSwipeEvents() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("zz", Arrays.asList("de", "que", "não"), 1000L);
        assertEquals("fallback_generic", t.onFallbackGeneric(1010L).kind());

        CorrectionTracker.Event asr = t.onAsrCorrection("ola", "olá", 1, 5000, "voice-1", 2000L);
        assertEquals("asr_word", asr.kind());
        assertEquals(1, (int) (Integer) asr.toFields().get("editDistance"));
        assertEquals("voice-1", asr.toFields().get("voiceId"));

        CorrectionTracker.Event swipe = t.onSwipeFailure("swipe-1", "nao", "não", 3000L);
        assertEquals("swipe_failure", swipe.kind());
        assertEquals(Boolean.FALSE, swipe.toFields().get("accepted"));
        assertEquals("swipe-1", swipe.toFields().get("swipeId"));
    }

    @Test
    public void seqIsMonotonicAcrossReset() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("a", Arrays.asList("a"), 1000L);
        long first = t.onSuggestionSelected(0, 1010L).seq();
        t.reset();
        t.onSuggestionsShown("b", Arrays.asList("b"), 2000L);
        long second = t.onSuggestionSelected(0, 2010L).seq();
        assertTrue("seq must be monotonic across reset", second > first);
        assertEquals(SESSION, t.sessionId());
    }

    @Test
    public void everyEventCarriesIdentityFields() {
        CorrectionTracker t = new CorrectionTracker(SESSION);
        t.onSuggestionsShown("x", Arrays.asList("x"), 1000L);
        for (CorrectionTracker.Event e : Arrays.asList(
                t.onSuggestionSelected(0, 1010L),
                t.onFallbackGeneric(1020L),
                t.onTypedCommit("y", 1030L))) {
            assertEquals(SESSION, e.sessionId());
            assertNotNull(e.toFields().get("id"));
            assertTrue((long) (Long) e.toFields().get("ts") >= 1000L);
            assertNotNull(e.toFields().get("kind"));
        }
    }
}

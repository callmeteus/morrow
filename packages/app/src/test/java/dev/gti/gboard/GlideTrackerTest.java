package dev.gti.gboard;

import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests for the glide path tracker (distance threshold + dedup). */
public class GlideTrackerTest {

    @Test
    public void addsDistinctKeys() {
        GlideTracker t = new GlideTracker(8f);
        assertTrue(t.add("b", 0, 0));
        assertTrue(t.add("o", 100, 0));
        assertTrue(t.add("m", 200, 0));
        assertEquals("bom", t.trail());
    }

    @Test
    public void ignoresRepeatedKey() {
        GlideTracker t = new GlideTracker(8f);
        assertTrue(t.add("b", 0, 0));
        assertFalse(t.add("b", 50, 0));
        assertEquals("b", t.trail());
    }

    @Test
    public void ignoresMovementBelowThreshold() {
        GlideTracker t = new GlideTracker(20f);
        assertTrue(t.add("b", 0, 0));
        assertFalse(t.add("o", 10, 0));
        assertEquals("b", t.trail());
    }

    @Test
    public void clearResetsPath() {
        GlideTracker t = new GlideTracker(8f);
        t.add("b", 0, 0);
        t.add("o", 100, 0);
        t.clear();
        assertEquals("", t.trail());
    }

    @Test
    public void ignoresNullKey() {
        GlideTracker t = new GlideTracker(8f);
        assertFalse(t.add(null, 0, 0));
        assertFalse(t.add("", 0, 0));
        assertEquals("", t.trail());
    }
}

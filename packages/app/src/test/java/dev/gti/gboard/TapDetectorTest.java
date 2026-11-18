package dev.gti.gboard;

import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests proving a plain tap never turns into a glide. */
public class TapDetectorTest {

    @Test
    public void tinyJitterIsStillATap() {
        TapDetector d = new TapDetector(12f);
        d.down(100, 100);
        d.move(103, 101); // < 12px jitter
        assertTrue("must stay a tap", d.isTap());
        assertFalse("no glide on jitter", d.isGlide());
    }

    @Test
    public void movementBeyondSlopActivatesGlide() {
        TapDetector d = new TapDetector(12f);
        d.down(100, 100);
        d.move(118, 100); // 18px > 12px
        assertTrue(d.isGlide());
        assertFalse(d.isTap());
    }

    @Test
    public void diagonalMovementUsesEuclideanDistance() {
        TapDetector d = new TapDetector(12f);
        d.down(100, 100);
        d.move(108, 108); // ~11.3px, still tap
        assertTrue(d.isTap());
        d.move(109, 109); // ~12.7px, now glide
        assertTrue(d.isGlide());
    }

    @Test
    public void upResetsState() {
        TapDetector d = new TapDetector(12f);
        d.down(100, 100);
        d.move(118, 100);
        d.up();
        assertFalse(d.isGlide());
        assertFalse(d.isTap());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveSlop() {
        new TapDetector(0f);
    }
}

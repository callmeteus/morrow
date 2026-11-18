package dev.gti.gboard;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Unit tests for rectangle-based glide key detection. */
public class GlideKeyResolverTest {

    private GlideKeyResolver resolver() {
        GlideKeyResolver r = new GlideKeyResolver();
        r.setKeys(Arrays.asList(
            new GlideKeyResolver.KeyBox("q", 0, 0, 100, 100),
            new GlideKeyResolver.KeyBox("w", 100, 0, 200, 100),
            new GlideKeyResolver.KeyBox("e", 200, 0, 300, 100)
        ));
        return r;
    }

    @Test
    public void tracksKeysInOrder() {
        GlideKeyResolver r = resolver();
        assertEquals("q", r.move(50, 50));
        assertEquals("w", r.move(150, 50));
        assertEquals("e", r.move(250, 50));
        assertEquals("qwe", r.trail());
    }

    @Test
    public void ignoresRepeatedSameKey() {
        GlideKeyResolver r = resolver();
        r.move(50, 50);
        assertNull("same key should not be added twice", r.move(60, 50));
        assertEquals("q", r.trail());
    }

    @Test
    public void ignoresPointsBetweenKeys() {
        GlideKeyResolver r = resolver();
        r.move(50, 50);
        assertNull("gap between keys should be ignored", r.move(350, 50));
        assertEquals("q", r.trail());
    }

    @Test
    public void resetClearsPath() {
        GlideKeyResolver r = resolver();
        r.move(50, 50);
        r.move(150, 50);
        r.reset();
        assertEquals("", r.trail());
    }
}

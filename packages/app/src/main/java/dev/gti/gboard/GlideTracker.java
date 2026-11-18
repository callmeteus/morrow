package dev.gti.gboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, unit-testable glide path tracker. Mirrors Gboard's
 * AbstractGestureMotionEventHandler: it records the sequence of keys the
 * finger crosses, using a proximity threshold and de-duplication so a jittery
 * finger does not produce repeated or spurious letters. No Android dependency.
 */
public final class GlideTracker {
    /** Minimum movement before a new key is accepted (dp-equivalent units). */
    private final float minStep;
    private final List<String> path = new ArrayList<>();
    private float lastX = Float.NaN, lastY = Float.NaN;

    public GlideTracker() { this(8f); }
    public GlideTracker(float minStep) { this.minStep = minStep; }

    public List<String> path() { return path; }
    public void clear() { path.clear(); lastX = Float.NaN; lastY = Float.NaN; }

    /**
     * Called with the key currently nearest the finger and its position.
     * A key is appended only when it differs from the last and the finger has
     * moved at least {@code minStep}, matching Gboard's distance threshold.
     */
    public boolean add(String key, float x, float y) {
        if (key == null || key.isEmpty()) return false;
        float moved = Float.isNaN(lastX) ? Float.MAX_VALUE
                : (float) Math.hypot(x - lastX, y - lastY);
        boolean isSame = !path.isEmpty() && path.get(path.size() - 1).equals(key);
        if (isSame || moved < minStep) return false;
        path.add(key);
        lastX = x;
        lastY = y;
        return true;
    }

    /** Builds the concatenated letter trail from single-character keys. */
    public String trail() {
        StringBuilder sb = new StringBuilder();
        for (String k : path) if (k.length() == 1) sb.append(k);
        return sb.toString();
    }
}

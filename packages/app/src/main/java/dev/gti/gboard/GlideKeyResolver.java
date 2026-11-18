package dev.gti.gboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, unit-testable resolver that turns a stream of finger coordinates into
 * a sequence of glide keys. A key is visited only when the finger enters its
 * rectangle; consecutive duplicates are collapsed. This mirrors Gboard's
 * AbstractGestureMotionEventHandler which tracks which key view the point is
 * inside of, rather than snapping to the nearest key from a distance.
 */
public final class GlideKeyResolver {
    /** Simple rectangle in screen coordinates. */
    public static final class KeyBox {
        public final String label;
        public final float left, top, right, bottom;
        public KeyBox(String label, float left, float top, float right, float bottom) {
            this.label = label;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private final List<KeyBox> boxes = new ArrayList<>();
    private final List<String> path = new ArrayList<>();
    private String current = null;

    public void setKeys(List<KeyBox> keys) {
        boxes.clear();
        boxes.addAll(keys);
    }

    public List<String> path() { return path; }

    public void reset() {
        path.clear();
        current = null;
    }

    /** Returns the visited key label when the finger enters a new key, else null. */
    public String move(float x, float y) {
        String hit = null;
        for (KeyBox k : boxes) {
            if (k.contains(x, y)) { hit = k.label; break; }
        }
        if (hit == null || hit.equals(current)) return null;
        current = hit;
        path.add(hit);
        return hit;
    }

    public String trail() {
        StringBuilder sb = new StringBuilder();
        for (String k : path) if (k.length() == 1) sb.append(k);
        return sb.toString();
    }
}

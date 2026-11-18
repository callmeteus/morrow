package dev.gti.gboard;

/**
 * Pure, unit-testable touch-slop logic. Decides whether a finger gesture is a
 * plain tap (insert one character) or a glide (trace across keys), using a
 * movement threshold like Gboard's ViewConfiguration scaled touch slop.
 * No Android dependency.
 */
public final class TapDetector {
    private final float slop;
    private float downX, downY;
    private boolean activated;
    private boolean down;

    public TapDetector(float slop) {
        if (slop <= 0) throw new IllegalArgumentException("slop must be > 0");
        this.slop = slop;
    }

    public void down(float x, float y) {
        downX = x;
        downY = y;
        activated = false;
        down = true;
    }

    /** Returns true when the gesture has crossed into glide mode. */
    public boolean move(float x, float y) {
        if (!down) return false;
        if (!activated && (float) Math.hypot(x - downX, y - downY) >= slop) {
            activated = true;
        }
        return activated;
    }

    public boolean isGlide() { return activated; }
    public boolean isTap() { return down && !activated; }
    public float downX() { return downX; }
    public float downY() { return downY; }

    public void up() {
        down = false;
        activated = false;
    }
}

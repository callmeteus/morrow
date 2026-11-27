/*
 * gti.dev: circle behind the mic key whose radius follows the voice RMS.
 * The mic icon stays fixed in the centre while only the circle grows/shrinks.
 */
package com.android.inputmethod.latin.suggestions;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

public final class PulsingCircleDrawable extends Drawable {
    // gti.dev: like Gboard, the mic key has no circle at rest. The circle only
    // appears once the user taps to speak, then follows the voice level.
    private static final int ACTIVE_COLOR = 0xFF3C4043;
    // gti.dev: keep the circle inside the key bounds so it never gets clipped.
    private static final float MAX_RADIUS_RATIO = 0.46f;
    private static final float START_RADIUS_RATIO = 0.30f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mLevel;
    private int mColor = ACTIVE_COLOR;
    private boolean mActive;

    public PulsingCircleDrawable() {
        mPaint.setColor(mColor);
    }

    /** gti.dev: show the circle only while dictation is active. */
    public void setActive(final boolean active) {
        if (mActive == active) return;
        mActive = active;
        invalidateSelf();
    }

    /** gti.dev: 0 = start size, 1 = full voice level. */
    public void setLevel(final float level) {
        mLevel = Math.max(0f, Math.min(1f, level));
        invalidateSelf();
    }

    public void setCircleColor(final int color) {
        mColor = color;
        mPaint.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(final Canvas canvas) {
        if (!mActive) return;
        final int w = getBounds().width();
        final int h = getBounds().height();
        if (w <= 0 || h <= 0) return;
        final float min = Math.min(w, h);
        final float start = min * START_RADIUS_RATIO;
        final float max = min * MAX_RADIUS_RATIO;
        // gti.dev: smoothstep easing so growth/decay eases in and out.
        final float t = mLevel * mLevel * (3f - 2f * mLevel);
        final float radius = start + (max - start) * t;
        canvas.drawCircle(w / 2f, h / 2f, radius, mPaint);
    }

    @Override public void setAlpha(final int alpha) { mPaint.setAlpha(alpha); }
    @Override public void setColorFilter(final ColorFilter cf) { mPaint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

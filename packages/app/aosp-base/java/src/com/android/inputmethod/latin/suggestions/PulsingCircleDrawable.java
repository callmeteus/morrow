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
    private static final int IDLE_COLOR = 0xFF3C4043;
    // gti.dev: keep the circle inside the key bounds so it never gets clipped.
    private static final float MAX_RADIUS_RATIO = 0.46f;
    private static final float IDLE_RADIUS_RATIO = 0.30f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mLevel;
    private int mColor = IDLE_COLOR;

    public PulsingCircleDrawable() {
        mPaint.setColor(mColor);
    }

    /** gti.dev: 0 = idle size, 1 = full voice level. */
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
        final int w = getBounds().width();
        final int h = getBounds().height();
        if (w <= 0 || h <= 0) return;
        final float min = Math.min(w, h);
        final float idle = min * IDLE_RADIUS_RATIO;
        final float max = min * MAX_RADIUS_RATIO;
        // gti.dev: smoothstep easing so growth/decay eases in and out.
        final float t = mLevel * mLevel * (3f - 2f * mLevel);
        final float radius = idle + (max - idle) * t;
        canvas.drawCircle(w / 2f, h / 2f, radius, mPaint);
    }

    @Override public void setAlpha(final int alpha) { mPaint.setAlpha(alpha); }
    @Override public void setColorFilter(final ColorFilter cf) { mPaint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

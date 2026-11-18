package dev.gti.gboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Voice activity bar driven by REAL microphone RMS levels. While active it
 * renders the scrolling levels fed through {@link #pushLevel(float)}; while
 * idle it flattens out. The level model ({@link WaveformLevels}) is pure Java
 * and unit-tested, so this View is only a thin renderer.
 */
public class WaveformView extends View {
    private static final int BARS = 22;
    private static final float IDLE_LEVEL = 0.12f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final WaveformLevels levels = new WaveformLevels(BARS);
    private boolean active;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setActive(boolean a) {
        if (a == active) return;
        active = a;
        if (!a) levels.reset();
        postInvalidateOnAnimation();
    }

    /** Feeds a real RMS sample from the recorder into the waveform. */
    public void pushLevel(float rms) {
        if (!active) return;
        levels.push(rms);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float gap = w / 60f;
        float barW = (w - gap * (BARS - 1)) / BARS;
        for (int i = 0; i < BARS; i++) {
            float level = active ? Math.max(IDLE_LEVEL, levels.get(i)) : IDLE_LEVEL;
            float bh = Math.max(barW, h * level);
            float x = i * (barW + gap);
            float y0 = (h - bh) / 2f;
            paint.setColor(active ? 0xFF1A73E8 : 0xFFB0BEC5);
            canvas.drawRoundRect(x, y0, x + barW, y0 + bh, barW / 2f, barW / 2f, paint);
        }
    }
}

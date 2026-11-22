/*
 * gti.dev: RMS-driven waveform for the dictation bar. Bar heights follow the
 * measured microphone level; a slow decay makes the shape settle in silence.
 */
package com.android.inputmethod.latin.suggestions;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

public final class VoiceWaveformView extends View {
    private static final int BAR_COUNT = 24;
    private static final long FRAME_MS = 50L;
    private static final float DECAY = 0.82f;
    private static final float MIN_HEIGHT = 0.15f;

    private final float[] mLevels = new float[BAR_COUNT];
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mColor = 0xFFE8EAED;
    private float mLatestRms;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            // gti.dev: shift left and insert the latest level, like a scope trace.
            System.arraycopy(mLevels, 1, mLevels, 0, BAR_COUNT - 1);
            mLevels[BAR_COUNT - 1] = mLatestRms;
            mLatestRms *= DECAY;
            invalidate();
            mHandler.postDelayed(this, FRAME_MS);
        }
    };

    public VoiceWaveformView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mPaint.setColor(mColor);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(6f);
    }

    /** Called with the RMS measured by LocalVoiceInput on the recorder thread. */
    public void setLevel(final float rms) {
        // gti.dev: scale the raw RMS into a visible 0..1 range.
        mLatestRms = Math.min(1f, Math.max(0f, rms * 20f));
    }

    public void setColor(final int color) {
        mColor = color;
        mPaint.setColor(color);
        invalidate();
    }

    public void start() {
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    public void stop() {
        mHandler.removeCallbacks(mTick);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) return;
        final float step = (float) w / BAR_COUNT;
        final float barW = step * 0.55f;
        final float cx = barW / 2f;
        for (int i = 0; i < BAR_COUNT; i++) {
            final float level = Math.max(MIN_HEIGHT, mLevels[i]);
            final float barH = level * h;
            final float x = i * step + cx;
            canvas.drawLine(x, (h - barH) / 2f, x, (h + barH) / 2f, mPaint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}

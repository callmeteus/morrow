package dev.gti.gboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the live gesture trail during glide typing, reimplementing the visual
 * contract of Gboard's GestureOverlayView (decompiled evidence in
 * docs/REA_GBOARD_UI.md): a round-capped stroke, fixed width/alpha and a decay
 * that fades older segments as the finger moves.
 */
public class GestureOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Point> points = new ArrayList<>();
    private static final int TRAIL_WIDTH_DP = 13;
    private static final int TRAIL_ALPHA = 250;
    private static final int TRAIL_COLOR = 0xFFA8E6C5; // Gboard mint-green trail

    public GestureOverlayView(Context c) { super(c); init(); }
    public GestureOverlayView(Context c, AttributeSet a) { super(c, a); init(); }
    public GestureOverlayView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(TRAIL_WIDTH_DP * density);
        paint.setColor(TRAIL_COLOR);
        paint.setAlpha(TRAIL_ALPHA);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        setWillNotDraw(false);
    }

    /** Adds a trail point in this view's local coordinates, with a timestamp. */
    public void addPoint(float x, float y) {
        points.add(new Point(x, y, System.currentTimeMillis()));
        if (points.size() > 120) points.remove(0);
        invalidate();
    }

    /** Returns a snapshot of the trail as [x/width, y/height, timestampMs]. */
    public List<float[]> snapshotNormalized(int width, int height) {
        List<float[]> out = new ArrayList<>();
        if (width <= 0 || height <= 0) return out;
        for (Point p : points) {
            out.add(new float[]{p.x / width, p.y / height, (float) p.t});
        }
        return out;
    }

    public void clear() {
        points.clear();
        invalidate();
    }

    public boolean isEmpty() { return points.isEmpty(); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 1; i < points.size(); i++) {
            Point p0 = points.get(i - 1), p1 = points.get(i);
            // Alpha decays for older segments, matching Gboard's alpha_decay_per_step.
            int fade = Math.max(40, TRAIL_ALPHA - (points.size() - i) * 6);
            paint.setAlpha(fade);
            canvas.drawLine(p0.x, p0.y, p1.x, p1.y, paint);
        }
        // Bright dot at the current finger position.
        if (!points.isEmpty()) {
            Point last = points.get(points.size() - 1);
            paint.setAlpha(TRAIL_ALPHA);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(last.x, last.y, TRAIL_WIDTH_DP * getResources()
                    .getDisplayMetrics().density * 0.5f, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
    }

    private static final class Point {
        final float x, y;
        final long t;
        Point(float x, float y, long t) { this.x = x; this.y = y; this.t = t; }
    }
}

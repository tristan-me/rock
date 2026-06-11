package com.example.rockcatcherapi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class GestureOverlayView extends View {
    private static final int MAX_GESTURES = 4;
    private static final long LIFETIME_MS = 2600L;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<GestureTrace> traces = new ArrayList<>();
    private final Runnable pruneRunnable = new Runnable() {
        @Override
        public void run() {
            pruneExpired();
            if (!traces.isEmpty()) {
                handler.postDelayed(this, 120L);
            }
        }
    };

    GestureOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeWidth(dp(4));
        pointPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(12));
        textPaint.setShadowLayer(dp(3), 0f, dp(1), Color.BLACK);
    }

    void addGesture(float startX, float startY, float endX, float endY, int durationMs) {
        long now = System.currentTimeMillis();
        traces.add(new GestureTrace(startX, startY, endX, endY, durationMs, now));
        while (traces.size() > MAX_GESTURES) {
            traces.remove(0);
        }
        handler.removeCallbacks(pruneRunnable);
        handler.postDelayed(pruneRunnable, 120L);
        invalidate();
    }

    void clear() {
        traces.clear();
        handler.removeCallbacks(pruneRunnable);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        pruneExpired(now);
        for (int i = 0; i < traces.size(); i++) {
            GestureTrace trace = traces.get(i);
            float age = now - trace.createdMs;
            float alphaRatio = Math.max(0f, 1f - age / LIFETIME_MS);
            int alpha = Math.max(0, Math.min(220, Math.round(220f * alphaRatio)));
            int color = colorForIndex(i, alpha);
            linePaint.setColor(color);
            pointPaint.setColor(color);
            canvas.drawLine(trace.startX, trace.startY, trace.endX, trace.endY, linePaint);
            canvas.drawCircle(trace.startX, trace.startY, dp(7), pointPaint);
            canvas.drawCircle(trace.endX, trace.endY, dp(7), pointPaint);
            textPaint.setAlpha(alpha);
            canvas.drawText(
                    String.format(Locale.US, "%.0f,%.0f -> %.0f,%.0f  %dms",
                            trace.startX,
                            trace.startY,
                            trace.endX,
                            trace.endY,
                            trace.durationMs),
                    Math.min(trace.startX, trace.endX) + dp(8),
                    Math.min(trace.startY, trace.endY) - dp(8),
                    textPaint);
        }
        if (!traces.isEmpty()) {
            postInvalidateDelayed(120L);
        }
    }

    private void pruneExpired() {
        pruneExpired(System.currentTimeMillis());
        invalidate();
    }

    private void pruneExpired(long now) {
        Iterator<GestureTrace> iterator = traces.iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().createdMs > LIFETIME_MS) {
                iterator.remove();
            }
        }
    }

    private int colorForIndex(int index, int alpha) {
        int[] colors = {
                Color.rgb(255, 214, 77),
                Color.rgb(64, 196, 255),
                Color.rgb(126, 231, 135),
                Color.rgb(255, 128, 171)
        };
        int rgb = colors[index % colors.length];
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class GestureTrace {
        final float startX;
        final float startY;
        final float endX;
        final float endY;
        final int durationMs;
        final long createdMs;

        GestureTrace(float startX, float startY, float endX, float endY, int durationMs, long createdMs) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.durationMs = durationMs;
            this.createdMs = createdMs;
        }
    }
}


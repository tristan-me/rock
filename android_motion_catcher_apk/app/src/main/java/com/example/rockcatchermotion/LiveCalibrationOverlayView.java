package com.example.rockcatchermotion;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.preference.PreferenceManager;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

final class LiveCalibrationOverlayView extends View {
    interface Listener {
        void onDone();
        void onStatus(String status);
    }

    private static final int NONE = 0;
    private static final int RETICLE = 1;
    private static final int BALL = 2;

    private final SharedPreferences prefs;
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint donePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private final RectF doneRect = new RectF();
    private float reticleX;
    private float reticleY;
    private float ballX;
    private float ballY;
    private int dragging = NONE;
    private float dragOffsetX;
    private float dragOffsetY;
    private long lastSaveMs = 0L;

    LiveCalibrationOverlayView(Context context) {
        super(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        CatchConfig.upgradeDefaults(prefs);
        setWillNotDraw(false);
        setFocusable(false);

        dimPaint.setColor(Color.argb(72, 0, 0, 0));
        panelPaint.setColor(Color.argb(215, 25, 36, 42));
        donePaint.setColor(Color.rgb(255, 214, 77));
        reticlePaint.setStyle(Paint.Style.STROKE);
        reticlePaint.setStrokeWidth(dp(3));
        reticlePaint.setColor(Color.rgb(255, 214, 77));
        ballPaint.setStyle(Paint.Style.STROKE);
        ballPaint.setStrokeWidth(dp(3));
        ballPaint.setColor(Color.rgb(64, 196, 255));
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(14));
        textPaint.setShadowLayer(dp(3), 0f, dp(1), Color.BLACK);
        loadMarks();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private void loadMarks() {
        reticleX = CatchConfig.getFloat(prefs, "reticle_x", 960f);
        reticleY = CatchConfig.getFloat(prefs, "reticle_y", 540f);
        ballX = CatchConfig.getFloat(prefs, "ball_x", 1720f);
        ballY = CatchConfig.getFloat(prefs, "ball_y", 860f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
        drawPanel(canvas);
        drawReticle(canvas);
        drawBall(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (doneRect.contains(event.getX(), event.getY())) {
                    saveMarks(true);
                    if (listener != null) {
                        listener.onDone();
                    }
                    return true;
                }
                dragging = hitMarker(event.getX(), event.getY());
                if (dragging == RETICLE) {
                    dragOffsetX = reticleX - event.getX();
                    dragOffsetY = reticleY - event.getY();
                    return true;
                }
                if (dragging == BALL) {
                    dragOffsetX = ballX - event.getX();
                    dragOffsetY = ballY - event.getY();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging == RETICLE) {
                    reticleX = clamp(event.getX() + dragOffsetX, 0f, Math.max(0f, getWidth() - 1f));
                    reticleY = clamp(event.getY() + dragOffsetY, 0f, Math.max(0f, getHeight() - 1f));
                    maybeSaveMarks();
                    invalidate();
                } else if (dragging == BALL) {
                    ballX = clamp(event.getX() + dragOffsetX, 0f, Math.max(0f, getWidth() - 1f));
                    ballY = clamp(event.getY() + dragOffsetY, 0f, Math.max(0f, getHeight() - 1f));
                    maybeSaveMarks();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != NONE) {
                    saveMarks(true);
                }
                dragging = NONE;
                return true;
            default:
                return true;
        }
    }

    private void drawPanel(Canvas canvas) {
        float margin = dp(12);
        float panelHeight = dp(82);
        RectF panel = new RectF(margin, margin, getWidth() - margin, margin + panelHeight);
        canvas.drawRoundRect(panel, dp(8), dp(8), panelPaint);
        canvas.drawText("拖动“准星”和“丢球键”到真实位置", panel.left + dp(14), panel.top + dp(28), textPaint);
        canvas.drawText(
                String.format(Locale.US, "准星 %.0f,%.0f    丢球键 %.0f,%.0f", reticleX, reticleY, ballX, ballY),
                panel.left + dp(14),
                panel.top + dp(56),
                textPaint);

        doneRect.set(panel.right - dp(86), panel.top + dp(16), panel.right - dp(14), panel.bottom - dp(16));
        canvas.drawRoundRect(doneRect, dp(8), dp(8), donePaint);
        int oldColor = textPaint.getColor();
        textPaint.setColor(Color.rgb(25, 36, 42));
        textPaint.clearShadowLayer();
        canvas.drawText("完成", doneRect.left + dp(20), doneRect.top + dp(29), textPaint);
        textPaint.setColor(oldColor);
        textPaint.setShadowLayer(dp(3), 0f, dp(1), Color.BLACK);
    }

    private void drawReticle(Canvas canvas) {
        float radius = dragging == RETICLE ? dp(28) : dp(24);
        fillPaint.setColor(Color.argb(75, 255, 214, 77));
        canvas.drawCircle(reticleX, reticleY, radius, fillPaint);
        canvas.drawCircle(reticleX, reticleY, radius, reticlePaint);
        canvas.drawLine(reticleX - radius * 1.25f, reticleY, reticleX + radius * 1.25f, reticleY, reticlePaint);
        canvas.drawLine(reticleX, reticleY - radius * 1.25f, reticleX, reticleY + radius * 1.25f, reticlePaint);
        canvas.drawText("准星", reticleX + radius + dp(8), reticleY - radius, textPaint);
    }

    private void drawBall(Canvas canvas) {
        float radius = dragging == BALL ? dp(32) : dp(28);
        fillPaint.setColor(Color.argb(75, 64, 196, 255));
        canvas.drawCircle(ballX, ballY, radius, fillPaint);
        canvas.drawCircle(ballX, ballY, radius, ballPaint);
        canvas.drawCircle(ballX, ballY, dp(7), ballPaint);
        canvas.drawText("丢球键", ballX + radius + dp(8), ballY - radius, textPaint);
    }

    private int hitMarker(float x, float y) {
        float reticleHit = dp(48);
        float ballHit = dp(56);
        float reticleDistance = distance(x, y, reticleX, reticleY);
        float ballDistance = distance(x, y, ballX, ballY);
        if (reticleDistance <= reticleHit && reticleDistance <= ballDistance) {
            return RETICLE;
        }
        if (ballDistance <= ballHit) {
            return BALL;
        }
        return NONE;
    }

    private void maybeSaveMarks() {
        long now = System.currentTimeMillis();
        if (now - lastSaveMs < 120L) {
            return;
        }
        saveMarks(false);
    }

    private void saveMarks(boolean announce) {
        lastSaveMs = System.currentTimeMillis();
        prefs.edit()
                .putString("reticle_x", String.format(Locale.US, "%.0f", reticleX))
                .putString("reticle_y", String.format(Locale.US, "%.0f", reticleY))
                .putString("ball_x", String.format(Locale.US, "%.0f", ballX))
                .putString("ball_y", String.format(Locale.US, "%.0f", ballY))
                .apply();
        if (announce && listener != null) {
            listener.onStatus(String.format(Locale.US, "现场标定已保存：准星 %.0f,%.0f；丢球键 %.0f,%.0f", reticleX, reticleY, ballX, ballY));
        }
    }

    private float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}

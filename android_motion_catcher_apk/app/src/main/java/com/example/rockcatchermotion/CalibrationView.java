package com.example.rockcatchermotion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class CalibrationView extends View {
    interface PointListener {
        void onPoint(float x, float y);
    }

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;
    private PointListener listener;
    private String mode = "reticle";
    private float reticleX;
    private float reticleY;
    private float ballX;
    private float ballY;
    private float scale = 1f;
    private float offsetX = 0f;
    private float offsetY = 0f;

    CalibrationView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(18, 22, 26));
        reticlePaint.setStyle(Paint.Style.STROKE);
        reticlePaint.setStrokeWidth(dp(2));
        reticlePaint.setColor(Color.rgb(255, 214, 77));
        ballPaint.setStyle(Paint.Style.STROKE);
        ballPaint.setStrokeWidth(dp(2));
        ballPaint.setColor(Color.rgb(64, 196, 255));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(13));
        textPaint.setShadowLayer(dp(3), 0f, dp(1), Color.BLACK);
    }

    void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        invalidate();
    }

    void setPointListener(PointListener listener) {
        this.listener = listener;
    }

    void setMode(String mode) {
        this.mode = mode;
        invalidate();
    }

    void setMarks(float reticleX, float reticleY, float ballX, float ballY) {
        this.reticleX = reticleX;
        this.reticleY = reticleY;
        this.ballX = ballX;
        this.ballY = ballY;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            canvas.drawText("没有截图。先用悬浮条录制，或点“导入截图”。", dp(18), dp(34), textPaint);
            return;
        }
        computeFit();
        RectF dst = new RectF(
                offsetX,
                offsetY,
                offsetX + bitmap.getWidth() * scale,
                offsetY + bitmap.getHeight() * scale);
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint);
        drawMark(canvas, reticleX, reticleY, reticlePaint, "准星");
        drawMark(canvas, ballX, ballY, ballPaint, "丢球键");
        canvas.drawText("当前标定：" + ("reticle".equals(mode) ? "准星" : "丢球键"), dp(14), getHeight() - dp(14), textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || listener == null) {
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            computeFit();
            float x = (event.getX() - offsetX) / scale;
            float y = (event.getY() - offsetY) / scale;
            if (x >= 0 && y >= 0 && x < bitmap.getWidth() && y < bitmap.getHeight()) {
                listener.onPoint(x, y);
            }
            return true;
        }
        return true;
    }

    private void drawMark(Canvas canvas, float imageX, float imageY, Paint paint, String label) {
        if (bitmap == null) {
            return;
        }
        float x = offsetX + imageX * scale;
        float y = offsetY + imageY * scale;
        float radius = dp(12);
        canvas.drawCircle(x, y, radius, paint);
        canvas.drawLine(x - radius * 1.4f, y, x + radius * 1.4f, y, paint);
        canvas.drawLine(x, y - radius * 1.4f, x, y + radius * 1.4f, paint);
        canvas.drawText(label, x + dp(10), y - dp(10), textPaint);
    }

    private void computeFit() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) {
            scale = 1f;
            offsetX = 0f;
            offsetY = 0f;
            return;
        }
        float sx = getWidth() / (float) bitmap.getWidth();
        float sy = getHeight() / (float) bitmap.getHeight();
        scale = Math.min(sx, sy);
        offsetX = (getWidth() - bitmap.getWidth() * scale) * 0.5f;
        offsetY = (getHeight() - bitmap.getHeight() * scale) * 0.5f;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}

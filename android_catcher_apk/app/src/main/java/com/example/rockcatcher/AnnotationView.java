package com.example.rockcatcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AnnotationView extends View {
    interface ChangeListener {
        void onAnnotationsChanged();
    }

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();

    private Bitmap bitmap;
    private List<Annotation> annotations = new ArrayList<>();
    private String currentLabel = Annotation.LABEL_SPRITE;
    private RectF dragBox;
    private float downX;
    private float downY;
    private ChangeListener changeListener;

    AnnotationView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(20, 24, 28));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(2));
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAlpha(36);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(12));
        emptyPaint.setColor(Color.rgb(210, 216, 224));
        emptyPaint.setTextSize(dp(16));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setChangeListener(ChangeListener listener) {
        this.changeListener = listener;
    }

    void setBitmap(Bitmap bitmap, List<Annotation> annotations) {
        this.bitmap = bitmap;
        this.annotations = annotations == null ? new ArrayList<>() : annotations;
        this.dragBox = null;
        invalidate();
    }

    void setCurrentLabel(String label) {
        this.currentLabel = Annotation.normalizeLabel(label);
        invalidate();
    }

    void undoLast() {
        if (!annotations.isEmpty()) {
            annotations.remove(annotations.size() - 1);
            notifyChanged();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            canvas.drawText("导入图片后在这里标注", getWidth() * 0.5f, getHeight() * 0.5f, emptyPaint);
            return;
        }

        computeImageRect();
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint);
        for (Annotation annotation : annotations) {
            drawAnnotation(canvas, annotation);
        }
        if (dragBox != null) {
            drawBox(canvas, dragBox, currentLabel, "new " + currentLabel);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || imageRect.isEmpty()) {
            return true;
        }
        float imageX = toImageX(event.getX());
        float imageY = toImageY(event.getY());
        if (Float.isNaN(imageX) || Float.isNaN(imageY)) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = imageX;
                downY = imageY;
                dragBox = new RectF(downX, downY, downX, downY);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragBox != null) {
                    dragBox.right = imageX;
                    dragBox.bottom = imageY;
                    clampBox(dragBox);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                finishDrag(imageX, imageY);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragBox = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void finishDrag(float imageX, float imageY) {
        RectF box = dragBox == null ? new RectF(downX, downY, imageX, imageY) : new RectF(dragBox);
        normalize(box);
        if (box.width() < dpImage(8) || box.height() < dpImage(8)) {
            float radius = defaultRadiusFor(currentLabel);
            box.set(downX - radius, downY - radius, downX + radius, downY + radius);
        }
        clampBox(box);
        annotations.add(new Annotation(currentLabel, box));
        dragBox = null;
        notifyChanged();
        invalidate();
    }

    private void drawAnnotation(Canvas canvas, Annotation annotation) {
        drawBox(canvas, annotation.box, annotation.label, annotation.label);
    }

    private void drawBox(Canvas canvas, RectF imageBox, String label, String text) {
        int color = Annotation.colorFor(label);
        RectF viewBox = imageToView(imageBox);
        boxPaint.setColor(color);
        fillPaint.setColor(color);
        canvas.drawRect(viewBox, fillPaint);
        canvas.drawRect(viewBox, boxPaint);
        canvas.drawText(
                String.format(Locale.US, "%s %.0fx%.0f", text, imageBox.width(), imageBox.height()),
                viewBox.left + dp(4),
                Math.max(dp(14), viewBox.top - dp(4)),
                textPaint);
    }

    private RectF imageToView(RectF box) {
        float scaleX = imageRect.width() / bitmap.getWidth();
        float scaleY = imageRect.height() / bitmap.getHeight();
        return new RectF(
                imageRect.left + box.left * scaleX,
                imageRect.top + box.top * scaleY,
                imageRect.left + box.right * scaleX,
                imageRect.top + box.bottom * scaleY);
    }

    private void computeImageRect() {
        float viewW = Math.max(1, getWidth());
        float viewH = Math.max(1, getHeight());
        float scale = Math.min(viewW / bitmap.getWidth(), viewH / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (viewW - width) * 0.5f;
        float top = (viewH - height) * 0.5f;
        imageRect.set(left, top, left + width, top + height);
    }

    private float toImageX(float viewX) {
        if (viewX < imageRect.left || viewX > imageRect.right) {
            return Float.NaN;
        }
        return (viewX - imageRect.left) * bitmap.getWidth() / imageRect.width();
    }

    private float toImageY(float viewY) {
        if (viewY < imageRect.top || viewY > imageRect.bottom) {
            return Float.NaN;
        }
        return (viewY - imageRect.top) * bitmap.getHeight() / imageRect.height();
    }

    private void clampBox(RectF box) {
        box.left = clamp(box.left, 0, bitmap.getWidth() - 1);
        box.top = clamp(box.top, 0, bitmap.getHeight() - 1);
        box.right = clamp(box.right, 0, bitmap.getWidth() - 1);
        box.bottom = clamp(box.bottom, 0, bitmap.getHeight() - 1);
    }

    private void normalize(RectF box) {
        float left = Math.min(box.left, box.right);
        float top = Math.min(box.top, box.bottom);
        float right = Math.max(box.left, box.right);
        float bottom = Math.max(box.top, box.bottom);
        box.set(left, top, right, bottom);
    }

    private float defaultRadiusFor(String label) {
        if (Annotation.LABEL_RETICLE.equals(label)) {
            return dpImage(18);
        }
        if (Annotation.LABEL_BALL_BUTTON.equals(label)) {
            return dpImage(36);
        }
        return dpImage(54);
    }

    private float dpImage(float dp) {
        if (imageRect.isEmpty()) {
            return dp;
        }
        return dp(dp) * bitmap.getWidth() / Math.max(1f, imageRect.width());
    }

    private void notifyChanged() {
        if (changeListener != null) {
            changeListener.onAnnotationsChanged();
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}

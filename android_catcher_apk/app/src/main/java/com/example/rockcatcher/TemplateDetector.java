package com.example.rockcatcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

final class TemplateDetector {
    private final TemplateModel model;
    private final String modelStatus;

    TemplateDetector(Context context) {
        TemplateModel loaded = null;
        String status;
        try {
            loaded = TemplateModel.load(context);
            status = "template loaded samples=" + loaded.samples.size();
        } catch (IOException ex) {
            status = ex.getMessage() == null ? TemplateModel.FILE_NAME + " not found" : ex.getMessage();
        }
        this.model = loaded;
        this.modelStatus = status;
    }

    TemplateDetector(TemplateModel model) {
        this.model = model;
        this.modelStatus = model == null ? "template model empty" : "template loaded samples=" + model.samples.size();
    }

    boolean hasModel() {
        return model != null && model.hasSamples();
    }

    String status() {
        return modelStatus;
    }

    String fillDetections(Bitmap source, CatchConfig config, DetectionResult result) {
        if (!hasModel()) {
            return modelStatus;
        }
        Detection sprite = findBestForLabel(source, config, Annotation.LABEL_SPRITE, true);
        if (sprite != null) {
            result.sprite = sprite;
        }

        return String.format(
                Locale.US,
                "%s sprite=%s",
                modelStatus,
                sprite == null ? "missing" : String.format(Locale.US, "%.3f", sprite.confidence));
    }

    Detection findBestForLabel(Bitmap source, CatchConfig config, String label, boolean applyThreshold) {
        if (!hasModel()) {
            return null;
        }
        List<TemplateModel.Sample> samples = model.samplesFor(label);
        if (samples.isEmpty()) {
            return null;
        }

        SearchFrame frame = SearchFrame.from(source, config.templateMaxSidePx);
        try {
            Match best = null;
            for (TemplateModel.Sample sample : samples) {
                Match match = searchSample(frame, sample, config, applyThreshold);
                if (match != null && (best == null || match.score > best.score)) {
                    best = match;
                }
            }
            if (best == null) {
                return null;
            }
            RectF box = new RectF(
                    best.left / frame.scaleX,
                    best.top / frame.scaleY,
                    best.right / frame.scaleX,
                    best.bottom / frame.scaleY);
            box.left = clamp(box.left, 0, source.getWidth() - 1);
            box.top = clamp(box.top, 0, source.getHeight() - 1);
            box.right = clamp(box.right, box.left + 1, source.getWidth());
            box.bottom = clamp(box.bottom, box.top + 1, source.getHeight());
            return new Detection(Annotation.normalizeLabel(label), best.score, box);
        } finally {
            frame.close();
        }
    }

    private Match searchSample(
            SearchFrame frame,
            TemplateModel.Sample sample,
            CatchConfig config,
            boolean applyThreshold) {
        int boxW = clamp(Math.round(sample.normalizedWidth() * frame.width), 8, frame.width);
        int boxH = clamp(Math.round(sample.normalizedHeight() * frame.height), 8, frame.height);
        if (boxW >= frame.width || boxH >= frame.height) {
            return null;
        }

        Rect area = searchArea(frame, sample.label, boxW, boxH, config);
        int stride = Math.max(2, config.templateStridePx);
        float minScore = applyThreshold ? config.templateMinScore : -1f;
        Match best = null;
        for (int y = area.top; y <= area.bottom - boxH; y += stride) {
            for (int x = area.left; x <= area.right - boxW; x += stride) {
                float score = score(frame, sample, x, y, boxW, boxH);
                if (score >= minScore && (best == null || score > best.score)) {
                    best = new Match(x, y, x + boxW, y + boxH, score);
                }
            }
        }
        return best;
    }

    private Rect searchArea(
            SearchFrame frame,
            String label,
            int boxW,
            int boxH,
            CatchConfig config) {
        if (Annotation.LABEL_RETICLE.equals(label) || Annotation.LABEL_BALL_BUTTON.equals(label)) {
            float centerX = Annotation.LABEL_RETICLE.equals(label)
                    ? config.fallbackReticleX
                    : config.fallbackBallX;
            float centerY = Annotation.LABEL_RETICLE.equals(label)
                    ? config.fallbackReticleY
                    : config.fallbackBallY;
            int scaledX = Math.round(centerX * frame.scaleX);
            int scaledY = Math.round(centerY * frame.scaleY);
            int marginX = Math.max(boxW * 5, Math.round(frame.width * 0.16f));
            int marginY = Math.max(boxH * 5, Math.round(frame.height * 0.16f));
            int left = clamp(scaledX - marginX, 0, Math.max(0, frame.width - boxW));
            int top = clamp(scaledY - marginY, 0, Math.max(0, frame.height - boxH));
            int right = clamp(scaledX + marginX, left + boxW, frame.width);
            int bottom = clamp(scaledY + marginY, top + boxH, frame.height);
            return new Rect(left, top, right, bottom);
        }
        return new Rect(0, 0, frame.width, frame.height);
    }

    private float score(SearchFrame frame, TemplateModel.Sample sample, int left, int top, int boxW, int boxH) {
        int diff = 0;
        int index = 0;
        for (int gy = 0; gy < TemplateModel.GRID_H; gy++) {
            int y = top + ((gy * 2 + 1) * boxH) / (TemplateModel.GRID_H * 2);
            y = clamp(y, 0, frame.height - 1);
            int row = y * frame.width;
            for (int gx = 0; gx < TemplateModel.GRID_W; gx++) {
                int x = left + ((gx * 2 + 1) * boxW) / (TemplateModel.GRID_W * 2);
                x = clamp(x, 0, frame.width - 1);
                int pixel = frame.pixels[row + x];
                diff += Math.abs(((pixel >> 16) & 0xFF) - (sample.rgb[index++] & 0xFF));
                diff += Math.abs(((pixel >> 8) & 0xFF) - (sample.rgb[index++] & 0xFF));
                diff += Math.abs((pixel & 0xFF) - (sample.rgb[index++] & 0xFF));
            }
        }
        float maxDiff = TemplateModel.GRID_W * TemplateModel.GRID_H * 3f * 255f;
        return 1f - diff / maxDiff;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Match {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final float score;

        Match(int left, int top, int right, int bottom, float score) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.score = score;
        }
    }

    private static final class SearchFrame {
        final Bitmap scaled;
        final int width;
        final int height;
        final float scaleX;
        final float scaleY;
        final int[] pixels;
        final boolean ownsBitmap;

        private SearchFrame(Bitmap scaled, int sourceWidth, int sourceHeight, boolean ownsBitmap) {
            this.scaled = scaled;
            this.width = scaled.getWidth();
            this.height = scaled.getHeight();
            this.scaleX = width / (float) sourceWidth;
            this.scaleY = height / (float) sourceHeight;
            this.pixels = new int[width * height];
            this.ownsBitmap = ownsBitmap;
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        }

        static SearchFrame from(Bitmap source, int maxSide) {
            int safeMaxSide = Math.max(160, maxSide);
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            int longSide = Math.max(sourceWidth, sourceHeight);
            if (longSide <= safeMaxSide) {
                return new SearchFrame(source, sourceWidth, sourceHeight, false);
            }
            float scale = safeMaxSide / (float) longSide;
            int width = Math.max(1, Math.round(sourceWidth * scale));
            int height = Math.max(1, Math.round(sourceHeight * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, false);
            return new SearchFrame(scaled, sourceWidth, sourceHeight, true);
        }

        void close() {
            if (ownsBitmap) {
                scaled.recycle();
            }
        }
    }
}

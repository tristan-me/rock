package com.example.rockcatcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TemplateModel {
    static final int GRID_W = 12;
    static final int GRID_H = 12;
    static final String FILE_NAME = "template_model.tsv";
    private static final int FEATURE_BYTES = GRID_W * GRID_H * 3;

    final List<Sample> samples = new ArrayList<>();

    static File modelFile(Context context) {
        return new File(context.getExternalFilesDir(null), FILE_NAME);
    }

    static File reportFile(Context context) {
        return new File(context.getExternalFilesDir(null), "template_model_report.txt");
    }

    boolean hasSamples() {
        return !samples.isEmpty();
    }

    int countForLabel(String label) {
        int count = 0;
        String normalized = Annotation.normalizeLabel(label);
        for (Sample sample : samples) {
            if (normalized.equals(sample.label)) {
                count++;
            }
        }
        return count;
    }

    List<Sample> samplesFor(String label) {
        String normalized = Annotation.normalizeLabel(label);
        ArrayList<Sample> result = new ArrayList<>();
        for (Sample sample : samples) {
            if (normalized.equals(sample.label)) {
                result.add(sample);
            }
        }
        return result;
    }

    void save(Context context) throws IOException {
        File file = modelFile(context);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(file),
                StandardCharsets.UTF_8))) {
            writer.println("version\t1");
            for (Sample sample : samples) {
                writer.printf(
                        Locale.US,
                        "sample\t%s\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%s%n",
                        sample.label,
                        sample.sourceWidth,
                        sample.sourceHeight,
                        sample.left,
                        sample.top,
                        sample.right,
                        sample.bottom,
                        Base64.encodeToString(sample.rgb, Base64.NO_WRAP));
            }
        }
    }

    static TemplateModel load(Context context) throws IOException {
        File file = modelFile(context);
        if (!file.exists()) {
            throw new IOException(FILE_NAME + " not found");
        }
        TemplateModel model = new TemplateModel();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length != 9 || !"sample".equals(parts[0])) {
                    continue;
                }
                if (!Annotation.isSupportedLabel(parts[1])) {
                    continue;
                }
                try {
                    byte[] rgb = Base64.decode(parts[8], Base64.NO_WRAP);
                    if (rgb.length != FEATURE_BYTES) {
                        continue;
                    }
                    model.samples.add(new Sample(
                            parts[1],
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]),
                            Float.parseFloat(parts[4]),
                            Float.parseFloat(parts[5]),
                            Float.parseFloat(parts[6]),
                            Float.parseFloat(parts[7]),
                            rgb));
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed samples.
                }
            }
        }
        return model;
    }

    static TemplateModel train(List<File> imageFiles, Map<String, List<Annotation>> annotations)
            throws IOException {
        TemplateModel model = new TemplateModel();
        for (File file : imageFiles) {
            List<Annotation> imageAnnotations = annotations.get(file.getName());
            if (imageAnnotations == null || imageAnnotations.isEmpty()) {
                continue;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) {
                continue;
            }
            try {
                for (Annotation annotation : imageAnnotations) {
                    Sample sample = sampleFrom(bitmap, annotation);
                    if (sample != null) {
                        model.samples.add(sample);
                    }
                }
            } finally {
                bitmap.recycle();
            }
        }
        if (model.countForLabel(Annotation.LABEL_SPRITE) == 0) {
            throw new IOException("至少需要标注 1 个 sprite 才能训练");
        }
        return model;
    }

    String buildReport(Context context, List<File> imageFiles, Map<String, List<Annotation>> annotations) {
        CatchConfig config = CatchConfig.from(android.preference.PreferenceManager.getDefaultSharedPreferences(context));
        TemplateDetector detector = new TemplateDetector(this);
        Map<LabelKey, Stats> stats = new EnumMap<>(LabelKey.class);
        for (LabelKey key : LabelKey.values()) {
            stats.put(key, new Stats());
        }

        for (File file : imageFiles) {
            List<Annotation> imageAnnotations = annotations.get(file.getName());
            if (imageAnnotations == null || imageAnnotations.isEmpty()) {
                continue;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) {
                continue;
            }
            try {
                for (String label : Annotation.LABELS) {
                    Detection detection = detector.findBestForLabel(bitmap, config, label, false);
                    if (detection == null) {
                        continue;
                    }
                    for (Annotation annotation : imageAnnotations) {
                        if (!annotation.label.equals(label)) {
                            continue;
                        }
                        Stats stat = stats.get(LabelKey.from(label));
                        stat.count++;
                        stat.totalScore += detection.confidence;
                        stat.totalIou += iou(annotation.box, detection.box);
                    }
                }
            } finally {
                bitmap.recycle();
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append(String.format(
                Locale.US,
                "trained samples: sprite=%d reticle=%d ball_button=%d%n",
                countForLabel(Annotation.LABEL_SPRITE),
                countForLabel(Annotation.LABEL_RETICLE),
                countForLabel(Annotation.LABEL_BALL_BUTTON)));
        builder.append("self-check on training images:\n");
        for (LabelKey key : LabelKey.values()) {
            Stats stat = stats.get(key);
            if (stat.count == 0) {
                builder.append(key.label).append(": no labels\n");
            } else {
                builder.append(String.format(
                        Locale.US,
                        "%s: boxes=%d avg_score=%.3f avg_iou=%.3f%n",
                        key.label,
                        stat.count,
                        stat.totalScore / stat.count,
                        stat.totalIou / stat.count));
            }
        }
        builder.append("model: ").append(modelFile(context).getAbsolutePath());
        return builder.toString();
    }

    void saveReport(Context context, String report) throws IOException {
        File file = reportFile(context);
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(file),
                StandardCharsets.UTF_8))) {
            writer.println(report);
        }
    }

    private static Sample sampleFrom(Bitmap bitmap, Annotation annotation) {
        RectF box = new RectF(annotation.box);
        box.left = clamp(box.left, 0, bitmap.getWidth() - 1);
        box.top = clamp(box.top, 0, bitmap.getHeight() - 1);
        box.right = clamp(box.right, box.left + 1, bitmap.getWidth());
        box.bottom = clamp(box.bottom, box.top + 1, bitmap.getHeight());
        if (box.width() < 4 || box.height() < 4) {
            return null;
        }

        byte[] rgb = new byte[FEATURE_BYTES];
        int index = 0;
        for (int gy = 0; gy < GRID_H; gy++) {
            float y = box.top + (gy + 0.5f) * box.height() / GRID_H;
            int iy = clamp(Math.round(y), 0, bitmap.getHeight() - 1);
            for (int gx = 0; gx < GRID_W; gx++) {
                float x = box.left + (gx + 0.5f) * box.width() / GRID_W;
                int ix = clamp(Math.round(x), 0, bitmap.getWidth() - 1);
                int pixel = bitmap.getPixel(ix, iy);
                rgb[index++] = (byte) ((pixel >> 16) & 0xFF);
                rgb[index++] = (byte) ((pixel >> 8) & 0xFF);
                rgb[index++] = (byte) (pixel & 0xFF);
            }
        }
        return new Sample(
                annotation.label,
                bitmap.getWidth(),
                bitmap.getHeight(),
                box.left,
                box.top,
                box.right,
                box.bottom,
                rgb);
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float areaA = Math.max(0f, a.width()) * Math.max(0f, a.height());
        float areaB = Math.max(0f, b.width()) * Math.max(0f, b.height());
        float union = areaA + areaB - intersection;
        return union <= 0f ? 0f : intersection / union;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Sample {
        final String label;
        final int sourceWidth;
        final int sourceHeight;
        final float left;
        final float top;
        final float right;
        final float bottom;
        final byte[] rgb;

        Sample(
                String label,
                int sourceWidth,
                int sourceHeight,
                float left,
                float top,
                float right,
                float bottom,
                byte[] rgb) {
            this.label = Annotation.normalizeLabel(label);
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.rgb = rgb;
        }

        float normalizedWidth() {
            return Math.max(1f, right - left) / Math.max(1, sourceWidth);
        }

        float normalizedHeight() {
            return Math.max(1f, bottom - top) / Math.max(1, sourceHeight);
        }
    }

    private enum LabelKey {
        SPRITE(Annotation.LABEL_SPRITE),
        RETICLE(Annotation.LABEL_RETICLE),
        BALL_BUTTON(Annotation.LABEL_BALL_BUTTON);

        final String label;

        LabelKey(String label) {
            this.label = label;
        }

        static LabelKey from(String label) {
            if (Annotation.LABEL_RETICLE.equals(label)) {
                return RETICLE;
            }
            if (Annotation.LABEL_BALL_BUTTON.equals(label)) {
                return BALL_BUTTON;
            }
            return SPRITE;
        }
    }

    private static final class Stats {
        int count;
        float totalScore;
        float totalIou;
    }
}

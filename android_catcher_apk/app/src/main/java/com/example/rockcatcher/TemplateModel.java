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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TemplateModel {
    static final int GRID_W = 12;
    static final int GRID_H = 12;
    static final String FILE_NAME = "template_model.tsv";
    private static final int FEATURE_BYTES = GRID_W * GRID_H * 3;
    private static final int RECOMMENDED_SPRITE_IMAGES = 8;

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
                if (!Annotation.LABEL_SPRITE.equals(Annotation.normalizeLabel(parts[1]))) {
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
                    if (!Annotation.LABEL_SPRITE.equals(annotation.label)) {
                        continue;
                    }
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
            throw new IOException("至少需要标注 1 个精灵框才能训练；准星和扔球键请在主界面填写坐标，不参与训练。");
        }
        return model;
    }

    String buildReport(Context context, List<File> imageFiles, Map<String, List<Annotation>> annotations) {
        CatchConfig config = CatchConfig.from(android.preference.PreferenceManager.getDefaultSharedPreferences(context));
        TemplateDetector detector = new TemplateDetector(this);
        Stats spriteStats = new Stats();
        int imagesWithSprite = countSpriteImages(imageFiles, annotations);

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
                Detection detection = detector.findBestForLabel(bitmap, config, Annotation.LABEL_SPRITE, false);
                for (Annotation annotation : imageAnnotations) {
                    if (!Annotation.LABEL_SPRITE.equals(annotation.label)) {
                        continue;
                    }
                    spriteStats.count++;
                    if (detection != null) {
                        spriteStats.totalScore += detection.confidence;
                        spriteStats.totalIou += iou(annotation.box, detection.box);
                    }
                }
            } finally {
                bitmap.recycle();
            }
        }

        int spriteSamples = countForLabel(Annotation.LABEL_SPRITE);
        int remainingImages = recommendedAdditionalSpriteImages(imageFiles, annotations);
        float avgScore = spriteStats.count == 0 ? 0f : spriteStats.totalScore / spriteStats.count;
        float avgIou = spriteStats.count == 0 ? 0f : spriteStats.totalIou / spriteStats.count;

        StringBuilder builder = new StringBuilder();
        builder.append("训练结果说明：\n");
        builder.append(String.format(
                Locale.US,
                "精灵样本数：%d 个。每个样本来自你框选的一只精灵，样本越覆盖不同背景、角度和亮度，识别越稳。\n",
                spriteSamples));
        builder.append(String.format(
                Locale.US,
                "已标注图片数：%d 张。建议至少 %d 张不同截图；当前还建议补 %d 张。\n",
                imagesWithSprite,
                RECOMMENDED_SPRITE_IMAGES,
                remainingImages));
        if (spriteStats.count == 0) {
            builder.append("训练自检：没有可用于自检的精灵标注，请先保存至少 1 个精灵框。\n");
        } else {
            builder.append(String.format(
                    Locale.US,
                    "平均匹配分 avg_score：%.3f。越接近 1 越像训练样本；低于最低匹配分时运行中会认为没识别到。\n",
                    avgScore));
            builder.append(String.format(
                    Locale.US,
                    "平均重合度 avg_iou：%.3f。越接近 1 表示预测框越贴近你手工框；低于 0.5 通常说明框选或样本差异需要检查。\n",
                    avgIou));
        }
        builder.append(String.format(
                Locale.US,
                "最低匹配分阈值：%.2f。误识别多就调高，识别不到就略降。\n",
                config.templateMinScore));
        builder.append(String.format(
                Locale.US,
                "扫描步长：%d px。数值越小越细、越慢；越大越快、越容易跳过目标。\n",
                config.templateStridePx));
        builder.append(String.format(
                Locale.US,
                "扫描最长边：%d px。运行时会把截图缩放到这个上限再找精灵，越大越准但越耗时。\n",
                config.templateMaxSidePx));
        builder.append("准星和扔球键：不参与训练，只使用主界面的 X/Y 坐标标定。\n");
        builder.append("模型文件：").append(modelFile(context).getAbsolutePath()).append('\n');
        builder.append("报告文件：").append(reportFile(context).getAbsolutePath());
        return builder.toString();
    }

    int countSpriteImages(List<File> imageFiles, Map<String, List<Annotation>> annotations) {
        int count = 0;
        for (File file : imageFiles) {
            List<Annotation> imageAnnotations = annotations.get(file.getName());
            if (imageAnnotations == null) {
                continue;
            }
            for (Annotation annotation : imageAnnotations) {
                if (Annotation.LABEL_SPRITE.equals(annotation.label)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    int recommendedAdditionalSpriteImages(List<File> imageFiles, Map<String, List<Annotation>> annotations) {
        return Math.max(0, RECOMMENDED_SPRITE_IMAGES - countSpriteImages(imageFiles, annotations));
    }

    static int recommendedSpriteImages() {
        return RECOMMENDED_SPRITE_IMAGES;
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
                Annotation.LABEL_SPRITE,
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

    private static final class Stats {
        int count;
        float totalScore;
        float totalIou;
    }
}

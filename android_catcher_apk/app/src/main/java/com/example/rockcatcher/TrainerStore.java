package com.example.rockcatcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TrainerStore {
    private final Context context;
    private final File rootDir;
    private final File imagesDir;
    private final File annotationsFile;

    TrainerStore(Context context) {
        this.context = context.getApplicationContext();
        this.rootDir = new File(this.context.getExternalFilesDir(null), "trainer");
        this.imagesDir = new File(rootDir, "images");
        this.annotationsFile = new File(rootDir, "annotations.tsv");
        this.imagesDir.mkdirs();
    }

    File getImagesDir() {
        return imagesDir;
    }

    File getAnnotationsFile() {
        return annotationsFile;
    }

    List<File> listImages() {
        File[] files = imagesDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.US);
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        });
        ArrayList<File> result = new ArrayList<>();
        if (files != null) {
            Collections.addAll(result, files);
        }
        Collections.sort(result, Comparator.comparing(File::getName));
        return result;
    }

    File importImage(Uri uri) throws IOException {
        Bitmap bitmap;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("cannot open selected image");
            }
            bitmap = BitmapFactory.decodeStream(input);
        }
        if (bitmap == null) {
            throw new IOException("selected file is not a bitmap");
        }

        imagesDir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File out = new File(imagesDir, "img_" + stamp + ".png");
        int suffix = 1;
        while (out.exists()) {
            out = new File(imagesDir, "img_" + stamp + "_" + suffix + ".png");
            suffix++;
        }
        try (FileOutputStream output = new FileOutputStream(out)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("failed to save imported image");
            }
        } finally {
            bitmap.recycle();
        }
        return out;
    }

    Map<String, List<Annotation>> loadAnnotations() {
        Map<String, List<Annotation>> result = new LinkedHashMap<>();
        if (!annotationsFile.exists()) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(annotationsFile),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length != 6 || !Annotation.isSupportedLabel(parts[1])) {
                    continue;
                }
                try {
                    RectF box = new RectF(
                            Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3]),
                            Float.parseFloat(parts[4]),
                            Float.parseFloat(parts[5]));
                    List<Annotation> list = result.computeIfAbsent(parts[0], key -> new ArrayList<>());
                    list.add(new Annotation(parts[1], box));
                } catch (NumberFormatException ignored) {
                    // Skip malformed rows so one bad edit does not break the trainer.
                }
            }
        } catch (IOException ignored) {
            return new LinkedHashMap<>();
        }
        return result;
    }

    void saveAnnotations(Map<String, List<Annotation>> annotations) throws IOException {
        rootDir.mkdirs();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(annotationsFile),
                StandardCharsets.UTF_8))) {
            for (Map.Entry<String, List<Annotation>> entry : annotations.entrySet()) {
                for (Annotation annotation : entry.getValue()) {
                    writer.printf(
                            Locale.US,
                            "%s\t%s\t%.2f\t%.2f\t%.2f\t%.2f%n",
                            entry.getKey(),
                            annotation.label,
                            annotation.box.left,
                            annotation.box.top,
                            annotation.box.right,
                            annotation.box.bottom);
                }
            }
        }
    }
}

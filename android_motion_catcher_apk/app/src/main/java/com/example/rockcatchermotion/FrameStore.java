package com.example.rockcatchermotion;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class FrameStore {
    private final Context context;

    FrameStore(Context context) {
        this.context = context.getApplicationContext();
    }

    File saveBitmap(Bitmap bitmap, String prefix) throws IOException {
        File dir = framesDir();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File file = new File(dir, prefix + "_" + stamp + ".png");
        FileOutputStream out = new FileOutputStream(file);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("bitmap compression failed");
            }
        } finally {
            out.close();
        }
        return file;
    }

    File importImage(Uri uri) throws IOException {
        File dir = framesDir();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File file = new File(dir, "import_" + stamp + ".png");
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("cannot open image uri");
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            in.close();
            out.close();
        }
        return file;
    }

    List<File> listFrames() {
        File[] files = framesDir().listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.US);
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
        });
        if (files == null) {
            return new ArrayList<>();
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        return new ArrayList<>(Arrays.asList(files));
    }

    File framesDir() {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, "motion_frames");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir.getAbsolutePath());
        }
        return dir;
    }
}

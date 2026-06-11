package com.example.rockcatcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CalibrationActivity extends Activity {
    private static final int REQ_IMAGES = 50;

    private TrainerStore store;
    private SharedPreferences prefs;
    private AnnotationView annotationView;
    private TextView statusView;
    private TextView imageView;
    private final List<File> imageFiles = new ArrayList<>();
    private int currentIndex = 0;
    private String currentLabel = Annotation.LABEL_RETICLE;
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new TrainerStore(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        buildUi();
        reloadImages();
    }

    @Override
    protected void onDestroy() {
        recycleCurrentBitmap();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("准星 / 扔球键标定");
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("导入一张游戏截图，然后点准星或扔球键中心。这里只写坐标，不训练。");
        statusView.setTextIsSelectable(true);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        imageView = new TextView(this);
        imageView.setTextIsSelectable(true);
        root.addView(imageView);

        LinearLayout importRow = row();
        addButton(importRow, "导入截图", v -> pickImages());
        addButton(importRow, "上一张", v -> showOffset(-1));
        addButton(importRow, "下一张", v -> showOffset(1));
        root.addView(importRow);

        LinearLayout modeRow = row();
        addButton(modeRow, "标定准星", v -> setLabel(Annotation.LABEL_RETICLE));
        addButton(modeRow, "标定扔球键", v -> setLabel(Annotation.LABEL_BALL_BUTTON));
        root.addView(modeRow);

        annotationView = new AnnotationView(this);
        annotationView.setBoxListener(this::handleBoxCreated);
        root.addView(annotationView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout actionRow = row();
        addButton(actionRow, "回主界面", v -> finish());
        root.addView(actionRow);

        setContentView(root);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQ_IMAGES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMAGES || resultCode != RESULT_OK || data == null) {
            return;
        }
        int imported = 0;
        try {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    store.importImage(uri);
                    imported++;
                }
            } else if (data.getData() != null) {
                store.importImage(data.getData());
                imported++;
            }
            reloadImages();
            if (!imageFiles.isEmpty()) {
                currentIndex = Math.max(0, imageFiles.size() - imported);
                showCurrent();
            }
            updateStatus("已导入截图: " + imported);
        } catch (IOException ex) {
            updateStatus("导入失败: " + ex.getMessage());
        }
    }

    private void reloadImages() {
        imageFiles.clear();
        imageFiles.addAll(store.listImages());
        if (currentIndex >= imageFiles.size()) {
            currentIndex = Math.max(0, imageFiles.size() - 1);
        }
        showCurrent();
    }

    private void showOffset(int offset) {
        if (imageFiles.isEmpty()) {
            return;
        }
        currentIndex = (currentIndex + offset + imageFiles.size()) % imageFiles.size();
        showCurrent();
    }

    private void showCurrent() {
        recycleCurrentBitmap();
        if (imageFiles.isEmpty()) {
            imageView.setText("未导入截图");
            annotationView.setBitmap(null, new ArrayList<>());
            annotationView.setCalibrationMarks(new ArrayList<>());
            return;
        }
        File file = imageFiles.get(currentIndex);
        currentBitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (currentBitmap == null) {
            imageView.setText("无法读取: " + file.getName());
            annotationView.setBitmap(null, new ArrayList<>());
            annotationView.setCalibrationMarks(new ArrayList<>());
            return;
        }
        annotationView.setBitmap(currentBitmap, new ArrayList<>());
        annotationView.setCalibrationMarks(calibrationMarks());
        annotationView.setCurrentLabel(currentLabel);
        imageView.setText(String.format(
                Locale.US,
                "%d/%d %s  %dx%d  当前=%s",
                currentIndex + 1,
                imageFiles.size(),
                file.getName(),
                currentBitmap.getWidth(),
                currentBitmap.getHeight(),
                currentLabel));
    }

    private void setLabel(String label) {
        currentLabel = Annotation.normalizeLabel(label);
        annotationView.setCurrentLabel(currentLabel);
        updateStatus(Annotation.LABEL_RETICLE.equals(currentLabel)
                ? "当前：点准星中心。"
                : "当前：点扔球键中心。");
    }

    private boolean handleBoxCreated(Annotation annotation) {
        float centerX = annotation.box.centerX();
        float centerY = annotation.box.centerY();
        SharedPreferences.Editor editor = prefs.edit();
        if (Annotation.LABEL_RETICLE.equals(annotation.label)) {
            editor.putString("reticle_x", String.format(Locale.US, "%.0f", centerX));
            editor.putString("reticle_y", String.format(Locale.US, "%.0f", centerY));
            editor.apply();
            annotationView.setCalibrationMarks(calibrationMarks());
            updateStatus(String.format(Locale.US, "准星坐标已保存: X=%.0f, Y=%.0f", centerX, centerY));
            return false;
        }
        editor.putString("ball_x", String.format(Locale.US, "%.0f", centerX));
        editor.putString("ball_y", String.format(Locale.US, "%.0f", centerY));
        editor.apply();
        annotationView.setCalibrationMarks(calibrationMarks());
        updateStatus(String.format(Locale.US, "扔球键坐标已保存: X=%.0f, Y=%.0f", centerX, centerY));
        return false;
    }

    private List<Annotation> calibrationMarks() {
        ArrayList<Annotation> marks = new ArrayList<>();
        float reticleX = CatchConfig.getFloat(prefs, "reticle_x", 960f);
        float reticleY = CatchConfig.getFloat(prefs, "reticle_y", 540f);
        float ballX = CatchConfig.getFloat(prefs, "ball_x", 1720f);
        float ballY = CatchConfig.getFloat(prefs, "ball_y", 860f);
        marks.add(new Annotation(Annotation.LABEL_RETICLE, centeredBox(reticleX, reticleY, 18f)));
        marks.add(new Annotation(Annotation.LABEL_BALL_BUTTON, centeredBox(ballX, ballY, 36f)));
        return marks;
    }

    private RectF centeredBox(float centerX, float centerY, float radius) {
        return new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
    }

    private void recycleCurrentBitmap() {
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }

    private void updateStatus(String status) {
        statusView.setText(status);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

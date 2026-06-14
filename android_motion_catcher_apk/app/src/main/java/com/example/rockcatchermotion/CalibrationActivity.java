package com.example.rockcatchermotion;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    private FrameStore store;
    private SharedPreferences prefs;
    private CalibrationView calibrationView;
    private TextView statusView;
    private TextView imageView;
    private final List<File> imageFiles = new ArrayList<>();
    private int currentIndex = 0;
    private String currentMode = "reticle";
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new FrameStore(this);
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
        title.setText("准星 / 丢球键标定");
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("用悬浮条“录制”保存当前游戏画面，或导入一张截图。然后点准星中心和丢球键中心。");
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
        addButton(modeRow, "点准星", v -> setMode("reticle"));
        addButton(modeRow, "点丢球键", v -> setMode("ball"));
        addButton(modeRow, "回主界面", v -> finish());
        root.addView(modeRow);

        calibrationView = new CalibrationView(this);
        calibrationView.setPointListener(this::handlePoint);
        root.addView(calibrationView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        setContentView(root);
        updateMarks();
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
            updateStatus("已导入截图 " + imported);
        } catch (IOException ex) {
            updateStatus("导入失败: " + ex.getMessage());
        }
    }

    private void reloadImages() {
        imageFiles.clear();
        imageFiles.addAll(store.listFrames());
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
            imageView.setText("还没有截图。");
            calibrationView.setBitmap(null);
            return;
        }
        File file = imageFiles.get(currentIndex);
        currentBitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (currentBitmap == null) {
            imageView.setText("无法读取: " + file.getName());
            calibrationView.setBitmap(null);
            return;
        }
        calibrationView.setBitmap(currentBitmap);
        updateMarks();
        imageView.setText(String.format(
                Locale.US,
                "%d/%d %s  %dx%d",
                currentIndex + 1,
                imageFiles.size(),
                file.getName(),
                currentBitmap.getWidth(),
                currentBitmap.getHeight()));
    }

    private void setMode(String mode) {
        currentMode = mode;
        calibrationView.setMode(mode);
        updateStatus("reticle".equals(mode) ? "当前：点击准星中心" : "当前：点击丢球键中心");
    }

    private void handlePoint(float x, float y) {
        SharedPreferences.Editor editor = prefs.edit();
        if ("reticle".equals(currentMode)) {
            editor.putString("reticle_x", String.format(Locale.US, "%.0f", x));
            editor.putString("reticle_y", String.format(Locale.US, "%.0f", y));
            editor.apply();
            updateStatus(String.format(Locale.US, "准星坐标已保存 X=%.0f, Y=%.0f", x, y));
        } else {
            editor.putString("ball_x", String.format(Locale.US, "%.0f", x));
            editor.putString("ball_y", String.format(Locale.US, "%.0f", y));
            editor.apply();
            updateStatus(String.format(Locale.US, "丢球键坐标已保存 X=%.0f, Y=%.0f", x, y));
        }
        updateMarks();
    }

    private void updateMarks() {
        if (calibrationView == null) {
            return;
        }
        calibrationView.setMarks(
                CatchConfig.getFloat(prefs, "reticle_x", 960f),
                CatchConfig.getFloat(prefs, "reticle_y", 540f),
                CatchConfig.getFloat(prefs, "ball_x", 1720f),
                CatchConfig.getFloat(prefs, "ball_y", 860f));
        calibrationView.setMode(currentMode);
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

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TrainerActivity extends Activity {
    private static final int REQ_IMAGES = 40;

    private TrainerStore store;
    private SharedPreferences prefs;
    private AnnotationView annotationView;
    private TextView statusView;
    private TextView imageView;
    private final List<File> imageFiles = new ArrayList<>();
    private Map<String, List<Annotation>> annotations = new LinkedHashMap<>();
    private int currentIndex = 0;
    private String currentLabel = Annotation.LABEL_SPRITE;
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
        title.setText("Rock Catcher 精灵训练与坐标标定");
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("先导入截图，框选精灵用于训练；准星和扔球键只标定中心坐标。");
        statusView.setTextIsSelectable(true);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        imageView = new TextView(this);
        imageView.setTextIsSelectable(true);
        root.addView(imageView);

        LinearLayout importRow = row();
        addButton(importRow, "导入图片", v -> pickImages());
        addButton(importRow, "上一张", v -> showOffset(-1));
        addButton(importRow, "下一张", v -> showOffset(1));
        root.addView(importRow);

        LinearLayout labelRow = row();
        addButton(labelRow, "标精灵", v -> setLabel(Annotation.LABEL_SPRITE));
        addButton(labelRow, "标定准星", v -> setLabel(Annotation.LABEL_RETICLE));
        addButton(labelRow, "标定扔球键", v -> setLabel(Annotation.LABEL_BALL_BUTTON));
        root.addView(labelRow);

        annotationView = new AnnotationView(this);
        annotationView.setChangeListener(() -> updateStatus("精灵标注已变更，记得保存。"));
        annotationView.setBoxListener(this::handleBoxCreated);
        root.addView(annotationView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout actionRow = row();
        addButton(actionRow, "撤销", v -> annotationView.undoLast());
        addButton(actionRow, "保存标注", v -> saveAnnotations());
        addButton(actionRow, "自动建议", v -> autoSuggest());
        root.addView(actionRow);

        LinearLayout trainRow = row();
        addButton(trainRow, "训练并评估", v -> trainAndEvaluate());
        addButton(trainRow, "回主界面", v -> finish());
        root.addView(trainRow);

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
            updateStatus("imported images: " + imported);
        } catch (IOException ex) {
            updateStatus("import failed: " + ex.getMessage());
        }
    }

    private void reloadImages() {
        imageFiles.clear();
        imageFiles.addAll(store.listImages());
        annotations = store.loadAnnotations();
        removeCalibrationAnnotations();
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
            imageView.setText("未导入图片");
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
        List<Annotation> list = annotationsFor(file);
        annotationView.setBitmap(currentBitmap, list);
        annotationView.setCalibrationMarks(calibrationMarks());
        annotationView.setCurrentLabel(currentLabel);
        imageView.setText(String.format(
                Locale.US,
                "%d/%d %s  %dx%d  精灵框=%d  当前=%s",
                currentIndex + 1,
                imageFiles.size(),
                file.getName(),
                currentBitmap.getWidth(),
                currentBitmap.getHeight(),
                countLabel(list, Annotation.LABEL_SPRITE),
                currentLabel));
    }

    private List<Annotation> annotationsFor(File file) {
        return annotations.computeIfAbsent(file.getName(), key -> new ArrayList<>());
    }

    private void removeCalibrationAnnotations() {
        for (List<Annotation> list : annotations.values()) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (!Annotation.LABEL_SPRITE.equals(list.get(i).label)) {
                    list.remove(i);
                }
            }
        }
    }

    private void setLabel(String label) {
        currentLabel = Annotation.normalizeLabel(label);
        annotationView.setCurrentLabel(currentLabel);
        showCurrentStatus();
    }

    private void showCurrentStatus() {
        if (Annotation.LABEL_SPRITE.equals(currentLabel)) {
            updateStatus("当前：框选精灵。只有精灵会参与训练。");
        } else if (Annotation.LABEL_RETICLE.equals(currentLabel)) {
            updateStatus("当前：标定准星。点一下或拖一个小框，会把中心坐标写入主界面的准星 X/Y。");
        } else {
            updateStatus("当前：标定扔球键。点一下或拖一个小框，会把中心坐标写入主界面的扔球键 X/Y。");
        }
        if (!imageFiles.isEmpty()) {
            showCurrent();
        }
    }

    private boolean handleBoxCreated(Annotation annotation) {
        if (Annotation.LABEL_SPRITE.equals(annotation.label)) {
            return true;
        }
        float centerX = annotation.box.centerX();
        float centerY = annotation.box.centerY();
        SharedPreferences.Editor editor = prefs.edit();
        if (Annotation.LABEL_RETICLE.equals(annotation.label)) {
            editor.putString("reticle_x", String.format(Locale.US, "%.0f", centerX));
            editor.putString("reticle_y", String.format(Locale.US, "%.0f", centerY));
            editor.apply();
            annotationView.setCalibrationMarks(calibrationMarks());
            updateStatus(String.format(Locale.US, "准星已标定到 X=%.0f, Y=%.0f；它不会参与训练。", centerX, centerY));
            return false;
        }
        editor.putString("ball_x", String.format(Locale.US, "%.0f", centerX));
        editor.putString("ball_y", String.format(Locale.US, "%.0f", centerY));
        editor.apply();
        annotationView.setCalibrationMarks(calibrationMarks());
        updateStatus(String.format(Locale.US, "扔球键已标定到 X=%.0f, Y=%.0f；它不会参与训练。", centerX, centerY));
        return false;
    }

    private void saveAnnotations() {
        try {
            store.saveAnnotations(annotations);
            updateStatus("精灵标注已保存: " + store.getAnnotationsFile().getAbsolutePath());
        } catch (IOException ex) {
            updateStatus("save failed: " + ex.getMessage());
        }
    }

    private void autoSuggest() {
        if (currentBitmap == null || imageFiles.isEmpty()) {
            updateStatus("no image loaded");
            return;
        }
        try {
            TemplateModel model = TemplateModel.train(imageFiles, annotations);
            TemplateDetector detector = new TemplateDetector(model);
            CatchConfig config = CatchConfig.from(prefs);
            File file = imageFiles.get(currentIndex);
            List<Annotation> list = annotationsFor(file);
            int added = 0;
            for (String label : Annotation.TRAINABLE_LABELS) {
                if (hasLabel(list, label)) {
                    continue;
                }
                Detection detection = detector.findBestForLabel(currentBitmap, config, label, true);
                if (detection != null) {
                    list.add(new Annotation(label, new RectF(detection.box)));
                    added++;
                }
            }
            annotationView.setBitmap(currentBitmap, list);
            annotationView.setCalibrationMarks(calibrationMarks());
            updateStatus("自动建议精灵框: " + added + " 个。准星和扔球键请手动标定位置。");
        } catch (IOException ex) {
            updateStatus("自动建议需要至少 1 个已保存的精灵框。");
        }
    }

    private boolean hasLabel(List<Annotation> list, String label) {
        for (Annotation annotation : list) {
            if (annotation.label.equals(label)) {
                return true;
            }
        }
        return false;
    }

    private void trainAndEvaluate() {
        try {
            store.saveAnnotations(annotations);
            TemplateModel model = TemplateModel.train(imageFiles, annotations);
            model.save(this);
            String report = model.buildReport(this, imageFiles, annotations);
            model.saveReport(this, report);
            updateStatus("训练完成\n" + report);
        } catch (IOException ex) {
            updateStatus("training failed: " + ex.getMessage());
        }
    }

    private int countLabel(List<Annotation> list, String label) {
        int count = 0;
        for (Annotation annotation : list) {
            if (annotation.label.equals(label)) {
                count++;
            }
        }
        return count;
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

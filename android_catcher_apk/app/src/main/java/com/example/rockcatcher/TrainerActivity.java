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
        title.setText("Rock Catcher Trainer");
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("idle");
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
        addButton(labelRow, "标准星", v -> setLabel(Annotation.LABEL_RETICLE));
        addButton(labelRow, "标扔球键", v -> setLabel(Annotation.LABEL_BALL_BUTTON));
        root.addView(labelRow);

        annotationView = new AnnotationView(this);
        annotationView.setChangeListener(() -> updateStatus("changed, remember to save"));
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
            return;
        }
        File file = imageFiles.get(currentIndex);
        currentBitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (currentBitmap == null) {
            imageView.setText("无法读取: " + file.getName());
            annotationView.setBitmap(null, new ArrayList<>());
            return;
        }
        List<Annotation> list = annotationsFor(file);
        annotationView.setBitmap(currentBitmap, list);
        annotationView.setCurrentLabel(currentLabel);
        imageView.setText(String.format(
                Locale.US,
                "%d/%d %s  %dx%d  labels=%d  current=%s",
                currentIndex + 1,
                imageFiles.size(),
                file.getName(),
                currentBitmap.getWidth(),
                currentBitmap.getHeight(),
                list.size(),
                currentLabel));
    }

    private List<Annotation> annotationsFor(File file) {
        return annotations.computeIfAbsent(file.getName(), key -> new ArrayList<>());
    }

    private void setLabel(String label) {
        currentLabel = Annotation.normalizeLabel(label);
        annotationView.setCurrentLabel(currentLabel);
        showCurrentStatus();
    }

    private void showCurrentStatus() {
        updateStatus("current label: " + currentLabel);
        if (!imageFiles.isEmpty()) {
            showCurrent();
        }
    }

    private void saveAnnotations() {
        try {
            store.saveAnnotations(annotations);
            updateStatus("annotations saved: " + store.getAnnotationsFile().getAbsolutePath());
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
            for (String label : Annotation.LABELS) {
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
            updateStatus("auto suggestions added: " + added);
        } catch (IOException ex) {
            updateStatus("auto suggest needs at least one saved sprite label");
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
            updateStatus("training complete\n" + report);
        } catch (IOException ex) {
            updateStatus("training failed: " + ex.getMessage());
        }
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

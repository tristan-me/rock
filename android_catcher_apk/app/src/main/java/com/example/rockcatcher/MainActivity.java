package com.example.rockcatcher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 10;

    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private SharedPreferences prefs;
    private TextView statusView;
    private TextView accessView;
    private boolean pendingArmed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        }
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CaptureService.setStatusListener(status -> runOnUiThread(() -> statusView.setText(status)));
    }

    @Override
    protected void onStop() {
        CaptureService.setStatusListener(null);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Rock Catcher APK");
        title.setTextSize(24f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("本 APK 是本地视觉和手势原型。训练只学习精灵；准星和扔球键只用下面的 X/Y 坐标标定。\n\n"
                + "Dry Run：只截屏、识别精灵、计算应该怎么滑，不会控制手机。\n"
                + "Armed：在 Dry Run 稳定后使用，会通过无障碍服务真正发送滑动手势。");
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note);

        TextView modelPath = new TextView(this);
        File modelFile = new File(getExternalFilesDir(null), "model.tflite");
        File templateFile = TemplateModel.modelFile(this);
        modelPath.setText("YOLO 模型路径:\n" + modelFile.getAbsolutePath()
                + "\n\n端上精灵模板路径:\n" + templateFile.getAbsolutePath());
        modelPath.setTextIsSelectable(true);
        root.addView(modelPath);

        accessView = new TextView(this);
        accessView.setPadding(0, dp(12), 0, dp(4));
        root.addView(accessView);

        addButton(root, "打开无障碍设置", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(root, "导入图片 / 标注 / 训练", v -> startActivity(new Intent(this, TrainerActivity.class)));
        addButton(root, "启动 Dry Run", v -> startCapture(false));
        addButton(root, "启动 Armed", v -> startCapture(true));
        addButton(root, "停止", v -> stopService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP)));
        addButton(root, "打开应用详情", v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        addField(root, "reticle_x", "准星 X", "960");
        addField(root, "reticle_y", "准星 Y", "540");
        addField(root, "ball_x", "扔球键 X", "1720");
        addField(root, "ball_y", "扔球键 Y", "860");
        TextView calibrationNote = new TextView(this);
        calibrationNote.setText("准星和扔球键不需要训练。可以手填坐标，也可以进入训练页，在截图上点“标定准星 / 标定扔球键”自动写入中心点。");
        calibrationNote.setPadding(0, dp(6), 0, dp(4));
        root.addView(calibrationNote);
        addField(root, "confidence", "置信度", "0.45");
        addField(root, "gain_x", "X 灵敏度", "0.65");
        addField(root, "gain_y", "Y 灵敏度", "0.65");
        addField(root, "direction_x", "X 方向", "1");
        addField(root, "direction_y", "Y 方向", "1");
        addField(root, "max_step", "最大滑动步长 px", "120");
        addField(root, "release_radius", "释放半径 px", "28");
        addField(root, "template_min_score", "端上模型最低匹配分", "0.68");
        addField(root, "template_stride", "端上模型扫描步长 px", "8");
        addField(root, "template_max_side", "端上模型扫描最长边 px", "640");
        addField(root, "gesture_ms", "手势时长 ms", "420");

        statusView = new TextView(this);
        statusView.setText("idle");
        statusView.setTextSize(16f);
        statusView.setPadding(0, dp(18), 0, 0);
        statusView.setTextIsSelectable(true);
        root.addView(statusView);

        setContentView(scroll);
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        root.addView(button);
    }

    private void addField(LinearLayout root, String key, String label, String fallback) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(10), 0, 0);
        root.addView(tv);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(prefs.getString(key, fallback));
        fields.put(key, edit);
        root.addView(edit);
    }

    private void startCapture(boolean armed) {
        saveFields();
        pendingArmed = armed;
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void saveFields() {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            editor.putString(entry.getKey(), entry.getValue().getText().toString());
        }
        editor.apply();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE || resultCode != RESULT_OK || data == null) {
            statusView.setText("screen capture permission denied");
            return;
        }
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_START);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(CaptureService.EXTRA_ARMED, pendingArmed);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void refreshAccessStatus() {
        boolean enabled = isAccessibilityEnabled();
        accessView.setText(enabled ? "无障碍手势: 已启用" : "无障碍手势: 未启用");
    }

    private boolean isAccessibilityEnabled() {
        String expected = getPackageName() + "/" + CatchAccessibilityService.class.getName();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(expected.toLowerCase());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

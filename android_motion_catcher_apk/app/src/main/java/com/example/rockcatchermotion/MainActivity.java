package com.example.rockcatchermotion;

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
    private TextView framePathView;

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
        refreshFramePath();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Motion Catcher APK");
        title.setTextSize(24f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("这个版本不用 YOLO 和训练模型。它持续观察屏幕，把几秒内从一个位置移动到另一个位置的紧凑运动像素块当作精灵；亮度变化会被归一化，转身导致的颜色变化会通过轨迹连续性容忍。\n\n"
                + "先开启无障碍手势，再启动悬浮条并同意截屏授权。进游戏后用悬浮条的“抓捕 / 暂停 / 录制”。");
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note);

        framePathView = new TextView(this);
        framePathView.setTextIsSelectable(true);
        root.addView(framePathView);

        accessView = new TextView(this);
        accessView.setPadding(0, dp(12), 0, dp(4));
        root.addView(accessView);

        addButton(root, "打开无障碍设置", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(root, "标定准星 / 丢球键", v -> {
            saveFields();
            startActivity(new Intent(this, CalibrationActivity.class));
        });
        addButton(root, "启动悬浮条 / 准备接管", v -> startCapture());
        addButton(root, "停止接管服务", v -> stopService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP)));
        addButton(root, "打开应用详情", v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        addSection(root, "坐标和手势");
        addField(root, "reticle_x", "准星 X", "960");
        addField(root, "reticle_y", "准星 Y", "540");
        addField(root, "ball_x", "丢球键 X", "1720");
        addField(root, "ball_y", "丢球键 Y", "860");
        addField(root, "gain_x", "X 灵敏度", "0.65");
        addField(root, "gain_y", "Y 灵敏度", "0.65");
        addField(root, "direction_x", "X 方向：反了就填 -1", "1");
        addField(root, "direction_y", "Y 方向：反了就填 -1", "1");
        addField(root, "max_step", "最大滑动步长 px", "120");
        addField(root, "release_radius", "准星接近精灵时的停止半径 px", "28");
        addField(root, "gesture_ms", "手势时长 ms", "420");

        addSection(root, "运动识别");
        addField(root, "frame_interval_ms", "识别间隔 ms", "120");
        addField(root, "sample_stride", "采样步长 px：小更准，大更快", "14");
        addField(root, "motion_threshold", "运动阈值：误报多就调高", "20");
        addField(root, "global_change_limit", "全屏变化过滤：光影/转场误报多就调低", "0.55");
        addField(root, "history_ms", "观察历史 ms", "3500");
        addField(root, "min_jump_px", "几秒内最小移动距离 px", "180");
        addField(root, "track_link_px", "相邻帧轨迹连接距离 px", "240");
        addField(root, "min_blob_cells", "最小运动块格子数", "4");
        addField(root, "max_blob_fraction", "最大运动块占比", "0.025");
        addField(root, "max_blob_side_px", "最大运动块边长 px", "360");
        addField(root, "min_track_score", "最低轨迹分", "0.52");
        addField(root, "hold_ms", "丢失后保留目标 ms", "900");
        addField(root, "ignore_top_px", "忽略顶部 px", "0");
        addField(root, "ignore_bottom_px", "忽略底部 px", "0");
        addField(root, "ignore_reticle_radius", "忽略准星半径 px", "80");
        addField(root, "ignore_ball_radius", "忽略丢球键半径 px", "140");

        statusView = new TextView(this);
        statusView.setText("idle");
        statusView.setTextSize(16f);
        statusView.setPadding(0, dp(18), 0, 0);
        statusView.setTextIsSelectable(true);
        root.addView(statusView);

        setContentView(scroll);
        refreshFramePath();
    }

    private void addSection(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18f);
        tv.setPadding(0, dp(18), 0, dp(4));
        root.addView(tv);
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

    private void startCapture() {
        saveFields();
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
            statusView.setText("截屏授权被取消");
            return;
        }
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_START);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(CaptureService.EXTRA_ARMED, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void refreshAccessStatus() {
        boolean enabled = isAccessibilityEnabled();
        accessView.setText(enabled ? "无障碍手势：已启用" : "无障碍手势：未启用");
    }

    private boolean isAccessibilityEnabled() {
        String expected = getPackageName() + "/" + CatchAccessibilityService.class.getName();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(expected.toLowerCase());
    }

    private void refreshFramePath() {
        if (framePathView == null) {
            return;
        }
        File dir = new FrameStore(this).framesDir();
        framePathView.setText("录制截图目录:\n" + dir.getAbsolutePath());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

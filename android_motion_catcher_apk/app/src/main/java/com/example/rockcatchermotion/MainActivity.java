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
    private LinearLayout advancedPanel;
    private Button advancedButton;
    private boolean advancedVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        CatchConfig.upgradeDefaults(prefs);
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
        title.setText("Motion Catcher");
        title.setTextSize(26f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("看屏幕运动找精灵；有目标就瞄准并点击丢球键，没目标 5 秒后自动探索。");
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note);

        accessView = new TextView(this);
        accessView.setPadding(0, dp(4), 0, dp(6));
        root.addView(accessView);

        LinearLayout primaryRow = row();
        addButton(primaryRow, "无障碍", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(primaryRow, "启动悬浮条", v -> startCapture());
        root.addView(primaryRow);

        LinearLayout toolRow = row();
        addButton(toolRow, "截图标定", v -> {
            saveFields();
            startActivity(new Intent(this, CalibrationActivity.class));
        });
        addButton(toolRow, "退出截屏服务", v -> stopService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP)));
        addButton(toolRow, "应用详情", v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        root.addView(toolRow);

        addSection(root, "常用设置");
        addField(root, "reticle_x", "准星 X", "960");
        addField(root, "reticle_y", "准星 Y", "540");
        addField(root, "ball_x", "丢球键 X", "1720");
        addField(root, "ball_y", "丢球键 Y", "860");
        addField(root, "gain_x", "X 灵敏度", "0.65");
        addField(root, "gain_y", "Y 灵敏度", "0.65");
        addField(root, "direction_x", "X 方向：反了就填 -1", "1");
        addField(root, "direction_y", "Y 方向：反了就填 1", "-1");
        addField(root, "max_step", "最大滑动步长 px", "70");
        addField(root, "release_radius", "准星接近精灵时的丢球半径 px", "42");

        advancedButton = new Button(this);
        advancedButton.setAllCaps(false);
        advancedButton.setText("显示高级参数");
        advancedButton.setOnClickListener(v -> setAdvancedVisible(!advancedVisible));
        root.addView(advancedButton);

        advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(advancedPanel);

        addSection(advancedPanel, "手势和探索");
        addField(advancedPanel, "gesture_ms", "手势时长 ms", "520");
        addField(advancedPanel, "gesture_gap_ms", "两次手势间隔 ms", "220");
        addField(advancedPanel, "post_gesture_settle_ms", "手势后画面稳定等待 ms", "160");
        addField(advancedPanel, "aim_smoothing", "瞄准平滑 0-0.95：大更稳，小更灵敏", "0.55");
        addField(advancedPanel, "throw_tap_ms", "丢球点击时长 ms", "95");
        addField(advancedPanel, "throw_cooldown_ms", "丢球冷却 ms", "1300");
        addField(advancedPanel, "search_wait_ms", "无目标等待多久再探索 ms", "5000");
        addField(advancedPanel, "search_step", "探索滑动距离 px", "160");
        addField(advancedPanel, "search_gesture_ms", "探索手势时长 ms", "640");

        addSection(advancedPanel, "运动识别");
        addField(advancedPanel, "frame_interval_ms", "识别间隔 ms", "100");
        addField(advancedPanel, "sample_stride", "采样步长 px：小更准，大更快", "12");
        addField(advancedPanel, "motion_threshold", "运动阈值：误报多就调高", "16");
        addField(advancedPanel, "global_change_limit", "全屏变化过滤：光影/转场误报多就调低", "0.65");
        addField(advancedPanel, "history_ms", "观察历史 ms", "3500");
        addField(advancedPanel, "min_jump_px", "几秒内最小移动距离 px", "110");
        addField(advancedPanel, "track_link_px", "相邻帧轨迹连接距离 px", "260");
        addField(advancedPanel, "min_blob_cells", "最小运动块格子数", "4");
        addField(advancedPanel, "max_blob_fraction", "最大运动块占比", "0.025");
        addField(advancedPanel, "max_blob_side_px", "最大运动块边长 px", "360");
        addField(advancedPanel, "min_track_score", "最低轨迹分", "0.40");
        addField(advancedPanel, "hold_ms", "丢失后保留目标 ms", "1500");
        addField(advancedPanel, "ignore_top_px", "忽略顶部 px", "0");
        addField(advancedPanel, "ignore_bottom_px", "忽略底部 px", "0");
        addField(advancedPanel, "ignore_reticle_radius", "忽略准星半径 px", "80");
        addField(advancedPanel, "ignore_ball_radius", "忽略丢球键半径 px", "140");

        framePathView = new TextView(this);
        framePathView.setTextIsSelectable(true);
        framePathView.setPadding(0, dp(10), 0, 0);
        advancedPanel.addView(framePathView);

        statusView = new TextView(this);
        statusView.setText("idle");
        statusView.setTextSize(16f);
        statusView.setPadding(0, dp(18), 0, 0);
        statusView.setTextIsSelectable(true);
        root.addView(statusView);

        setContentView(scroll);
        setAdvancedVisible(false);
        refreshFramePath();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
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
        if (root.getOrientation() == LinearLayout.HORIZONTAL) {
            root.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            root.addView(button);
        }
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

    private void setAdvancedVisible(boolean visible) {
        advancedVisible = visible;
        if (advancedPanel != null) {
            advancedPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (advancedButton != null) {
            advancedButton.setText(visible ? "隐藏高级参数" : "显示高级参数");
        }
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

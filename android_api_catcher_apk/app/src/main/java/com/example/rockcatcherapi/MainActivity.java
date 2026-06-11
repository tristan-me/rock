package com.example.rockcatcherapi;

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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 10;

    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private SharedPreferences prefs;
    private TextView statusView;
    private TextView accessView;

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
        title.setText("Rock Catcher API APK");
        title.setTextSize(24f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("这个版本不使用 YOLO/TFLite。它会把当前游戏截图压缩后发送到你配置的大模型视觉 API，要求 API 返回精灵中心坐标，然后继续用同一套悬浮条和无障碍手势接管手机。\n\n"
                + "先填 API，再标定准星和扔球键；点“启动悬浮条 / 准备接管”授权截屏。进入游戏后，悬浮条里的“抓捕”才会真正控制屏幕，“暂停”只识别不操作。");
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note);

        accessView = new TextView(this);
        accessView.setPadding(0, dp(12), 0, dp(4));
        root.addView(accessView);

        addButton(root, "打开无障碍设置", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(root, "标定准星 / 标定扔球键", v -> startActivity(new Intent(this, CalibrationActivity.class)));
        addButton(root, "启动悬浮条 / 准备接管", v -> startCapture());
        addButton(root, "停止接管服务", v -> stopService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP)));
        addButton(root, "打开应用详情", v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        addSection(root, "大模型 API");
        addField(root, "api_url", "API URL", "https://api.deepseek.com/chat/completions", false);
        addField(root, "api_key", "API Key", "", true);
        addField(root, "api_model", "模型名", "deepseek-v4-flash", false);
        addField(root, "api_interval_ms", "请求间隔 ms", "2500", false);
        addField(root, "api_timeout_ms", "单次超时 ms", "15000", false);
        addField(root, "api_image_max_side", "发送图片最长边 px", "768", false);
        addField(root, "api_jpeg_quality", "发送图片 JPEG 质量", "70", false);
        TextView apiNote = new TextView(this);
        apiNote.setText("API 必须支持图片输入，并且最好支持 OpenAI 兼容的 chat/completions 图片消息格式。返回慢时可以增大请求间隔，减少误触；截图会发送给 API 服务商。");
        apiNote.setPadding(0, dp(6), 0, dp(4));
        root.addView(apiNote);

        addSection(root, "坐标标定");
        addField(root, "reticle_x", "准星 X", "960", false);
        addField(root, "reticle_y", "准星 Y", "540", false);
        addField(root, "ball_x", "扔球键 X", "1720", false);
        addField(root, "ball_y", "扔球键 Y", "860", false);
        TextView calibrationNote = new TextView(this);
        calibrationNote.setText("准星和扔球键不需要训练，只需要标定中心坐标。也可以在截图标定页里点中心点自动写入。");
        calibrationNote.setPadding(0, dp(6), 0, dp(4));
        root.addView(calibrationNote);

        addSection(root, "手势调参");
        TextView sensitivityNote = new TextView(this);
        sensitivityNote.setText("长按并移动扔球键会带动游戏视角，所以仍然要按你的手机和游戏灵敏度调 X/Y 灵敏度、方向、最大步长。悬浮条“录制”会保存当前画面，方便回看坐标和 API 识别效果。");
        sensitivityNote.setPadding(0, dp(6), 0, dp(4));
        root.addView(sensitivityNote);
        addField(root, "confidence", "最低置信度", "0.45", false);
        addField(root, "gain_x", "X 灵敏度", "0.65", false);
        addField(root, "gain_y", "Y 灵敏度", "0.65", false);
        addField(root, "direction_x", "X 方向", "1", false);
        addField(root, "direction_y", "Y 方向", "1", false);
        addField(root, "max_step", "最大滑动步长 px", "120", false);
        addField(root, "release_radius", "释放半径 px", "28", false);
        addField(root, "gesture_ms", "手势时长 ms", "420", false);

        statusView = new TextView(this);
        statusView.setText("idle");
        statusView.setTextSize(16f);
        statusView.setPadding(0, dp(18), 0, 0);
        statusView.setTextIsSelectable(true);
        root.addView(statusView);

        setContentView(scroll);
    }

    private void addSection(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18f);
        tv.setPadding(0, dp(18), 0, dp(2));
        root.addView(tv);
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        root.addView(button);
    }

    private void addField(LinearLayout root, String key, String label, String fallback, boolean password) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(10), 0, 0);
        root.addView(tv);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(prefs.getString(key, fallback));
        if (password) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

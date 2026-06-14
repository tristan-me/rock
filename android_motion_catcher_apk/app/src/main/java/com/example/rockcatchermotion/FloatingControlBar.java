package com.example.rockcatchermotion;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class FloatingControlBar extends LinearLayout {
    interface Controller {
        void onCaptureToggle(boolean active);
        void onStop();
        void onRecord();
        void onSaveFrame();
        void onMoved(int x, int y);
    }

    private final Controller controller;
    private final TextView handle;
    private final Button captureButton;
    private boolean captureActive = false;
    private float downRawX;
    private float downRawY;
    private int startX;
    private int startY;

    FloatingControlBar(Context context, Controller controller) {
        super(context);
        this.controller = controller;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(8), dp(5), dp(8), dp(5));
        setBackground(makeBackground());

        handle = new TextView(context);
        handle.setText("Move");
        handle.setTextColor(Color.WHITE);
        handle.setTextSize(12f);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(0, 0, dp(6), 0);
        handle.setOnTouchListener(this::handleDragTouch);
        addView(handle, new LayoutParams(dp(46), dp(34)));

        captureButton = button("抓捕");
        captureButton.setOnClickListener(v -> toggleCapture());
        addView(captureButton);

        Button stopButton = button("停止");
        stopButton.setOnClickListener(v -> controller.onStop());
        addView(stopButton);

        Button recordButton = button("标定");
        recordButton.setOnClickListener(v -> controller.onRecord());
        recordButton.setOnLongClickListener(v -> {
            controller.onSaveFrame();
            return true;
        });
        addView(recordButton);
    }

    void setCaptureActive(boolean active) {
        captureActive = active;
        captureButton.setText(active ? "暂停" : "抓捕");
    }

    private void toggleCapture() {
        setCaptureActive(!captureActive);
        controller.onCaptureToggle(captureActive);
    }

    private boolean handleDragTouch(View view, MotionEvent event) {
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) getLayoutParams();
        if (params == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startX = params.x;
                startY = params.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = Math.round(event.getRawX() - downRawX);
                int dy = Math.round(event.getRawY() - downRawY);
                if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) {
                    params.x = Math.max(0, startX + dx);
                    params.y = Math.max(0, startY + dy);
                    controller.onMoved(params.x, params.y);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private Button button(String text) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(34));
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable makeBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(225, 25, 36, 42));
        drawable.setStroke(dp(1), Color.argb(220, 255, 255, 255));
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

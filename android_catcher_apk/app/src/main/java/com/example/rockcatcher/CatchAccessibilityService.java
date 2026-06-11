package com.example.rockcatcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public final class CatchAccessibilityService extends AccessibilityService {
    private static volatile CatchAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private GestureOverlayView overlayView;

    static boolean isReady() {
        return instance != null;
    }

    static boolean performSwipe(float startX, float startY, float endX, float endY, int durationMs) {
        CatchAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(80, durationMs));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean sent = service.dispatchGesture(gesture, null, null);
        if (sent) {
            service.showGesture(startX, startY, endX, endY, durationMs);
        }
        return sent;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mainHandler.post(this::ensureOverlay);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        mainHandler.post(this::removeOverlay);
        super.onDestroy();
    }

    private void showGesture(float startX, float startY, float endX, float endY, int durationMs) {
        mainHandler.post(() -> {
            ensureOverlay();
            if (overlayView != null) {
                overlayView.addGesture(startX, startY, endX, endY, durationMs);
            }
        });
    }

    private void ensureOverlay() {
        if (overlayView != null) {
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        overlayView = new GestureOverlayView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(overlayView, params);
    }

    private void removeOverlay() {
        if (overlayView != null) {
            overlayView.clear();
            if (windowManager != null) {
                windowManager.removeView(overlayView);
            }
            overlayView = null;
        }
        windowManager = null;
    }
}

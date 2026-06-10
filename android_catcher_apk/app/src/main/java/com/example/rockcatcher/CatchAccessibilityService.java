package com.example.rockcatcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public final class CatchAccessibilityService extends AccessibilityService {
    private static volatile CatchAccessibilityService instance;

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
        return service.dispatchGesture(gesture, null, null);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
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
        super.onDestroy();
    }
}

package com.example.rockcatchermotion;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public final class CatchAccessibilityService extends AccessibilityService {
    private static volatile CatchAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private GestureOverlayView overlayView;
    private LiveCalibrationOverlayView calibrationOverlayView;
    private FloatingControlBar controlBar;
    private WindowManager.LayoutParams controlParams;
    private SharedPreferences prefs;

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
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        CatchConfig.upgradeDefaults(prefs);
        mainHandler.post(() -> {
            ensureGestureOverlay();
            ensureControlBar();
        });
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
            ensureGestureOverlay();
            if (overlayView != null) {
                overlayView.addGesture(startX, startY, endX, endY, durationMs);
            }
        });
    }

    private void ensureGestureOverlay() {
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

    private void ensureControlBar() {
        if (controlBar != null) {
            return;
        }
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) {
            return;
        }
        controlBar = new FloatingControlBar(this, new FloatingControlBar.Controller() {
            @Override
            public void onCaptureToggle(boolean active) {
                sendCaptureAction(active ? CaptureService.ACTION_ARM : CaptureService.ACTION_PAUSE);
            }

            @Override
            public void onStop() {
                if (controlBar != null) {
                    controlBar.setCaptureActive(false);
                }
                sendCaptureAction(CaptureService.ACTION_STOP);
            }

            @Override
            public void onRecord() {
                toggleCalibrationOverlay();
            }

            @Override
            public void onSaveFrame() {
                sendCaptureAction(CaptureService.ACTION_RECORD);
            }

            @Override
            public void onMoved(int x, int y) {
                if (controlParams != null && windowManager != null && controlBar != null) {
                    controlParams.x = x;
                    controlParams.y = y;
                    windowManager.updateViewLayout(controlBar, controlParams);
                    prefs.edit()
                            .putString("bar_x", Integer.toString(x))
                            .putString("bar_y", Integer.toString(y))
                            .apply();
                }
            }
        });
        controlParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        controlParams.gravity = Gravity.TOP | Gravity.START;
        controlParams.x = Math.max(0, Math.round(CatchConfig.getFloat(prefs, "bar_x", 24f)));
        controlParams.y = Math.max(0, Math.round(CatchConfig.getFloat(prefs, "bar_y", 120f)));
        windowManager.addView(controlBar, controlParams);
    }

    private void sendCaptureAction(String action) {
        if (!CaptureService.isRunning() && !CaptureService.ACTION_STOP.equals(action)) {
            Toast.makeText(this, "请先回 Motion Catcher 点“启动悬浮条 / 准备接管”并授权截屏", Toast.LENGTH_LONG).show();
            if (controlBar != null) {
                controlBar.setCaptureActive(false);
            }
            return;
        }
        Intent intent = new Intent(this, CaptureService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void toggleCalibrationOverlay() {
        mainHandler.post(() -> {
            if (calibrationOverlayView != null) {
                removeCalibrationOverlay();
            } else {
                ensureCalibrationOverlay();
            }
        });
    }

    private void ensureCalibrationOverlay() {
        if (calibrationOverlayView != null) {
            return;
        }
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) {
            return;
        }
        calibrationOverlayView = new LiveCalibrationOverlayView(this);
        calibrationOverlayView.setListener(new LiveCalibrationOverlayView.Listener() {
            @Override
            public void onDone() {
                removeCalibrationOverlay();
            }

            @Override
            public void onStatus(String status) {
                Toast.makeText(CatchAccessibilityService.this, status, Toast.LENGTH_SHORT).show();
            }
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(calibrationOverlayView, params);
        Toast.makeText(this, "拖动准星和丢球键，完成后点上方“完成”", Toast.LENGTH_LONG).show();
    }

    private void removeCalibrationOverlay() {
        if (calibrationOverlayView != null) {
            if (windowManager != null) {
                windowManager.removeView(calibrationOverlayView);
            }
            calibrationOverlayView = null;
        }
    }

    private void removeOverlay() {
        removeCalibrationOverlay();
        if (overlayView != null) {
            overlayView.clear();
            if (windowManager != null) {
                windowManager.removeView(overlayView);
            }
            overlayView = null;
        }
        if (controlBar != null) {
            if (windowManager != null) {
                windowManager.removeView(controlBar);
            }
            controlBar = null;
            controlParams = null;
        }
        windowManager = null;
    }
}

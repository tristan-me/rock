package com.example.rockcatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureService extends Service {
    static final String ACTION_START = "com.example.rockcatcher.START";
    static final String ACTION_STOP = "com.example.rockcatcher.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    static final String EXTRA_ARMED = "armed";

    interface StatusListener {
        void onStatus(String status);
    }

    private static volatile StatusListener listener;

    static void setStatusListener(StatusListener statusListener) {
        listener = statusListener;
    }

    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TfLiteYoloDetector detector;
    private SharedPreferences prefs;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private volatile boolean armed = false;
    private long lastGestureMs = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        detector = new TfLiteYoloDetector(this);
        thread = new HandlerThread("capture-loop");
        thread.start();
        handler = new Handler(thread.getLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            armed = intent.getBooleanExtra(EXTRA_ARMED, false);
            startForeground(1, buildNotification(armed));
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startProjection(resultCode, data);
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent data) {
        stopProjection();
        if (data == null) {
            emit("missing screen capture permission data");
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            emit("failed to create MediaProjection");
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                emit("projection stopped");
            }
        }, handler);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);
        virtualDisplay = projection.createVirtualDisplay(
                "RockCatcherCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler);
        emit("capture started " + width + "x" + height + (armed ? " armed" : " dry-run"));
    }

    private void onImageAvailable(ImageReader reader) {
        if (!processing.compareAndSet(false, true)) {
            Image stale = reader.acquireLatestImage();
            if (stale != null) {
                stale.close();
            }
            return;
        }
        Image image = reader.acquireLatestImage();
        if (image == null) {
            processing.set(false);
            return;
        }
        try {
            Bitmap bitmap = imageToBitmap(image);
            processFrame(bitmap);
            bitmap.recycle();
        } catch (Exception ex) {
            emit("frame error: " + ex.getMessage());
        } finally {
            image.close();
            processing.set(false);
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private void processFrame(Bitmap bitmap) {
        CatchConfig config = CatchConfig.from(prefs);
        DetectionResult result = detector.detect(bitmap, config);
        if (!result.hasTarget()) {
            emit((armed ? "armed " : "dry ") + result.status);
            return;
        }

        Detection reticle = result.reticle;
        Detection ball = result.ballButton;
        float errorX = result.sprite.centerX() - reticle.centerX();
        float errorY = result.sprite.centerY() - reticle.centerY();
        float distance = (float) Math.hypot(errorX, errorY);
        float stepX = errorX * config.gainX * config.directionX;
        float stepY = errorY * config.gainY * config.directionY;
        float stepLength = (float) Math.hypot(stepX, stepY);
        if (stepLength > config.maxStepPx && config.maxStepPx > 0) {
            float scale = config.maxStepPx / stepLength;
            stepX *= scale;
            stepY *= scale;
        }
        if (distance <= config.releaseRadiusPx) {
            stepX = 0f;
            stepY = 0f;
        }

        float endX = clamp(ball.centerX() + stepX, 0, result.frameWidth - 1);
        float endY = clamp(ball.centerY() + stepY, 0, result.frameHeight - 1);
        String status = String.format(
                Locale.US,
                "%s dist=%.1f step=(%.0f,%.0f) ball=(%.0f,%.0f)->(%.0f,%.0f)",
                armed ? "armed" : "dry-run only",
                distance,
                stepX,
                stepY,
                ball.centerX(),
                ball.centerY(),
                endX,
                endY);

        if (armed) {
            long now = System.currentTimeMillis();
            if (!CatchAccessibilityService.isReady()) {
                status += " accessibility disabled";
            } else if (now - lastGestureMs >= config.gestureDurationMs + 120L) {
                boolean sent = CatchAccessibilityService.performSwipe(
                        ball.centerX(),
                        ball.centerY(),
                        endX,
                        endY,
                        config.gestureDurationMs);
                lastGestureMs = now;
                status += sent ? " gesture sent" : " gesture failed";
            }
        }
        emit(status);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void stopProjection() {
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "capture",
                    "Rock Catcher Capture",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(boolean armedMode) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, "capture")
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Rock Catcher")
                .setContentText(armedMode ? "Armed screen loop running" : "Dry-run screen loop running")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }

    private void emit(String status) {
        StatusListener current = listener;
        if (current != null) {
            current.onStatus(status);
        }
    }

    @Override
    public void onDestroy() {
        stopProjection();
        if (detector != null) {
            detector.close();
        }
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

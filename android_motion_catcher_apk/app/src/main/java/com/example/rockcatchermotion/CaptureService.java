package com.example.rockcatchermotion;

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

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureService extends Service {
    static final String ACTION_START = "com.example.rockcatchermotion.START";
    static final String ACTION_ARM = "com.example.rockcatchermotion.ARM";
    static final String ACTION_PAUSE = "com.example.rockcatchermotion.PAUSE";
    static final String ACTION_RECORD = "com.example.rockcatchermotion.RECORD";
    static final String ACTION_STOP = "com.example.rockcatchermotion.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    static final String EXTRA_ARMED = "armed";

    interface StatusListener {
        void onStatus(String status);
    }

    private static volatile StatusListener listener;
    private static volatile boolean running = false;

    static void setStatusListener(StatusListener statusListener) {
        listener = statusListener;
    }

    static boolean isRunning() {
        return running;
    }

    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MotionSpriteDetector detector;
    private SharedPreferences prefs;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private volatile boolean armed = false;
    private volatile boolean recordNextFrame = false;
    private long lastGestureMs = 0L;
    private long lastProcessMs = 0L;
    private Bitmap latestBitmap;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        detector = new MotionSpriteDetector();
        thread = new HandlerThread("motion-capture-loop");
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
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ARM.equals(action)) {
            armed = true;
            emit("抓捕已开启：检测到运动精灵后会发送滑动手势。");
            return START_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            armed = false;
            emit("抓捕已暂停：继续看屏幕，但不会控制手机。");
            return START_STICKY;
        }
        if (ACTION_RECORD.equals(action)) {
            recordNextFrame = true;
            saveLatestFrameIfAvailable();
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            armed = intent.getBooleanExtra(EXTRA_ARMED, false);
            running = true;
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
            emit("缺少截屏授权数据");
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            emit("无法创建 MediaProjection");
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                emit("截屏服务已停止");
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
                "MotionCatcherCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler);
        emit("悬浮条已准备 " + width + "x" + height + (armed ? "，抓捕中" : "，待命中"));
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
            rememberLatestFrame(bitmap);
            if (recordNextFrame) {
                recordNextFrame = false;
                saveFrame(bitmap);
            }
            CatchConfig config = CatchConfig.from(prefs);
            long now = System.currentTimeMillis();
            if (now - lastProcessMs >= config.frameIntervalMs) {
                lastProcessMs = now;
                processFrame(bitmap, config);
            }
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

    private void processFrame(Bitmap bitmap, CatchConfig config) {
        DetectionResult result = detector.detect(bitmap, config);
        if (!result.hasTarget()) {
            emit((armed ? "抓捕中 " : "待命 ") + result.status);
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
                "%s %s dist=%.1f step=(%.0f,%.0f) ball=(%.0f,%.0f)->(%.0f,%.0f)",
                armed ? "抓捕中" : "待命识别",
                result.status,
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
        forgetLatestFrame();
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
                    "Motion Catcher Capture",
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
                .setContentTitle("Motion Catcher")
                .setContentText(armedMode ? "抓捕运行中" : "悬浮条待命中")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }

    private void rememberLatestFrame(Bitmap bitmap) {
        synchronized (this) {
            if (latestBitmap != null) {
                latestBitmap.recycle();
            }
            latestBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    private void forgetLatestFrame() {
        synchronized (this) {
            if (latestBitmap != null) {
                latestBitmap.recycle();
                latestBitmap = null;
            }
        }
    }

    private void saveLatestFrameIfAvailable() {
        Bitmap copy = null;
        synchronized (this) {
            if (latestBitmap != null) {
                copy = latestBitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
        }
        if (copy == null) {
            emit("已请求录制：等待下一帧截屏。");
            return;
        }
        try {
            saveFrame(copy);
        } finally {
            copy.recycle();
        }
    }

    private void saveFrame(Bitmap bitmap) {
        try {
            File file = new FrameStore(this).saveBitmap(bitmap, "record");
            emit("已录制当前画面 " + file.getAbsolutePath());
        } catch (Exception ex) {
            emit("录制失败: " + ex.getMessage());
        }
    }

    private void emit(String status) {
        StatusListener current = listener;
        if (current != null) {
            current.onStatus(status);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        stopProjection();
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

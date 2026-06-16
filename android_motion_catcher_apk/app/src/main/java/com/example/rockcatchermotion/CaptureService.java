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
import java.util.Random;
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
    private final Random random = new Random();
    private volatile boolean armed = false;
    private volatile boolean recordNextFrame = false;
    private long lastGestureMs = 0L;
    private long lastProcessMs = 0L;
    private long noTargetSinceMs = 0L;
    private long lastSearchMs = 0L;
    private long lastThrowMs = 0L;
    private int searchSweepIndex = 0;
    private float smoothedStepX = 0f;
    private float smoothedStepY = 0f;
    private Bitmap latestBitmap;
    private volatile boolean projectionStopExpected = false;
    private volatile int projectionGeneration = 0;

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
            armed = false;
            running = false;
            projectionStopExpected = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ARM.equals(action)) {
            armed = true;
            noTargetSinceMs = 0L;
            resetAimState();
            emit("抓捕已开启：先识别精灵，识别不到 5 秒后再探索移动。");
            return START_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            armed = false;
            resetAimState();
            emit("已释放接管：悬浮条和截屏会话保留，再点抓捕可直接恢复。");
            return START_STICKY;
        }
        if (ACTION_RECORD.equals(action)) {
            recordNextFrame = true;
            saveLatestFrameIfAvailable();
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            armed = intent.getBooleanExtra(EXTRA_ARMED, false);
            running = false;
            projectionStopExpected = false;
            startForeground(1, buildNotification(armed));
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startProjection(resultCode, data);
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent data) {
        projectionGeneration++;
        if (projection != null || virtualDisplay != null || imageReader != null) {
            projectionStopExpected = true;
            releaseProjectionResources(true);
        }
        projectionStopExpected = false;
        if (data == null) {
            running = false;
            emit("缺少截屏授权数据");
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            running = false;
            emit("无法创建 MediaProjection");
            return;
        }
        final int generation = projectionGeneration;
        running = true;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                handler.post(() -> handleProjectionStopped(generation));
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

    private void handleProjectionStopped(int generation) {
        if (generation != projectionGeneration) {
            return;
        }
        boolean expected = projectionStopExpected;
        projectionStopExpected = false;
        armed = false;
        running = false;
        resetAimState();
        detector.reset();
        releaseProjectionResources(false);
        if (expected) {
            emit("截屏服务已退出");
        } else {
            emit("系统停止了截屏，通常是系统录屏占用了投屏通道；请回 Motion Catcher 重新授权一次。");
            stopSelf();
        }
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
            long settleUntilMs = config.gestureDurationMs + config.postGestureSettleMs;
            if (armed && lastGestureMs > 0L && now - lastGestureMs < settleUntilMs) {
                emit("手势后等待画面稳定 " + (settleUntilMs - (now - lastGestureMs)) + "ms");
                bitmap.recycle();
                return;
            }
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
            decaySmoothedStep();
            handleNoTarget(result, config);
            return;
        }
        noTargetSinceMs = 0L;

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
        float follow = 1f - config.aimSmoothing;
        smoothedStepX = smoothedStepX * config.aimSmoothing + stepX * follow;
        smoothedStepY = smoothedStepY * config.aimSmoothing + stepY * follow;
        if (Math.abs(smoothedStepX) < 1f) {
            smoothedStepX = 0f;
        }
        if (Math.abs(smoothedStepY) < 1f) {
            smoothedStepY = 0f;
        }
        stepX = smoothedStepX;
        stepY = smoothedStepY;

        float aimStartX = clamp(reticle.centerX(), 0, result.frameWidth - 1);
        float aimStartY = clamp(reticle.centerY(), 0, result.frameHeight - 1);
        float endX = clamp(aimStartX + stepX, 0, result.frameWidth - 1);
        float endY = clamp(aimStartY + stepY, 0, result.frameHeight - 1);
        String status = String.format(
                Locale.US,
                "%s %s dist=%.1f aim=(%.0f,%.0f)->(%.0f,%.0f)",
                armed ? "抓捕中" : "待命识别",
                result.status,
                distance,
                aimStartX,
                aimStartY,
                endX,
                endY);

        if (armed) {
            long now = System.currentTimeMillis();
            if (!CatchAccessibilityService.isReady()) {
                status += " accessibility disabled";
            } else if (distance <= config.releaseRadiusPx) {
                status += tryThrow(ball, config, now);
            } else if (now - lastGestureMs >= config.gestureDurationMs + config.gestureGapMs) {
                boolean sent = CatchAccessibilityService.performSwipe(
                        aimStartX,
                        aimStartY,
                        endX,
                        endY,
                        config.gestureDurationMs);
                lastGestureMs = now;
                status += sent ? " aim swipe sent" : " aim swipe failed";
            } else {
                status += " waiting gesture gap";
            }
        }
        emit(status);
    }

    private void handleNoTarget(DetectionResult result, CatchConfig config) {
        long now = System.currentTimeMillis();
        if (!armed) {
            emit("待命 " + result.status);
            return;
        }
        if (noTargetSinceMs == 0L) {
            noTargetSinceMs = now;
        }
        long waitMs = now - noTargetSinceMs;
        String status = String.format(
                Locale.US,
                "抓捕中 %s no-target=%.1fs",
                result.status,
                waitMs / 1000f);
        if (!CatchAccessibilityService.isReady()) {
            emit(status + " accessibility disabled");
            return;
        }
        long minGap = Math.max(config.searchGestureMs + config.gestureGapMs, config.gestureDurationMs + config.gestureGapMs);
        if (waitMs >= config.searchWaitMs && now - lastSearchMs >= minGap && now - lastGestureMs >= minGap) {
            int direction = chooseSearchDirection();
            SearchMove move = searchMove(direction, result, config);
            boolean sent = CatchAccessibilityService.performSwipe(
                    move.startX,
                    move.startY,
                    move.endX,
                    move.endY,
                    config.searchGestureMs);
            lastSearchMs = now;
            lastGestureMs = now;
            noTargetSinceMs = now;
            smoothedStepX = 0f;
            smoothedStepY = 0f;
            detector.reset();
            status += sent
                    ? String.format(Locale.US, " search %s %.0fpx", move.name, move.distance)
                    : " search failed";
        }
        emit(status);
    }

    private String tryThrow(Detection ball, CatchConfig config, long now) {
        if (now - lastGestureMs < config.gestureDurationMs + config.gestureGapMs) {
            return " aligned, waiting gesture gap";
        }
        if (now - lastThrowMs < config.throwCooldownMs) {
            return " aligned, waiting throw cooldown";
        }
        boolean sent = CatchAccessibilityService.performTap(ball.centerX(), ball.centerY(), config.throwTapMs);
        lastThrowMs = now;
        lastGestureMs = now;
        smoothedStepX = 0f;
        smoothedStepY = 0f;
        detector.reset();
        return sent ? " aligned, throw tap sent" : " aligned, throw tap failed";
    }

    private void decaySmoothedStep() {
        smoothedStepX *= 0.65f;
        smoothedStepY *= 0.65f;
        if (Math.abs(smoothedStepX) < 1f) {
            smoothedStepX = 0f;
        }
        if (Math.abs(smoothedStepY) < 1f) {
            smoothedStepY = 0f;
        }
    }

    private void resetAimState() {
        noTargetSinceMs = 0L;
        lastSearchMs = 0L;
        lastGestureMs = 0L;
        searchSweepIndex = 0;
        smoothedStepX = 0f;
        smoothedStepY = 0f;
    }

    private int chooseSearchDirection() {
        int[] horizontalSweep = {0, 0, 2, 2};
        int direction = horizontalSweep[searchSweepIndex % horizontalSweep.length];
        searchSweepIndex++;
        return direction;
    }

    private SearchMove searchMove(int direction, DetectionResult result, CatchConfig config) {
        float startX = clamp(result.reticle.centerX(), 0, result.frameWidth - 1);
        float startY = clamp(result.reticle.centerY(), result.frameHeight * 0.30f, result.frameHeight * 0.72f);
        float distance = Math.min(config.searchStepPx, Math.max(40f, Math.min(result.frameWidth, result.frameHeight) * 0.24f));
        distance *= 0.86f + random.nextFloat() * 0.18f;
        float dx = 0f;
        String name;
        if (direction == 0) {
            dx = distance;
            name = "pan-right";
        } else {
            dx = -distance;
            name = "pan-left";
        }
        float endX = clamp(startX + dx, 0, result.frameWidth - 1);
        float endY = startY;
        if (Math.abs(endX - startX) < 8f) {
            startX = result.frameWidth * 0.5f;
            startY = result.frameHeight * 0.56f;
            endX = clamp(startX + dx, 0, result.frameWidth - 1);
            endY = startY;
        }
        return new SearchMove(startX, startY, endX, endY, distance, name);
    }

    private static final class SearchMove {
        final float startX;
        final float startY;
        final float endX;
        final float endY;
        final float distance;
        final String name;

        SearchMove(float startX, float startY, float endX, float endY, float distance, String name) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.distance = distance;
            this.name = name;
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void stopProjection() {
        projectionStopExpected = true;
        releaseProjectionResources(true);
    }

    private void releaseProjectionResources(boolean stopProjection) {
        forgetLatestFrame();
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        MediaProjection currentProjection = projection;
        projection = null;
        if (currentProjection != null && stopProjection) {
            currentProjection.stop();
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
        armed = false;
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

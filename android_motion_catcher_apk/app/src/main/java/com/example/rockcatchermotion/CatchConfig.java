package com.example.rockcatchermotion;

import android.content.SharedPreferences;

final class CatchConfig {
    final float fallbackReticleX;
    final float fallbackReticleY;
    final float fallbackBallX;
    final float fallbackBallY;
    final float gainX;
    final float gainY;
    final float directionX;
    final float directionY;
    final float maxStepPx;
    final float releaseRadiusPx;
    final int gestureDurationMs;
    final int gestureGapMs;
    final int postGestureSettleMs;
    final float aimSmoothing;
    final int throwTapMs;
    final int throwCooldownMs;
    final int searchWaitMs;
    final int searchStepPx;
    final int searchGestureMs;

    final int frameIntervalMs;
    final int sampleStridePx;
    final float motionThreshold;
    final float globalChangeLimit;
    final int historyMs;
    final int minJumpPx;
    final int trackLinkPx;
    final int minBlobCells;
    final float maxBlobFraction;
    final int maxBlobSidePx;
    final float minTrackScore;
    final int holdMs;
    final int ignoreTopPx;
    final int ignoreBottomPx;
    final int ignoreReticleRadiusPx;
    final int ignoreBallRadiusPx;

    private CatchConfig(
            float fallbackReticleX,
            float fallbackReticleY,
            float fallbackBallX,
            float fallbackBallY,
            float gainX,
            float gainY,
            float directionX,
            float directionY,
            float maxStepPx,
            float releaseRadiusPx,
            int gestureDurationMs,
            int gestureGapMs,
            int postGestureSettleMs,
            float aimSmoothing,
            int throwTapMs,
            int throwCooldownMs,
            int searchWaitMs,
            int searchStepPx,
            int searchGestureMs,
            int frameIntervalMs,
            int sampleStridePx,
            float motionThreshold,
            float globalChangeLimit,
            int historyMs,
            int minJumpPx,
            int trackLinkPx,
            int minBlobCells,
            float maxBlobFraction,
            int maxBlobSidePx,
            float minTrackScore,
            int holdMs,
            int ignoreTopPx,
            int ignoreBottomPx,
            int ignoreReticleRadiusPx,
            int ignoreBallRadiusPx) {
        this.fallbackReticleX = fallbackReticleX;
        this.fallbackReticleY = fallbackReticleY;
        this.fallbackBallX = fallbackBallX;
        this.fallbackBallY = fallbackBallY;
        this.gainX = gainX;
        this.gainY = gainY;
        this.directionX = directionX;
        this.directionY = directionY;
        this.maxStepPx = maxStepPx;
        this.releaseRadiusPx = releaseRadiusPx;
        this.gestureDurationMs = gestureDurationMs;
        this.gestureGapMs = gestureGapMs;
        this.postGestureSettleMs = postGestureSettleMs;
        this.aimSmoothing = aimSmoothing;
        this.throwTapMs = throwTapMs;
        this.throwCooldownMs = throwCooldownMs;
        this.searchWaitMs = searchWaitMs;
        this.searchStepPx = searchStepPx;
        this.searchGestureMs = searchGestureMs;
        this.frameIntervalMs = frameIntervalMs;
        this.sampleStridePx = sampleStridePx;
        this.motionThreshold = motionThreshold;
        this.globalChangeLimit = globalChangeLimit;
        this.historyMs = historyMs;
        this.minJumpPx = minJumpPx;
        this.trackLinkPx = trackLinkPx;
        this.minBlobCells = minBlobCells;
        this.maxBlobFraction = maxBlobFraction;
        this.maxBlobSidePx = maxBlobSidePx;
        this.minTrackScore = minTrackScore;
        this.holdMs = holdMs;
        this.ignoreTopPx = ignoreTopPx;
        this.ignoreBottomPx = ignoreBottomPx;
        this.ignoreReticleRadiusPx = ignoreReticleRadiusPx;
        this.ignoreBallRadiusPx = ignoreBallRadiusPx;
    }

    static CatchConfig from(SharedPreferences prefs) {
        upgradeDefaults(prefs);
        return new CatchConfig(
                getFloat(prefs, "reticle_x", 960f),
                getFloat(prefs, "reticle_y", 540f),
                getFloat(prefs, "ball_x", 1720f),
                getFloat(prefs, "ball_y", 860f),
                getFloat(prefs, "gain_x", 0.65f),
                getFloat(prefs, "gain_y", 0.65f),
                getFloat(prefs, "direction_x", 1f),
                getFloat(prefs, "direction_y", -1f),
                getFloat(prefs, "max_step", 70f),
                getFloat(prefs, "release_radius", 42f),
                Math.max(80, (int) getFloat(prefs, "gesture_ms", 520f)),
                clampInt((int) getFloat(prefs, "gesture_gap_ms", 220f), 0, 1500),
                clampInt((int) getFloat(prefs, "post_gesture_settle_ms", 160f), 0, 1200),
                clamp(getFloat(prefs, "aim_smoothing", 0.55f), 0f, 0.95f),
                clampInt((int) getFloat(prefs, "throw_tap_ms", 95f), 40, 300),
                clampInt((int) getFloat(prefs, "throw_cooldown_ms", 1300f), 300, 5000),
                clampInt((int) getFloat(prefs, "search_wait_ms", 5000f), 1200, 20000),
                clampInt((int) getFloat(prefs, "search_step", 220f), 40, 900),
                clampInt((int) getFloat(prefs, "search_gesture_ms", 520f), 120, 1600),
                Math.max(60, (int) getFloat(prefs, "frame_interval_ms", 100f)),
                clampInt((int) getFloat(prefs, "sample_stride", 14f), 6, 36),
                getFloat(prefs, "motion_threshold", 18f),
                clamp(getFloat(prefs, "global_change_limit", 0.65f), 0.08f, 0.95f),
                clampInt((int) getFloat(prefs, "history_ms", 3500f), 800, 8000),
                clampInt((int) getFloat(prefs, "min_jump_px", 130f), 20, 1200),
                clampInt((int) getFloat(prefs, "track_link_px", 240f), 40, 1200),
                clampInt((int) getFloat(prefs, "min_blob_cells", 4f), 1, 80),
                clamp(getFloat(prefs, "max_blob_fraction", 0.025f), 0.002f, 0.25f),
                clampInt((int) getFloat(prefs, "max_blob_side_px", 360f), 40, 1600),
                clamp(getFloat(prefs, "min_track_score", 0.44f), 0.05f, 0.95f),
                clampInt((int) getFloat(prefs, "hold_ms", 1200f), 0, 3000),
                Math.max(0, (int) getFloat(prefs, "ignore_top_px", 0f)),
                Math.max(0, (int) getFloat(prefs, "ignore_bottom_px", 0f)),
                Math.max(0, (int) getFloat(prefs, "ignore_reticle_radius", 80f)),
                Math.max(0, (int) getFloat(prefs, "ignore_ball_radius", 140f)));
    }

    static void upgradeDefaults(SharedPreferences prefs) {
        String version = prefs.getString("config_version", "");
        if ("4".equals(version)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        replaceDefault(editor, prefs, "direction_y", "1", "-1");
        replaceDefault(editor, prefs, "max_step", "120", "70");
        replaceDefault(editor, prefs, "gesture_ms", "420", "520");
        replaceDefault(editor, prefs, "frame_interval_ms", "120", "100");
        replaceDefault(editor, prefs, "release_radius", "28", "42");
        replaceDefault(editor, prefs, "motion_threshold", "20", "18");
        replaceDefault(editor, prefs, "global_change_limit", "0.55", "0.65");
        replaceDefault(editor, prefs, "min_jump_px", "180", "130");
        replaceDefault(editor, prefs, "min_track_score", "0.52", "0.44");
        replaceDefault(editor, prefs, "hold_ms", "900", "1200");
        putIfMissing(editor, prefs, "gesture_gap_ms", "220");
        putIfMissing(editor, prefs, "post_gesture_settle_ms", "160");
        putIfMissing(editor, prefs, "aim_smoothing", "0.55");
        putIfMissing(editor, prefs, "throw_tap_ms", "95");
        putIfMissing(editor, prefs, "throw_cooldown_ms", "1300");
        putIfMissing(editor, prefs, "search_wait_ms", "5000");
        putIfMissing(editor, prefs, "search_step", "220");
        putIfMissing(editor, prefs, "search_gesture_ms", "520");
        editor.putString("config_version", "4");
        editor.apply();
    }

    static float getFloat(SharedPreferences prefs, String key, float fallback) {
        String value = prefs.getString(key, Float.toString(fallback));
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void replaceDefault(SharedPreferences.Editor editor, SharedPreferences prefs, String key, String oldValue, String newValue) {
        String current = prefs.getString(key, null);
        if (current == null || sameNumber(current, oldValue)) {
            editor.putString(key, newValue);
        }
    }

    private static void putIfMissing(SharedPreferences.Editor editor, SharedPreferences prefs, String key, String value) {
        if (!prefs.contains(key)) {
            editor.putString(key, value);
        }
    }

    private static boolean sameNumber(String left, String right) {
        try {
            return Math.abs(Float.parseFloat(left.trim()) - Float.parseFloat(right.trim())) < 0.0001f;
        } catch (Exception ignored) {
            return left != null && left.trim().equals(right);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

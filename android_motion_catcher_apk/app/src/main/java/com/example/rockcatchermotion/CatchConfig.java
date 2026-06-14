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
        return new CatchConfig(
                getFloat(prefs, "reticle_x", 960f),
                getFloat(prefs, "reticle_y", 540f),
                getFloat(prefs, "ball_x", 1720f),
                getFloat(prefs, "ball_y", 860f),
                getFloat(prefs, "gain_x", 0.65f),
                getFloat(prefs, "gain_y", 0.65f),
                getFloat(prefs, "direction_x", 1f),
                getFloat(prefs, "direction_y", 1f),
                getFloat(prefs, "max_step", 120f),
                getFloat(prefs, "release_radius", 28f),
                Math.max(80, (int) getFloat(prefs, "gesture_ms", 420f)),
                Math.max(60, (int) getFloat(prefs, "frame_interval_ms", 120f)),
                clampInt((int) getFloat(prefs, "sample_stride", 14f), 6, 36),
                getFloat(prefs, "motion_threshold", 20f),
                clamp(getFloat(prefs, "global_change_limit", 0.55f), 0.08f, 0.95f),
                clampInt((int) getFloat(prefs, "history_ms", 3500f), 800, 8000),
                clampInt((int) getFloat(prefs, "min_jump_px", 180f), 20, 1200),
                clampInt((int) getFloat(prefs, "track_link_px", 240f), 40, 1200),
                clampInt((int) getFloat(prefs, "min_blob_cells", 4f), 1, 80),
                clamp(getFloat(prefs, "max_blob_fraction", 0.025f), 0.002f, 0.25f),
                clampInt((int) getFloat(prefs, "max_blob_side_px", 360f), 40, 1600),
                clamp(getFloat(prefs, "min_track_score", 0.52f), 0.05f, 0.95f),
                clampInt((int) getFloat(prefs, "hold_ms", 900f), 0, 3000),
                Math.max(0, (int) getFloat(prefs, "ignore_top_px", 0f)),
                Math.max(0, (int) getFloat(prefs, "ignore_bottom_px", 0f)),
                Math.max(0, (int) getFloat(prefs, "ignore_reticle_radius", 80f)),
                Math.max(0, (int) getFloat(prefs, "ignore_ball_radius", 140f)));
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

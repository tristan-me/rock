package com.example.rockcatcherapi;

import android.content.SharedPreferences;

final class CatchConfig {
    final String apiUrl;
    final String apiKey;
    final String apiModel;
    final int apiIntervalMs;
    final int apiTimeoutMs;
    final int apiImageMaxSidePx;
    final int apiJpegQuality;
    final float fallbackReticleX;
    final float fallbackReticleY;
    final float fallbackBallX;
    final float fallbackBallY;
    final float confidence;
    final float gainX;
    final float gainY;
    final float directionX;
    final float directionY;
    final float maxStepPx;
    final float releaseRadiusPx;
    final int gestureDurationMs;

    private CatchConfig(
            String apiUrl,
            String apiKey,
            String apiModel,
            int apiIntervalMs,
            int apiTimeoutMs,
            int apiImageMaxSidePx,
            int apiJpegQuality,
            float fallbackReticleX,
            float fallbackReticleY,
            float fallbackBallX,
            float fallbackBallY,
            float confidence,
            float gainX,
            float gainY,
            float directionX,
            float directionY,
            float maxStepPx,
            float releaseRadiusPx,
            int gestureDurationMs) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.apiModel = apiModel;
        this.apiIntervalMs = apiIntervalMs;
        this.apiTimeoutMs = apiTimeoutMs;
        this.apiImageMaxSidePx = apiImageMaxSidePx;
        this.apiJpegQuality = apiJpegQuality;
        this.fallbackReticleX = fallbackReticleX;
        this.fallbackReticleY = fallbackReticleY;
        this.fallbackBallX = fallbackBallX;
        this.fallbackBallY = fallbackBallY;
        this.confidence = confidence;
        this.gainX = gainX;
        this.gainY = gainY;
        this.directionX = directionX;
        this.directionY = directionY;
        this.maxStepPx = maxStepPx;
        this.releaseRadiusPx = releaseRadiusPx;
        this.gestureDurationMs = gestureDurationMs;
    }

    static CatchConfig from(SharedPreferences prefs) {
        return new CatchConfig(
                getString(prefs, "api_url", "https://api.deepseek.com/chat/completions"),
                getString(prefs, "api_key", ""),
                getString(prefs, "api_model", "deepseek-v4-flash"),
                clampInt((int) getFloat(prefs, "api_interval_ms", 2500f), 800, 60000),
                clampInt((int) getFloat(prefs, "api_timeout_ms", 15000f), 3000, 60000),
                clampInt((int) getFloat(prefs, "api_image_max_side", 768f), 256, 1600),
                clampInt((int) getFloat(prefs, "api_jpeg_quality", 70f), 30, 95),
                getFloat(prefs, "reticle_x", 960f),
                getFloat(prefs, "reticle_y", 540f),
                getFloat(prefs, "ball_x", 1720f),
                getFloat(prefs, "ball_y", 860f),
                getFloat(prefs, "confidence", 0.45f),
                getFloat(prefs, "gain_x", 0.65f),
                getFloat(prefs, "gain_y", 0.65f),
                getFloat(prefs, "direction_x", 1f),
                getFloat(prefs, "direction_y", 1f),
                getFloat(prefs, "max_step", 120f),
                getFloat(prefs, "release_radius", 28f),
                Math.max(80, (int) getFloat(prefs, "gesture_ms", 420f)));
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

    private static String getString(SharedPreferences prefs, String key, String fallback) {
        String value = prefs.getString(key, fallback);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

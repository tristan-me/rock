package com.example.rockcatcher;

import android.content.SharedPreferences;

final class CatchConfig {
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
    final float templateMinScore;
    final int templateStridePx;
    final int templateMaxSidePx;
    final int gestureDurationMs;

    private CatchConfig(
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
            float templateMinScore,
            int templateStridePx,
            int templateMaxSidePx,
            int gestureDurationMs) {
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
        this.templateMinScore = templateMinScore;
        this.templateStridePx = templateStridePx;
        this.templateMaxSidePx = templateMaxSidePx;
        this.gestureDurationMs = gestureDurationMs;
    }

    static CatchConfig from(SharedPreferences prefs) {
        return new CatchConfig(
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
                getFloat(prefs, "template_min_score", 0.68f),
                Math.max(2, (int) getFloat(prefs, "template_stride", 8f)),
                Math.max(160, (int) getFloat(prefs, "template_max_side", 640f)),
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
}

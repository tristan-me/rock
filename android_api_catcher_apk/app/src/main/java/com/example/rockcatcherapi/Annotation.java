package com.example.rockcatcherapi;

import android.graphics.Color;
import android.graphics.RectF;

final class Annotation {
    static final String LABEL_SPRITE = "sprite";
    static final String LABEL_RETICLE = "reticle";
    static final String LABEL_BALL_BUTTON = "ball_button";
    static final String[] LABELS = {LABEL_SPRITE, LABEL_RETICLE, LABEL_BALL_BUTTON};
    static final String[] TRAINABLE_LABELS = {LABEL_SPRITE};

    final String label;
    final RectF box;

    Annotation(String label, RectF box) {
        this.label = normalizeLabel(label);
        this.box = new RectF(box);
        normalizeBox(this.box);
    }

    static boolean isSupportedLabel(String label) {
        String normalized = normalizeLabel(label);
        for (String item : LABELS) {
            if (item.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeLabel(String label) {
        if (label == null) {
            return LABEL_SPRITE;
        }
        String value = label.trim();
        if (LABEL_RETICLE.equals(value)) {
            return LABEL_RETICLE;
        }
        if (LABEL_BALL_BUTTON.equals(value)) {
            return LABEL_BALL_BUTTON;
        }
        return LABEL_SPRITE;
    }

    static int colorFor(String label) {
        String normalized = normalizeLabel(label);
        if (LABEL_RETICLE.equals(normalized)) {
            return Color.rgb(65, 140, 255);
        }
        if (LABEL_BALL_BUTTON.equals(normalized)) {
            return Color.rgb(255, 176, 48);
        }
        return Color.rgb(28, 190, 112);
    }

    private static void normalizeBox(RectF box) {
        float left = Math.min(box.left, box.right);
        float top = Math.min(box.top, box.bottom);
        float right = Math.max(box.left, box.right);
        float bottom = Math.max(box.top, box.bottom);
        box.set(left, top, right, bottom);
    }
}


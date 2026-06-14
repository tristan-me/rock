package com.example.rockcatchermotion;

import android.graphics.RectF;

final class Detection {
    final String label;
    final float confidence;
    final RectF box;

    Detection(String label, float confidence, RectF box) {
        this.label = label;
        this.confidence = confidence;
        this.box = box;
    }

    float centerX() {
        return (box.left + box.right) * 0.5f;
    }

    float centerY() {
        return (box.top + box.bottom) * 0.5f;
    }
}

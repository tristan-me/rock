package com.example.rockcatcherapi;

final class DetectionResult {
    Detection sprite;
    Detection reticle;
    Detection ballButton;
    String status = "";
    int frameWidth;
    int frameHeight;

    boolean hasTarget() {
        return sprite != null;
    }
}


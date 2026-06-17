package com.example.rockcatchermotion;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class MotionSpriteDetector {
    private int prevFrameWidth;
    private int prevFrameHeight;
    private int prevGridWidth;
    private int prevGridHeight;
    private int prevStride;
    private int[] prevR;
    private int[] prevG;
    private int[] prevB;
    private int[] prevLuma;
    private boolean[] prevValid;

    private final ArrayList<Track> tracks = new ArrayList<>();
    private int nextTrackId = 1;
    private long frameSerial = 0L;
    private Detection lastDetection;
    private long lastDetectionMs = 0L;
    private final ArrayList<AppearanceSnapshot> appearanceHistory = new ArrayList<>();

    void reset() {
        prevR = null;
        prevG = null;
        prevB = null;
        prevLuma = null;
        prevValid = null;
        tracks.clear();
        lastDetection = null;
        lastDetectionMs = 0L;
        appearanceHistory.clear();
    }

    DetectionResult detect(Bitmap bitmap, CatchConfig config) {
        long now = System.currentTimeMillis();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stride = config.sampleStridePx;
        int gridWidth = (width + stride - 1) / stride;
        int gridHeight = (height + stride - 1) / stride;
        int total = gridWidth * gridHeight;

        DetectionResult result = new DetectionResult();
        result.frameWidth = width;
        result.frameHeight = height;
        float reticleX = clamp(resolvedReticleX(config, width), 0, width - 1);
        float reticleY = clamp(resolvedReticleY(config, height), 0, height - 1);
        float ballX = clamp(resolvedBallX(config, width), 0, width - 1);
        float ballY = clamp(resolvedBallY(config, height), 0, height - 1);
        result.reticle = fixed("reticle", reticleX, reticleY, 18f);
        result.ballButton = fixed("ball", ballX, ballY, 36f);

        int[] currR = new int[total];
        int[] currG = new int[total];
        int[] currB = new int[total];
        int[] currLuma = new int[total];
        boolean[] currValid = new boolean[total];
        long currLumaSum = 0L;
        int validCount = 0;
        int radius = Math.max(1, stride / 3);

        for (int gy = 0; gy < gridHeight; gy++) {
            for (int gx = 0; gx < gridWidth; gx++) {
                int idx = gy * gridWidth + gx;
                int px = Math.min(width - 1, gx * stride + stride / 2);
                int py = Math.min(height - 1, gy * stride + stride / 2);
                boolean valid = !isIgnored(px, py, width, height, config);
                currValid[idx] = valid;
                int color = averageColor(bitmap, px, py, radius);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                currR[idx] = r;
                currG[idx] = g;
                currB[idx] = b;
                int luma = luma(r, g, b);
                currLuma[idx] = luma;
                if (valid) {
                    currLumaSum += luma;
                    validCount++;
                }
            }
        }

        if (!hasCompatiblePrevious(width, height, gridWidth, gridHeight, stride)) {
            tracks.clear();
            boolean[] noChanged = new boolean[total];
            float[] noDeltas = new float[total];
            ArrayList<Blob> appearanceBlobs = buildAppearanceBlobs(
                    noChanged,
                    noDeltas,
                    currR,
                    currG,
                    currB,
                    currLuma,
                    currValid,
                    gridWidth,
                    gridHeight,
                    stride,
                    width,
                    height,
                    config);
            rememberAppearanceBlobs(appearanceBlobs, now, config);
            storePrevious(width, height, gridWidth, gridHeight, stride, currR, currG, currB, currLuma, currValid);
            result.status = String.format(Locale.US, "warming up motion grid %dx%d stride=%d dark=%d", gridWidth, gridHeight, stride, appearanceBlobs.size());
            return result;
        }

        float currMean = validCount > 0 ? (float) currLumaSum / validCount : 0f;
        float prevMean = meanLuma(prevLuma, prevValid);
        boolean[] changed = new boolean[total];
        float[] deltas = new float[total];
        int changedCount = 0;
        for (int i = 0; i < total; i++) {
            if (!currValid[i] || !prevValid[i]) {
                continue;
            }
            float delta = colorDelta(
                    currR[i], currG[i], currB[i], currLuma[i], currMean,
                    prevR[i], prevG[i], prevB[i], prevLuma[i], prevMean);
            deltas[i] = delta;
            if (delta >= config.motionThreshold) {
                changed[i] = true;
                changedCount++;
            }
        }

        float globalChange = validCount > 0 ? (float) changedCount / validCount : 0f;
        frameSerial++;
        if (globalChange > config.globalChangeLimit) {
            pruneTracks(now, config);
            result.status = String.format(
                    Locale.US,
                    "global change %.2f > %.2f, skip frame",
                    globalChange,
                    config.globalChangeLimit);
            result.sprite = heldDetection(now, config);
            storePrevious(width, height, gridWidth, gridHeight, stride, currR, currG, currB, currLuma, currValid);
            return result;
        }

        ArrayList<Blob> blobs = buildBlobs(
                changed,
                deltas,
                currR,
                currG,
                currB,
                currLuma,
                gridWidth,
                gridHeight,
                stride,
                width,
                height,
                validCount,
                config);
        ArrayList<Blob> appearanceBlobs = buildAppearanceBlobs(
                changed,
                deltas,
                currR,
                currG,
                currB,
                currLuma,
                currValid,
                gridWidth,
                gridHeight,
                stride,
                width,
                height,
                config);
        Candidate appearanceShift = findAppearanceShift(appearanceBlobs, config, now);
        ArrayList<Blob> fusedBlobs = fuseBlobs(blobs, appearanceBlobs);
        updateTracks(fusedBlobs, now, config);
        Candidate best = findBestCandidate(now, config);
        Candidate presence = findDarkPresenceCandidate(now, config);
        if (best == null && presence != null) {
            best = presence;
        }
        if (best == null && appearanceShift != null) {
            best = appearanceShift;
        }
        if (best != null) {
            Blob blob = best.blob();
            RectF box = paddedBox(blob.box, Math.max(24f, stride * 1.8f), width, height);
            result.sprite = new Detection("sprite", best.score, box);
            lastDetection = result.sprite;
            lastDetectionMs = now;
            result.status = String.format(
                    Locale.US,
                    "%s target score=%.2f jump=%.0f motion=%d dark=%d tracks=%d global=%.2f",
                    best.source,
                    best.score,
                    best.jumpPx,
                    blobs.size(),
                    appearanceBlobs.size(),
                    activeTrackCount(now, config),
                    globalChange);
        } else {
            result.sprite = heldDetection(now, config);
            result.status = String.format(
                    Locale.US,
                    "%s motion=%d dark=%d tracks=%d changed=%.2f",
                    result.sprite != null ? "holding last target" : "searching motion",
                    blobs.size(),
                    appearanceBlobs.size(),
                    activeTrackCount(now, config),
                    globalChange);
        }

        rememberAppearanceBlobs(appearanceBlobs, now, config);
        storePrevious(width, height, gridWidth, gridHeight, stride, currR, currG, currB, currLuma, currValid);
        return result;
    }

    private Detection heldDetection(long now, CatchConfig config) {
        if (lastDetection == null || config.holdMs <= 0 || now - lastDetectionMs > config.holdMs) {
            return null;
        }
        return new Detection("sprite_hold", lastDetection.confidence * 0.75f, new RectF(lastDetection.box));
    }

    private void updateTracks(List<Blob> blobs, long now, CatchConfig config) {
        for (Track track : tracks) {
            track.updatedSerial = -1L;
            track.missedFrames++;
        }
        Collections.sort(blobs, (a, b) -> Float.compare(b.motion, a.motion));
        for (Blob blob : blobs) {
            Track bestTrack = null;
            float bestCost = Float.MAX_VALUE;
            for (Track track : tracks) {
                if (track.updatedSerial == frameSerial) {
                    continue;
                }
                long dt = Math.max(1L, Math.min(900L, now - track.lastTimeMs));
                float predictedX = track.lastX + track.velocityXPerMs * dt;
                float predictedY = track.lastY + track.velocityYPerMs * dt;
                float distance = distance(blob.centerX, blob.centerY, predictedX, predictedY);
                float allowed = config.trackLinkPx + Math.min(120f, Math.max(blob.width(), blob.height()) * 0.5f);
                if (distance > allowed) {
                    continue;
                }
                float sizePenalty = Math.abs(blob.cellCount - track.lastCellCount)
                        / (float) Math.max(blob.cellCount, track.lastCellCount);
                float colorPenalty = chromaDistance(blob.avgR, blob.avgG, blob.avgB, track.avgR, track.avgG, track.avgB);
                float cost = distance + sizePenalty * 55f + colorPenalty * 0.18f;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestTrack = track;
                }
            }
            if (bestTrack == null) {
                bestTrack = new Track(nextTrackId++);
                tracks.add(bestTrack);
            }
            bestTrack.update(blob, now, frameSerial, config);
        }
        pruneTracks(now, config);
    }

    private Candidate findBestCandidate(long now, CatchConfig config) {
        Candidate best = null;
        for (Track track : tracks) {
            if (track.updatedSerial != frameSerial || track.updates < 3 || track.lastBlob == null) {
                continue;
            }
            if (looksLikeSelfAvatar(track.lastBlob)) {
                continue;
            }
            boolean smallDarkTarget = looksLikeSmallDarkTarget(track.lastBlob);
            float jump = track.maxJump(now, config.historyMs, Math.min(700, Math.max(350, config.historyMs / 4)));
            float requiredJump = smallDarkTarget
                    ? Math.max(26f, config.minJumpPx * 0.34f)
                    : config.minJumpPx;
            if (jump < requiredJump) {
                continue;
            }
            long age = Math.max(1L, track.lastTimeMs - track.firstTimeInWindow());
            float travelScore = clamp01(jump / Math.max(1f, requiredJump));
            float updateScore = clamp01((track.updates - 1f) / 6f);
            float ageScore = clamp01(age / 1200f);
            float motionScore = clamp01(track.lastBlob.motion / Math.max(1f, config.motionThreshold * 2.2f));
            float compactScore = track.lastBlob.compactScore;
            float score = travelScore * 0.42f
                    + updateScore * 0.22f
                    + ageScore * 0.14f
                    + motionScore * 0.14f
                    + compactScore * 0.08f;
            float threshold = smallDarkTarget ? Math.max(0.30f, config.minTrackScore - 0.12f) : config.minTrackScore;
            if (smallDarkTarget) {
                score += 0.08f;
            }
            if (score >= threshold && (best == null || score > best.score)) {
                best = new Candidate(track, score, jump);
            }
        }
        return best;
    }

    private Candidate findDarkPresenceCandidate(long now, CatchConfig config) {
        Candidate best = null;
        for (Track track : tracks) {
            if (track.updatedSerial != frameSerial || track.updates < 4 || track.lastBlob == null) {
                continue;
            }
            Blob blob = track.lastBlob;
            if (!looksLikeSmallDarkTarget(blob) || looksLikeSelfAvatar(blob)) {
                continue;
            }
            float jump = track.maxJump(now, config.historyMs, 350);
            long age = Math.max(1L, track.lastTimeMs - track.firstTimeInWindow());
            boolean locallyAlive = blob.changeRatio >= 0.012f
                    || blob.motion >= config.motionThreshold * 0.32f
                    || jump >= Math.max(10f, config.minJumpPx * 0.10f);
            if (!locallyAlive) {
                continue;
            }
            float darknessScore = clamp01((94f - blob.avgLuma) / 58f);
            float compactScore = blob.compactScore;
            float updateScore = clamp01((track.updates - 3f) / 7f);
            float ageScore = clamp01(age / 1600f);
            float motionScore = clamp01(Math.max(blob.motion, blob.changeRatio * config.motionThreshold * 2.4f)
                    / Math.max(1f, config.motionThreshold));
            float travelScore = clamp01(jump / Math.max(1f, config.minJumpPx * 0.55f));
            float regionScore = worldRegionScore(blob);
            float score = darknessScore * 0.25f
                    + compactScore * 0.20f
                    + updateScore * 0.16f
                    + ageScore * 0.12f
                    + motionScore * 0.12f
                    + travelScore * 0.08f
                    + regionScore * 0.07f;
            if (score >= Math.max(0.31f, config.minTrackScore - 0.09f)
                    && (best == null || score > best.score)) {
                best = new Candidate(track, score, jump, "dark-presence");
            }
        }
        return best;
    }

    private Candidate findAppearanceShift(List<Blob> appearanceBlobs, CatchConfig config, long now) {
        if (appearanceHistory.isEmpty() || appearanceBlobs.isEmpty()) {
            return null;
        }
        Candidate best = null;
        float minJump = Math.max(20f, config.minJumpPx * 0.20f);
        float maxJump = Math.max(config.trackLinkPx * 2.8f, config.minJumpPx * 2.8f);
        for (Blob current : appearanceBlobs) {
            if (!looksLikeSmallDarkTarget(current) || looksLikeSelfAvatar(current)) {
                continue;
            }
            boolean locallyMoving = current.changeRatio >= 0.025f
                    || current.motion >= config.motionThreshold * 0.38f;
            if (!locallyMoving && current.compactScore < 0.45f) {
                continue;
            }
            for (AppearanceSnapshot snapshot : appearanceHistory) {
                long age = now - snapshot.timeMs;
                if (age < 450L || age > config.historyMs) {
                    continue;
                }
                Blob previous = snapshot.blob;
                float jump = distance(current.centerX, current.centerY, previous.centerX, previous.centerY);
                if (jump < minJump || jump > maxJump) {
                    continue;
                }
                float color = chromaDistance(
                        current.avgR,
                        current.avgG,
                        current.avgB,
                        previous.avgR,
                        previous.avgG,
                        previous.avgB);
                if (color > 36f) {
                    continue;
                }
                float sizeRatio = Math.max(current.cellCount, previous.cellCount)
                        / (float) Math.max(1, Math.min(current.cellCount, previous.cellCount));
                if (sizeRatio > 3.8f) {
                    continue;
                }
                float jumpScore = clamp01(jump / Math.max(1f, config.minJumpPx * 0.72f));
                float colorScore = clamp01(1f - color / 36f);
                float sizeScore = clamp01(1f - (sizeRatio - 1f) / 2.8f);
                float changeScore = clamp01(Math.max(current.changeRatio, previous.changeRatio) * 3.2f);
                float motionScore = clamp01(Math.max(current.motion, previous.motion) / Math.max(1f, config.motionThreshold));
                float ageScore = clamp01(age / 1400f);
                float compactScore = current.compactScore;
                float score = jumpScore * 0.32f
                        + colorScore * 0.17f
                        + sizeScore * 0.14f
                        + changeScore * 0.14f
                        + motionScore * 0.10f
                        + ageScore * 0.05f
                        + compactScore * 0.08f;
                if (score >= Math.max(0.28f, config.minTrackScore - 0.16f)
                        && (best == null || score > best.score)) {
                    best = new Candidate(current, score, jump, "dark-history");
                }
            }
        }
        return best;
    }

    private void rememberAppearanceBlobs(List<Blob> appearanceBlobs, long now, CatchConfig config) {
        pruneAppearanceHistory(now, config);
        for (Blob blob : appearanceBlobs) {
            if (looksLikeSmallDarkTarget(blob) && (blob.changeRatio >= 0.015f || blob.compactScore >= 0.35f)) {
                appearanceHistory.add(new AppearanceSnapshot(new Blob(
                        blob.centerX,
                        blob.centerY,
                        new RectF(blob.box),
                        blob.cellCount,
                        blob.avgR,
                        blob.avgG,
                        blob.avgB,
                        blob.avgLuma,
                        blob.motion,
                        blob.compactScore,
                        blob.changeRatio), now));
            }
        }
        while (appearanceHistory.size() > 90) {
            appearanceHistory.remove(0);
        }
    }

    private void pruneAppearanceHistory(long now, CatchConfig config) {
        Iterator<AppearanceSnapshot> iterator = appearanceHistory.iterator();
        while (iterator.hasNext()) {
            AppearanceSnapshot snapshot = iterator.next();
            if (now - snapshot.timeMs > config.historyMs) {
                iterator.remove();
            }
        }
    }

    private ArrayList<Blob> buildBlobs(
            boolean[] changed,
            float[] deltas,
            int[] currR,
            int[] currG,
            int[] currB,
            int[] currLuma,
            int gridWidth,
            int gridHeight,
            int stride,
            int frameWidth,
            int frameHeight,
            int validCount,
            CatchConfig config) {
        ArrayList<Blob> blobs = new ArrayList<>();
        int total = gridWidth * gridHeight;
        boolean[] visited = new boolean[total];
        int[] queue = new int[total];
        int maxCells = Math.max(config.minBlobCells + 1, Math.round(validCount * config.maxBlobFraction));

        for (int start = 0; start < total; start++) {
            if (!changed[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;

            int count = 0;
            int minGx = gridWidth;
            int minGy = gridHeight;
            int maxGx = 0;
            int maxGy = 0;
            float sumX = 0f;
            float sumY = 0f;
            float sumR = 0f;
            float sumG = 0f;
            float sumB = 0f;
            float sumLuma = 0f;
            float sumMotion = 0f;

            while (head < tail) {
                int idx = queue[head++];
                int gx = idx % gridWidth;
                int gy = idx / gridWidth;
                count++;
                minGx = Math.min(minGx, gx);
                minGy = Math.min(minGy, gy);
                maxGx = Math.max(maxGx, gx);
                maxGy = Math.max(maxGy, gy);
                float px = Math.min(frameWidth - 1, gx * stride + stride * 0.5f);
                float py = Math.min(frameHeight - 1, gy * stride + stride * 0.5f);
                sumX += px;
                sumY += py;
                sumR += currR[idx];
                sumG += currG[idx];
                sumB += currB[idx];
                sumLuma += currLuma[idx];
                sumMotion += deltas[idx];

                for (int oy = -1; oy <= 1; oy++) {
                    int ny = gy + oy;
                    if (ny < 0 || ny >= gridHeight) {
                        continue;
                    }
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) {
                            continue;
                        }
                        int nx = gx + ox;
                        if (nx < 0 || nx >= gridWidth) {
                            continue;
                        }
                        int next = ny * gridWidth + nx;
                        if (changed[next] && !visited[next]) {
                            visited[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }

            int bboxCells = Math.max(1, (maxGx - minGx + 1) * (maxGy - minGy + 1));
            float density = count / (float) bboxCells;
            int boxWidth = (maxGx - minGx + 1) * stride;
            int boxHeight = (maxGy - minGy + 1) * stride;
            if (count < config.minBlobCells
                    || count > maxCells
                    || boxWidth > config.maxBlobSidePx
                    || boxHeight > config.maxBlobSidePx
                    || (density < 0.12f && count > 5)) {
                continue;
            }

            RectF box = new RectF(
                    clamp(minGx * stride, 0, frameWidth - 1),
                    clamp(minGy * stride, 0, frameHeight - 1),
                    clamp((maxGx + 1) * stride, 1, frameWidth),
                    clamp((maxGy + 1) * stride, 1, frameHeight));
            float compact = clamp01(density * 2.3f) * clamp01(count / 8f);
            blobs.add(new Blob(
                    sumX / count,
                    sumY / count,
                    box,
                    count,
                    sumR / count,
                    sumG / count,
                    sumB / count,
                    sumLuma / count,
                    sumMotion / count,
                    compact,
                    1f));
        }
        return blobs;
    }

    private ArrayList<Blob> buildAppearanceBlobs(
            boolean[] changed,
            float[] deltas,
            int[] currR,
            int[] currG,
            int[] currB,
            int[] currLuma,
            boolean[] currValid,
            int gridWidth,
            int gridHeight,
            int stride,
            int frameWidth,
            int frameHeight,
            CatchConfig config) {
        int total = gridWidth * gridHeight;
        int[] sumIntegral = new int[(gridWidth + 1) * (gridHeight + 1)];
        int[] countIntegral = new int[(gridWidth + 1) * (gridHeight + 1)];
        for (int gy = 0; gy < gridHeight; gy++) {
            int rowSum = 0;
            int rowCount = 0;
            for (int gx = 0; gx < gridWidth; gx++) {
                int idx = gy * gridWidth + gx;
                if (currValid[idx]) {
                    rowSum += currLuma[idx];
                    rowCount++;
                }
                int integralIndex = (gy + 1) * (gridWidth + 1) + gx + 1;
                int above = gy * (gridWidth + 1) + gx + 1;
                sumIntegral[integralIndex] = sumIntegral[above] + rowSum;
                countIntegral[integralIndex] = countIntegral[above] + rowCount;
            }
        }

        boolean[] mask = new boolean[total];
        float[] scores = new float[total];
        for (int gy = 0; gy < gridHeight; gy++) {
            for (int gx = 0; gx < gridWidth; gx++) {
                int idx = gy * gridWidth + gx;
                if (!currValid[idx]) {
                    continue;
                }
                int px = Math.min(frameWidth - 1, gx * stride + stride / 2);
                int py = Math.min(frameHeight - 1, gy * stride + stride / 2);
                if (isAppearanceIgnored(px, py, frameWidth, frameHeight, config)) {
                    continue;
                }
                int localRadius = 4;
                int x0 = Math.max(0, gx - localRadius);
                int y0 = Math.max(0, gy - localRadius);
                int x1 = Math.min(gridWidth - 1, gx + localRadius);
                int y1 = Math.min(gridHeight - 1, gy + localRadius);
                int localSum = integralSum(sumIntegral, gridWidth, x0, y0, x1, y1);
                int localCount = integralSum(countIntegral, gridWidth, x0, y0, x1, y1);
                if (localCount <= 0) {
                    continue;
                }
                float localMean = localSum / (float) localCount;
                int r = currR[idx];
                int g = currG[idx];
                int b = currB[idx];
                int luma = currLuma[idx];
                int saturation = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                float contrast = localMean - luma;
                boolean dark = luma < 88 && (contrast > 5f || luma < 62);
                boolean purpleBlueDark = saturation >= 8 && b >= r - 2 && b >= g - 18;
                boolean neutralDark = luma < 62 && saturation >= 4 && contrast > 3f;
                boolean notDebugRed = !(r > 170 && r > g + 60 && r > b + 55);
                if (dark && (purpleBlueDark || neutralDark) && notDebugRed) {
                    mask[idx] = true;
                    scores[idx] = Math.max(0f, contrast) + Math.max(0, 88 - luma) * 0.45f + saturation * 0.10f + deltas[idx] * 0.35f;
                }
            }
        }
        return buildAppearanceBlobsFromMask(mask, scores, changed, currR, currG, currB, currLuma,
                gridWidth, gridHeight, stride, frameWidth, frameHeight, config);
    }

    private ArrayList<Blob> buildAppearanceBlobsFromMask(
            boolean[] mask,
            float[] scores,
            boolean[] changed,
            int[] currR,
            int[] currG,
            int[] currB,
            int[] currLuma,
            int gridWidth,
            int gridHeight,
            int stride,
            int frameWidth,
            int frameHeight,
            CatchConfig config) {
        ArrayList<Blob> blobs = new ArrayList<>();
        int total = gridWidth * gridHeight;
        boolean[] visited = new boolean[total];
        int[] queue = new int[total];
        int maxCells = Math.max(18, Math.min(180, Math.round(gridWidth * gridHeight * 0.012f)));
        int maxSide = Math.max(90, Math.min(230, config.maxBlobSidePx));

        for (int start = 0; start < total; start++) {
            if (!mask[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;

            int count = 0;
            int changedCells = 0;
            int minGx = gridWidth;
            int minGy = gridHeight;
            int maxGx = 0;
            int maxGy = 0;
            float sumX = 0f;
            float sumY = 0f;
            float sumR = 0f;
            float sumG = 0f;
            float sumB = 0f;
            float sumLuma = 0f;
            float sumScore = 0f;

            while (head < tail) {
                int idx = queue[head++];
                int gx = idx % gridWidth;
                int gy = idx / gridWidth;
                count++;
                if (changed[idx]) {
                    changedCells++;
                }
                minGx = Math.min(minGx, gx);
                minGy = Math.min(minGy, gy);
                maxGx = Math.max(maxGx, gx);
                maxGy = Math.max(maxGy, gy);
                float px = Math.min(frameWidth - 1, gx * stride + stride * 0.5f);
                float py = Math.min(frameHeight - 1, gy * stride + stride * 0.5f);
                sumX += px;
                sumY += py;
                sumR += currR[idx];
                sumG += currG[idx];
                sumB += currB[idx];
                sumLuma += currLuma[idx];
                sumScore += scores[idx];

                for (int oy = -1; oy <= 1; oy++) {
                    int ny = gy + oy;
                    if (ny < 0 || ny >= gridHeight) {
                        continue;
                    }
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) {
                            continue;
                        }
                        int nx = gx + ox;
                        if (nx < 0 || nx >= gridWidth) {
                            continue;
                        }
                        int next = ny * gridWidth + nx;
                        if (mask[next] && !visited[next]) {
                            visited[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }

            int bboxCells = Math.max(1, (maxGx - minGx + 1) * (maxGy - minGy + 1));
            float density = count / (float) bboxCells;
            int boxWidth = (maxGx - minGx + 1) * stride;
            int boxHeight = (maxGy - minGy + 1) * stride;
            if (count < 1
                    || count > maxCells
                    || boxWidth > maxSide
                    || boxHeight > maxSide
                    || density < 0.16f) {
                continue;
            }
            RectF box = new RectF(
                    clamp(minGx * stride, 0, frameWidth - 1),
                    clamp(minGy * stride, 0, frameHeight - 1),
                    clamp((maxGx + 1) * stride, 1, frameWidth),
                    clamp((maxGy + 1) * stride, 1, frameHeight));
            float compact = clamp01(density * 2.0f) * clamp01(count / 9f);
            blobs.add(new Blob(
                    sumX / count,
                    sumY / count,
                    box,
                    count,
                    sumR / count,
                    sumG / count,
                    sumB / count,
                    sumLuma / count,
                    sumScore / count,
                    compact,
                    changedCells / (float) count));
        }
        return blobs;
    }

    private ArrayList<Blob> fuseBlobs(List<Blob> motionBlobs, List<Blob> appearanceBlobs) {
        ArrayList<Blob> fused = new ArrayList<>(motionBlobs);
        for (Blob appearance : appearanceBlobs) {
            boolean overlaps = false;
            for (Blob motion : motionBlobs) {
                if (rectOverlapRatio(appearance.box, motion.box) > 0.35f
                        || distance(appearance.centerX, appearance.centerY, motion.centerX, motion.centerY)
                        < Math.max(appearance.width(), appearance.height()) * 0.55f) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                fused.add(appearance);
            }
        }
        return fused;
    }

    private boolean hasCompatiblePrevious(int width, int height, int gridWidth, int gridHeight, int stride) {
        return prevR != null
                && prevFrameWidth == width
                && prevFrameHeight == height
                && prevGridWidth == gridWidth
                && prevGridHeight == gridHeight
                && prevStride == stride;
    }

    private void storePrevious(
            int width,
            int height,
            int gridWidth,
            int gridHeight,
            int stride,
            int[] currR,
            int[] currG,
            int[] currB,
            int[] currLuma,
            boolean[] currValid) {
        prevFrameWidth = width;
        prevFrameHeight = height;
        prevGridWidth = gridWidth;
        prevGridHeight = gridHeight;
        prevStride = stride;
        prevR = currR;
        prevG = currG;
        prevB = currB;
        prevLuma = currLuma;
        prevValid = currValid;
    }

    private void pruneTracks(long now, CatchConfig config) {
        Iterator<Track> iterator = tracks.iterator();
        long keepMs = Math.max(config.historyMs + config.holdMs, 2200);
        while (iterator.hasNext()) {
            Track track = iterator.next();
            track.prune(now, config.historyMs);
            if (now - track.lastTimeMs > keepMs || track.missedFrames > 16 || track.points.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private int activeTrackCount(long now, CatchConfig config) {
        int count = 0;
        for (Track track : tracks) {
            if (now - track.lastTimeMs <= config.historyMs) {
                count++;
            }
        }
        return count;
    }

    private float meanLuma(int[] luma, boolean[] valid) {
        long sum = 0L;
        int count = 0;
        for (int i = 0; i < luma.length; i++) {
            if (valid[i]) {
                sum += luma[i];
                count++;
            }
        }
        return count > 0 ? (float) sum / count : 0f;
    }

    private boolean isIgnored(int x, int y, int width, int height, CatchConfig config) {
        if (y < config.ignoreTopPx || y >= height - config.ignoreBottomPx) {
            return true;
        }
        int topUi = Math.max(config.ignoreTopPx, Math.round(height * 0.13f));
        int bottomUi = height - Math.max(config.ignoreBottomPx, Math.round(height * 0.12f));
        if (y < topUi || y >= bottomUi) {
            return true;
        }
        if (x < Math.round(width * 0.13f)) {
            return true;
        }
        if (looksInsideSelfAvatarRegion(x, y, width, height)) {
            return true;
        }
        if (x > Math.round(width * 0.46f)
                && x < Math.round(width * 0.82f)
                && y > Math.round(height * 0.15f)
                && y < Math.round(height * 0.29f)) {
            return true;
        }
        if (x > Math.round(width * 0.84f) && y < Math.round(height * 0.22f)) {
            return true;
        }
        if (x > Math.round(width * 0.69f) && y > Math.round(height * 0.58f)) {
            return true;
        }
        float reticleX = resolvedReticleX(config, width);
        float reticleY = resolvedReticleY(config, height);
        float ballX = resolvedBallX(config, width);
        float ballY = resolvedBallY(config, height);
        if (config.ignoreReticleRadiusPx > 0
                && distance(x, y, reticleX, reticleY) < config.ignoreReticleRadiusPx) {
            return true;
        }
        return config.ignoreBallRadiusPx > 0
                && distance(x, y, ballX, ballY) < config.ignoreBallRadiusPx;
    }

    private boolean isAppearanceIgnored(int x, int y, int width, int height, CatchConfig config) {
        if (isIgnored(x, y, width, height, config)) {
            return true;
        }
        return false;
    }

    private boolean looksLikeSmallDarkTarget(Blob blob) {
        float frameHeight = Math.max(1f, prevFrameHeight);
        float width = blob.width();
        float height = blob.height();
        float blueBias = blob.avgB - Math.max(blob.avgR, blob.avgG);
        boolean compactSmall = width <= 110f && height <= 100f && blob.cellCount <= 36;
        boolean darkEnough = blob.avgLuma < 88f;
        boolean notBottomUi = prevFrameHeight <= 0 || blob.centerY < frameHeight * 0.78f;
        boolean colorOk = blueBias >= -18f || blob.avgLuma < 58f;
        return compactSmall && darkEnough && notBottomUi && colorOk;
    }

    private boolean looksLikeSelfAvatar(Blob blob) {
        float frameWidth = Math.max(1f, prevFrameWidth);
        float frameHeight = Math.max(1f, prevFrameHeight);
        return looksInsideSelfAvatarRegion(
                Math.round(blob.centerX),
                Math.round(blob.centerY),
                Math.round(frameWidth),
                Math.round(frameHeight));
    }

    private boolean looksInsideSelfAvatarRegion(int x, int y, int width, int height) {
        return x > Math.round(width * 0.30f)
                && x < Math.round(width * 0.55f)
                && y > Math.round(height * 0.27f)
                && y < Math.round(height * 0.84f);
    }

    private float worldRegionScore(Blob blob) {
        float frameWidth = Math.max(1f, prevFrameWidth);
        float frameHeight = Math.max(1f, prevFrameHeight);
        float x = blob.centerX / frameWidth;
        float y = blob.centerY / frameHeight;
        float vertical = y >= 0.22f && y <= 0.74f ? 1f : 0.35f;
        float horizontal = x >= 0.12f && x <= 0.84f ? 1f : 0.45f;
        return vertical * horizontal;
    }

    private float resolvedReticleX(CatchConfig config, int width) {
        return config.autoReticle ? width * 0.50f : config.fallbackReticleX;
    }

    private float resolvedReticleY(CatchConfig config, int height) {
        return config.autoReticle ? height * 0.56f : config.fallbackReticleY;
    }

    private float resolvedBallX(CatchConfig config, int width) {
        return config.autoBall ? width * 0.84f : config.fallbackBallX;
    }

    private float resolvedBallY(CatchConfig config, int height) {
        return config.autoBall ? height * 0.84f : config.fallbackBallY;
    }

    private int integralSum(int[] integral, int gridWidth, int x0, int y0, int x1, int y1) {
        int stride = gridWidth + 1;
        int left = x0;
        int top = y0;
        int right = x1 + 1;
        int bottom = y1 + 1;
        return integral[bottom * stride + right]
                - integral[top * stride + right]
                - integral[bottom * stride + left]
                + integral[top * stride + left];
    }

    private int averageColor(Bitmap bitmap, int x, int y, int radius) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int c0 = bitmap.getPixel(clamp(x, 0, width - 1), clamp(y, 0, height - 1));
        int c1 = bitmap.getPixel(clamp(x - radius, 0, width - 1), clamp(y, 0, height - 1));
        int c2 = bitmap.getPixel(clamp(x + radius, 0, width - 1), clamp(y, 0, height - 1));
        int c3 = bitmap.getPixel(clamp(x, 0, width - 1), clamp(y - radius, 0, height - 1));
        int c4 = bitmap.getPixel(clamp(x, 0, width - 1), clamp(y + radius, 0, height - 1));
        int r = Color.red(c0) + Color.red(c1) + Color.red(c2) + Color.red(c3) + Color.red(c4);
        int g = Color.green(c0) + Color.green(c1) + Color.green(c2) + Color.green(c3) + Color.green(c4);
        int b = Color.blue(c0) + Color.blue(c1) + Color.blue(c2) + Color.blue(c3) + Color.blue(c4);
        return Color.rgb(r / 5, g / 5, b / 5);
    }

    private float colorDelta(
            int r1,
            int g1,
            int b1,
            int luma1,
            float mean1,
            int r2,
            int g2,
            int b2,
            int luma2,
            float mean2) {
        float chroma = chromaDistance(r1, g1, b1, r2, g2, b2);
        float localLuma = Math.abs((luma1 - mean1) - (luma2 - mean2));
        float raw = (Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2)) / 3f;
        return chroma * 0.82f + localLuma * 0.13f + raw * 0.05f;
    }

    private float chromaDistance(float r1, float g1, float b1, float r2, float g2, float b2) {
        float sum1 = Math.max(1f, r1 + g1 + b1);
        float sum2 = Math.max(1f, r2 + g2 + b2);
        float nr1 = r1 * 255f / sum1;
        float ng1 = g1 * 255f / sum1;
        float nb1 = b1 * 255f / sum1;
        float nr2 = r2 * 255f / sum2;
        float ng2 = g2 * 255f / sum2;
        float nb2 = b2 * 255f / sum2;
        return (Math.abs(nr1 - nr2) + Math.abs(ng1 - ng2) + Math.abs(nb1 - nb2)) / 3f;
    }

    private int luma(int r, int g, int b) {
        return (r * 77 + g * 150 + b * 29) >> 8;
    }

    private Detection fixed(String label, float centerX, float centerY, float radius) {
        return new Detection(label, 1f, new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius));
    }

    private RectF paddedBox(RectF source, float minRadius, int width, int height) {
        float cx = source.centerX();
        float cy = source.centerY();
        float halfW = Math.max(minRadius, source.width() * 0.65f);
        float halfH = Math.max(minRadius, source.height() * 0.65f);
        return new RectF(
                clamp(cx - halfW, 0, width - 1),
                clamp(cy - halfH, 0, height - 1),
                clamp(cx + halfW, 1, width),
                clamp(cy + halfH, 1, height));
    }

    private float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float rectOverlapRatio(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) {
            return 0f;
        }
        float intersection = (right - left) * (bottom - top);
        float smaller = Math.max(1f, Math.min(a.width() * a.height(), b.width() * b.height()));
        return intersection / smaller;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Blob {
        final float centerX;
        final float centerY;
        final RectF box;
        final int cellCount;
        final float avgR;
        final float avgG;
        final float avgB;
        final float avgLuma;
        final float motion;
        final float compactScore;
        final float changeRatio;

        Blob(
                float centerX,
                float centerY,
                RectF box,
                int cellCount,
                float avgR,
                float avgG,
                float avgB,
                float avgLuma,
                float motion,
                float compactScore,
                float changeRatio) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.box = box;
            this.cellCount = cellCount;
            this.avgR = avgR;
            this.avgG = avgG;
            this.avgB = avgB;
            this.avgLuma = avgLuma;
            this.motion = motion;
            this.compactScore = compactScore;
            this.changeRatio = changeRatio;
        }

        float width() {
            return box.width();
        }

        float height() {
            return box.height();
        }
    }

    private static final class Track {
        final int id;
        final ArrayList<TrackPoint> points = new ArrayList<>();
        float lastX;
        float lastY;
        long lastTimeMs;
        long updatedSerial = -1L;
        int updates;
        int missedFrames;
        float velocityXPerMs;
        float velocityYPerMs;
        float avgR;
        float avgG;
        float avgB;
        int lastCellCount = 1;
        Blob lastBlob;

        Track(int id) {
            this.id = id;
        }

        void update(Blob blob, long now, long serial, CatchConfig config) {
            if (updates > 0) {
                long dt = Math.max(1L, now - lastTimeMs);
                float vx = (blob.centerX - lastX) / dt;
                float vy = (blob.centerY - lastY) / dt;
                velocityXPerMs = velocityXPerMs * 0.65f + vx * 0.35f;
                velocityYPerMs = velocityYPerMs * 0.65f + vy * 0.35f;
                avgR = avgR * 0.75f + blob.avgR * 0.25f;
                avgG = avgG * 0.75f + blob.avgG * 0.25f;
                avgB = avgB * 0.75f + blob.avgB * 0.25f;
            } else {
                avgR = blob.avgR;
                avgG = blob.avgG;
                avgB = blob.avgB;
            }
            lastX = blob.centerX;
            lastY = blob.centerY;
            lastTimeMs = now;
            updatedSerial = serial;
            updates++;
            missedFrames = 0;
            lastCellCount = blob.cellCount;
            lastBlob = blob;
            points.add(new TrackPoint(now, blob.centerX, blob.centerY));
            prune(now, config.historyMs);
        }

        void prune(long now, int historyMs) {
            Iterator<TrackPoint> iterator = points.iterator();
            while (iterator.hasNext()) {
                TrackPoint point = iterator.next();
                if (now - point.timeMs > historyMs) {
                    iterator.remove();
                }
            }
        }

        float maxJump(long now, int historyMs, int minAgeMs) {
            float best = 0f;
            for (TrackPoint point : points) {
                long age = now - point.timeMs;
                if (age < minAgeMs || age > historyMs) {
                    continue;
                }
                float dx = lastX - point.x;
                float dy = lastY - point.y;
                best = Math.max(best, (float) Math.sqrt(dx * dx + dy * dy));
            }
            return best;
        }

        long firstTimeInWindow() {
            return points.isEmpty() ? lastTimeMs : points.get(0).timeMs;
        }
    }

    private static final class TrackPoint {
        final long timeMs;
        final float x;
        final float y;

        TrackPoint(long timeMs, float x, float y) {
            this.timeMs = timeMs;
            this.x = x;
            this.y = y;
        }
    }

    private static final class AppearanceSnapshot {
        final Blob blob;
        final long timeMs;

        AppearanceSnapshot(Blob blob, long timeMs) {
            this.blob = blob;
            this.timeMs = timeMs;
        }
    }

    private static final class Candidate {
        final Track track;
        final Blob blob;
        final float score;
        final float jumpPx;
        final String source;

        Candidate(Track track, float score, float jumpPx) {
            this(track, score, jumpPx, "motion");
        }

        Candidate(Track track, float score, float jumpPx, String source) {
            this.track = track;
            this.blob = null;
            this.score = score;
            this.jumpPx = jumpPx;
            this.source = source;
        }

        Candidate(Blob blob, float score, float jumpPx, String source) {
            this.track = null;
            this.blob = blob;
            this.score = score;
            this.jumpPx = jumpPx;
            this.source = source;
        }

        Blob blob() {
            return blob != null ? blob : track.lastBlob;
        }
    }
}

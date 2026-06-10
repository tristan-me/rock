package com.example.rockcatcher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

final class TfLiteYoloDetector implements AutoCloseable {
    private static final String[] LABELS = {"sprite", "reticle", "ball_button"};

    private final Context context;
    private final TemplateDetector templateDetector;
    private Interpreter interpreter;
    private int inputWidth = 640;
    private int inputHeight = 640;
    private boolean nchw = false;
    private String modelStatus = "not loaded";

    TfLiteYoloDetector(Context context) {
        this.context = context.getApplicationContext();
        this.templateDetector = new TemplateDetector(this.context);
        reload();
    }

    synchronized void reload() {
        close();
        try {
            MappedByteBuffer model = loadModel();
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(2);
            interpreter = new Interpreter(model, options);
            int[] shape = interpreter.getInputTensor(0).shape();
            if (shape.length == 4 && shape[1] == 3) {
                nchw = true;
                inputHeight = shape[2];
                inputWidth = shape[3];
            } else if (shape.length == 4) {
                nchw = false;
                inputHeight = shape[1];
                inputWidth = shape[2];
            }
            modelStatus = "model loaded " + inputWidth + "x" + inputHeight;
        } catch (Exception ex) {
            interpreter = null;
            modelStatus = ex.getMessage() == null ? "model.tflite not found" : ex.getMessage();
        }
    }

    synchronized DetectionResult detect(Bitmap source, CatchConfig config) {
        DetectionResult result = new DetectionResult();
        result.frameWidth = source.getWidth();
        result.frameHeight = source.getHeight();
        result.reticle = new Detection(
                "reticle",
                1f,
                centeredBox(config.fallbackReticleX, config.fallbackReticleY, 24f));
        result.ballButton = new Detection(
                "ball_button",
                1f,
                centeredBox(config.fallbackBallX, config.fallbackBallY, 40f));

        Interpreter local = interpreter;
        if (local == null) {
            result.status = templateDetector.fillDetections(source, config, result);
            if (!result.hasTarget()) {
                result.status = modelStatus + "; " + templateDetector.status();
            }
            return result;
        }

        Bitmap scaled = Bitmap.createScaledBitmap(source, inputWidth, inputHeight, false);
        try {
            ByteBuffer input = bitmapToInput(scaled);
            int[] outputShape = local.getOutputTensor(0).shape();
            if (outputShape.length != 3) {
                result.status = "unsupported output shape";
            } else {
                float[][][] output = new float[outputShape[0]][outputShape[1]][outputShape[2]];
                local.run(input, output);
                parseOutput(output[0], outputShape, source.getWidth(), source.getHeight(), config, result);
            }
        } finally {
            scaled.recycle();
        }
        if (result.sprite == null) {
            String templateStatus = templateDetector.fillDetections(source, config, result);
            result.status = String.format(
                    Locale.US,
                    "%s sprite=missing; %s",
                    modelStatus,
                    templateStatus);
        } else {
            result.status = String.format(Locale.US, "%s sprite=ok", modelStatus);
        }
        return result;
    }

    private MappedByteBuffer loadModel() throws IOException {
        File external = new File(context.getExternalFilesDir(null), "model.tflite");
        if (external.exists()) {
            try (FileInputStream stream = new FileInputStream(external)) {
                FileChannel channel = stream.getChannel();
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            }
        }
        try {
            AssetFileDescriptor afd = context.getAssets().openFd("model.tflite");
            try (FileInputStream stream = new FileInputStream(afd.getFileDescriptor())) {
                FileChannel channel = stream.getChannel();
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
            }
        } catch (IOException ignored) {
            throw new IOException("model.tflite not found");
        }
    }

    private ByteBuffer bitmapToInput(Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());
        int[] pixels = new int[inputWidth * inputHeight];
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        if (nchw) {
            for (int channel = 0; channel < 3; channel++) {
                for (int pixel : pixels) {
                    int value;
                    if (channel == 0) {
                        value = (pixel >> 16) & 0xFF;
                    } else if (channel == 1) {
                        value = (pixel >> 8) & 0xFF;
                    } else {
                        value = pixel & 0xFF;
                    }
                    buffer.putFloat(value / 255f);
                }
            }
        } else {
            for (int pixel : pixels) {
                buffer.putFloat(((pixel >> 16) & 0xFF) / 255f);
                buffer.putFloat(((pixel >> 8) & 0xFF) / 255f);
                buffer.putFloat((pixel & 0xFF) / 255f);
            }
        }
        buffer.rewind();
        return buffer;
    }

    private void parseOutput(
            float[][] output,
            int[] shape,
            int frameWidth,
            int frameHeight,
            CatchConfig config,
            DetectionResult result) {
        int dimA = shape[1];
        int dimB = shape[2];
        boolean channelFirst = dimA <= 32 && dimB > dimA;
        int boxes = channelFirst ? dimB : dimA;
        int channels = channelFirst ? dimA : dimB;
        if (channels < 4 + LABELS.length) {
            result.status = "output has too few channels";
            return;
        }

        Detection[] best = new Detection[LABELS.length];
        for (int i = 0; i < boxes; i++) {
            float cx = value(output, channelFirst, i, 0);
            float cy = value(output, channelFirst, i, 1);
            float w = value(output, channelFirst, i, 2);
            float h = value(output, channelFirst, i, 3);
            boolean hasObjectness = channels >= 5 + LABELS.length;
            float objectness = hasObjectness ? value(output, channelFirst, i, 4) : 1f;
            int classStart = hasObjectness ? 5 : 4;

            int bestClass = -1;
            float bestScore = 0f;
            for (int cls = 0; cls < LABELS.length; cls++) {
                float score = objectness * value(output, channelFirst, i, classStart + cls);
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = cls;
                }
            }
            if (bestClass < 0 || bestScore < config.confidence) {
                continue;
            }

            RectF box = toFrameBox(cx, cy, w, h, frameWidth, frameHeight);
            Detection detection = new Detection(LABELS[bestClass], bestScore, box);
            if (best[bestClass] == null || detection.confidence > best[bestClass].confidence) {
                best[bestClass] = detection;
            }
        }

        if (best[0] != null) {
            result.sprite = best[0];
        }
        if (best[1] != null) {
            result.reticle = best[1];
        }
        if (best[2] != null) {
            result.ballButton = best[2];
        }
    }

    private float value(float[][] output, boolean channelFirst, int box, int channel) {
        return channelFirst ? output[channel][box] : output[box][channel];
    }

    private RectF toFrameBox(float cx, float cy, float w, float h, int frameWidth, int frameHeight) {
        boolean normalized = Math.max(Math.max(cx, cy), Math.max(w, h)) <= 2f;
        float scaleX = normalized ? frameWidth : frameWidth / (float) inputWidth;
        float scaleY = normalized ? frameHeight : frameHeight / (float) inputHeight;
        float left = (cx - w * 0.5f) * scaleX;
        float top = (cy - h * 0.5f) * scaleY;
        float right = (cx + w * 0.5f) * scaleX;
        float bottom = (cy + h * 0.5f) * scaleY;
        return new RectF(
                clamp(left, 0, frameWidth - 1),
                clamp(top, 0, frameHeight - 1),
                clamp(right, 0, frameWidth - 1),
                clamp(bottom, 0, frameHeight - 1));
    }

    private RectF centeredBox(float x, float y, float radius) {
        return new RectF(x - radius, y - radius, x + radius, y + radius);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public synchronized void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}

package com.example.rockcatcherapi;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ApiVisionDetector {
    private long lastRequestMs = 0L;
    private Detection lastSprite;
    private String lastStatus = "api idle";

    DetectionResult detect(Bitmap bitmap, CatchConfig config) {
        DetectionResult result = new DetectionResult();
        result.frameWidth = bitmap.getWidth();
        result.frameHeight = bitmap.getHeight();
        result.reticle = centered("reticle", config.fallbackReticleX, config.fallbackReticleY, 18f, 1f);
        result.ballButton = centered("ball_button", config.fallbackBallX, config.fallbackBallY, 36f, 1f);

        if (config.apiKey.trim().isEmpty()) {
            result.status = "请先填写 API Key";
            return result;
        }
        if (config.apiUrl.trim().isEmpty() || config.apiModel.trim().isEmpty()) {
            result.status = "请先填写 API URL 和模型名";
            return result;
        }

        long now = System.currentTimeMillis();
        if (now - lastRequestMs < config.apiIntervalMs) {
            result.sprite = lastSprite;
            result.status = lastStatus + " cached";
            return result;
        }

        lastRequestMs = now;
        try {
            ApiSprite sprite = requestSprite(bitmap, config);
            if (!sprite.found || sprite.confidence < config.confidence) {
                lastSprite = null;
                lastStatus = String.format(Locale.US, "api no target conf=%.2f %s", sprite.confidence, sprite.reason);
                result.status = lastStatus;
                return result;
            }
            Detection detection = centered("sprite", sprite.x, sprite.y, 28f, sprite.confidence);
            lastSprite = detection;
            lastStatus = String.format(Locale.US, "api target x=%.0f y=%.0f conf=%.2f", sprite.x, sprite.y, sprite.confidence);
            result.sprite = detection;
            result.status = lastStatus;
            return result;
        } catch (Exception ex) {
            result.status = "api error: " + ex.getMessage();
            return result;
        }
    }

    void close() {
        lastSprite = null;
    }

    private ApiSprite requestSprite(Bitmap source, CatchConfig config) throws IOException, JSONException {
        ScaledImage scaled = scaleForApi(source, config.apiImageMaxSidePx);
        String image64 = encodeJpegBase64(scaled.bitmap, config.apiJpegQuality);
        try {
            JSONObject payload = buildPayload(config, scaled.width, scaled.height, image64);
            HttpURLConnection connection = (HttpURLConnection) new URL(config.apiUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(config.apiTimeoutMs);
            connection.setReadTimeout(config.apiTimeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int code = connection.getResponseCode();
            String text = readAll(code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new IOException("http " + code + " " + trimForStatus(text));
            }
            ApiSprite sprite = parseSpriteFromResponse(text);
            if (sprite.found) {
                sprite.x = clamp(sprite.x * source.getWidth() / scaled.width, 0f, source.getWidth() - 1f);
                sprite.y = clamp(sprite.y * source.getHeight() / scaled.height, 0f, source.getHeight() - 1f);
            }
            return sprite;
        } finally {
            if (scaled.bitmap != source) {
                scaled.bitmap.recycle();
            }
        }
    }

    private JSONObject buildPayload(CatchConfig config, int width, int height, String image64) throws JSONException {
        String prompt = "你是手机游戏画面定位器。请在截图中寻找要抓捕的精灵或主要目标，只输出一行 JSON，不要 Markdown。"
                + "JSON 格式必须是 {\"found\":true,\"x\":123,\"y\":456,\"confidence\":0.86,\"reason\":\"短理由\"}。"
                + "x/y 必须是当前发送图片里的像素坐标，左上角为 0,0。"
                + "当前发送图片尺寸 width=" + width + ", height=" + height + "。"
                + "如果没有明确目标，输出 {\"found\":false,\"x\":0,\"y\":0,\"confidence\":0,\"reason\":\"no target\"}。";

        JSONArray content = new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", prompt))
                .put(new JSONObject()
                        .put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + image64)));

        JSONArray messages = new JSONArray()
                .put(new JSONObject()
                        .put("role", "user")
                        .put("content", content));

        JSONObject payload = new JSONObject()
                .put("model", config.apiModel)
                .put("messages", messages)
                .put("temperature", 0)
                .put("max_tokens", 120)
                .put("response_format", new JSONObject().put("type", "json_object"));
        return payload;
    }

    private ApiSprite parseSpriteFromResponse(String responseText) throws JSONException {
        JSONObject root = new JSONObject(responseText);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new JSONException("missing choices");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) {
            throw new JSONException("missing message");
        }
        String content = message.optString("content", "");
        JSONObject answer = extractJsonObject(content);
        ApiSprite sprite = new ApiSprite();
        sprite.found = answer.optBoolean("found", false);
        sprite.x = (float) answer.optDouble("x", 0);
        sprite.y = (float) answer.optDouble("y", 0);
        sprite.confidence = (float) answer.optDouble("confidence", 0);
        sprite.reason = answer.optString("reason", "");
        return sprite;
    }

    private JSONObject extractJsonObject(String content) throws JSONException {
        String trimmed = content == null ? "" : content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new JSONException("missing json answer");
        }
        return new JSONObject(trimmed.substring(start, end + 1));
    }

    private ScaledImage scaleForApi(Bitmap source, int maxSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxSide) {
            return new ScaledImage(source, width, height);
        }
        float scale = (float) maxSide / longest;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        return new ScaledImage(scaled, targetWidth, targetHeight);
    }

    private String encodeJpegBase64(Bitmap bitmap, int quality) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private Detection centered(String label, float centerX, float centerY, float radius, float confidence) {
        return new Detection(label, confidence, new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius));
    }

    private String readAll(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private String trimForStatus(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 180 ? compact.substring(0, 180) : compact;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ScaledImage {
        final Bitmap bitmap;
        final int width;
        final int height;

        ScaledImage(Bitmap bitmap, int width, int height) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
        }
    }

    private static final class ApiSprite {
        boolean found;
        float x;
        float y;
        float confidence;
        String reason = "";
    }
}

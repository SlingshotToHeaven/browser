package com.privacy.browser.ai;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

public class VisionToolExecutor {

    public interface VisionCallback {
        void onCaptured(String base64Jpeg);
        void onError(String error);
    }

    public static void captureViewportBase64(final WebView webView, final VisionCallback callback) {
        if (webView == null) {
            callback.onError("No active WebView to capture.");
            return;
        }

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    int width = webView.getWidth();
                    int height = webView.getHeight();
                    if (width <= 0 || height <= 0) {
                        callback.onError("WebView has invalid dimensions.");
                        return;
                    }

                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    webView.draw(canvas);

                    // Downscale to 480p (shorter edge constrained to 480px, preserving aspect ratio)
                    int shortSide = Math.min(width, height);
                    if (shortSide > 480) {
                        float scale = 480.0f / shortSide;
                        int scaledW = Math.round(width * scale);
                        int scaledH = Math.round(height * scale);
                        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true);
                        bitmap.recycle();
                        bitmap = scaled;
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 75, baos);
                    byte[] bytes = baos.toByteArray();
                    bitmap.recycle();

                    String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    callback.onCaptured(base64);
                } catch (Throwable t) {
                    callback.onError("Failed to capture viewport: " + t.getMessage());
                }
            }
        });
    }

    public static void captureElementBase64(final WebView webView, final String cssSelector, final VisionCallback callback) {
        if (webView == null) {
            callback.onError("No active WebView to capture.");
            return;
        }
        if (cssSelector == null || cssSelector.trim().isEmpty()) {
            callback.onError("Missing cssSelector.");
            return;
        }

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                // Query element bounds in viewport via getBoundingClientRect()
                String js = "(function() {"
                        + "  var el = document.querySelector('" + cssSelector.replace("'", "\\'") + "');"
                        + "  if (!el) return JSON.stringify({status:'not_found'});"
                        + "  var rect = el.getBoundingClientRect();"
                        + "  return JSON.stringify({"
                        + "    status: 'found',"
                        + "    left: rect.left,"
                        + "    top: rect.top,"
                        + "    width: rect.width,"
                        + "    height: rect.height"
                        + "  });"
                        + "})();";

                webView.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        try {
                            if (value != null && value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                                value = value.substring(1, value.length() - 1)
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\");
                            }
                            JSONObject json = new JSONObject(value != null ? value : "{}");
                            if (!"found".equals(json.optString("status"))) {
                                callback.onError("Element not found for selector: " + cssSelector);
                                return;
                            }

                            float left = (float) json.optDouble("left", 0);
                            float top = (float) json.optDouble("top", 0);
                            float w = (float) json.optDouble("width", 0);
                            float h = (float) json.optDouble("height", 0);

                            if (w <= 0 || h <= 0) {
                                callback.onError("Element has zero or negative dimensions.");
                                return;
                            }

                            // Capture viewport bitmap
                            int viewW = webView.getWidth();
                            int viewH = webView.getHeight();
                            Bitmap fullBitmap = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(fullBitmap);
                            webView.draw(canvas);

                            // Multiply by webview scale factor if needed, clamp within bounds
                            int cropX = Math.max(0, Math.min((int) left, viewW - 1));
                            int cropY = Math.max(0, Math.min((int) top, viewH - 1));
                            int cropW = Math.max(1, Math.min((int) w, viewW - cropX));
                            int cropH = Math.max(1, Math.min((int) h, viewH - cropY));

                            Bitmap cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH);
                            fullBitmap.recycle();

                            // Downscale element to 480p boundary if larger
                            int shortSide = Math.min(cropW, cropH);
                            if (shortSide > 480) {
                                float scale = 480.0f / shortSide;
                                int scaledW = Math.round(cropW * scale);
                                int scaledH = Math.round(cropH * scale);
                                Bitmap scaled = Bitmap.createScaledBitmap(cropped, scaledW, scaledH, true);
                                cropped.recycle();
                                cropped = scaled;
                            }

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            cropped.compress(Bitmap.CompressFormat.WEBP, 75, baos);
                            byte[] bytes = baos.toByteArray();
                            cropped.recycle();

                            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                            callback.onCaptured(base64);

                        } catch (Exception e) {
                            callback.onError("Error cropping element: " + e.getMessage());
                        }
                    }
                });
            }
        });
    }
}

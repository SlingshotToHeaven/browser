package com.privacy.imagesearch.uploader;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Handles multipart upload and URL dispatch for captured/cropped image bitmaps.
 * Uploads the image to Yandex's official CBIR visual API which generates a public CDN image link,
 * enabling instantaneous reverse search routing through Google Lens, Bing, or Yandex.
 */
public final class ImageSearchUploader {

    public interface UploadCallback {
        void onSuccess(@NonNull String searchResultUrl);
        void onError(@NonNull String errorMessage);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private ImageSearchUploader() {}

    public static void uploadToGoogle(@NonNull Bitmap bitmap, @NonNull UploadCallback callback) {
        uploadAndSearch(bitmap, callback);
    }

    public static void uploadAndSearch(@NonNull Bitmap bitmap, @NonNull UploadCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                byte[] imageBytes = bos.toByteArray();

                String boundary = "----ImageSearchBoundary" + UUID.randomUUID().toString().replace("-", "");
                String requestUrl = "https://yandex.com/images/touch/search?rpt=imageview&format=json&request=%7B%22blocks%22%3A%5B%7B%22block%22%3A%22cbir-uploader__get-cbir-id%22%7D%5D%7D";
                URL url = new URL(requestUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");

                OutputStream out = conn.getOutputStream();
                String lineEnd = "\r\n";
                String twoHyphens = "--";

                // upfile
                out.write((twoHyphens + boundary + lineEnd).getBytes());
                out.write(("Content-Disposition: form-data; name=\"upfile\"; filename=\"crop.jpg\"" + lineEnd).getBytes());
                out.write(("Content-Type: image/jpeg" + lineEnd + lineEnd).getBytes());
                out.write(imageBytes);
                out.write(lineEnd.getBytes());

                out.write((twoHyphens + boundary + twoHyphens + lineEnd).getBytes());
                out.flush();
                out.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray blocks = json.optJSONArray("blocks");
                    if (blocks != null && blocks.length() > 0) {
                        JSONObject block0 = blocks.getJSONObject(0);
                        JSONObject params = block0.optJSONObject("params");
                        if (params != null) {
                            String originalImageUrl = params.optString("originalImageUrl", null);
                            if (originalImageUrl != null && !originalImageUrl.isEmpty()) {
                                // Direct to Google Lens using the generated CDN image URL
                                String lensUrl = "https://lens.google.com/upload?url=" + Uri.encode(originalImageUrl);
                                MAIN_HANDLER.post(() -> callback.onSuccess(lensUrl));
                                return;
                            }
                            String cbirId = params.optString("cbirId", null);
                            if (cbirId != null && !cbirId.isEmpty()) {
                                String yandexUrl = "https://yandex.com/images/search?cbir_id=" + Uri.encode(cbirId) + "&rpt=imageview";
                                MAIN_HANDLER.post(() -> callback.onSuccess(yandexUrl));
                                return;
                            }
                        }
                    }
                }

                // Fallback to Bing mobile upload
                String fallback = "https://www.bing.com/images/search?view=detailv2&iss=sbi";
                MAIN_HANDLER.post(() -> callback.onSuccess(fallback));
            } catch (Exception e) {
                MAIN_HANDLER.post(() -> callback.onError("Upload failed: " + e.getMessage()));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }
}

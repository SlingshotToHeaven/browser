package com.privacy.browser.adblock.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Java HTTP client communicating with the Cloudflare Worker AdBlock State Sync API.
 */
public class AdBlockCloudClient {

    private static final String TAG = "AdBlockCloudClient";
    private static final String PREFS_NAME = "adblock_cloud_prefs";
    private static final String KEY_ENDPOINT = "cloud_endpoint_url";
    private static final String DEFAULT_ENDPOINT = "https://adblock-state-sync.ishikawaadachi.workers.dev";

    public static class StateSummary {
        public final String stateId;
        public final String description;
        public final String author;
        public final long timestamp;
        public final int filesChanged;

        public StateSummary(String stateId, String description, String author, long timestamp, int filesChanged) {
            this.stateId = stateId;
            this.description = description;
            this.author = author;
            this.timestamp = timestamp;
            this.filesChanged = filesChanged;
        }
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AdBlockCloudClient(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getEndpointUrl() {
        return prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT);
    }

    public void setEndpointUrl(String url) {
        if (url != null) {
            String clean = url.trim();
            if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
            prefs.edit().putString(KEY_ENDPOINT, clean).apply();
        }
    }

    /**
     * Lists the latest community state summaries from the cloud.
     */
    public void listStates(@NonNull Callback<List<StateSummary>> callback) {
        executor.execute(() -> {
            try {
                String endpoint = getEndpointUrl() + "/api/states";
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    String jsonStr = readStream(conn.getInputStream());
                    JSONArray arr = new JSONArray(jsonStr);
                    List<StateSummary> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        list.add(new StateSummary(
                            item.optString("state_id", "unknown"),
                            item.optString("description", "Untitled State"),
                            item.optString("author", "Anonymous"),
                            item.optLong("timestamp", 0),
                            item.optInt("files_changed", 0)
                        ));
                    }
                    mainHandler.post(() -> callback.onSuccess(list));
                } else {
                    String err = readStream(conn.getErrorStream());
                    mainHandler.post(() -> callback.onError("Server returned " + code + ": " + err));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed listing cloud states", e);
                mainHandler.post(() -> callback.onError("Connection error: " + e.getMessage()));
            }
        });
    }

    /**
     * Downloads a complete state payload (including diffs) by ID.
     */
    public void downloadState(@NonNull String stateId, @NonNull Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                String endpoint = getEndpointUrl() + "/api/states/" + stateId;
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    String jsonStr = readStream(conn.getInputStream());
                    JSONObject obj = new JSONObject(jsonStr);
                    mainHandler.post(() -> callback.onSuccess(obj));
                } else {
                    String err = readStream(conn.getErrorStream());
                    mainHandler.post(() -> callback.onError("Failed fetching state (" + code + "): " + err));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed downloading state", e);
                mainHandler.post(() -> callback.onError("Network error: " + e.getMessage()));
            }
        });
    }

    /**
     * Commits/uploads a new state payload to the cloud.
     */
    public void commitState(@NonNull JSONObject statePayload, @NonNull Callback<String> callback) {
        executor.execute(() -> {
            try {
                String endpoint = getEndpointUrl() + "/api/states";
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                byte[] postBytes = statePayload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    String jsonStr = readStream(conn.getInputStream());
                    JSONObject res = new JSONObject(jsonStr);
                    String stateId = res.optString("state_id", "state_uploaded");
                    mainHandler.post(() -> callback.onSuccess(stateId));
                } else {
                    String err = readStream(conn.getErrorStream());
                    mainHandler.post(() -> callback.onError("Upload failed (" + code + "): " + err));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed uploading state commit", e);
                mainHandler.post(() -> callback.onError("Upload error: " + e.getMessage()));
            }
        });
    }

    private static String readStream(@Nullable InputStream is) {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}

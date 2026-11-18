package com.privacy.browser.adblock.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.downloader.net.RequestContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.privacy.browser.adblock.cloud.RuleSnapshotWire.*;

final class AdBlockCloudClient implements AutoCloseable {
    private static final String API = "/api/v2/states";
    private static final String DEFAULT_ENDPOINT = "https://adblock-state-sync.ishikawaadachi.workers.dev";
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile HttpURLConnection active;

    static final class Summary {
        final String id, description;
        final long timestamp;
        Summary(JSONObject object) throws Exception {
            id = validId(object.getString("state_id"));
            description = validDescription(object.getString("description"));
            timestamp = object.getLong("timestamp");
        }
    }

    static final class Page {
        final List<Summary> entries;
        final long next;
        Page(List<Summary> entries, long next) { this.entries = entries; this.next = next; }
    }

    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    AdBlockCloudClient(Context context) {
        prefs = context.getSharedPreferences("adblock_cloud_prefs", Context.MODE_PRIVATE);
    }

    String getEndpointUrl() { return prefs.getString("cloud_endpoint_url", DEFAULT_ENDPOINT); }

    void setEndpointUrl(String value) { prefs.edit().putString("cloud_endpoint_url", endpoint(value)).apply(); }

    private static String endpoint(String value) {
        String clean = value.trim();
        String origin = RequestContext.origin(clean);
        if (origin == null || !origin.startsWith("https://") || clean.length() > 250) {
            throw new IllegalArgumentException("Enter an HTTPS Worker origin.");
        }
        URI uri = URI.create(clean);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("Use the Worker origin without an endpoint path or query.");
        }
        return origin;
    }

    void list(String source, long before, Callback<Page> callback) {
        run(() -> {
            JSONObject result = request(source, API + (before > 0 ? "?before=" + before : ""), null, 256 * 1024);
            requireV2(result);
            JSONArray array = result.getJSONArray("states");
            if (array.length() > 100) throw new IOException("Catalog returned too many rows.");
            List<Summary> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) entries.add(new Summary(array.getJSONObject(i)));
            long next = result.optLong("next_before", 0);
            if (next < 0 || (before > 0 && next >= before)) throw new IOException("Invalid catalog cursor.");
            return new Page(entries, next);
        }, callback);
    }

    void download(String source, String id, Callback<FilterSnapshot> callback) {
        run(() -> RuleSnapshotWire.decode(request(source, API + "/" + validId(id), null, MAX_BODY), id), callback);
    }

    void publish(String source, String description, FilterSnapshot snapshot, Callback<String> callback) {
        run(() -> {
            JSONObject body = RuleSnapshotWire.encode(description, snapshot);
            JSONObject result = request(source, API, body.toString().getBytes(StandardCharsets.UTF_8), 16 * 1024);
            requireV2(result);
            if (!Boolean.TRUE.equals(result.opt("success"))) throw new IOException("Catalog did not confirm publication.");
            return validId(result.getString("state_id"));
        }, callback);
    }

    private JSONObject request(String source, String route, byte[] body, int maxBytes) throws Exception {
        if (body != null && body.length > MAX_BODY) throw new IOException("Encoded snapshot is too large to publish.");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint(source) + route).openConnection();
        active = connection;
        try {
            if (closed) throw new IOException("Catalog closed.");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            }
            int status = connection.getResponseCode();
            if (status != 200 && !(body != null && status == 201)) {
                if (status == 404 || status == 410) throw new IOException("Snapshot or v2 endpoint not found. Check the Worker version.");
                if (status == 403) throw new IOException("The catalog operator disabled publishing.");
                if (status == 413) throw new IOException("The Worker rejected the snapshot size.");
                if (status == 507) throw new IOException("The catalog is full. No new state was published.");
                throw new IOException("Catalog request failed (HTTP " + status + ").");
            }
            if (connection.getContentLengthLong() > maxBytes) throw new IOException("Catalog response is too large.");
            try (InputStream in = connection.getInputStream()) {
                String text = FilterSnapshot.readText(in, maxBytes);
                if (!boundedJson(text)) throw new IOException("Malformed catalog response.");
                return new JSONObject(text);
            }
        } finally {
            active = null;
            connection.disconnect();
        }
    }

    private static boolean boundedJson(String text) {
        int depth = 0;
        boolean inside = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inside) {
                if (ch == '\\') i++;
                else if (ch == '"') inside = false;
            } else if (ch == '"') inside = true;
            else if (ch == '[' || ch == '{') { if (++depth > 5) return false; }
            else if (ch == ']' || ch == '}') { if (--depth < 0) return false; }
            else if (ch == '/' || ch == '\'' || ch == '#' || ch == '\\') return false;
        }
        return !inside && depth == 0;
    }

    <T> void run(Callable<T> work, Callback<T> callback) {
        if (closed) return;
        executor.execute(() -> {
            try {
                T value = work.call();
                main.post(() -> { if (!closed) callback.onSuccess(value); });
            } catch (Exception e) {
                String reason = e.getMessage();
                String message = reason == null || reason.length() > 250 ? "Catalog operation failed." : reason;
                main.post(() -> { if (!closed) callback.onError(message); });
            }
        });
    }

    @Override public void close() {
        closed = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
        executor.shutdownNow();
    }
}

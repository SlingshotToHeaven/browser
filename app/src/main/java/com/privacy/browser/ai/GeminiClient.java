package com.privacy.browser.ai;

import android.content.Context;

import com.privacy.browser.adblock.rules.FilterSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

final class GeminiClient implements AgentSessionManager.Provider {
    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int RESPONSE_LIMIT = 512 * 1024;
    private final AiConfigManager config;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1));
    private FutureTask<Void> task;
    private final JSONArray tools;
    private long generation;
    private HttpsURLConnection active;

    GeminiClient(Context context) {
        config = new AiConfigManager(context);
        tools = loadTools(context);
    }

    private static JSONArray loadTools(Context context) {
        try (InputStream in = context.getAssets().open("ai/ai_assistant_guide.json")) {
            JSONArray input = new JSONObject(FilterSnapshot.readText(in, 64 * 1024)).getJSONArray("tools");
            JSONArray declared = new JSONArray();
            for (int i = 0; i < input.length(); i++) {
                JSONObject function = input.getJSONObject(i);
                if (AssistantAction.Kind.find(function.getString("name")) != null) declared.put(function);
            }
            return new JSONArray().put(new JSONObject().put("function_declarations", declared));
        } catch (Exception ignored) { return null; }
    }

    @Override public synchronized void cancelCurrentRequest() {
        generation++;
        if (task != null) {
            task.cancel(true);
            executor.remove(task);
            task = null;
        }
        if (active != null) active.disconnect();
        active = null;
    }

    private synchronized boolean current(long expected, GeminiSettings selection) {
        return generation == expected && selection.matches(config.connection());
    }

    @Override public synchronized void generateContent(GeminiSettings selection, JSONArray contents, ResponseCallback callback) {
        cancelCurrentRequest();
        final long token = generation;
        task = new FutureTask<>(() -> {
            if (!current(token, selection)) { callback.onError("Agent settings or request changed. Nothing was sent."); return null; }
            try { callback.onSuccess(request(token, selection, contents)); }
            catch (IOException e) { callback.onError(e.getMessage()); }
            catch (Exception e) { callback.onError("Gemini request failed or returned an invalid response."); }
            return null;
        });
        executor.execute(task);
    }

    private GeminiProtocol.Response request(long token, GeminiSettings selection, JSONArray contents) throws Exception {
        JSONObject payload = GeminiProtocol.payload(contents, tools, selection.tools);
        byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > 400 * 1024) throw new IOException("Assistant request exceeds its size limit.");

        HttpsURLConnection connection = (HttpsURLConnection) new URL(GEMINI + selection.model + ":generateContent").openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", selection.apiKey);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(data.length);
            synchronized (this) {
                if (!current(token, selection)) throw new IOException("Request superseded before dispatch.");
                active = connection;
            }
            // A Stop after this point disconnects best-effort; it cannot recall transmitted bytes.
            try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("Selected provider rejected the request (HTTP " + status + ").");
            try (InputStream in = connection.getInputStream()) { return GeminiProtocol.parse(FilterSnapshot.readText(in, RESPONSE_LIMIT), selection.tools); }
        } finally {
            synchronized (this) { if (active == connection) active = null; }
            connection.disconnect();
        }
    }

}

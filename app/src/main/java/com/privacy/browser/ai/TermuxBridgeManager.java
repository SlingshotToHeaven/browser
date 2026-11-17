package com.privacy.browser.ai;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TermuxBridgeManager {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface PingCallback {
        void onResult(boolean isOnline, long latencyMs, String engineName, String message);
    }

    public static void pingBridge(final String endpointUrl, final PingCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                try {
                    String cleanUrl = endpointUrl != null && !endpointUrl.trim().isEmpty()
                            ? endpointUrl.trim()
                            : AiConfigManager.DEFAULT_TERMUX_ENDPOINT;

                    if (!cleanUrl.endsWith("/")) {
                        cleanUrl += "/";
                    }

                    URL url = new URL(cleanUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);

                    int code = conn.getResponseCode();
                    final long latency = System.currentTimeMillis() - start;

                    String engine = "Antigravity CLI (Google AI Pro)";
                    String toolSummary = "";
                    if (code >= 200 && code < 300) {
                        try (InputStream is = conn.getInputStream();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line);
                            }
                            JSONObject json = new JSONObject(sb.toString());
                            if (json.has("engine")) {
                                engine = json.getString("engine");
                            }
                            org.json.JSONArray tools = json.optJSONArray("termux_tools");
                            if (tools != null && tools.length() > 0) {
                                toolSummary = " [" + tools.length() + " CLI tools]";
                            }
                        } catch (Exception ignored) {
                        }

                        final String finalEngine = engine + toolSummary;
                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onResult(true, latency, finalEngine, "Active (" + latency + "ms) - " + finalEngine);
                                }
                            }
                        });
                    } else {
                        MAIN_HANDLER.post(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onResult(false, latency, null, "HTTP " + code + " (" + latency + "ms) - Bridge Error");
                                }
                            }
                        });
                    }
                    conn.disconnect();

                } catch (final Exception e) {
                    final long latency = System.currentTimeMillis() - start;
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                String err = e.getMessage() != null ? e.getMessage() : e.toString();
                                if (err.contains("refused") || err.contains("ECONNREFUSED") || err.contains("failed to connect")) {
                                    callback.onResult(false, latency, null, "Offline (Start 'python ~/browser_agent_bridge.py' in Termux)");
                                } else {
                                    callback.onResult(false, latency, null, "Offline: " + err);
                                }
                            }
                        }
                    });
                }
            }
        }).start();
    }

    public static void openTermux(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage("com.termux");
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } else {
                Toast.makeText(context, "Termux app is not installed on this device", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Failed to launch Termux: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}

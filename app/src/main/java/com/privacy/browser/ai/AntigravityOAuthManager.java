package com.privacy.browser.ai;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.privacy.browser.MainActivity;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class AntigravityOAuthManager {
    private static final String TAG = "AntigravityOAuth";

    public static final String CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    public static final String CLIENT_SECRET = "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";
    public static final int LOOPBACK_PORT = 8085;
    public static final String REDIRECT_URI = "http://127.0.0.1:" + LOOPBACK_PORT;

    public static final String[] SCOPES = new String[]{
            "email",
            "profile",
            "openid",
            "https://www.googleapis.com/auth/aicode",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/experimentsandconfigs"
    };

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static ServerSocket loopbackServer;
    private static Thread serverThread;

    public interface AuthCallback {
        void onSuccess(String email);
        void onError(Exception e);
    }

    public static String buildAuthUrl() {
        try {
            StringBuilder sb = new StringBuilder("https://accounts.google.com/o/oauth2/auth?");
            sb.append("client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"));
            sb.append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"));
            sb.append("&response_type=code");
            sb.append("&scope=").append(URLEncoder.encode(String.join(" ", SCOPES), "UTF-8"));
            sb.append("&access_type=offline");
            sb.append("&prompt=consent");
            return sb.toString();
        } catch (Exception e) {
            return "https://accounts.google.com/o/oauth2/auth";
        }
    }

    public static synchronized void stopLoopbackServer() {
        try {
            if (loopbackServer != null && !loopbackServer.isClosed()) {
                loopbackServer.close();
            }
        } catch (Exception ignored) {
        }
    }

    public static void startOAuthFlow(final Context context, final AiConfigManager configManager, final AuthCallback callback) {
        stopLoopbackServer();

        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loopbackServer = new ServerSocket(LOOPBACK_PORT);
                    Log.i(TAG, "Loopback server listening on " + REDIRECT_URI);
                    Socket socket = loopbackServer.accept();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String requestLine = reader.readLine();
                    Log.i(TAG, "Received HTTP callback: " + requestLine);

                    String code = null;
                    if (requestLine != null && requestLine.contains("GET /?")) {
                        String query = requestLine.split(" ")[1].substring(2);
                        String[] params = query.split("&");
                        for (String p : params) {
                            if (p.startsWith("code=")) {
                                code = java.net.URLDecoder.decode(p.substring(5), "UTF-8");
                                break;
                            }
                        }
                    }

                    // Return HTML response to browser tab
                    OutputStream os = socket.getOutputStream();
                    String html = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n"
                            + "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                            + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                            + "<title>Antigravity Authentication</title>"
                            + "<style>"
                            + "body { background: #0F172A; color: #FFFFFF; font-family: sans-serif; text-align: center; padding: 60px 20px; }"
                            + "h2 { color: #38BDF8; font-size: 22px; }"
                            + "p { color: #94A3B8; font-size: 14px; }"
                            + "</style></head><body>"
                            + "<h2>Authentication Successful</h2>"
                            + "<p>Your Google account is being linked to Antigravity...</p>"
                            + "</body></html>";
                    os.write(html.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    socket.close();
                    stopLoopbackServer();

                    // Proactively bring Privacy Browser back to foreground to ensure network permissions are active
                    try {
                        Intent bringToFront = new Intent(context, MainActivity.class);
                        bringToFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        context.startActivity(bringToFront);
                    } catch (Exception ignored) {
                    }

                    if (code != null) {
                        exchangeCodeForTokens(context, configManager, code, callback);
                    } else {
                        postError(callback, new Exception("No authorization code received from Google"));
                    }

                } catch (Exception e) {
                    if (loopbackServer != null && !loopbackServer.isClosed()) {
                        Log.e(TAG, "Loopback error: " + e.getMessage(), e);
                        postError(callback, e);
                    }
                }
            }
        });
        serverThread.start();

        // Launch sign-in url explicitly targeted at Privacy Browser (prevent opening in Chrome)
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(buildAuthUrl()));
        intent.setPackage(context.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback to default browser if explicit package intent fails
            intent.setPackage(null);
            context.startActivity(intent);
        }
    }

    private static void exchangeCodeForTokens(final Context context, final AiConfigManager configManager, final String code, final AuthCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int maxRetries = 5;
                Exception lastException = null;

                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    HttpURLConnection conn = null;
                    try {
                        // Allow network interface / DNS to settle if app just switched foreground
                        if (attempt > 1) {
                            try {
                                Thread.sleep(600L * attempt);
                            } catch (InterruptedException ignored) {
                            }
                        } else {
                            try {
                                Thread.sleep(300L);
                            } catch (InterruptedException ignored) {
                            }
                        }

                        URL tokenUrl = new URL("https://oauth2.googleapis.com/token");
                        conn = (HttpURLConnection) tokenUrl.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(15000);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                        StringBuilder postData = new StringBuilder();
                        postData.append("code=").append(URLEncoder.encode(code.trim(), "UTF-8"));
                        postData.append("&client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"));
                        postData.append("&client_secret=").append(URLEncoder.encode(CLIENT_SECRET, "UTF-8"));
                        postData.append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"));
                        postData.append("&grant_type=authorization_code");

                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(postData.toString().getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }

                        int respCode = conn.getResponseCode();
                        InputStream is = (respCode >= 200 && respCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                        String body = readStream(is);

                        if (respCode < 200 || respCode >= 300) {
                            throw new Exception("Token exchange failed HTTP " + respCode + ": " + body);
                        }

                        JSONObject tokenJson = new JSONObject(body);
                        String accessToken = tokenJson.getString("access_token");
                        String refreshToken = tokenJson.optString("refresh_token", "");
                        int expiresIn = tokenJson.optInt("expires_in", 3600);
                        long expiresAt = System.currentTimeMillis() + (expiresIn * 1000L);

                        String userEmail = fetchUserEmail(accessToken);

                        JSONObject authRecord = new JSONObject();
                        authRecord.put("access_token", accessToken);
                        authRecord.put("refresh_token", refreshToken);
                        authRecord.put("email", userEmail);
                        authRecord.put("expires_at", expiresAt);

                        configManager.setAntigravityAuthJson(authRecord.toString());
                        postSuccess(callback, userEmail);
                        return; // Successfully exchanged!

                    } catch (Exception e) {
                        lastException = e;
                        Log.w(TAG, "Code exchange attempt " + attempt + " failed: " + e.getMessage());
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                }

                Log.e(TAG, "Code exchange failed after " + maxRetries + " attempts", lastException);
                postError(callback, lastException);
            }
        }).start();
    }

    public static String getOrRefreshAccessToken(AiConfigManager configManager) throws Exception {
        String authJsonStr = configManager.getAntigravityAuthJson();
        if (authJsonStr == null || authJsonStr.trim().isEmpty()) {
            throw new IllegalStateException("Antigravity account is not connected. Please sign in via Settings.");
        }

        JSONObject authJson = new JSONObject(authJsonStr);
        String accessToken = authJson.optString("access_token", "");
        long expiresAt = authJson.optLong("expires_at", 0);
        String refreshToken = authJson.optString("refresh_token", "");

        // If access token is still valid with at least 60 seconds buffer
        if (!accessToken.isEmpty() && expiresAt > System.currentTimeMillis() + 60000L) {
            return accessToken;
        }

        if (refreshToken.isEmpty()) {
            if (!accessToken.isEmpty()) {
                return accessToken; // Fall back to existing token if no refresh token available
            }
            throw new IllegalStateException("Missing refresh token for Antigravity authentication.");
        }

        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpURLConnection conn = null;
            try {
                if (attempt > 1) {
                    try {
                        Thread.sleep(600L * attempt);
                    } catch (InterruptedException ignored) {
                    }
                }

                URL tokenUrl = new URL("https://oauth2.googleapis.com/token");
                conn = (HttpURLConnection) tokenUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                StringBuilder postData = new StringBuilder();
                postData.append("client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"));
                postData.append("&client_secret=").append(URLEncoder.encode(CLIENT_SECRET, "UTF-8"));
                postData.append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"));
                postData.append("&grant_type=refresh_token");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postData.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int respCode = conn.getResponseCode();
                InputStream is = (respCode >= 200 && respCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String body = readStream(is);

                if (respCode < 200 || respCode >= 300) {
                    throw new Exception("Token refresh failed (" + respCode + "): " + body);
                }

                JSONObject tokenJson = new JSONObject(body);
                String newAccessToken = tokenJson.getString("access_token");
                int expiresIn = tokenJson.optInt("expires_in", 3600);
                long newExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L);

                authJson.put("access_token", newAccessToken);
                authJson.put("expires_at", newExpiresAt);
                if (tokenJson.has("refresh_token")) {
                    authJson.put("refresh_token", tokenJson.getString("refresh_token"));
                }

                configManager.setAntigravityAuthJson(authJson.toString());
                return newAccessToken;

            } catch (Exception e) {
                lastException = e;
                Log.w(TAG, "Token refresh attempt " + attempt + " failed: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        throw lastException != null ? lastException : new Exception("Token refresh failed");
    }

    private static String fetchUserEmail(String accessToken) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                if (attempt > 1) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ignored) {
                    }
                }
                URL url = new URL("https://www.googleapis.com/oauth2/v3/userinfo");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() == 200) {
                    String body = readStream(conn.getInputStream());
                    JSONObject json = new JSONObject(body);
                    return json.optString("email", "Google User");
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return "Connected Google Account";
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toString("UTF-8");
    }

    private static void postSuccess(final AuthCallback callback, final String email) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onSuccess(email);
            }
        });
    }

    private static void postError(final AuthCallback callback, final Exception e) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onError(e);
            }
        });
    }
}

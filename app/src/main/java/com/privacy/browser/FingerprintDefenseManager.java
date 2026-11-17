package com.privacy.browser;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

/**
 * Manages document-start script injection and two-way bridge communication
 * for anti-fingerprinting protection.
 */
public class FingerprintDefenseManager {

    private static final String TAG = "FingerprintDefense";
    private static final String SCRIPT_PATH = "scripts/fingerprint_defense.js";
    private static final String BRIDGE_NAME = "PrivacyShieldBridge";

    public interface ShieldEventListener {
        void onFingerprintIntercepted(String apiType);
    }

    private static String cachedScript = null;

    public static void attach(@NonNull Context context,
                              @NonNull WebView webView,
                              @NonNull ShieldEventListener listener) {
        // 1. Establish Two-Way Bridge for Telemetry
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Set<String> allowedOrigins = Collections.singleton("*");
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                allowedOrigins,
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    String data = message.getData();
                    if (data != null && data.contains("FINGERPRINT_INTERCEPTED")) {
                        Log.d(TAG, "Shield event from: " + sourceOrigin + " -> " + data);
                        listener.onFingerprintIntercepted(data);
                    }
                }
            );
        } else {
            Log.w(TAG, "WEB_MESSAGE_LISTENER feature not supported by current WebView provider.");
        }

        // 2. Inject Anti-Fingerprinting Script at document_start
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            String script = loadScript(context);
            if (script != null && !script.isEmpty()) {
                Set<String> allowedOrigins = Collections.singleton("*");
                WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOrigins);
                Log.i(TAG, "FingerprintDefense.js successfully registered at document_start.");
            }
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT feature not supported by current WebView provider.");
        }
    }

    private static synchronized String loadScript(@NonNull Context context) {
        if (cachedScript != null) {
            return cachedScript;
        }

        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getAssets().open(SCRIPT_PATH);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            cachedScript = sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load " + SCRIPT_PATH, e);
        }
        return cachedScript;
    }
}

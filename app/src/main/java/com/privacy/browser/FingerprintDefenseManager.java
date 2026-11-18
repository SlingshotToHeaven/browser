package com.privacy.browser;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Manages document-start script injection
 * for anti-fingerprinting protection.
 */
public final class FingerprintDefenseManager {

    private static final String TAG = "FingerprintDefense";
    private static final String SCRIPT_PATH = "scripts/fingerprint_defense.js";
    private static String cachedScript;

    static void prepare(@NonNull Context context) {
        String script = loadScript(context);
        if (script == null || script.isEmpty()) throw new IllegalStateException("Missing fingerprint script");
    }

    public static ScriptHandler attach(@NonNull Context context, @NonNull WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null;
        String script = loadScript(context);
        if (script != null && !script.isEmpty()) {
            try {
                return WebViewCompat.addDocumentStartJavaScript(webView, InternalHome.excludeFromScript(script), Collections.singleton("*"));
            } catch (RuntimeException e) {
                Log.w(TAG, "Provider did not register fingerprint protection");
            }
        }
        return null;
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

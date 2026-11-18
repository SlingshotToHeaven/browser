package com.privacy.browser.adblock;

import android.content.Context;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;

import com.privacy.browser.InternalHome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

/** Scoped registration and a main-frame commit fallback, never a second install in the same page. */
public final class YouTubeAdDefuser {
    static final int MAX_SCRIPT_BYTES = 256 * 1024;
    private static final String ASSET = "filters/scriptlets/youtube_defuser.js";
    private static final String LEGACY_SHA256 = "f49cdcd814b26c93a41c07ffdf27c57ee971797803e8b579ba253fa02efc41a2";
    private static volatile String cached;

    private YouTubeAdDefuser() {}

    static boolean isLegacyDefault(String source) {
        try {
            String normalized = source.endsWith("\n") ? source : source + "\n";
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b & 0xff) >> 4, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return LEGACY_SHA256.contentEquals(hex);
        } catch (java.security.NoSuchAlgorithmException e) {
            return false;
        }
    }

    static synchronized void prepare(Context context) {
        if (getScriptJs(context).isEmpty()) {
            cached = null; // allow a retry; don't memoize a failed startup read
            throw new IllegalStateException("Missing YouTube defuser script");
        }
    }

    private static synchronized String getScriptJs(Context context) {
        if (cached != null) return cached;
        AdBlockFileManager files = AdBlockFileManager.getInstance(context);
        java.io.File seed = files.getFile(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER);
        String source = seed.length() <= MAX_SCRIPT_BYTES ? files.readFileContent(AdBlockFileManager.Category.SCRIPTLETS,
                AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER) : "";
        if (source.trim().isEmpty() || isLegacyDefault(source)) {
            try (InputStream in = context.getAssets().open(ASSET)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int count;
                while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
                source = out.toString("UTF-8");
            } catch (IOException e) {
                // Fail closed rather than resurrecting the obsolete Java copy.
                source = "";
            }
        }
        cached = source;
        return source;
    }

    public static synchronized void clearCache() {
        cached = null;
        WebScriptPolicy.changed();
    }

    static ScriptHandler register(WebView view, AdBlockManager policy) {
        Set<String> origins = YouTubeScope.selectOrigins(policy.isAntiAdblockDefuserEnabled(), policy::isAdBlockActiveForSite);
        if (origins.isEmpty()) return null;
        String code = getScriptJs(view.getContext());
        if (code.isEmpty()) return null;
        Set<String> cosmeticOrigins = YouTubeScope.selectOrigins(policy.isCosmeticFilteringEnabled(),
                origin -> origins.contains(origin) && policy.isSiteCosmeticEnabled(origin));
        try {
            return WebViewCompat.addDocumentStartJavaScript(view,
                    YouTubeScope.wrap(origins, cosmeticOrigins, !policy.hasSiteExceptions(),
                            !policy.hasCosmeticExceptions(), code), origins);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void injectOnCommittedPage(WebView view, String url, AdBlockManager policy) {
        String origin = YouTubeScope.origin(url);
        if (origin == null || !policy.isAntiAdblockDefuserEnabled() || !policy.isAdBlockActiveForSite(url)) return;
        String code = getScriptJs(view.getContext());
        if (code.isEmpty()) return;
        Set<String> origins = Collections.singleton(origin);
        Set<String> cosmeticOrigins = policy.isSiteCosmeticEnabled(url) ? origins : Collections.emptySet();
        view.evaluateJavascript(InternalHome.guardExternalPage(url,
                YouTubeScope.wrap(origins, cosmeticOrigins, false, false, code)), null);
    }
}

package com.privacy.browser.adblock;

import android.content.Context;
import android.webkit.WebView;

import androidx.annotation.NonNull;


/**
 * Scriptlet engine neutralizing rogue popups, tab-unders, window.open hijacking,
 * and transparent full-screen clickjacking overlays from ad networks.
 */
public class PopupClickDefuser {

    private static volatile String cachedDefuserJs = null;

    @NonNull
    public static String getDefuserJs(@NonNull Context context) {
        if (cachedDefuserJs != null) return cachedDefuserJs;
        synchronized (PopupClickDefuser.class) {
            if (cachedDefuserJs != null) return cachedDefuserJs;
            AdBlockFileManager fileManager = AdBlockFileManager.getInstance(context);
            cachedDefuserJs = fileManager.optimizedScriptlet(AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER);
            return cachedDefuserJs;
        }
    }

    public static void clearCache() {
        synchronized (PopupClickDefuser.class) {
            cachedDefuserJs = null;
            WebScriptPolicy.changed();
        }
    }

    public static void inject(@NonNull WebView webView, String committedUrl) {
        String js = getDefuserJs(webView.getContext().getApplicationContext());
        webView.evaluateJavascript(com.privacy.browser.InternalHome.guardExternalPage(committedUrl, js), null);
    }
}

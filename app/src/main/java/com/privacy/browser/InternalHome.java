package com.privacy.browser;

import java.net.URI;
import java.net.URISyntaxException;

/** One app-owned HTTPS origin. Never serve other assets or use a network fallback for this host. */
public final class InternalHome {
    public static final String HOST = "home.privacy-browser.invalid";
    public static final String ORIGIN = "https://" + HOST;
    public static final String URL = ORIGIN + "/";
    private static final String LEGACY = "file:///android_asset/home.html";

    private InternalHome() {}

    public static boolean isHome(String url) {
        return matchesPage(url, URL);
    }

    public static boolean isLegacy(String url) {
        return matchesPage(url, LEGACY);
    }

    private static boolean matchesPage(String url, String page) {
        return url != null && (url.equals(page) || url.startsWith(page + "#"));
    }

    public static String migrate(String url) {
        return isLegacy(url) || (url != null && url.startsWith(LEGACY + "?")) ? URL : url;
    }

    public static boolean ownsHost(String url) {
        if (url == null) return false;
        try {
            String host = new URI(url).getHost();
            return HOST.equalsIgnoreCase(host) || (HOST + ".").equalsIgnoreCase(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static boolean allowsMessage(boolean mainFrame, String origin, String committedUrl) {
        return mainFrame && ORIGIN.equals(origin) && isHome(committedUrl);
    }

    public static String guardExternalPage(String url, String script) {
        return "(function(){if(window.top!==window||location.href!==" + org.json.JSONObject.quote(url)
                + " || location.origin==='" + ORIGIN + "')return;\n" + script + "\n})();";
    }

    public static String excludeFromScript(String script) {
        return "(function(){if((location.protocol!=='http:'&&location.protocol!=='https:')"
                + "||location.hostname==='" + HOST + "'||location.hostname==='" + HOST + ".')return;\n"
                + "if(window.__privacyFingerprintInstalled)return;window.__privacyFingerprintInstalled=true;\n"
                + script + "\n})();";
    }
}

package com.privacy.browser.adblock;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Manages standalone custom user scriptlets, handles metadata parsing (@run-at, @match, @enabled),
 * and dynamically injects them at the user's chosen timing (document-start or document-end).
 */
public class UserScriptManager {

    private static final String TAG = "UserScriptManager";

    public enum RunAt {
        DOCUMENT_START("document-start"),
        DOCUMENT_END("document-end");

        public final String identifier;
        public String displayLabel() { return this == DOCUMENT_START ? "After page commit" : "After page load"; }

        RunAt(String id) {
            this.identifier = id;
        }

        @NonNull
        public static RunAt fromString(@Nullable String str) {
            if (str != null) {
                String s = str.trim().toLowerCase(Locale.US);
                if (s.contains("end") || s.contains("finish") || s.contains("idle") || s.contains("ready")) {
                    return DOCUMENT_END;
                }
            }
            return DOCUMENT_START;
        }
    }

    public static class ScriptModel {
        public String fileName;
        public String name;
        public RunAt runAt;
        public String match;
        public boolean enabled;
        public String code;
    }

    public static class ScriptPreset {
        public final String id;
        public final String label;
        public final String defaultFileName;
        public final String defaultMatch;
        public final RunAt recommendedRunAt;
        public final String description;
        public final String codeTemplate;

        public ScriptPreset(String id, String label, String defaultFileName, String defaultMatch,
                            RunAt recommendedRunAt, String description, String codeTemplate) {
            this.id = id;
            this.label = label;
            this.defaultFileName = defaultFileName;
            this.defaultMatch = defaultMatch;
            this.recommendedRunAt = recommendedRunAt;
            this.description = description;
            this.codeTemplate = codeTemplate;
        }
    }

    @NonNull
    public static List<ScriptPreset> getBuiltInPresets() {
        List<ScriptPreset> list = new ArrayList<>();

        list.add(new ScriptPreset(
                "blank",
                "Blank Scriptlet",
                "custom_script",
                "*",
                RunAt.DOCUMENT_START,
                "Empty starter template for custom JavaScript adblocking or page modification.",
                "(function() {\n" +
                "    'use strict';\n" +
                "    // Custom adblock scriptlet logic\n" +
                "})();\n"
        ));

        list.add(new ScriptPreset(
                "popup_interceptor",
                "window.open (Popups)",
                "popup_interceptor",
                "*",
                RunAt.DOCUMENT_START,
                "Hooks window.open after the main page commits; cannot block scripts that already ran.",
                "(function() {\n" +
                "    'use strict';\n" +
                "    // Intercept and sanitize window.open calls to suppress unwanted popups/redirects\n" +
                "    const originalOpen = window.open;\n" +
                "    window.open = function(url, target, features) {\n" +
                "        console.log('[AdBlock] Blocked or filtered window.open popup:', url);\n" +
                "        return null;\n" +
                "    };\n" +
                "})();\n"
        ));

        list.add(new ScriptPreset(
                "yt_defuser",
                "ytInitialPlayerResponse (YouTube)",
                "yt_ad_defuser",
                "https://*.youtube.com/*",
                RunAt.DOCUMENT_START,
                "Prunes adPlacements and adSlots from later ytInitialPlayerResponse assignments after page commit.",
                "(function() {\n" +
                "    'use strict';\n" +
                "    // Intercept ytInitialPlayerResponse to strip adPlacements before player initialization\n" +
                "    let _playerResp = undefined;\n" +
                "    Object.defineProperty(window, 'ytInitialPlayerResponse', {\n" +
                "        configurable: true,\n" +
                "        enumerable: true,\n" +
                "        get: function() { return _playerResp; },\n" +
                "        set: function(val) {\n" +
                "            if (val && typeof val === 'object') {\n" +
                "                if (val.adPlacements) delete val.adPlacements;\n" +
                "                if (val.adSlots) delete val.adSlots;\n" +
                "                if (val.playerAds) delete val.playerAds;\n" +
                "            }\n" +
                "            _playerResp = val;\n" +
                "        }\n" +
                "    });\n" +
                "})();\n"
        ));

        list.add(new ScriptPreset(
                "anti_adblock_defuser",
                "adsbygoogle (Anti-Adblock)",
                "anti_adblock_defuser",
                "*",
                RunAt.DOCUMENT_START,
                "Stubs window.adsbygoogle and common bait detection probes so adblock-detecting sites proceed normally.",
                "(function() {\n" +
                "    'use strict';\n" +
                "    // Defuse anti-adblock detection probes that check window.adsbygoogle\n" +
                "    window.adsbygoogle = window.adsbygoogle || [];\n" +
                "    window.adsbygoogle.loaded = true;\n" +
                "    window.adsbygoogle.push = function(arg) {\n" +
                "        return 1;\n" +
                "    };\n" +
                "})();\n"
        ));

        list.add(new ScriptPreset(
                "network_interceptor",
                "fetch / XHR (Network)",
                "network_interceptor",
                "*",
                RunAt.DOCUMENT_START,
                "Hooks window.fetch and XMLHttpRequest to inspect, monitor, or drop tracking and analytics requests.",
                "(function() {\n" +
                "    'use strict';\n" +
                "    // Hook fetch to inspect, filter, or mock API responses\n" +
                "    const origFetch = window.fetch;\n" +
                "    window.fetch = async function(...args) {\n" +
                "        const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');\n" +
                "        // Custom URL inspection or filtering logic here\n" +
                "        return origFetch.apply(this, args);\n" +
                "    };\n" +
                "})();\n"
        ));

        list.add(new ScriptPreset(
                "dom_ad_pruner",
                "MutationObserver (DOM)",
                "dom_ad_pruner",
                "*",
                RunAt.DOCUMENT_END,
                "Watches for dynamically inserted ad nodes in the DOM after page load and removes them in real-time.",
                "(function() {\n" +
                "    'use strict';\n" +
                "    // Observe dynamic ad injections in the DOM and remove them immediately\n" +
                "    const observer = new MutationObserver(function(mutations) {\n" +
                "        const ads = document.querySelectorAll('.ad-banner, [id^=\"ad-\"], [class*=\"sponsored\"]');\n" +
                "        ads.forEach(function(el) { el.remove(); });\n" +
                "    });\n" +
                "    observer.observe(document.documentElement || document.body, { childList: true, subtree: true });\n" +
                "})();\n"
        ));

        return list;
    }

    private static volatile UserScriptManager instance;

    public static UserScriptManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (UserScriptManager.class) {
                if (instance == null) {
                    instance = new UserScriptManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final AdBlockFileManager fileManager;
    private volatile List<ScriptModel> cachedScripts = null;

    private UserScriptManager(@NonNull Context context) {
        this.fileManager = AdBlockFileManager.getInstance(context);
    }

    public synchronized void clearCache() {
        cachedScripts = null;
        WebScriptPolicy.changed();
    }

    @NonNull
    public List<ScriptModel> getLoadedScripts() {
        if (cachedScripts != null) {
            return cachedScripts;
        }
        synchronized (this) {
            if (cachedScripts != null) {
                return cachedScripts;
            }
            List<ScriptModel> list = new ArrayList<>();
            List<File> files = fileManager.getUserScriptFiles();
            for (File file : files) {
                String fileName = file.getName();
                String content = fileManager.readUserScript(fileName);
                list.add(parseScript(fileName, content));
            }
            cachedScripts = list;
            return list;
        }
    }

    @NonNull
    public static ScriptModel parseScript(@NonNull String fileName, @Nullable String content) {
        ScriptModel model = new ScriptModel();
        model.fileName = fileName;
        model.name = fileName.replace(".js", "");
        model.runAt = RunAt.DOCUMENT_START;
        model.match = "*";
        model.enabled = true;
        model.code = content != null ? content : "";

        if (content != null) {
            String[] lines = content.split("\n", 40);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                    String clean = trimmed.replaceAll("^[/\\*\\s]+", "");
                    if (clean.startsWith("@name")) {
                        String val = clean.substring(5).trim();
                        if (!val.isEmpty()) model.name = val;
                    } else if (clean.startsWith("@run-at")) {
                        model.runAt = RunAt.fromString(clean.substring(7));
                    } else if (clean.startsWith("@match")) {
                        model.match = clean.substring(6).trim();
                    } else if (clean.startsWith("@enabled")) {
                        String val = clean.substring(8).trim().toLowerCase(Locale.US);
                        model.enabled = !val.equals("false") && !val.equals("0");
                    }
                }
            }
        }
        return model;
    }

    @NonNull
    public static String updateRunAtInContent(@NonNull String content, @NonNull RunAt newRunAt) {
        if (content.contains("@run-at")) {
            return content.replaceAll("(?i)(//\\s*@run-at\\s+)[a-zA-Z0-9_-]+", "$1" + newRunAt.identifier);
        } else {
            return "// ==UserScript==\n// @run-at       " + newRunAt.identifier + "\n// @match        *\n// ==/UserScript==\n\n" + content;
        }
    }

    public static boolean isUrlMatched(@NonNull String pattern, @Nullable String url) {
        return UserScriptMatch.matches(pattern, url);
    }

    // Invoked only by the current WebView's policy. Legacy 'document-start' means the
    // first verified commit, not before inline scripts or in child frames.
    void injectOnPage(WebView view, String url, RunAt timing) {
        for (ScriptModel script : getLoadedScripts()) {
            if (!script.enabled || script.runAt != timing || !isUrlMatched(script.match, url) || TextUtils.isEmpty(script.code)) continue;
            try {
                String once = org.json.JSONObject.quote(script.fileName + ":" + timing.name());
                view.evaluateJavascript(com.privacy.browser.InternalHome.guardExternalPage(url,
                        "const installed=window.__privacyBrowserUserScripts||(window.__privacyBrowserUserScripts=Object.create(null));"
                                + "if(installed[" + once + "])return;installed[" + once + "]=true;\n"
                                + "(function(){\n" + script.code + "\n})();"), null);
            } catch (RuntimeException e) {
                Log.w(TAG, "Provider rejected a user script evaluation", e);
            }
        }
    }
}

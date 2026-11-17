package com.privacy.browser.search;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SearchEngineManager {

    private static final String PREFS_NAME = "browser_search_settings";
    public static final String KEY_SELECTED_ENGINE = "selected_search_engine";
    public static final String KEY_CUSTOM_URL = "custom_search_url";
    public static final String KEY_BANGS_ENABLED = "search_bangs_enabled";
    public static final String KEY_AUTOCOMPLETE_ENABLED = "omnibox_autocomplete_enabled";

    public static final String DEFAULT_ENGINE_ID = "duckduckgo";

    public static class SearchEngine {
        private final String id;
        private final String name;
        private final String description;
        private final String queryUrl;
        private final String homeUrl;
        private final boolean isPrivacyFocused;

        public SearchEngine(String id, String name, String description, String queryUrl, String homeUrl, boolean isPrivacyFocused) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.queryUrl = queryUrl;
            this.homeUrl = homeUrl;
            this.isPrivacyFocused = isPrivacyFocused;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getQueryUrl() { return queryUrl; }
        public String getHomeUrl() { return homeUrl; }
        public boolean isPrivacyFocused() { return isPrivacyFocused; }
    }

    public static class BangResult {
        private final String tag;
        private final String serviceName;
        private final String cleanQuery;
        private final String targetUrl;

        public BangResult(String tag, String serviceName, String cleanQuery, String targetUrl) {
            this.tag = tag;
            this.serviceName = serviceName;
            this.cleanQuery = cleanQuery;
            this.targetUrl = targetUrl;
        }

        public String getTag() { return tag; }
        public String getServiceName() { return serviceName; }
        public String getCleanQuery() { return cleanQuery; }
        public String getTargetUrl() { return targetUrl; }
    }

    private static final Map<String, SearchEngine> ENGINES = new LinkedHashMap<>();
    private static final Map<String, BangDefinition> BANGS = new LinkedHashMap<>();

    private static class BangDefinition {
        final String serviceName;
        final String urlPrefix;

        BangDefinition(String serviceName, String urlPrefix) {
            this.serviceName = serviceName;
            this.urlPrefix = urlPrefix;
        }
    }

    static {
        ENGINES.put("duckduckgo", new SearchEngine(
                "duckduckgo",
                "DuckDuckGo",
                "Privacy-first search, does not track or profile users",
                "https://html.duckduckgo.com/html/?q=",
                "https://html.duckduckgo.com",
                true
        ));
        ENGINES.put("brave", new SearchEngine(
                "brave",
                "Brave Search",
                "Independent, private web index built from scratch",
                "https://search.brave.com/search?q=",
                "https://search.brave.com",
                true
        ));
        ENGINES.put("startpage", new SearchEngine(
                "startpage",
                "Startpage",
                "Google search results with complete privacy protection",
                "https://www.startpage.com/sp/search?query=",
                "https://www.startpage.com",
                true
        ));
        ENGINES.put("searxng", new SearchEngine(
                "searxng",
                "SearXNG",
                "Decentralized, open-source privacy metasearch engine",
                "https://searx.be/search?q=",
                "https://searx.be",
                true
        ));
        ENGINES.put("google", new SearchEngine(
                "google",
                "Google",
                "Standard global search index",
                "https://www.google.com/search?q=",
                "https://www.google.com",
                false
        ));
        ENGINES.put("bing", new SearchEngine(
                "bing",
                "Bing",
                "Microsoft web and image search",
                "https://www.bing.com/search?q=",
                "https://www.bing.com",
                false
        ));

        // Search Bangs definitions
        BANGS.put("!w", new BangDefinition("Wikipedia", "https://en.wikipedia.org/wiki/Special:Search?search="));
        BANGS.put("!g", new BangDefinition("Google", "https://www.google.com/search?q="));
        BANGS.put("!ddg", new BangDefinition("DuckDuckGo", "https://html.duckduckgo.com/html/?q="));
        BANGS.put("!b", new BangDefinition("Brave", "https://search.brave.com/search?q="));
        BANGS.put("!yt", new BangDefinition("YouTube", "https://www.youtube.com/results?search_query="));
        BANGS.put("!gh", new BangDefinition("GitHub", "https://github.com/search?q="));
        BANGS.put("!r", new BangDefinition("Reddit", "https://www.reddit.com/search/?q="));
        BANGS.put("!a", new BangDefinition("Amazon", "https://www.amazon.com/s?k="));
        BANGS.put("!maps", new BangDefinition("OpenStreetMap", "https://www.openstreetmap.org/search?query="));
        BANGS.put("!wikt", new BangDefinition("Wiktionary", "https://en.wiktionary.org/wiki/Special:Search?search="));
        BANGS.put("!sp", new BangDefinition("Startpage", "https://www.startpage.com/sp/search?query="));
        BANGS.put("!sx", new BangDefinition("SearXNG", "https://searx.be/search?q="));
    }

    public static List<SearchEngine> getAvailableEngines() {
        return Collections.unmodifiableList(new ArrayList<>(ENGINES.values()));
    }

    public static SearchEngine getEngineById(String id) {
        if (id == null) return ENGINES.get(DEFAULT_ENGINE_ID);
        SearchEngine engine = ENGINES.get(id.toLowerCase(Locale.US));
        return engine != null ? engine : ENGINES.get(DEFAULT_ENGINE_ID);
    }

    public static SearchEngine getSelectedEngine(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_SELECTED_ENGINE, DEFAULT_ENGINE_ID);
        return getEngineById(id);
    }

    public static void setSelectedEngine(Context context, String engineId) {
        if (engineId == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SELECTED_ENGINE, engineId.toLowerCase(Locale.US)).apply();
    }

    public static boolean isBangsEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_BANGS_ENABLED, true);
    }

    public static void setBangsEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_BANGS_ENABLED, enabled).apply();
    }

    public static boolean isAutocompleteEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTOCOMPLETE_ENABLED, true);
    }

    public static void setAutocompleteEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_AUTOCOMPLETE_ENABLED, enabled).apply();
    }

    public static String getCustomUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CUSTOM_URL, "");
    }

    public static void setCustomUrl(Context context, String customUrl) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CUSTOM_URL, customUrl != null ? customUrl.trim() : "").apply();
    }

    /**
     * Detects if query begins or ends with a registered bang command (e.g. "!w android").
     */
    public static BangResult detectBang(String query) {
        if (query == null) return null;
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return null;

        for (Map.Entry<String, BangDefinition> entry : BANGS.entrySet()) {
            String bang = entry.getKey();
            BangDefinition def = entry.getValue();

            // Check prefix: "!w query"
            if (trimmed.equalsIgnoreCase(bang) || trimmed.toLowerCase(Locale.US).startsWith(bang.toLowerCase(Locale.US) + " ")) {
                String clean = trimmed.substring(bang.length()).trim();
                String target = def.urlPrefix + encodeQuery(clean);
                return new BangResult(bang, def.serviceName, clean, target);
            }

            // Check suffix: "query !w"
            if (trimmed.toLowerCase(Locale.US).endsWith(" " + bang.toLowerCase(Locale.US))) {
                String clean = trimmed.substring(0, trimmed.length() - bang.length()).trim();
                String target = def.urlPrefix + encodeQuery(clean);
                return new BangResult(bang, def.serviceName, clean, target);
            }
        }

        return null;
    }

    /**
     * Builds the complete search URL, resolving bangs if enabled, or formatting with selected engine.
     */
    public static String buildSearchUrl(Context context, String query) {
        if (query == null) query = "";
        String trimmed = query.trim();

        if (isBangsEnabled(context)) {
            BangResult bang = detectBang(trimmed);
            if (bang != null) {
                return bang.getTargetUrl();
            }
        }

        SearchEngine engine = getSelectedEngine(context);
        String baseQueryUrl = engine.getQueryUrl();

        String customUrl = getCustomUrl(context);
        if ("custom".equalsIgnoreCase(engine.getId()) && !customUrl.isEmpty()) {
            baseQueryUrl = customUrl;
        }

        return baseQueryUrl + encodeQuery(trimmed);
    }

    public static String encodeQuery(String query) {
        if (query == null) return "";
        try {
            return URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return query.replace(" ", "+");
        }
    }
}

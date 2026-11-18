package com.privacy.browser;

import java.util.ArrayList;
import java.util.List;

/** Shared UI/tool scope. No per-site request may fall through to profile-wide deletion. */
public final class BrowserDataPolicy {
    public static final String SITE_CLEAR_UNAVAILABLE =
            "Clearing just this site is unavailable in this version. No data was deleted.";
    public static final String LIMITS = "These controls act on the browser’s regular WebView profile for all sites. "
            + "Open pages can recreate data. Legacy Web Storage deletion has no completion check and does not guarantee removal of "
            + "IndexedDB, service workers, or Cache Storage. Bookmarks, previews, saved icons, downloads, and settings are kept.";
    public static final String OLDER_PRIVATE_DATA = "Older versions shared WebView storage between regular and private tabs. "
            + "That data cannot be separated retroactively; nothing was deleted automatically.";

    private BrowserDataPolicy() {}

    public static final class Plan {
        public final boolean cookies;
        public final boolean urlCache;
        public final boolean legacyStorage;
        public final boolean localHistoryAndTabs;

        private Plan(boolean cookies, boolean urlCache, boolean legacyStorage, boolean localHistoryAndTabs) {
            this.cookies = cookies;
            this.urlCache = urlCache;
            this.legacyStorage = legacyStorage;
            this.localHistoryAndTabs = localHistoryAndTabs;
        }

        public boolean isEmpty() { return !cookies && !urlCache && !legacyStorage && !localHistoryAndTabs; }

        public String selectedItems() {
            List<String> items = new ArrayList<>();
            if (cookies) items.add("WebView cookies for all sites (signs you out)");
            if (urlCache) items.add("WebView URL cache for all sites");
            if (legacyStorage) items.add("Request legacy Web Storage deletion for all sites");
            if (localHistoryAndTabs) items.add("Browser history and saved tabs (closes all tabs)");
            return String.join("\n", items);
        }
    }

    public static Plan allSites(boolean cookies, boolean urlCache, boolean legacyStorage, boolean historyAndTabs) {
        return new Plan(cookies, urlCache, legacyStorage, historyAndTabs);
    }

    public static Plan fromTool(String scope, boolean cookies, boolean urlCache, boolean legacyStorage) {
        if (!"*".equals(scope)) throw new IllegalArgumentException(SITE_CLEAR_UNAVAILABLE);
        Plan plan = allSites(cookies, urlCache, legacyStorage, false);
        if (plan.isEmpty()) throw new IllegalArgumentException("Select at least one data type. No data was deleted.");
        return plan;
    }
}

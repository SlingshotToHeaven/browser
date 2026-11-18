package com.privacy.browser.adblock;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Trace;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * uBlock-grade central ad-blocking, cosmetic filtering, and anti-adblock coordination engine.
 */
public class AdBlockManager {

    private static final String PREFS_NAME = "adblock_preferences";
    private static final String KEY_GLOBAL_ENABLED = "adblock_global_enabled";
    private static final String KEY_COSMETIC_ENABLED = "adblock_cosmetic_enabled";
    private static final String KEY_ANTI_ADBLOCK_ENABLED = "adblock_anti_adblock_enabled";
    private static final String KEY_ANNOYANCES_ENABLED = "adblock_annoyances_enabled";
    private static final String KEY_TOTAL_BLOCKED = "adblock_total_blocked";
    private static final String KEY_WHITELISTED_HOSTS = "adblock_whitelisted_hosts";
    private static final String KEY_SITE_NO_SCRIPT = "adblock_site_no_script";
    private static final String KEY_SITE_BLOCK_FONTS = "adblock_site_block_fonts";
    private static final String KEY_SITE_BLOCK_MEDIA = "adblock_site_block_media";
    private static final String KEY_SITE_BLOCK_POPUPS = "adblock_site_block_popups";
    private static final String KEY_SITE_DISABLE_COSMETIC = "adblock_site_disable_cosmetic";

    public static class AntiAdblockEvent {
        public final String name;
        public final String detail;
        public final long timestamp;

        public AntiAdblockEvent(String name, String detail) {
            this.name = name;
            this.detail = detail;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static volatile AdBlockManager instance;

    public static AdBlockManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (AdBlockManager.class) {
                if (instance == null) {
                    instance = new AdBlockManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private volatile SiteRequestPolicy.Settings requestSettings;
    private final Set<String> siteNoScriptHosts = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> siteBlockPopupsHosts = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> siteDisableCosmeticHosts = Collections.synchronizedSet(new HashSet<>());

    private static final ScheduledExecutorService COUNTER_WRITER = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "browser-filter-totals"));
    private final AdBlockTally tally;

    private static final class PageStats {
        final AtomicInteger blocked = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        private final Map<String, Integer> hosts = new HashMap<>();

        void block(String host) {
            blocked.incrementAndGet();
            synchronized (hosts) {
                // The admission check and insertion must share the lock, even for different hosts.
                if (hosts.size() < 512 || hosts.containsKey(host)) hosts.merge(host, 1, Integer::sum);
            }
        }

        Map<String, Integer> hostSnapshot() {
            synchronized (hosts) { return new HashMap<>(hosts); }
        }
    }

    private final Map<String, PageStats> pages = new ConcurrentHashMap<>();

    private AdBlockManager(@NonNull Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        requestSettings = new SiteRequestPolicy.Settings(prefs.getBoolean(KEY_GLOBAL_ENABLED, true),
                prefs.getStringSet(KEY_WHITELISTED_HOSTS, null), prefs.getStringSet(KEY_SITE_BLOCK_FONTS, null),
                prefs.getStringSet(KEY_SITE_BLOCK_MEDIA, null));
        tally = new AdBlockTally(prefs.getLong(KEY_TOTAL_BLOCKED, 0),
                (task, delay) -> COUNTER_WRITER.schedule(task, delay, TimeUnit.MILLISECONDS), this::saveTally);
        Set<String> savedNoScript = prefs.getStringSet(KEY_SITE_NO_SCRIPT, null);
        if (savedNoScript != null) siteNoScriptHosts.addAll(savedNoScript);
        Set<String> savedPopups = prefs.getStringSet(KEY_SITE_BLOCK_POPUPS, null);
        if (savedPopups != null) siteBlockPopupsHosts.addAll(savedPopups);
        Set<String> savedCosmetic = prefs.getStringSet(KEY_SITE_DISABLE_COSMETIC, null);
        if (savedCosmetic != null) siteDisableCosmeticHosts.addAll(savedCosmetic);
    }

    /** Warm the exact filter index and first-page script inputs on the browser startup worker. */
    public void initialize() {
        AdBlockFileManager.prepareForBrowsing(context);
        if (!hasNoScriptRules() && isGlobalEnabled()) {
            if (isAntiAdblockDefuserEnabled()) {
                YouTubeAdDefuser.prepare(context);
                AntiAdblockDefuser.getScriptletJs(context);
            }
            if (isCosmeticFilteringEnabled()) AntiAdblockDefuser.getCosmeticJs(context);
            PopupClickDefuser.getDefuserJs(context);
            UserScriptManager.getInstance(context).getLoadedScripts();
        }
    }

    public boolean isGlobalEnabled() { return requestSettings.enabled; }

    public synchronized void setGlobalEnabled(boolean enabled) {
        SiteRequestPolicy.Settings before = requestSettings;
        if (before.enabled == enabled) return;
        requestSettings = new SiteRequestPolicy.Settings(enabled, before.exceptions, before.fonts, before.media);
        prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply();
        WebScriptPolicy.changed();
    }

    public SiteRequestPolicy.Cache requestPolicies(@Nullable String topUrl) {
        AdBlockFileManager files = AdBlockFileManager.getInstance(context);
        return new SiteRequestPolicy.Cache(topUrl, () -> requestSettings, files::ruleSnapshot, AdBlockManager::extractCleanHost);
    }

    public boolean isCosmeticFilteringEnabled() {
        return prefs.getBoolean(KEY_COSMETIC_ENABLED, true);
    }

    public void setCosmeticFilteringEnabled(boolean enabled) {
        if (prefs.getBoolean(KEY_COSMETIC_ENABLED, true) == enabled) return;
        prefs.edit().putBoolean(KEY_COSMETIC_ENABLED, enabled).apply();
        WebScriptPolicy.changed();
    }

    public boolean isAntiAdblockDefuserEnabled() {
        return prefs.getBoolean(KEY_ANTI_ADBLOCK_ENABLED, true);
    }

    public void setAntiAdblockDefuserEnabled(boolean enabled) {
        if (prefs.getBoolean(KEY_ANTI_ADBLOCK_ENABLED, true) == enabled) return;
        prefs.edit().putBoolean(KEY_ANTI_ADBLOCK_ENABLED, enabled).apply();
        WebScriptPolicy.changed();
    }

    public boolean isAnnoyanceBlockingEnabled() {
        return prefs.getBoolean(KEY_ANNOYANCES_ENABLED, true);
    }

    public void setAnnoyanceBlockingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ANNOYANCES_ENABLED, enabled).apply();
    }

    public boolean isAdBlockActiveForSite(@Nullable String urlOrHost) {
        SiteRequestPolicy.Settings config = requestSettings;
        return config.enabled && config.active(urlOrHost, extractCleanHost(urlOrHost));
    }

    public boolean isSiteWhitelisted(@Nullable String urlOrHost) {
        String host = extractCleanHost(urlOrHost);
        return host != null && requestSettings.exceptions.contains(host);
    }

    public synchronized void setSiteWhitelisted(@Nullable String urlOrHost, boolean whitelisted) {
        String host = extractCleanHost(urlOrHost);
        SiteRequestPolicy.Settings before = requestSettings;
        Set<String> next = update(before.exceptions, host, whitelisted);
        if (next == before.exceptions) return;
        requestSettings = new SiteRequestPolicy.Settings(before.enabled, next, before.fonts, before.media);
        prefs.edit().putStringSet(KEY_WHITELISTED_HOSTS, next).apply();
        WebScriptPolicy.changed();
    }

    private static Set<String> update(Set<String> before, String host, boolean present) {
        if (host == null || before.contains(host) == present) return before;
        Set<String> next = new HashSet<>(before);
        if (present) next.add(host);
        else next.remove(host);
        return next;
    }

    boolean hasSiteExceptions() { return !requestSettings.exceptions.isEmpty(); }
    boolean hasCosmeticExceptions() { return !siteDisableCosmeticHosts.isEmpty(); }

    public static String extractCleanHost(String urlOrHost) {
        if (urlOrHost == null) return null;
        String host = urlOrHost.trim().toLowerCase(Locale.US);
        if (host.startsWith("http://") || host.startsWith("https://")) {
            try {
                Uri parsed = Uri.parse(host);
                if (parsed.getHost() != null) {
                    host = parsed.getHost().toLowerCase(Locale.US);
                }
            } catch (Exception ignored) {}
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host;
    }

    /** Used by native link actions. Intercepted requests use the view's cached policy. */
    public boolean isBlocked(@Nullable Uri uri, @Nullable String topPageUrl) {
        SiteRequestPolicy.Settings config = requestSettings;
        if (uri == null || !config.enabled) return false;
        String host = extractCleanHost(topPageUrl);
        return config.active(topPageUrl, host) && new SiteRequestPolicy(config,
                AdBlockFileManager.getInstance(context).ruleSnapshot(), topPageUrl, host).blocksNetwork(uri);
    }

    private void saveTally(long blocked) {
        Trace.beginSection("Browser.filters.persistTotals");
        try {
            // Only the existing aggregate is durable. Page counts and request denominators stay in memory.
            prefs.edit().putLong(KEY_TOTAL_BLOCKED, blocked).commit();
        } finally { Trace.endSection(); }
    }

    /** Returns true only if the count belongs to a tracked page. */
    public boolean recordBlockedHost(@Nullable String pageKey, @NonNull String host) {
        tally.block();
        PageStats page = pageKey == null ? null : pages.get(pageKey);
        if (page == null) return false;
        page.block(host);
        return true;
    }

    public int getPageBlockedCount(@Nullable String tabId) {
        PageStats page = tabId == null ? null : pages.get(tabId);
        return page == null ? 0 : page.blocked.get();
    }

    public long getTotalBlockedCount() {
        return tally.blocked();
    }

    public Map<String, Integer> getBlockedHostsForPage(@Nullable String tabId) {
        PageStats page = tabId == null ? null : pages.get(tabId);
        return page == null ? Collections.emptyMap() : page.hostSnapshot();
    }

    // Page-script telemetry is not a trustworthy signal. Do not retain it as native statistics.
    public List<AntiAdblockEvent> getNeutralizedAntiAdblockForPage(@Nullable String tabId) {
        return Collections.emptyList();
    }

    public void beginPageStats(String key) {
        pages.put(key, new PageStats());
    }

    public void resetPageStats(String key) {
        pages.remove(key);
    }

    public void recordRequest(@Nullable String tabId) {
        PageStats page = tabId == null ? null : pages.get(tabId);
        if (page != null) page.requests.incrementAndGet();
    }

    public int getPageRequestCount(@Nullable String tabId) {
        PageStats page = tabId == null ? null : pages.get(tabId);
        return page == null ? 0 : page.requests.get();
    }

    public int getPageBlockedPercentage(@Nullable String tabId) {
        int total = getPageRequestCount(tabId);
        int blocked = getPageBlockedCount(tabId);
        if (total <= 0) return 0;
        return Math.min(100, (int) Math.round(((double) blocked / (double) total) * 100.0));
    }

    public void clearNoScriptRules() {
        if (siteNoScriptHosts.isEmpty()) return;
        siteNoScriptHosts.clear();
        prefs.edit().remove(KEY_SITE_NO_SCRIPT).apply();
        WebScriptPolicy.changed();
    }

    public boolean hasNoScriptRules() {
        return !siteNoScriptHosts.isEmpty();
    }

    public boolean isSiteJavaScriptEnabled(@Nullable String urlOrHost) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return true;
        return !siteNoScriptHosts.contains(cleanHost);
    }

    public void setSiteJavaScriptEnabled(@Nullable String urlOrHost, boolean enabled) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return;
        if (!(enabled ? siteNoScriptHosts.remove(cleanHost) : siteNoScriptHosts.add(cleanHost))) return;
        prefs.edit().putStringSet(KEY_SITE_NO_SCRIPT, new HashSet<>(siteNoScriptHosts)).apply();
        WebScriptPolicy.changed();
    }

    public boolean isSiteFontsBlocked(@Nullable String urlOrHost) {
        String host = extractCleanHost(urlOrHost);
        return host != null && requestSettings.fonts.contains(host);
    }

    public synchronized void setSiteFontsBlocked(@Nullable String urlOrHost, boolean blocked) {
        SiteRequestPolicy.Settings before = requestSettings;
        Set<String> next = update(before.fonts, extractCleanHost(urlOrHost), blocked);
        if (next == before.fonts) return;
        requestSettings = new SiteRequestPolicy.Settings(before.enabled, before.exceptions, next, before.media);
        prefs.edit().putStringSet(KEY_SITE_BLOCK_FONTS, next).apply();
    }

    public boolean isSiteLargeMediaBlocked(@Nullable String urlOrHost) {
        String host = extractCleanHost(urlOrHost);
        return host != null && requestSettings.media.contains(host);
    }

    public synchronized void setSiteLargeMediaBlocked(@Nullable String urlOrHost, boolean blocked) {
        SiteRequestPolicy.Settings before = requestSettings;
        Set<String> next = update(before.media, extractCleanHost(urlOrHost), blocked);
        if (next == before.media) return;
        requestSettings = new SiteRequestPolicy.Settings(before.enabled, before.exceptions, before.fonts, next);
        prefs.edit().putStringSet(KEY_SITE_BLOCK_MEDIA, next).apply();
    }

    public boolean isSitePopupsBlocked(@Nullable String urlOrHost) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return true;
        return !siteBlockPopupsHosts.contains(cleanHost);
    }

    public void setSitePopupsBlocked(@Nullable String urlOrHost, boolean blocked) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return;
        if (!(blocked ? siteBlockPopupsHosts.remove(cleanHost) : siteBlockPopupsHosts.add(cleanHost))) return;
        prefs.edit().putStringSet(KEY_SITE_BLOCK_POPUPS, new HashSet<>(siteBlockPopupsHosts)).apply();
        WebScriptPolicy.changed();
    }

    public boolean isSiteCosmeticEnabled(@Nullable String urlOrHost) {
        if (!isCosmeticFilteringEnabled()) return false;
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return true;
        return !siteDisableCosmeticHosts.contains(cleanHost);
    }

    public void setSiteCosmeticEnabled(@Nullable String urlOrHost, boolean enabled) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return;
        if (!(enabled ? siteDisableCosmeticHosts.remove(cleanHost) : siteDisableCosmeticHosts.add(cleanHost))) return;
        prefs.edit().putStringSet(KEY_SITE_DISABLE_COSMETIC, new HashSet<>(siteDisableCosmeticHosts)).apply();
        WebScriptPolicy.changed();
    }

    public int getRuleCount() {
        return AdBlockFileManager.getInstance(context).ruleSnapshot().hostCount();
    }

    public int getBlockedHostsCount() {
        return AdBlockFileManager.getInstance(context).ruleSnapshot().hostCount();
    }
}

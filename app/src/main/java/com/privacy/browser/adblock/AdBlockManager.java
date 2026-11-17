package com.privacy.browser.adblock;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * uBlock-grade central ad-blocking, cosmetic filtering, and anti-adblock coordination engine.
 */
public class AdBlockManager {

    private static final String TAG = "AdBlockManager";
    private static final String PREFS_NAME = "adblock_preferences";
    private static final String KEY_GLOBAL_ENABLED = "adblock_global_enabled";
    private static final String KEY_COSMETIC_ENABLED = "adblock_cosmetic_enabled";
    private static final String KEY_ANTI_ADBLOCK_ENABLED = "adblock_anti_adblock_enabled";
    private static final String KEY_ANNOYANCES_ENABLED = "adblock_annoyances_enabled";
    private static final String KEY_TOTAL_BLOCKED = "adblock_total_blocked";
    private static final String KEY_TOTAL_REQUESTS = "adblock_total_requests";
    private static final String KEY_WHITELISTED_HOSTS = "adblock_whitelisted_hosts";
    private static final String KEY_SITE_NO_SCRIPT = "adblock_site_no_script";
    private static final String KEY_SITE_BLOCK_FONTS = "adblock_site_block_fonts";
    private static final String KEY_SITE_BLOCK_MEDIA = "adblock_site_block_media";
    private static final String KEY_SITE_BLOCK_POPUPS = "adblock_site_block_popups";
    private static final String KEY_SITE_DISABLE_COSMETIC = "adblock_site_disable_cosmetic";
    private static final String HOSTS_FILE = "filters/hosts.txt";

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
    private volatile Set<String> blockedHosts = new HashSet<>(135000, 0.75f);
    private final Set<String> whitelistedHosts = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> siteNoScriptHosts = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> siteBlockFontsHosts = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> siteBlockMediaHosts = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> siteBlockPopupsHosts = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> siteDisableCosmeticHosts = Collections.synchronizedSet(new HashSet<>());
    private boolean isLoaded = false;

    private final AtomicLong totalBlockedAllTime = new AtomicLong(0);
    private final AtomicLong totalRequestsAllTime = new AtomicLong(0);

    // Per-tab tracking
    private final Map<String, AtomicInteger> tabBlockedCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tabTotalRequests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> tabBlockedHosts = new ConcurrentHashMap<>();
    private final Map<String, List<AntiAdblockEvent>> tabAntiAdblockEvents = new ConcurrentHashMap<>();

    private AdBlockManager(@NonNull Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.totalBlockedAllTime.set(prefs.getLong(KEY_TOTAL_BLOCKED, 0));
        this.totalRequestsAllTime.set(prefs.getLong(KEY_TOTAL_REQUESTS, 0));
        Set<String> savedWhitelist = prefs.getStringSet(KEY_WHITELISTED_HOSTS, null);
        if (savedWhitelist != null) {
            whitelistedHosts.addAll(savedWhitelist);
        }
        Set<String> savedNoScript = prefs.getStringSet(KEY_SITE_NO_SCRIPT, null);
        if (savedNoScript != null) siteNoScriptHosts.addAll(savedNoScript);
        Set<String> savedFonts = prefs.getStringSet(KEY_SITE_BLOCK_FONTS, null);
        if (savedFonts != null) siteBlockFontsHosts.addAll(savedFonts);
        Set<String> savedMedia = prefs.getStringSet(KEY_SITE_BLOCK_MEDIA, null);
        if (savedMedia != null) siteBlockMediaHosts.addAll(savedMedia);
        Set<String> savedPopups = prefs.getStringSet(KEY_SITE_BLOCK_POPUPS, null);
        if (savedPopups != null) siteBlockPopupsHosts.addAll(savedPopups);
        Set<String> savedCosmetic = prefs.getStringSet(KEY_SITE_DISABLE_COSMETIC, null);
        if (savedCosmetic != null) siteDisableCosmeticHosts.addAll(savedCosmetic);
    }

    public synchronized void initialize() {
        if (isLoaded) return;
        reloadRules();
    }

    @Nullable
    private static String parseHostLine(@NonNull String raw) {
        String line = raw.trim().toLowerCase(Locale.US);
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
            return null;
        }
        if (line.startsWith("127.0.0.1 ") || line.startsWith("127.0.0.1\t")) {
            line = line.substring(9).trim();
        } else if (line.startsWith("0.0.0.0 ") || line.startsWith("0.0.0.0\t")) {
            line = line.substring(7).trim();
        }
        int commentIdx = line.indexOf('#');
        if (commentIdx != -1) {
            line = line.substring(0, commentIdx).trim();
        }
        if (line.isEmpty() || line.contains(" ") || line.contains("/")) {
            return null;
        }
        if ("canyoublockit.com".equals(line) || line.endsWith(".canyoublockit.com")
                || "adblock-tester.com".equals(line) || line.endsWith(".adblock-tester.com")
                || "d3ward.github.io".equals(line)) {
            return null;
        }
        return line;
    }

    public synchronized void reloadRules() {
        Set<String> newBlockedHosts = new HashSet<>(135000, 0.75f);
        AdBlockFileManager fileManager = AdBlockFileManager.getInstance(context);
        File hostsFile = fileManager.getFile(AdBlockFileManager.Category.NETWORK, AdBlockFileManager.FILE_HOSTS);
        if (hostsFile.exists()) {
            try (FileInputStream fis = new FileInputStream(hostsFile);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String parsed = parseHostLine(line);
                    if (parsed != null) {
                        newBlockedHosts.add(parsed);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed reading " + hostsFile.getAbsolutePath(), e);
            }
        } else {
            // Fallback to bundled asset
            try (InputStream is = context.getAssets().open(HOSTS_FILE);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String parsed = parseHostLine(line);
                    if (parsed != null) {
                        newBlockedHosts.add(parsed);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed fallback load of " + HOSTS_FILE, e);
            }
        }
        blockedHosts = newBlockedHosts;
        isLoaded = true;
        Log.i(TAG, "AdBlock engine loaded " + blockedHosts.size() + " network filtering rules from file.");
    }

    public boolean isGlobalEnabled() {
        return prefs.getBoolean(KEY_GLOBAL_ENABLED, true);
    }

    public void setGlobalEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply();
    }

    public boolean isCosmeticFilteringEnabled() {
        return prefs.getBoolean(KEY_COSMETIC_ENABLED, true);
    }

    public void setCosmeticFilteringEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_COSMETIC_ENABLED, enabled).apply();
    }

    public boolean isAntiAdblockDefuserEnabled() {
        return prefs.getBoolean(KEY_ANTI_ADBLOCK_ENABLED, true);
    }

    public void setAntiAdblockDefuserEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ANTI_ADBLOCK_ENABLED, enabled).apply();
    }

    public boolean isAnnoyanceBlockingEnabled() {
        return prefs.getBoolean(KEY_ANNOYANCES_ENABLED, true);
    }

    public void setAnnoyanceBlockingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ANNOYANCES_ENABLED, enabled).apply();
    }

    public boolean isAdBlockActiveForSite(@Nullable String urlOrHost) {
        if (!isGlobalEnabled()) {
            return false;
        }
        if (urlOrHost == null || urlOrHost.trim().isEmpty() || "about:blank".equalsIgnoreCase(urlOrHost.trim())) {
            return true;
        }
        return !isSiteWhitelisted(urlOrHost);
    }

    public boolean isSiteWhitelisted(@Nullable String urlOrHost) {
        if (urlOrHost == null) return false;
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return false;
        return whitelistedHosts.contains(cleanHost);
    }

    public void setSiteWhitelisted(@Nullable String urlOrHost, boolean whitelisted) {
        if (urlOrHost == null) return;
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return;

        if (whitelisted) {
            whitelistedHosts.add(cleanHost);
        } else {
            whitelistedHosts.remove(cleanHost);
        }
        prefs.edit().putStringSet(KEY_WHITELISTED_HOSTS, new HashSet<>(whitelistedHosts)).apply();
    }

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

    /**
     * Determines whether a given subresource request should be blocked.
     */
    public boolean isBlocked(@Nullable Uri uri, @Nullable String topPageUrl) {
        if (!isAdBlockActiveForSite(topPageUrl)) return false;
        if (uri == null || !isLoaded) return false;

        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);

        if ("localhost".equals(host) || "127.0.0.1".equals(host) || host.startsWith("192.168.") || host.startsWith("10.") || "0.0.0.0".equals(host)) {
            return false;
        }

        if ("canyoublockit.com".equals(host) || host.endsWith(".canyoublockit.com")
                || "adblock-tester.com".equals(host) || host.endsWith(".adblock-tester.com")
                || "d3ward.github.io".equals(host)) {
            return false;
        }

        // Check custom user filters
        com.privacy.browser.adblock.rules.UserFiltersManager ufm = com.privacy.browser.adblock.rules.UserFiltersManager.getInstance(context);
        if (ufm.isHostWhitelisted(host)) {
            return false;
        }
        if (ufm.shouldBlockHost(host)) {
            return true;
        }

        if (isYouTubeAdRequest(uri, host)) {
            return true;
        }

        if (blockedHosts.contains(host)) {
            return true;
        }

        int index = host.indexOf('.');
        while (index != -1 && index < host.length() - 1) {
            String parent = host.substring(index + 1);
            if (blockedHosts.contains(parent)) {
                return true;
            }
            index = host.indexOf('.', index + 1);
        }

        return false;
    }

    public static boolean isYouTubeAdRequest(@NonNull Uri uri, @NonNull String host) {
        if (host.contains("youtube.com") || host.contains("youtube-nocookie.com")) {
            String path = uri.getPath();
            if (path != null) {
                String lower = path.toLowerCase(Locale.US);
                if (lower.contains("/api/stats/ads")
                        || lower.contains("/pagead/")
                        || lower.contains("/ptracking")
                        || lower.contains("/get_midroll_info")) {
                    return true;
                }
            }
            String query = uri.getQuery();
            if (query != null && (query.contains("adformat=") || query.contains("ad_cpn="))) {
                return true;
            }
        }
        return false;
    }

    public void recordBlockedHost(@Nullable String tabId, @NonNull String host) {
        totalBlockedAllTime.incrementAndGet();
        prefs.edit().putLong(KEY_TOTAL_BLOCKED, totalBlockedAllTime.get()).apply();

        if (tabId != null) {
            tabBlockedCounts.computeIfAbsent(tabId, k -> new AtomicInteger(0)).incrementAndGet();
            Map<String, Integer> hostMap = tabBlockedHosts.computeIfAbsent(tabId, k -> new ConcurrentHashMap<>());
            hostMap.put(host, hostMap.getOrDefault(host, 0) + 1);
        }
    }

    public void recordNeutralizedAntiAdblock(@Nullable String tabId, @NonNull String detectorName, @NonNull String detail) {
        if (tabId != null) {
            List<AntiAdblockEvent> events = tabAntiAdblockEvents.computeIfAbsent(tabId, k -> Collections.synchronizedList(new ArrayList<>()));
            // Avoid duplicates of the same detector name on the page
            boolean exists = false;
            synchronized (events) {
                for (AntiAdblockEvent ev : events) {
                    if (ev.name.equalsIgnoreCase(detectorName)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    events.add(new AntiAdblockEvent(detectorName, detail));
                }
            }
        }
    }

    public int getPageBlockedCount(@Nullable String tabId) {
        if (tabId == null) return 0;
        AtomicInteger count = tabBlockedCounts.get(tabId);
        return count != null ? count.get() : 0;
    }

    public long getTotalBlockedCount() {
        return totalBlockedAllTime.get();
    }

    public Map<String, Integer> getBlockedHostsForPage(@Nullable String tabId) {
        if (tabId == null) return Collections.emptyMap();
        Map<String, Integer> map = tabBlockedHosts.get(tabId);
        return map != null ? new HashMap<>(map) : Collections.emptyMap();
    }

    public List<AntiAdblockEvent> getNeutralizedAntiAdblockForPage(@Nullable String tabId) {
        if (tabId == null) return Collections.emptyList();
        List<AntiAdblockEvent> list = tabAntiAdblockEvents.get(tabId);
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public void resetPageStats(@Nullable String tabId) {
        if (tabId != null) {
            tabBlockedCounts.remove(tabId);
            tabTotalRequests.remove(tabId);
            tabBlockedHosts.remove(tabId);
            tabAntiAdblockEvents.remove(tabId);
        }
    }

    public void recordRequest(@Nullable String tabId) {
        totalRequestsAllTime.incrementAndGet();
        if (tabId != null) {
            tabTotalRequests.computeIfAbsent(tabId, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    public int getPageRequestCount(@Nullable String tabId) {
        if (tabId == null) return 0;
        AtomicInteger count = tabTotalRequests.get(tabId);
        return count != null ? count.get() : 0;
    }

    public int getPageBlockedPercentage(@Nullable String tabId) {
        int total = getPageRequestCount(tabId);
        int blocked = getPageBlockedCount(tabId);
        if (total <= 0) return 0;
        return Math.min(100, (int) Math.round(((double) blocked / (double) total) * 100.0));
    }

    public boolean isSiteJavaScriptEnabled(@Nullable String urlOrHost) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return true;
        return !siteNoScriptHosts.contains(cleanHost);
    }

    public void setSiteJavaScriptEnabled(@Nullable String urlOrHost, boolean enabled) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return;
        if (!enabled) siteNoScriptHosts.add(cleanHost);
        else siteNoScriptHosts.remove(cleanHost);
        prefs.edit().putStringSet(KEY_SITE_NO_SCRIPT, new HashSet<>(siteNoScriptHosts)).apply();
    }

    public boolean isSiteFontsBlocked(@Nullable String urlOrHost) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return false;
        return siteBlockFontsHosts.contains(cleanHost);
    }

    public void setSiteFontsBlocked(@Nullable String urlOrHost, boolean blocked) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return;
        if (blocked) siteBlockFontsHosts.add(cleanHost);
        else siteBlockFontsHosts.remove(cleanHost);
        prefs.edit().putStringSet(KEY_SITE_BLOCK_FONTS, new HashSet<>(siteBlockFontsHosts)).apply();
    }

    public boolean isSiteLargeMediaBlocked(@Nullable String urlOrHost) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return false;
        return siteBlockMediaHosts.contains(cleanHost);
    }

    public void setSiteLargeMediaBlocked(@Nullable String urlOrHost, boolean blocked) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return;
        if (blocked) siteBlockMediaHosts.add(cleanHost);
        else siteBlockMediaHosts.remove(cleanHost);
        prefs.edit().putStringSet(KEY_SITE_BLOCK_MEDIA, new HashSet<>(siteBlockMediaHosts)).apply();
    }

    public boolean isSitePopupsBlocked(@Nullable String urlOrHost) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return true;
        return !siteBlockPopupsHosts.contains(cleanHost);
    }

    public void setSitePopupsBlocked(@Nullable String urlOrHost, boolean blocked) {
        String cleanHost = extractCleanHost(urlOrHost);
        if (cleanHost == null) return;
        if (!blocked) siteBlockPopupsHosts.add(cleanHost);
        else siteBlockPopupsHosts.remove(cleanHost);
        prefs.edit().putStringSet(KEY_SITE_BLOCK_POPUPS, new HashSet<>(siteBlockPopupsHosts)).apply();
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
        if (!enabled) siteDisableCosmeticHosts.add(cleanHost);
        else siteDisableCosmeticHosts.remove(cleanHost);
        prefs.edit().putStringSet(KEY_SITE_DISABLE_COSMETIC, new HashSet<>(siteDisableCosmeticHosts)).apply();
    }

    public int getRuleCount() {
        return blockedHosts.size();
    }

    public int getBlockedHostsCount() {
        return blockedHosts.size();
    }
}

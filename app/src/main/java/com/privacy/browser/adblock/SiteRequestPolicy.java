package com.privacy.browser.adblock;

import android.net.Uri;
import android.os.Trace;

import com.privacy.browser.adblock.rules.FilterSnapshot;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** One coherent set of request decisions. Filter context is not page or credential authority. */
public final class SiteRequestPolicy {
    public enum Asset { PASS, FONT, MEDIA }

    static final class Settings {
        final boolean enabled;
        final Set<String> exceptions, fonts, media;

        Settings(boolean enabled, Set<String> exceptions, Set<String> fonts, Set<String> media) {
            this.enabled = enabled;
            this.exceptions = freeze(exceptions);
            this.fonts = freeze(fonts);
            this.media = freeze(media);
        }

        private static Set<String> freeze(Set<String> value) {
            return value == null || value.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(value));
        }

        boolean active(String url, String host) {
            if (!enabled) return false;
            String scope = url == null ? "" : url.trim();
            return scope.isEmpty() || "about:blank".equalsIgnoreCase(scope) || host == null || !exceptions.contains(host);
        }
    }

    private final Settings settings;
    private final FilterSnapshot rules;
    private final boolean active, fonts, media;

    SiteRequestPolicy(Settings settings, FilterSnapshot rules, String url, String host) {
        this.settings = settings;
        this.rules = rules;
        active = settings.active(url, host);
        fonts = active && host != null && settings.fonts.contains(host);
        media = active && host != null && settings.media.contains(host);
    }

    public boolean hasAssetPolicy() { return fonts || media; }

    /** Explicit native discovery must not work around the site's media switch with a metadata fetch. */
    public boolean blocksMedia() { return media; }

    public Asset asset(String path) {
        if (!hasAssetPolicy() || path == null) return Asset.PASS;
        String p = path.toLowerCase(Locale.US);
        if (fonts && (p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf") || p.endsWith(".otf")
                || p.contains("/fonts/"))) return Asset.FONT;
        if (media && (p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".m4v") || p.endsWith(".jpg")
                || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp") || p.endsWith(".gif"))) return Asset.MEDIA;
        return Asset.PASS;
    }

    public boolean blocksNetwork(Uri uri) {
        if (!active || uri == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);
        if (exempt(host)) return false;
        // Exceptions also take precedence over the built-in YouTube check.
        FilterSnapshot.Network match = rules.network(host, uri.getEncodedPath(), uri.getEncodedQuery());
        return match == FilterSnapshot.Network.BLOCK || (match == FilterSnapshot.Network.NONE
                && YouTubeScope.hasHost(host) && isYouTubeAdRequest(uri));
    }

    static boolean exempt(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "0.0.0.0".equals(host)
                || host.startsWith("192.168.") || host.startsWith("10.")
                || "canyoublockit.com".equals(host) || host.endsWith(".canyoublockit.com")
                || "adblock-tester.com".equals(host) || host.endsWith(".adblock-tester.com")
                || "d3ward.github.io".equals(host);
    }

    static boolean isYouTubeAdRequest(Uri uri) {
        if (YouTubeScope.origin(uri.toString()) == null) return false;
        String path = uri.getPath();
        if (path != null) {
            String lower = path.toLowerCase(Locale.US);
            if (lower.contains("/api/stats/ads") || lower.contains("/pagead/") || lower.contains("/ptracking")
                    || lower.contains("/get_midroll_info")) return true;
        }
        String query = uri.getQuery();
        return query != null && (query.contains("adformat=") || query.contains("ad_cpn="));
    }

    /** Per view: one native navigation context and one alternate (main-frame URL or fallback).
     * Interception workers may race a replacement; they cannot write to the new navigation's entry. */
    public static final class Cache {
        private static final class Entry {
            final String url, host;
            volatile SiteRequestPolicy policy;
            Entry(String url, String host) { this.url = url; this.host = host; }
        }

        private final Supplier<Settings> settings;
        private final Supplier<FilterSnapshot> rules;
        private final Function<String, String> clean;
        private volatile Entry navigation;
        private volatile Entry alternate;

        Cache(String url, Supplier<Settings> settings, Supplier<FilterSnapshot> rules, Function<String, String> clean) {
            this.settings = settings;
            this.rules = rules;
            this.clean = clean;
            navigation = entry(url);
        }

        /** Only trusted owner-thread navigation callbacks can seed subresource context. */
        public void navigate(String url) {
            if (!Objects.equals(url, navigation.url)) {
                Entry other = alternate;
                navigation = other != null && Objects.equals(url, other.url) ? other : entry(url);
                alternate = null;
            }
        }

        public SiteRequestPolicy forSubresource() {
            Entry top = navigation;
            return top.url == null || top.url.isEmpty() ? null : read(top);
        }

        public SiteRequestPolicy forUrl(String url) {
            Entry top = navigation;
            if (Objects.equals(url, top.url)) return read(top);
            Entry other = alternate;
            if (other == null || !Objects.equals(url, other.url)) {
                other = entry(url);
                // Do not hold an oversized untrusted Referer merely to save its next parse.
                if (url == null || url.length() <= 8192) alternate = other;
            }
            return read(other);
        }

        private Entry entry(String url) { return new Entry(url, clean.apply(url)); }

        private SiteRequestPolicy read(Entry entry) {
            // Publication identity is the revision. Neither lookup needs preferences or a write lock.
            Settings config = settings.get();
            FilterSnapshot index = rules.get();
            SiteRequestPolicy old = entry.policy;
            if (old != null && old.settings == config && old.rules == index) return old;
            Trace.beginSection("Browser.filters.sitePolicy");
            try {
                SiteRequestPolicy next = new SiteRequestPolicy(config, index, entry.url, entry.host);
                entry.policy = next;
                return next;
            } finally { Trace.endSection(); }
        }
    }
}

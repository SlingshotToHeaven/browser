package com.privacy.browser.adblock;

import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.privacy.browser.adblock.SiteRequestPolicy.Asset.*;
import static org.junit.Assert.*;

public class SiteRequestPolicyTest {
    private static final String TOP = "https://page.invalid/", NEXT = "https://other.invalid/";

    @Test public void repeatedResourcesReuseOnePolicyAndAnAlternateNeverChangesTheirContext() {
        AtomicInteger extractions = new AtomicInteger();
        FilterSnapshot rules = rules("");
        SiteRequestPolicy.Settings settings = config(true, null, Set.of(TOP), Set.of(NEXT));
        SiteRequestPolicy.Cache cache = new SiteRequestPolicy.Cache(TOP, () -> settings, () -> rules, url -> {
            extractions.incrementAndGet();
            return url;
        });
        SiteRequestPolicy top = cache.forSubresource();
        for (int i = 0; i < 2000; i++) assertSame(top, cache.forSubresource());
        assertEquals(1, extractions.get());
        SiteRequestPolicy alternate = cache.forUrl(NEXT);
        assertSame(alternate, cache.forUrl(NEXT));
        assertEquals(MEDIA, alternate.asset("/poster.JPG"));
        assertSame(top, cache.forSubresource());
        cache.navigate(NEXT);
        assertSame(alternate, cache.forSubresource());
        assertEquals(2, extractions.get());
        cache.navigate(null);
        assertNull(cache.forSubresource());
        assertEquals(FONT, cache.forUrl(TOP).asset("/test.WOFF2"));
        assertNull(cache.forSubresource());
    }

    @Test public void revisionsKeepAnInFlightPolicyImmutableAndRefreshTheNextRequest() {
        AtomicReference<SiteRequestPolicy.Settings> settings = new AtomicReference<>(config(true, null, null, null));
        AtomicReference<FilterSnapshot> rules = new AtomicReference<>(rules(""));
        SiteRequestPolicy.Cache cache = new SiteRequestPolicy.Cache(TOP, settings::get, rules::get, url -> url);
        SiteRequestPolicy first = cache.forSubresource();
        Set<String> fonts = new HashSet<>(Set.of(TOP));
        settings.set(config(true, null, fonts, Set.of(TOP)));
        fonts.clear(); // published settings must not borrow caller preferences or mutable sets
        SiteRequestPolicy second = cache.forSubresource();
        assertNotSame(first, second);
        assertEquals(PASS, first.asset("/fonts/poster.webp"));
        assertEquals(FONT, second.asset("/fonts/poster.webp")); // font filter comes first
        assertEquals(MEDIA, second.asset("/poster.webp"));
        assertEquals(PASS, second.asset("/playlist.m3u8"));
        rules.set(rules("||tracker.invalid^"));
        assertNotSame(second, cache.forSubresource());
        settings.set(config(true, Set.of(TOP), Set.of(TOP), Set.of(TOP)));
        assertFalse(cache.forSubresource().hasAssetPolicy());
        settings.set(config(false, null, null, Set.of(TOP)));
        assertEquals(PASS, cache.forSubresource().asset("/poster.webp"));
        assertEquals(MEDIA, second.asset("/poster.webp"));
    }

    @Test public void onlyTheExistingLocalAndTestDestinationsAreExempt() {
        for (String host : Set.of("localhost", "127.0.0.1", "192.168.2.1", "10.9.8.7", "0.0.0.0",
                "canyoublockit.com", "sub.adblock-tester.com", "d3ward.github.io")) assertTrue(SiteRequestPolicy.exempt(host));
        for (String host : Set.of("localhost.invalid", "127.0.0.2", "d3ward.github.io.invalid", "172.16.0.1")) {
            assertFalse(SiteRequestPolicy.exempt(host));
        }
    }

    private static FilterSnapshot rules(String network) {
        EnumMap<Part, String> parts = new EnumMap<>(Part.class);
        for (Part part : Part.values()) parts.put(part, "");
        parts.put(Part.NETWORK, network);
        return FilterSnapshot.parse(parts, true);
    }

    private static SiteRequestPolicy.Settings config(boolean enabled, Set<String> allowed, Set<String> fonts, Set<String> media) {
        return new SiteRequestPolicy.Settings(enabled, allowed == null ? Collections.emptySet() : allowed, fonts, media);
    }
}

package com.privacy.browser;

import com.privacy.browser.favicon.FaviconManager;

import org.junit.Test;

import static org.junit.Assert.*;

public class BrowserDataPolicyTest {
    @Test public void aSiteOrMissingScopeNeverWidensToAllSites() {
        for (String scope : new String[] {null, "", "all", " * ", "example.org", "https://example.org:8443"}) {
            assertThrows(IllegalArgumentException.class, () -> BrowserDataPolicy.fromTool(scope, true, true, true));
        }
    }

    @Test public void toolCanOnlySelectExplicitWebViewCategories() {
        BrowserDataPolicy.Plan plan = BrowserDataPolicy.fromTool("*", true, false, false);
        assertTrue(plan.cookies);
        assertFalse(plan.urlCache);
        assertFalse(plan.legacyStorage);
        assertFalse(plan.localHistoryAndTabs);
        assertTrue(plan.selectedItems().contains("all sites"));
        assertThrows(IllegalArgumentException.class, () -> BrowserDataPolicy.fromTool("*", false, false, false));
    }

    @Test public void historySelectionDoesNotImplicitlyClearCookiesOrStorage() {
        BrowserDataPolicy.Plan plan = BrowserDataPolicy.allSites(false, false, false, true);
        assertTrue(plan.localHistoryAndTabs);
        assertFalse(plan.cookies);
        assertFalse(plan.urlCache);
        assertFalse(plan.legacyStorage);
        assertTrue(plan.selectedItems().contains("closes all tabs"));
    }

    @Test public void unsupportedPrivateRequestFailsClosed() {
        assertThrows(IllegalStateException.class, () -> PrivateTabPolicy.requireAvailable(true));
        PrivateTabPolicy.requireAvailable(false);
    }

    @Test public void onlyRegularWebHostsAreValidFaviconKeys() {
        assertEquals("example.org", FaviconManager.extractDomain("https://WWW.Example.org/page"));
        assertEquals("", FaviconManager.extractDomain(InternalHome.URL));
        assertEquals("", FaviconManager.extractDomain("file:///android_asset/home.html"));
        assertEquals("", FaviconManager.extractDomain("about:blank"));
        assertEquals("", FaviconManager.extractDomain("https://user@example.org/"));
    }
}

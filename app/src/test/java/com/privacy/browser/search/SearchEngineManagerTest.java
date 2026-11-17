package com.privacy.browser.search;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SearchEngineManagerTest {

    @Test
    public void testAvailableEngines() {
        List<SearchEngineManager.SearchEngine> engines = SearchEngineManager.getAvailableEngines();
        assertNotNull(engines);
        assertTrue(engines.size() >= 6);

        SearchEngineManager.SearchEngine ddg = SearchEngineManager.getEngineById("duckduckgo");
        assertNotNull(ddg);
        assertEquals("DuckDuckGo", ddg.getName());
        assertTrue(ddg.isPrivacyFocused());

        SearchEngineManager.SearchEngine brave = SearchEngineManager.getEngineById("brave");
        assertNotNull(brave);
        assertEquals("Brave Search", brave.getName());
        assertTrue(brave.isPrivacyFocused());

        SearchEngineManager.SearchEngine google = SearchEngineManager.getEngineById("google");
        assertNotNull(google);
        assertFalse(google.isPrivacyFocused());
    }

    @Test
    public void testBangDetectionPrefix() {
        SearchEngineManager.BangResult res = SearchEngineManager.detectBang("!w Android operating system");
        assertNotNull(res);
        assertEquals("!w", res.getTag());
        assertEquals("Wikipedia", res.getServiceName());
        assertEquals("Android operating system", res.getCleanQuery());
        assertTrue(res.getTargetUrl().contains("Special:Search?search=Android+operating+system") ||
                   res.getTargetUrl().contains("Special:Search?search=Android%20operating%20system"));
    }

    @Test
    public void testBangDetectionSuffix() {
        SearchEngineManager.BangResult res = SearchEngineManager.detectBang("funny cats !yt");
        assertNotNull(res);
        assertEquals("!yt", res.getTag());
        assertEquals("YouTube", res.getServiceName());
        assertEquals("funny cats", res.getCleanQuery());
        assertTrue(res.getTargetUrl().contains("results?search_query=funny+cats") ||
                   res.getTargetUrl().contains("results?search_query=funny%20cats"));
    }

    @Test
    public void testBangDetectionGitHubAndReddit() {
        SearchEngineManager.BangResult gh = SearchEngineManager.detectBang("!gh privacy browser");
        assertNotNull(gh);
        assertEquals("GitHub", gh.getServiceName());

        SearchEngineManager.BangResult reddit = SearchEngineManager.detectBang("!r privacy");
        assertNotNull(reddit);
        assertEquals("Reddit", reddit.getServiceName());
    }

    @Test
    public void testNoBangInRegularQuery() {
        SearchEngineManager.BangResult res = SearchEngineManager.detectBang("how to bake sourdough bread");
        assertNull(res);

        SearchEngineManager.BangResult resUrl = SearchEngineManager.detectBang("https://example.com");
        assertNull(resUrl);
    }

    @Test
    public void testOmniboxSuggestionModel() {
        OmniboxSuggestion suggestion = new OmniboxSuggestion(
                OmniboxSuggestion.Type.BOOKMARK,
                "Mozilla",
                "https://mozilla.org",
                "https://mozilla.org",
                123
        );
        assertEquals(OmniboxSuggestion.Type.BOOKMARK, suggestion.getType());
        assertEquals("Mozilla", suggestion.getTitle());
        assertEquals("https://mozilla.org", suggestion.getSubtitle());
        assertEquals("https://mozilla.org", suggestion.getTargetUrl());
        assertEquals(123, suggestion.getIconResId());
    }
}

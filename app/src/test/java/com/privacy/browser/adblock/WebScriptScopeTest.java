package com.privacy.browser.adblock;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class WebScriptScopeTest {
    @Test public void youtubeRequiresExactSupportedHttpsOrigin() {
        assertEquals("https://www.youtube.com", YouTubeScope.origin("HTTPS://WWW.YouTube.com:443/watch?v=test"));
        assertEquals("https://www.youtube-nocookie.com", YouTubeScope.origin("https://www.youtube-nocookie.com/embed/test"));
        for (String url : new String[] {"http://youtube.com/", "https://youtube.com:8443/", "https://youtu.be/test",
                "https://youtube.com.example.org/", "https://example.org/path/youtube.com", "https://www.youtube.com@elsewhere.invalid/",
                "https://user@www.youtube.com/", "https://elsewhere.invalid/?q=https://youtube.com", "file:///youtube.com"}) {
            assertNull(url, YouTubeScope.origin(url));
        }
    }

    @Test public void registrationSelectsOnlyExactEligibleOrigins() {
        assertTrue(YouTubeScope.selectOrigins(false, origin -> { fail("Disabled module asked for origin"); return true; }).isEmpty());
        assertEquals(Collections.singleton("https://m.youtube.com"), YouTubeScope.selectOrigins(true, "https://m.youtube.com"::equals));
        assertEquals(5, YouTubeScope.selectOrigins(true, origin -> true).size());
    }

    @Test public void nativeWrapperAlwaysIncludesOriginAndFrameCheckBeforeCustomCode() {
        String js = YouTubeScope.wrap(Collections.singleton("https://m.youtube.com"), Collections.emptySet(),
                false, false, "marker()");
        assertTrue(js.indexOf("location.origin") < js.indexOf("marker()"));
        assertTrue(js.indexOf("'/embed/'") < js.indexOf("marker()"));
        assertTrue(js.indexOf("if(!sameSite&&!false)return;") < js.indexOf("marker()"));
        assertFalse(js.contains("https://www.youtube.com"));
        assertEquals("", YouTubeScope.wrap(Collections.singleton("https://elsewhere.invalid"), Collections.emptySet(),
                true, true, "marker()"));
    }

    @Test public void userMatchersRespectHostBoundaryPathSchemeAndPort() {
        assertTrue(UserScriptMatch.matches("https://*.example.org:443/a/*", "https://sub.example.org/a/item?q=1"));
        assertTrue(UserScriptMatch.matches("*://example.org/*", "http://example.org/page"));
        assertFalse(UserScriptMatch.matches("https://*.example.org/a/*", "https://example.org/wrong/a/item"));
        assertFalse(UserScriptMatch.matches("https://*.example.org:123/a/*", "https://example.org/a/item"));
        assertFalse(UserScriptMatch.matches("http://example.org/*", "https://example.org/page"));
        assertFalse(UserScriptMatch.matches("example.org", "https://sub.example.org/"));
        assertFalse(UserScriptMatch.matches("*.example.org", "https://example.org.attacker.invalid/"));
        assertFalse(UserScriptMatch.matches("example.org", "https://other.invalid/?dest=example.org"));
        assertFalse(UserScriptMatch.matches("*", "https://home.privacy-browser.invalid/"));
        assertFalse(UserScriptMatch.matches("*", "data:text/plain,test"));
        assertFalse(UserScriptMatch.matches("", "https://example.org/"));
        assertTrue(UserScriptMatch.matches("*youtube.com*", "https://m.youtube.com/watch"));
        assertFalse(UserScriptMatch.matches("*youtube.com*", "https://unrelated.invalid/youtube.com/watch"));
    }
}

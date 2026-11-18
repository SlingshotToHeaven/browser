package com.privacy.downloader.engine;

import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.RequestContextFixture;
import com.privacy.downloader.net.ScopedHttp;

import org.junit.Test;

import java.io.IOException;
import java.net.CookieHandler;
import java.util.ArrayList;
import java.util.List;

import static com.privacy.downloader.engine.LoopbackHttpFixture.fixed;
import static org.junit.Assert.*;

public class ScopedHttpTest {
    private static final byte[] EMPTY = new byte[0];
    private static final String BROWSER_UA = "Fixture scoped browser";

    @Test(timeout = 5000)
    public void sameOriginRedirectLooksUpCookiesForEachActualUrlAndDoesNotUseSetCookie() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(
                fixed(302, EMPTY, "Location: /paths/next?step=1#not-on-wire", "Set-Cookie: injected=old"),
                fixed(303, EMPTY, "Location: ?step=2"), fixed(200, EMPTY))) {
            List<String> lookups = new ArrayList<>();
            RequestContext browser = RequestContextFixture.browser(server.url("/page?private-query=yes#section"), BROWSER_UA, url -> {
                lookups.add(url);
                return "scoped=" + lookups.size();
            });
            try (ScopedHttp.Response response = ScopedHttp.get(server.url(), browser, null, 2000)) {
                assertEquals(200, response.status);
                assertEquals(server.url("/paths/next?step=2"), response.url());
                assertSame(browser, response.context);
            }
            assertEquals(List.of(server.url(), server.url("/paths/next?step=1"), server.url("/paths/next?step=2")), lookups);
            int hop = 0;
            for (LoopbackHttpFixture.Request request : server.requests()) {
                assertEquals("scoped=" + ++hop, request.header("Cookie"));
                assertEquals(BROWSER_UA, request.header("User-Agent"));
                assertEquals(server.url("/"), request.header("Referer"));
            }
        }
    }

    @Test(timeout = 5000)
    public void crossOriginRedirectCannotCarryOrReacquireSession() throws Exception {
        String[] other = new String[1];
        try (LoopbackHttpFixture first = new LoopbackHttpFixture(
                req -> fixed(307, EMPTY, "Location: " + other[0]), fixed(206, new byte[]{1}));
             LoopbackHttpFixture second = new LoopbackHttpFixture(fixed(308, EMPTY, "Location: " + first.url("/return"),
                     "Set-Cookie: unrelated=1; Path=/"))) {
            other[0] = second.url();
            List<String> lookups = new ArrayList<>();
            RequestContext browser = RequestContextFixture.browser(first.url("/account?anything"), BROWSER_UA, url -> {
                lookups.add(url);
                return "fixture=private-to-first-origin";
            });
            try (ScopedHttp.Response response = ScopedHttp.download(first.url(), browser, null, 2000, 2000,
                    "bytes=0-0", null, () -> false)) {
                assertEquals(first.url("/return"), response.url());
                assertFalse(response.context.hasWebSession());
            }
            assertEquals(List.of(first.url()), lookups);
            assertEquals("fixture=private-to-first-origin", first.requests().get(0).header("Cookie"));
            for (LoopbackHttpFixture.Request request : List.of(second.requests().get(0), first.requests().get(1))) {
                assertNull(request.header("Cookie"));
                assertNull(request.header("Referer"));
                assertNull(request.header("Authorization"));
                assertEquals(RequestContext.DEFAULT_USER_AGENT, request.header("User-Agent"));
                assertEquals("bytes=0-0", request.header("Range"));
            }
        }
    }

    @Test(timeout = 5000)
    public void unboundTargetUsesAnonymousHeadAndAnActualRedirectRemainsHead() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(
                fixed(303, EMPTY, "Location: /next"), fixed(200, EMPTY))) {
            RequestContext unrelated = RequestContextFixture.browser("https://example.test/a", BROWSER_UA, url -> {
                fail("Unbound lookup for " + url);
                return null;
            });
            try (ScopedHttp.Response response = ScopedHttp.head(server.url(), unrelated, null, 2000)) {
                assertEquals(200, response.status);
                assertFalse(response.context.hasWebSession());
            }
            for (LoopbackHttpFixture.Request request : server.requests()) {
                assertEquals("HEAD", request.method);
                assertEquals(RequestContext.DEFAULT_USER_AGENT, request.header("User-Agent"));
                assertNull(request.header("Cookie"));
                assertNull(request.header("Referer"));
            }
        }
    }

    @Test(timeout = 5000)
    public void privateInvalidAndUserinfoUrlsDoNotUseTransport() throws Exception {
        RequestContext privateTab = RequestContext.fromBrowser("https://example.test/private", BROWSER_UA, true);
        assertTrue(privateTab.isPrivate());
        assertFalse(privateTab.hasWebSession());
        assertNull(privateTab.pageOrigin());
        assertThrows(IOException.class, () -> ScopedHttp.get("https://example.test/file", privateTab, null, 100));
        for (String url : new String[]{"file:///tmp/file", "ftp://example.test/file", "https://name:secret@example.test/file",
                "https://example.test:100000/file", "not a URL"}) {
            assertThrows(IOException.class, () -> ScopedHttp.get(url, RequestContext.anonymous(), null, 100));
        }
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(302, EMPTY, "Location: file:///tmp/file"))) {
            assertThrows(IOException.class, () -> ScopedHttp.get(server.url(), RequestContext.anonymous(), null, 1000));
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    public void originMatchesSchemeHostAndEffectivePortAndNeverContainsPageData() {
        RequestContext browser = RequestContextFixture.browser("HTTPS://example.TEST:443/private/path?a=x#secret", BROWSER_UA, url -> null);
        assertEquals("https://example.test", browser.pageOrigin());
        assertSame(browser, browser.forTarget("https://example.test/file"));
        for (String url : new String[]{"http://example.test/file", "https://example.test:444/file", "https://example.test.other/file",
                "https://sub.example.test/file", "https://example.test@other.test/file", "blob:https://example.test/value"}) {
            assertFalse(url, browser.forTarget(url).hasWebSession());
        }
        assertEquals("http://[::1]:8080", RequestContext.origin("http://[::1]:8080/path"));
    }

    @Test
    public void refusesAmbientProcessCookieJarWithoutChangingIt() {
        CookieHandler original = CookieHandler.getDefault();
        CookieHandler jar = new java.net.CookieManager();
        try {
            CookieHandler.setDefault(jar);
            IOException error = assertThrows(IOException.class, () -> ScopedHttp.get("http://127.0.0.1:1/none", RequestContext.anonymous(), null, 100));
            assertTrue(error.getMessage().contains("cookie jar"));
            assertSame(jar, CookieHandler.getDefault());
        } finally {
            CookieHandler.setDefault(original);
        }
    }
}

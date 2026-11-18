package com.privacy.browser;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.browser.adblock.AdBlockFileManager;
import com.privacy.browser.adblock.AdBlockManager;
import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Direct checks of the real Android response types. No request is sent and no private view is navigated. */
public class FilterInterceptionDeviceTest {
    private static final String PAGE = "http://page.perf17.invalid/", EXCEPTED = "http://excepted.perf17.invalid/";
    private static final String DENY = "http://tracker.perf17.invalid/deny/";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void requestContextsAndLiveRevisionsKeepTheSameResponseDecisions() throws Exception {
        TrackerBlocker protection = new TrackerBlocker(context);
        AdBlockManager filters = protection.manager();
        assertTrue("Use a disposable default profile", filters.isGlobalEnabled());
        AdBlockFileManager files = AdBlockFileManager.getInstance(context);
        FilterSnapshot before = files.ruleSnapshot();
        FilterSnapshot fixture = rules(true);
        files.applyRuleSnapshot(before, fixture);
        try (Harness h = new Harness(protection, false)) {
            filters.setSiteWhitelisted(EXCEPTED, true);
            mime("image/png", h.request(DENY + "ad.svg", false, EXCEPTED));
            mime("application/javascript", h.request(DENY + "ad.js", false, null));
            mime("application/json", h.request(DENY + "ad.json", false, null));
            mime("text/css", h.request(DENY + "ad.css", false, null));
            mime("text/html", h.request(DENY + "frame", false, null));
            assertTrue(body(h.request(DENY + "frame", true, null)).contains("Advertisement Blocked"));
            assertNull(h.request(EXCEPTED + "blocked-on-a-normal-site", true, null));
            assertNull(h.request(DENY + "allowed.json?case=test", false, null));
            assertNull(h.request("http://127.0.0.1/deny/ad.js", false, null));
            assertNull(h.request("http://sub.adblock-tester.com/ad.js", false, null));
            assertNull(h.request("https://www.youtube.com/api/stats/ads?fixture=ok", false, null));
            mime("text/html", h.request("https://www.youtube.com/api/stats/ads", false, null));
            assertNull(h.request("https://youtube.com.example.invalid/api/stats/ads", false, null));

            h.navigate(EXCEPTED);
            assertNull(h.request(DENY + "ad.js", false, PAGE));
            instrumentation.runOnMainSync(() -> assertTrue(h.client.shouldOverrideUrlLoading(h.view, new Request(DENY + "page", true, null))));
            h.navigate(null);
            assertNull(h.request(DENY + "ad.js", false, EXCEPTED));
            mime("application/javascript", h.request(DENY + "ad.js", false, null));
            h.navigate(PAGE);

            filters.setSiteFontsBlocked(PAGE, true);
            filters.setSiteLargeMediaBlocked(PAGE, true);
            mime("font/woff2", h.request(DENY + "fonts/ad.webp", false, null));
            mime("text/plain", h.request(DENY + "ad.png", false, null));
            filters.setSiteWhitelisted(PAGE, true);
            assertNull(h.request(DENY + "fonts/ad.webp", false, null));
            filters.setSiteWhitelisted(PAGE, false);
            filters.setGlobalEnabled(false);
            assertNull(h.request(DENY + "ad.js", false, null));
            filters.setGlobalEnabled(true);
            files.applyRuleSnapshot(fixture, rules(false));
            assertNull(h.request(DENY + "ad.js", false, null));
        } finally {
            filters.setSiteWhitelisted(EXCEPTED, false);
            filters.setSiteWhitelisted(PAGE, false);
            filters.setSiteFontsBlocked(PAGE, false);
            filters.setSiteLargeMediaBlocked(PAGE, false);
            filters.setGlobalEnabled(true);
            files.applyRuleSnapshot(files.ruleSnapshot(), before);
        }
    }

    @Test public void homeBypassesCountersAndPrivateInterceptionNeverPublishesStatistics() throws Exception {
        TrackerBlocker protection = new TrackerBlocker(context);
        AdBlockManager filters = protection.manager();
        long before = filters.getTotalBlockedCount();
        try (Harness regular = new Harness(protection, false); Harness privateModel = new Harness(protection, true)) {
            mime("text/html", regular.request(InternalHome.URL, true, null));
            WebResourceResponse childHome = regular.request(InternalHome.URL, false, null);
            assertEquals(404, childHome.getStatusCode());
            childHome.getData().close();
            assertEquals(0, filters.getPageRequestCount(regular.tab.pageKey()));
            mime("text/html", privateModel.request("https://www.youtube.com/api/stats/ads", false, null));
            assertEquals(0, filters.getPageBlockedCount(privateModel.tab.pageKey()));
            assertEquals(0, filters.getPageRequestCount(privateModel.tab.pageKey()));
            assertTrue(filters.getBlockedHostsForPage(privateModel.tab.pageKey()).isEmpty());
            assertEquals(0, privateModel.notifications);
            assertEquals(before, filters.getTotalBlockedCount());
        }
    }

    private static FilterSnapshot rules(boolean deny) {
        EnumMap<Part, String> parts = new EnumMap<>(Part.class);
        for (Part part : Part.values()) parts.put(part, "");
        parts.put(Part.NETWORK, "@@||www.youtube.com/api/stats/ads?fixture=ok^\n"
                + "||excepted.perf17.invalid^\n||127.0.0.1^\n||sub.adblock-tester.com^\n");
        parts.put(Part.USER_FILTERS, "@@||tracker.perf17.invalid/deny/allowed.json^\n"
                + (deny ? "||tracker.perf17.invalid/deny/\n" : ""));
        return FilterSnapshot.parse(parts, true);
    }

    private static void mime(String expected, WebResourceResponse reply) throws Exception {
        assertNotNull(reply);
        assertEquals(200, reply.getStatusCode());
        assertEquals(expected, reply.getMimeType());
        reply.getData().close();
    }

    private static String body(WebResourceResponse reply) throws Exception {
        assertNotNull(reply);
        assertEquals(200, reply.getStatusCode());
        try (InputStream in = reply.getData(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[1024];
            int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private final class Harness implements AutoCloseable, PrivacyWebViewClient.ClientListener {
        PrivacyWebView view;
        TabModel tab;
        PrivacyWebViewClient client;
        int notifications;

        Harness(TrackerBlocker protection, boolean privateModel) {
            instrumentation.runOnMainSync(() -> {
                view = new PrivacyWebView(context);
                tab = new TabModel(view, PAGE);
                tab.setIncognito(privateModel);
                tab.navigation.start(view, PAGE);
                tab.navigation.commit(view, PAGE, PAGE);
                tab.updatePageScope(null, view);
                tab.messages = new TabPageMessages(view, false, ignored -> false, (page, kind, payload, reply) -> {});
                client = new PrivacyWebViewClient(protection, tab, context, this);
            });
        }

        void navigate(String url) {
            instrumentation.runOnMainSync(() -> client.setNavigationTarget(url));
        }

        WebResourceResponse request(String url, boolean mainFrame, String referer) {
            return client.shouldInterceptRequest(view, new Request(url, mainFrame, referer));
        }

        @Override public void close() { instrumentation.runOnMainSync(tab::destroy); }
        @Override public void onPageStarted(String url) {}
        @Override public void onPageCommitted(String url) {}
        @Override public void onNativeHistoryChanged(String url) {}
        @Override public void onPageVisible(String url) {}
        @Override public boolean onPageFinished(String url) { return false; }
        @Override public void onPageFailed(String url) {}
        @Override public void onTrackerBlocked(String pageKey) { notifications++; }
        @Override public boolean canPrompt() { return false; }
        @Override public void onRendererLost() {}
    }

    private static final class Request implements WebResourceRequest {
        final Uri url;
        final boolean main;
        final String referer;
        Request(String url, boolean main, String referer) {
            this.url = Uri.parse(url);
            this.main = main;
            this.referer = referer;
        }
        @Override public Uri getUrl() { return url; }
        @Override public boolean isForMainFrame() { return main; }
        @Override public boolean isRedirect() { return false; }
        @Override public boolean hasGesture() { return false; }
        @Override public String getMethod() { return "GET"; }
        @Override public Map<String, String> getRequestHeaders() {
            return referer == null ? Collections.emptyMap() : Collections.singletonMap("referer", referer);
        }
    }
}

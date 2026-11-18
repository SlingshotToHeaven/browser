package com.privacy.browser;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Only use a disposable debug install. The cookie-clear test clears the regular profile's cookies. */
public class BrowserPrivacyTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void privateTabAdmissionDoesNotFallThroughToDefaultProfile() {
        ui(() -> {
            TabsManager tabs = new TabsManager();
            assertThrows(IllegalStateException.class, () -> tabs.createTab(context, "https://a.invalid/", true));
            assertEquals(0, tabs.getTabCount());
            return null;
        });
    }

    @Test public void explicitCookieClearAffectsBothSitesAndDoesNotTouchAppFiles() throws Exception {
        String[] urls = sites();
        Path unrelated = Files.createTempFile(context.getFilesDir().toPath(), "not-browsing-data-", ".txt");
        try {
            Files.write(unrelated, new byte[] { 23, 45 });
            CountDownLatch set = new CountDownLatch(2);
            ui(() -> {
                for (String url : urls) CookieManager.getInstance().setCookie(url, "fixture=1; Path=/; Secure", ok -> set.countDown());
                return null;
            });
            assertTrue(set.await(5, TimeUnit.SECONDS));
            for (String url : urls) assertTrue(ui(() -> CookieManager.getInstance().getCookie(url)).contains("fixture=1"));
            CountDownLatch cleared = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            ui(() -> {
                BrowserDataCleaner.clear(context, BrowserDataPolicy.allSites(true, false, false, false),
                        complete -> fail("App history was not selected"), report -> {
                            result.set(report.toJson());
                            cleared.countDown();
                        });
                return null;
            });
            assertTrue(cleared.await(12, TimeUnit.SECONDS));
            assertTrue(result.get().contains("\"status\":\"finished\""));
            for (String url : urls) assertNull(ui(() -> CookieManager.getInstance().getCookie(url)));
            assertArrayEquals(new byte[] { 23, 45 }, Files.readAllBytes(unrelated));
        } finally {
            Files.deleteIfExists(unrelated);
        }
    }

    @Test public void localCleanupReportWaitsForTheActualResultAndDoesNotAssumeSuccess() {
        ui(() -> {
            AtomicReference<java.util.function.Consumer<Boolean>> complete = new AtomicReference<>();
            java.util.List<String> reports = new java.util.ArrayList<>();
            BrowserDataCleaner.clear(context, BrowserDataPolicy.allSites(false, false, false, true), complete::set,
                    report -> reports.add(report.toJson()));
            assertTrue(BrowserDataCleaner.inProgress());
            assertTrue(reports.isEmpty());
            complete.get().accept(false);
            complete.get().accept(true); // a mistaken late completion must not report success
            assertFalse(BrowserDataCleaner.inProgress());
            assertEquals(1, reports.size());
            assertTrue(reports.get(0).contains("\"status\":\"partial_or_unconfirmed\""));
            return null;
        });
    }

    @Test public void providerProfileProbeIsolatesCookiesAndDomStorageAndDeletesNamedProfile() throws Exception {
        assumeTrue(ui(() -> WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)));
        String name = "browser-privacy-audit-" + UUID.randomUUID();
        WebView regular = makeView(null);
        WebView isolated = null;
        String[] sites = sites();
        try {
            isolated = makeView(name);
            for (String site : sites) {
                open(regular, site);
                assertEquals("\"regular\"", js(regular, "localStorage.mark='regular';document.cookie='mark=regular; Path=/; Secure';localStorage.mark"));
                open(isolated, site);
                assertEquals("\"missing|\"", js(isolated, "(localStorage.mark||'missing')+'|'+document.cookie"));
                assertEquals("\"isolated\"", js(isolated, "localStorage.mark='isolated';document.cookie='mark=isolated; Path=/; Secure';localStorage.mark"));
                assertEquals("\"regular|mark=regular\"", js(regular, "localStorage.mark+'|'+document.cookie"));
            }
            destroy(isolated);
            isolated = null;
            assertTrue(ui(() -> ProfileStore.getInstance().deleteProfile(name)));
            isolated = makeView(name);
            for (String site : sites) {
                open(isolated, site);
                assertEquals("\"missing|\"", js(isolated, "(localStorage.mark||'missing')+'|'+document.cookie"));
                open(regular, site);
                assertEquals("\"regular|mark=regular\"", js(regular, "localStorage.mark+'|'+document.cookie"));
                js(regular, "localStorage.removeItem('mark');document.cookie='mark=; Max-Age=0; Path=/; Secure';");
            }
        } finally {
            if (isolated != null) destroy(isolated);
            destroy(regular);
            ui(() -> ProfileStore.getInstance().deleteProfile(name));
        }
    }

    private static String[] sites() {
        String tag = UUID.randomUUID().toString();
        return new String[] { "https://one-" + tag + ".invalid/", "https://two-" + tag + ".invalid/" };
    }

    private WebView makeView(String profile) {
        return ui(() -> {
            WebView view = new WebView(context);
            try {
                if (profile != null) WebViewCompat.setProfile(view, profile);
                view.getSettings().setJavaScriptEnabled(true);
                view.getSettings().setDomStorageEnabled(true);
                view.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(
                                "<!doctype html><title>Privacy fixture</title>".getBytes(StandardCharsets.UTF_8)));
                    }
                    @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                        WebViewDisposal.detachAndDestroy(v);
                        return true;
                    }
                });
                return view;
            } catch (RuntimeException e) {
                WebViewDisposal.detachAndDestroy(view);
                throw e;
            }
        });
    }

    private void destroy(WebView view) { instrumentation.runOnMainSync(() -> WebViewDisposal.detachAndDestroy(view)); }

    private void open(WebView view, String url) throws Exception {
        instrumentation.runOnMainSync(() -> view.loadUrl(url));
        long deadline = SystemClock.uptimeMillis() + 8000;
        do {
            if (("\"" + url + "|Privacy fixture\"").equals(js(view, "location.href+'|'+document.title"))) return;
            Thread.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Fixture document did not load in the selected profile");
    }

    private String js(WebView view, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> view.evaluateJavascript(script, result -> {
            value.set(result);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return value.get();
    }

    private <T> T ui(Supplier<T> read) {
        AtomicReference<T> ref = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> ref.set(read.get()));
        return ref.get();
    }
}

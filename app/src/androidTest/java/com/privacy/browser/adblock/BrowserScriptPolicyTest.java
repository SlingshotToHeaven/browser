package com.privacy.browser.adblock;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Disposable debug profile only. Every HTTP(S) response is synthetic; no YouTube account or network. */
public class BrowserScriptPolicyTest {
    private static final String OFFSITE = "https://script-policy-fixture.invalid";
    private static final String YOUTUBE = "https://www.youtube.com";
    private static final String WATCH = YOUTUBE + "/watch?fixture=1";
    private static final String MARKER = "window.__privacy_ubo_yt_defuser_v3===true";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void providerInstallsBeforePageScriptOnlyOnApprovedOriginsAndEmbedPaths() throws Exception {
        assumeTrue(ui(() -> WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)));
        AdBlockManager manager = defaultPolicy();
        try (Fixture fixture = ui(() -> new Fixture(true))) {
            fixture.load(WATCH);
            assertEquals("early", fixture.title());
            fixture.load(OFFSITE + "/frames");
            assertEquals("plain", fixture.title());
            assertEquals("\"early,plain\"", fixture.frameResults());
            ui(() -> { fixture.policy.close(true); return null; });
            fixture.load(YOUTUBE + "/watch?after-removal");
            assertEquals("plain", fixture.title());
        }
        try {
            ui(() -> { manager.setSiteWhitelisted(OFFSITE, true); return null; });
            try (Fixture fixture = ui(() -> new Fixture(true))) {
                fixture.load(OFFSITE + "/frames");
                assertEquals("\"plain,plain\"", fixture.frameResults());
                fixture.load(WATCH);
                assertEquals("early", fixture.title());
            }
        } finally {
            ui(() -> { manager.setSiteWhitelisted(OFFSITE, false); return null; });
        }
    }

    @Test public void fallbackOnlyEvaluatesForCurrentMainDocumentAndDoesNotDoubleWrap() throws Exception {
        defaultPolicy();
        try (Fixture fixture = ui(() -> new Fixture(false))) {
            fixture.load(OFFSITE + "/youtube.com/watch");
            fixture.commit(OFFSITE + "/youtube.com/watch");
            assertEquals("false", fixture.js(MARKER));
            fixture.load(WATCH);
            assertEquals("plain", fixture.title());
            fixture.commit(YOUTUBE + "/watch?old-commit");
            assertEquals("false", fixture.js(MARKER));
            fixture.commit(WATCH);
            assertEquals("true", fixture.js(MARKER + " && !!(window.fixtureFetch=window.fetch)"));
            fixture.commit(WATCH);
            assertEquals("true", fixture.js("window.fixtureFetch===window.fetch"));
            fixture.load(OFFSITE + "/frames");
            fixture.commit(OFFSITE + "/frames");
            assertEquals("\"plain,plain\"", fixture.frameResults());
        }
    }

    @Test public void masterDefuserAndSiteSwitchesInvalidatePolicyAndGateFallback() throws Exception {
        AdBlockManager manager = defaultPolicy();
        try {
            try (Fixture old = ui(() -> new Fixture(false))) {
                old.load(WATCH);
                ui(() -> { manager.setGlobalEnabled(false); return null; });
                assertFalse(ui(old.policy::isCurrent));
                old.commit(WATCH);
                assertEquals("false", old.js(MARKER));
            }
            assertFallbackDisabled();
            ui(() -> { manager.setGlobalEnabled(true); manager.setAntiAdblockDefuserEnabled(false); return null; });
            assertFallbackDisabled();
            ui(() -> { manager.setAntiAdblockDefuserEnabled(true); manager.setSiteWhitelisted(WATCH, true); return null; });
            assertFallbackDisabled();
        } finally {
            ui(() -> {
                manager.setGlobalEnabled(true);
                manager.setAntiAdblockDefuserEnabled(true);
                manager.setSiteWhitelisted(WATCH, false);
                return null;
            });
        }
    }

    @Test public void aRuleForOneHostDisablesPageAndInjectedJavaScriptBeforeOtherHostsLoad() throws Exception {
        AdBlockManager manager = defaultPolicy();
        String rule = "https://another-script-policy-fixture.invalid/";
        try {
            ui(() -> { manager.setSiteJavaScriptEnabled(rule, false); return null; });
            try (Fixture fixture = ui(() -> new Fixture(true))) {
                assertFalse(ui(() -> fixture.view.getSettings().getJavaScriptEnabled()));
                for (String url : new String[] {OFFSITE + "/frames", WATCH}) {
                    fixture.load(url);
                    fixture.commit(url);
                    assertEquals("no-page-script", fixture.title());
                }
            }
        } finally {
            ui(() -> { manager.setSiteJavaScriptEnabled(rule, true); return null; });
        }
        try (Fixture fixture = ui(() -> new Fixture(false))) {
            fixture.load(OFFSITE + "/");
            assertEquals("plain", fixture.title());
        }
    }

    private void assertFallbackDisabled() throws Exception {
        try (Fixture fixture = ui(() -> new Fixture(false))) {
            fixture.load(WATCH);
            fixture.commit(WATCH);
            assertEquals("false", fixture.js(MARKER));
        }
    }

    private AdBlockManager defaultPolicy() {
        AdBlockManager manager = ui(() -> AdBlockManager.getInstance(context));
        assertTrue("Use a fresh disposable profile", ui(() -> manager.isGlobalEnabled() && manager.isAntiAdblockDefuserEnabled()
                && !manager.hasNoScriptRules() && !manager.hasSiteExceptions()));
        return manager;
    }

    private final class Fixture implements AutoCloseable {
        final WebView view = new WebView(context);
        final WebScriptPolicy policy;
        private String expected;
        private CountDownLatch finished;
        private boolean destroyed;

        Fixture(boolean allowDocumentStart) {
            view.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            policy = new WebScriptPolicy(view, allowDocumentStart);
            view.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    String html = "<!doctype html><meta charset=utf-8><title>no-page-script</title><script>"
                            + "document.title=" + MARKER + "?'early':'plain';"
                            + "if(window.top!==window){parent.postMessage("
                            + "(location.pathname.startsWith('/embed/')?'embed':'other')+'='+document.title,'" + OFFSITE + "');}"
                            + "window.results={};addEventListener('message',event=>{if(event.origin==='" + YOUTUBE + "'){"
                            + "const pair=event.data.split('=');results[pair[0]]=pair[1];}});</script>";
                    if ((OFFSITE + "/frames").equals(url)) {
                        html += "<iframe src='" + YOUTUBE + "/embed/fixture'></iframe>"
                                + "<iframe src='" + YOUTUBE + "/fixture'></iframe>";
                    }
                    return new WebResourceResponse("text/html", "UTF-8",
                            new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                }

                @Override public void onPageFinished(WebView view, String url) {
                    if (url.equals(expected)) finished.countDown();
                }

                @Override public boolean onRenderProcessGone(WebView lost, android.webkit.RenderProcessGoneDetail detail) {
                    policy.close(false);
                    destroyed = true;
                    lost.destroy();
                    return true;
                }
            });
        }

        void load(String url) throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            ui(() -> { expected = url; finished = latch; view.loadUrl(url); return null; });
            assertTrue("No finish event for " + url, latch.await(10, TimeUnit.SECONDS));
        }

        void commit(String url) { ui(() -> { policy.onCommittedPage(url); return null; }); }

        String title() { return ui(view::getTitle); }

        String frameResults() throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            String values;
            do {
                values = js("[results.embed||'',results.other||''].join(',')");
                if (values != null && !values.startsWith("\",") && !values.endsWith(",\"")) break;
                Thread.sleep(50);
            } while (SystemClock.uptimeMillis() < deadline);
            return values;
        }

        String js(String source) throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            ui(() -> { view.evaluateJavascript(source, value -> { result.set(value); latch.countDown(); }); return null; });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            return result.get();
        }

        @Override public void close() {
            ui(() -> {
                if (!destroyed) {
                    destroyed = true;
                    policy.close(true);
                    view.destroy();
                }
                return null;
            });
        }
    }

    private <T> T ui(Supplier<T> read) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(read.get()));
        return result.get();
    }
}

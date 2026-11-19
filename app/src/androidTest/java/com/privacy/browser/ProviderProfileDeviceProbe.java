package com.privacy.browser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in provider experiment, not an implementation or qualification of private tabs. */
public class ProviderProfileDeviceProbe {
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    // Chromium 137 can buffer profile-registry writes for 10 s. Cookie flush only
    // covers cookies; actual registry and DOM storage persistence are checked on reopen.
    private static final long PROFILE_WRITE_GRACE_MS = 15_000;
    private static final String MARKERS = "(localStorage.getItem('mark')||'missing')+'|'+document.cookie";
    private static final String CLEAR = "localStorage.removeItem('mark');document.cookie='mark=; Max-Age=0; Path=/; Secure';";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    private enum Phase { SEED, REOPEN, AFTER_DELETE }

    private static final class Fixture {
        final String profile;
        final String[] origins;
        Fixture(String id) {
            profile = "browser-privacy-audit-" + id;
            origins = new String[]{"https://one-" + id + ".invalid/", "https://two-" + id + ".invalid/"};
        }
    }

    @Test public void seedDistinctMarkers() throws Exception {
        Fixture f = fixture();
        assertFalse("Choose a new synthetic probe ID", exists(f));
        verifyBoth(f, Phase.SEED);
        assertTrue("Provider did not register the fixture name", exists(f));
    }

    @Test public void persistedMarkersRemainIsolatedAfterRestart() throws Exception {
        Fixture f = fixture();
        assertTrue("Named profile missing on cold start; check that seed passed with the same ID and app data was retained",
                exists(f));
        verifyBoth(f, Phase.REOPEN);
    }

    @Test public void deletionInFreshProcessRetiresOnlyTheNamedProfile() throws Exception {
        Fixture f = fixture();
        // Do not construct a view for, or retrieve, the named profile in this process
        // before deleteProfile. Destruction of its last WebView is not an unload API.
        assertTrue("No named profile to delete; preserve the fixture between phases", exists(f));
        assertTrue("Provider did not retire the registered profile",
                ui(() -> ProfileStore.getInstance().deleteProfile(f.profile)));
        assertFalse("Provider still advertises the deleted name", exists(f));
        verifyBoth(f, Phase.AFTER_DELETE);
    }

    @Test public void removeProbeResidueInFreshProcess() throws Exception {
        Fixture f = fixture();
        if (exists(f)) assertTrue(ui(() -> ProfileStore.getInstance().deleteProfile(f.profile)));
        assertFalse(exists(f));
        WebView regular = makeView(null);
        try {
            for (String origin : f.origins) {
                open(regular, origin);
                assertEquals("\"missing|\"", js(regular, CLEAR + MARKERS));
            }
            ui(() -> { CookieManager.getInstance().flush(); return null; });
            // Give the provider time to write the registry deletion and DOM marker removal.
            SystemClock.sleep(PROFILE_WRITE_GRACE_MS);
        } finally { destroy(regular); }
    }

    private Fixture fixture() throws Exception {
        String id = InstrumentationRegistry.getArguments().getString("profileProbeId");
        assumeTrue("Use the separate process steps in the provider-profile probe instructions", id != null);
        assertEquals("Use the disposable debug app", "com.browser", context.getPackageName());
        assertTrue((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        assertTrue("Use a fresh lower-case UUID as profileProbeId", UUID.matcher(id).matches());
        assumeTrue("MULTI_PROFILE is unavailable on this provider", ui(() -> WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)));
        return new Fixture(id);
    }

    private boolean exists(Fixture f) throws Exception {
        return ui(() -> ProfileStore.getInstance().getAllProfileNames().contains(f.profile));
    }

    private void verifyBoth(Fixture f, Phase phase) throws Exception {
        WebView regular = makeView(null);
        WebView isolated = null;
        try {
            WebView named = isolated = makeView(f.profile);
            for (int i = 0; i < f.origins.length; i++) {
                String origin = f.origins[i];
                String regularMarker = "regular-" + i;
                String namedMarker = "isolated-" + i;
                open(regular, origin);
                if (phase == Phase.SEED) {
                    assertEquals("New fixture origin must start empty", "\"missing|\"", js(regular, MARKERS));
                    seed(regular, regularMarker);
                }
                assertMarker(regular, regularMarker);

                open(named, origin);
                if (phase != Phase.REOPEN) assertEquals("\"missing|\"", js(named, MARKERS));
                if (phase == Phase.SEED) seed(named, namedMarker);
                if (phase != Phase.AFTER_DELETE) assertMarker(named, namedMarker);
                assertMarker(regular, regularMarker);
            }
            if (phase == Phase.SEED) {
                ui(() -> {
                    CookieManager.getInstance().flush();
                    WebViewCompat.getProfile(named).getCookieManager().flush();
                    return null;
                });
            }
            if (phase != Phase.REOPEN) {
                // Wait inside instrumentation, off the UI thread and before finishing the
                // process, for background writes after seed or delete-and-recreate.
                SystemClock.sleep(PROFILE_WRITE_GRACE_MS);
            }
        } finally {
            if (isolated != null) destroy(isolated);
            destroy(regular);
        }
    }

    private void assertMarker(WebView view, String marker) throws Exception {
        assertEquals("\"" + marker + "|mark=" + marker + "\"", js(view, MARKERS));
    }

    private void seed(WebView view, String marker) throws Exception {
        js(view, "localStorage.setItem('mark','" + marker + "');document.cookie='mark="
                + marker + "; Max-Age=3600; Path=/; Secure';");
    }

    private WebView makeView(String profile) throws Exception {
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
            } catch (RuntimeException failure) {
                WebViewDisposal.detachAndDestroy(view);
                throw failure;
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
        fail("Synthetic document did not load in the selected profile");
    }

    private String js(WebView view, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> view.evaluateJavascript(script, result -> {
            value.set(result);
            done.countDown();
        }));
        assertTrue("No JS reply from the selected synthetic document", done.await(5, TimeUnit.SECONDS));
        return value.get();
    }

    private <T> T ui(Supplier<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation::get);
        instrumentation.runOnMainSync(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}

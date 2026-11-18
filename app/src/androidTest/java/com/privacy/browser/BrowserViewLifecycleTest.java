package com.privacy.browser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewRenderProcess;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewFeature;

import com.privacy.browser.bookmarks.BookmarkDbHelper;

import org.junit.After;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Use a disposable debug install. This drives the real Activity and Android WebView. */
public class BrowserViewLifecycleTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity activity;

    @After public void stop() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
    }

    @Test public void bundledHomeCanReadRecentBookmarksThroughOnlyTheNewEndpoint() throws Exception {
        boolean supportsMessages = ui(() -> WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
        String bookmark = "https://example.invalid/fixture-" + UUID.randomUUID();
        BookmarkDbHelper db = BookmarkDbHelper.getInstance(context);
        assertTrue(db.addBookmarkAndGetId("Home test bookmark", bookmark, 0) > 0);
        try {
            WebView home = openHome();
            assertEquals("\"undefined\"", js(home, "typeof window.AndroidInterface"));
            if (supportsMessages) {
                long deadline = SystemClock.uptimeMillis() + 5000;
                String text;
                do {
                    text = js(home, "document.getElementById('bookmarks-container').textContent");
                    if (text != null && text.contains(bookmark)) return;
                    Thread.sleep(50);
                } while (SystemClock.uptimeMillis() < deadline);
                fail("Bundled main-frame homepage never received its scoped bookmark reply");
            } else {
                assertEquals("\"undefined\"", js(home, "typeof window.PrivacyHome"));
                assertEquals("\"\"", js(home, "document.getElementById('bookmarks-container').textContent"));
            }
        } finally {
            db.removeBookmark(bookmark);
        }
    }

    @Test public void terminatedRendererCanBeRestoredWithoutReplacingTheActivity() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 29);
        WebView dead = openHome();
        boolean terminated = ui(() -> {
            WebViewRenderProcess process = dead.getWebViewRenderProcess();
            return process != null && process.terminate();
        });
        assumeTrue("WebView renderer is not separately terminable on this device", terminated);
        assertTrue(await(() -> pageView() == null));
        assertTrue(ui(() -> !activity.isFinishing() && activity.findViewById(R.id.btn_reload).performClick()));
        assertTrue(await(() -> pageView() != null && pageView() != dead && InternalHome.URL.equals(pageView().getUrl())));
        assertNotSame(dead, ui(this::pageView));
    }

    private WebView openHome() throws InterruptedException {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_LOAD_URL, InternalHome.URL)
                .putExtra(MainActivity.EXTRA_OPEN_IN_NEW_TAB, true);
        activity = (MainActivity) instrumentation.startActivitySync(intent);
        assertTrue(await(() -> pageView() != null && InternalHome.URL.equals(pageView().getUrl())
                && "Privacy Browser".equals(pageView().getTitle())));
        return ui(this::pageView);
    }

    private WebView pageView() {
        ViewGroup container = activity.findViewById(R.id.webview_container);
        if (container == null || container.getChildCount() == 0) return null;
        View first = container.getChildAt(0);
        return first instanceof WebView ? (WebView) first : null;
    }

    private boolean await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            if (ui(condition)) return true;
            Thread.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private String js(WebView view, String script) throws InterruptedException {
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

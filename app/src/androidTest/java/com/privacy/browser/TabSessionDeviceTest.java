package com.privacy.browser;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.webkit.WebBackForwardList;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Only intercepted fixture pages, no external network or changes to the app's saved tab files. */
public class TabSessionDeviceTest {
    private static final String URL = "https://session-fixture.invalid/";
    private static final byte[] PAGE = "<!doctype html><title>Session history fixture</title>".getBytes(StandardCharsets.UTF_8);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void nativeSameAddressHistoryCanBeDetectedCapturedAndRestored() throws Exception {
        AtomicInteger historyUpdates = new AtomicInteger();
        WebView original = ui(() -> view(historyUpdates));
        WebView restored = ui(() -> view(new AtomicInteger()));
        WebView mismatched = ui(() -> view(new AtomicInteger()));
        try {
            ui(() -> { original.loadUrl(URL); return null; });
            assertTrue(await(() -> "Session history fixture".equals(original.getTitle())
                    && original.copyBackForwardList().getSize() == 1 && original.getProgress() == 100));
            int before = historyUpdates.get();
            CountDownLatch evaluated = new CountDownLatch(1);
            ui(() -> {
                original.evaluateJavascript("history.pushState({n:1},'',location.href);"
                        + "history.pushState({n:2},'',location.href);", ignored -> evaluated.countDown());
                return null;
            });
            assertTrue(evaluated.await(5, TimeUnit.SECONDS));
            assertTrue(await(() -> original.copyBackForwardList().getSize() == 3 && historyUpdates.get() > before));
            TabSessionCheckpoint.History end = ui(() -> NativeTabSession.history(original, URL));
            assertNotNull(end);
            assertEquals(2, end.current);

            ui(() -> { original.goBackOrForward(-1); return null; });
            assertTrue(await(() -> original.copyBackForwardList().getCurrentIndex() == 1));
            TabSessionCheckpoint.History backed = ui(() -> NativeTabSession.history(original, URL));
            assertNotNull(backed);
            assertEquals(1, backed.current);
            assertEquals(end.entries, backed.entries);
            assertNotEquals(end, backed);
            byte[] bytes = ui(() -> NativeTabSession.capture(original, URL, backed));
            assertNotNull(bytes);
            assertTrue(bytes.length > 0);
            assertEquals(backed, ui(() -> NativeTabSession.restore(restored, bytes, URL)));
            WebBackForwardList copied = ui(restored::copyBackForwardList);
            assertEquals(3, copied.getSize());
            assertEquals(1, copied.getCurrentIndex());
            for (int i = 0; i < copied.getSize(); i++) assertEquals(URL, copied.getItemAtIndex(i).getUrl());
            assertNull(ui(() -> NativeTabSession.restore(mismatched, bytes, URL + "other")));
        } finally {
            ui(() -> { original.destroy(); restored.destroy(); mismatched.destroy(); return null; });
        }
    }

    @Test public void platformBundleWithoutNativeHistoryIsNotARestoredPage() {
        assertNull(ui(() -> {
            WebView candidate = view(new AtomicInteger());
            Parcel parcel = Parcel.obtain();
            try {
                new Bundle().writeToParcel(parcel, 0);
                return NativeTabSession.restore(candidate, parcel.marshall(), URL);
            } finally {
                parcel.recycle();
                candidate.destroy();
            }
        }));
    }

    private WebView view(AtomicInteger updates) {
        WebView view = new WebView(instrumentation.getTargetContext());
        view.getSettings().setJavaScriptEnabled(true);
        view.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView owner, WebResourceRequest request) {
                return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(PAGE));
            }

            @Override public void doUpdateVisitedHistory(WebView owner, String url, boolean reload) {
                if (URL.equals(url)) updates.incrementAndGet();
            }
        });
        return view;
    }

    private boolean await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            if (ui(condition)) return true;
            Thread.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private <T> T ui(Supplier<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(operation.get()));
        return result.get();
    }
}

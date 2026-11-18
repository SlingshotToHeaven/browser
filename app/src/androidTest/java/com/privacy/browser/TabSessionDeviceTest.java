package com.privacy.browser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Foreground debug fixture only; no external network or edits to saved browser tabs. */
public class TabSessionDeviceTest {
    private static final String URL = "https://session-fixture.invalid/";
    private static final String TITLE = "Session history fixture ";
    private static final byte[] PAGE = ("""
            <!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Session history fixture 0</title>
            <style>html,body{height:100%;margin:0} button{width:100%;height:100%;touch-action:manipulation}</style>
            <button type="button" id="push">Add a same-address entry</button>
            <script>
              const index = () => (history.state && history.state.n) || 0;
              const show = () => { document.title = 'Session history fixture ' + index(); };
              document.getElementById('push').addEventListener('click', event => {
                if (!event.isTrusted) return;
                history.pushState({n:index() + 1}, '', location.href);
                show();
              });
              window.addEventListener('popstate', show);
            </script>
            """).getBytes(StandardCharsets.UTF_8);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void nativeSameAddressHistoryCanBeDetectedCapturedAndRestored() throws Exception {
        Activity host = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), WebViewFixtureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicInteger historyUpdates = new AtomicInteger();
        WebView original = null, restored = null, mismatched = null;
        try {
            WebView live = original = ui(() -> {
                WebView view = view(host, historyUpdates);
                host.setContentView(view);
                view.loadUrl(URL);
                return view;
            });
            await(live, "Foreground fixture was not ready", () -> live.hasWindowFocus() && live.isShown()
                    && live.getWidth() > 0 && live.getHeight() > 0 && live.getProgress() == 100
                    && (TITLE + "0").equals(live.getTitle()) && at(live, 1, 0));
            CountDownLatch readyToDraw = new CountDownLatch(1);
            ui(() -> {
                live.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                    @Override public void onComplete(long requestId) { readyToDraw.countDown(); }
                });
                return null;
            });
            assertTrue("WebView did not make the fixture available to draw", readyToDraw.await(5, TimeUnit.SECONDS));
            // Entries are created by actual Android input, not an unactivated JS injection.
            // This also keeps both the document and its native navigation controller foregrounded.
            int before = historyUpdates.get();
            for (int entry = 1; entry <= 2; entry++) {
                final int expected = entry;
                tapCenter(live);
                await(live, "Trusted fixture tap did not commit entry " + expected,
                        () -> at(live, expected + 1, expected));
            }
            await(live, "No native history callback for same-address entries", () -> historyUpdates.get() > before);
            TabSessionCheckpoint.History end = ui(() -> NativeTabSession.history(live, URL));
            assertNotNull("Third native entry did not describe the fixture URL", end);
            assertEquals(2, end.current);

            assertTrue("Native Back was unavailable at the fixture's third entry", ui(() -> live.canGoBackOrForward(-1)));
            ui(() -> { live.goBackOrForward(-1); return null; });
            await(live, "Native Back did not return to same-address entry 1", () -> at(live, 3, 1));
            TabSessionCheckpoint.History backed = ui(() -> NativeTabSession.history(live, URL));
            assertNotNull(backed);
            assertEquals(1, backed.current);
            assertEquals(end.entries, backed.entries);
            assertNotEquals(end, backed);
            byte[] bytes = ui(() -> NativeTabSession.capture(live, URL, backed));
            assertNotNull(bytes);
            assertTrue(bytes.length > 0);
            WebView recovered = restored = ui(() -> view(host, new AtomicInteger()));
            assertEquals(backed, ui(() -> NativeTabSession.restore(recovered, bytes, URL)));
            WebBackForwardList copied = ui(recovered::copyBackForwardList);
            assertEquals(3, copied.getSize());
            assertEquals(1, copied.getCurrentIndex());
            for (int i = 0; i < copied.getSize(); i++) assertEquals(URL, copied.getItemAtIndex(i).getUrl());
            WebView wrong = mismatched = ui(() -> view(host, new AtomicInteger()));
            assertNull(ui(() -> NativeTabSession.restore(wrong, bytes, URL + "other")));
        } finally {
            WebView[] owned = {original, restored, mismatched};
            ui(() -> {
                for (WebView view : owned) if (view != null) WebViewDisposal.detachAndDestroy(view);
                host.finish();
                return null;
            });
        }
    }

    @Test public void platformBundleWithoutNativeHistoryIsNotARestoredPage() throws Exception {
        assertNull(ui(() -> {
            WebView candidate = view(instrumentation.getTargetContext(), new AtomicInteger());
            Parcel parcel = Parcel.obtain();
            try {
                new Bundle().writeToParcel(parcel, 0);
                return NativeTabSession.restore(candidate, parcel.marshall(), URL);
            } finally {
                parcel.recycle();
                WebViewDisposal.detachAndDestroy(candidate);
            }
        }));
    }

    private static WebView view(Context context, AtomicInteger updates) {
        WebView view = new WebView(context);
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

    private static boolean at(WebView view, int size, int index) {
        WebBackForwardList list = view.copyBackForwardList();
        return list.getSize() == size && list.getCurrentIndex() == index;
    }

    private void tapCenter(WebView view) throws Exception {
        int[] coords = ui(() -> {
            assertTrue("Fixture must be the focused window before injecting input", view.hasWindowFocus());
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            location[0] += view.getWidth() / 2;
            location[1] += view.getHeight() / 2;
            return location;
        });
        long downTime = SystemClock.uptimeMillis();
        sendTouch(coords, downTime, MotionEvent.ACTION_DOWN);
        sendTouch(coords, downTime, MotionEvent.ACTION_UP);
    }

    private void sendTouch(int[] coords, long downTime, int action) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, coords[0], coords[1], 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            instrumentation.sendPointerSync(event);
        } finally { event.recycle(); }
    }

    private void await(WebView view, String message, Supplier<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            if (ui(condition)) return;
            Thread.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message + ui(() -> {
            WebBackForwardList list = view.copyBackForwardList();
            return " (entries=" + list.getSize() + ", index=" + list.getCurrentIndex()
                    + ", title=" + view.getTitle() + ", focus=" + view.hasWindowFocus() + ")";
        }));
    }

    private <T> T ui(Supplier<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation::get);
        instrumentation.runOnMainSync(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}

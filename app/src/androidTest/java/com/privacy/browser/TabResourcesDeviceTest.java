package com.privacy.browser;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.webkit.WebViewRenderProcess;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Disposable install only: replaces its tabs. Requires startup_restore.py and adb reverse TCP 8014. */
public class TabResourcesDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity activity;
    private TabsManager tabs;
    private TabPreviews previews;

    @After public void finish() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
    }

    @Test public void thirtyVisitsBoundResidentsAndAnEvictedTabRecoversItsOwnBackStack() throws Exception {
        openIsolatedTab(address(1));
        TabModel first = ui(tabs::getActiveTab);
        PrivacyWebView oldView = ui(first::getWebView);
        String second = address(1) + "?second=1";
        navigate(second, false);
        assertTrue(await(() -> loaded(second) && first.getWebView().canGoBack()));

        for (int i = 2; i <= 30; i++) {
            String url = address(i);
            navigate(url, true);
            assertTrue("tab " + i + " did not load", await(() -> loaded(url)));
            assertTrue(ui(() -> liveViews() <= TabViewBudget.LIVE_UNPINNED));
            assertTrue(ui(() -> previews.retainedBytes() <= TabPreviews.MAX_BYTES));
        }
        assertEquals(30, (int) ui(tabs::getTabCount));
        assertTrue(await(() -> liveViews() <= 1 + TabViewBudget.INACTIVE));
        assertTrue(ui(() -> first.getWebView() == null && first.recovery() != null && !first.navigation.rendererLost()));

        ui(() -> { tabs.switchTab(0); return null; });
        assertTrue("parked tab should restore the second entry", await(() -> loaded(second) && first.getWebView().canGoBack()));
        PrivacyWebView restored = ui(first::getWebView);
        assertNotSame(oldView, restored);
        ui(() -> { restored.goBack(); return null; });
        assertTrue(await(() -> loaded(address(1))));
        ui(() -> { activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW); return null; });
        assertTrue(await(() -> liveViews() == 1));
        assertSame(restored, ui(first::getWebView));
        assertEquals(30, (int) ui(tabs::getTabCount));
    }

    @Test public void aGridPreviewBindingDoesNotKeepPixelsAfterTrimAndDismissal() throws Exception {
        openIsolatedTab(address(1));
        TabModel selected = ui(tabs::getActiveTab);
        PrivacyWebView view = ui(selected::getWebView);
        assertTrue("settled foreground page should produce a preview on the fixture device",
                await(() -> previews.drawable(selected) != null));
        ui(() -> {
            assertTrue(activity.findViewById(R.id.btn_tabs).performClick());
            AlertDialog grid = activityField("tabGrid", AlertDialog.class);
            assertNotNull(grid);
            Drawable previouslyBound = previews.drawable(selected);
            assertNotNull(previouslyBound);
            activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
            assertNull(previews.drawable(selected));
            Bitmap canvasTarget = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            try {
                previouslyBound.setBounds(0, 0, 32, 32);
                previouslyBound.draw(new Canvas(canvasTarget));
                assertEquals(Color.TRANSPARENT, canvasTarget.getPixel(16, 16));
            } finally { canvasTarget.recycle(); }
            grid.dismiss();
            return null;
        });
        // Wait past the settle delay; dismissing the grid isn't a new capture trigger.
        SystemClock.sleep(1200);
        assertEquals(0, (int) ui(previews::retainedBytes));
        assertSame(view, ui(selected::getWebView));
    }

    @Test public void rendererLossWithGridOpenInvalidatesThePreviewAndStillRequiresExplicitRestore() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 29);
        openIsolatedTab(address(1));
        TabModel selected = ui(tabs::getActiveTab);
        assertTrue(await(() -> previews.drawable(selected) != null));
        boolean terminated = ui(() -> {
            assertTrue(activity.findViewById(R.id.btn_tabs).performClick());
            WebViewRenderProcess renderer = selected.getWebView().getWebViewRenderProcess();
            return renderer != null && renderer.terminate();
        });
        assumeTrue("WebView renderer is not separately terminable", terminated);
        assertTrue(await(() -> selected.navigation.rendererLost() && selected.getWebView() == null));
        ui(() -> {
            assertNull(selected.recovery());
            assertNull(previews.drawable(selected));
            AlertDialog grid = activityField("tabGrid", AlertDialog.class);
            if (grid != null) grid.dismiss();
            return null;
        });
        SystemClock.sleep(1200);
        assertEquals(0, (int) ui(this::liveViews));
        assertEquals(0, (int) ui(previews::retainedBytes));
        ui(() -> { assertTrue(activity.findViewById(R.id.btn_reload).performClick()); return null; });
        assertTrue(await(() -> loaded(address(1))));
    }

    private void openIsolatedTab(String url) throws InterruptedException {
        activity = (MainActivity) instrumentation.startActivitySync(intent(url, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        tabs = ui(() -> activityField("tabsManager", TabsManager.class));
        previews = ui(() -> activityField("tabPreviews", TabPreviews.class));
        assertTrue("start the loopback fixture and dismiss any permission prompt",
                await(() -> activity.hasWindowFocus() && loaded(url)));
        ui(() -> { tabs.closeAllTabs(activity); return null; });
        navigate(url, false);
        assertTrue(await(() -> loaded(url)));
    }

    private void navigate(String url, boolean newTab) {
        ui(() -> { activity.onNewIntent(intent(url, newTab)); return null; });
    }

    private Intent intent(String url, boolean newTab) {
        return new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_LOAD_URL, url)
                .putExtra(MainActivity.EXTRA_OPEN_IN_NEW_TAB, newTab);
    }

    private static String address(int tab) { return "http://127.0.0.1:8014/tab/" + tab; }

    private boolean loaded(String url) {
        TabModel tab = tabs.getActiveTab();
        PrivacyWebView view = tab == null ? null : tab.getWebView();
        return view != null && tab.navigation.ready() && url.equals(tab.getUrl())
                && url.equals(view.getUrl()) && view.getProgress() == 100;
    }

    private int liveViews() { return TabViewBudget.count(tabs.getTabs(), tab -> tab.getWebView() != null); }

    private boolean await(Supplier<Boolean> ready) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            if (ui(ready)) return true;
            Thread.sleep(40);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private <T> T activityField(String name, Class<T> type) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(activity));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private <T> T ui(Supplier<T> operation) {
        FutureTask<T> result = new FutureTask<>(operation::get);
        instrumentation.runOnMainSync(result);
        try { return result.get(); }
        catch (ExecutionException failure) { throw new AssertionError(failure.getCause()); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}

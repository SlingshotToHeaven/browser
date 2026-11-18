package com.privacy.browser;

import android.app.Instrumentation;
import android.content.Context;
import android.webkit.CookieManager;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;

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

    private static String[] sites() {
        String tag = UUID.randomUUID().toString();
        return new String[] { "https://one-" + tag + ".invalid/", "https://two-" + tag + ".invalid/" };
    }

    private <T> T ui(Supplier<T> read) {
        AtomicReference<T> ref = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> ref.set(read.get()));
        return ref.get();
    }
}

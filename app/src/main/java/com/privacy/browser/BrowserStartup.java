package com.privacy.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.util.Log;

import com.privacy.browser.adblock.WebScriptPolicy;
import com.privacy.browser.ai.AutomationManager;
import com.privacy.downloader.engine.DownloadEngine;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Only a complete session index + protection state can hand off to the Activity. No WebView here. */
final class BrowserStartup {
    static final class Ready {
        final TabSessionWriter journal;
        final TrackerBlocker protection;

        Ready(TabSessionWriter journal, TrackerBlocker protection) {
            this.journal = journal;
            this.protection = protection;
        }
    }

    private static final Executor boot = Executors.newFixedThreadPool(2, task -> new Thread(task, "browser-bootstrap"));
    private static final Executor maintenance = new ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> new Thread(task, "browser-resume-work"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static CompletableFuture<Ready> attempt;

    private BrowserStartup() {}

    static void request(Context context, Consumer<Ready> done) {
        Handler main = new Handler(Looper.getMainLooper());
        prepare(context.getApplicationContext()).whenComplete((ready, error) -> main.post(() -> {
            if (error != null) Log.w("BrowserStartup", "Browser preparation could not be completed");
            done.accept(ready);
        }));
    }

    private static synchronized CompletableFuture<Ready> prepare(Context app) {
        if (attempt == null || attempt.isCompletedExceptionally()) {
            CompletableFuture<TabSessionWriter> journal = CompletableFuture.supplyAsync(
                    () -> traced("Browser.session.readIndex", () -> NativeTabSession.journal(app)), boot);
            CompletableFuture<TrackerBlocker> filters = CompletableFuture.supplyAsync(
                    () -> traced("Browser.startup.filters", () -> {
                        TrackerBlocker blocker = new TrackerBlocker(app);
                        if (WebScriptPolicy.javaScriptEnabled(app)) FingerprintDefenseManager.prepare(app);
                        return blocker;
                    }), boot);
            attempt = journal.thenCombine(filters, Ready::new);
        }
        return attempt;
    }

    static void resumeMaintenance(Context context) {
        Context app = context.getApplicationContext();
        AutomationManager.onBrowserResume(app);
        maintenance.execute(() -> {
            try { DownloadEngine.getInstance(app).reconcile(); }
            catch (RuntimeException failure) { Log.w("BrowserStartup", "Download recovery could not be started"); }
        });
    }

    private static <T> T traced(String name, Supplier<T> task) {
        Trace.beginSection(name);
        try { return task.get(); } finally { Trace.endSection(); }
    }
}

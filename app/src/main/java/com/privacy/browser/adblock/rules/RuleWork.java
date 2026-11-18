package com.privacy.browser.adblock.rules;

import android.os.Handler;
import android.os.Looper;
import android.os.Trace;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Build candidate data rules away from the UI; deliver a single result to the authorizing owner. */
public final class RuleWork {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> new Thread(task, "browser-rule-edit"));

    private RuleWork() {}

    public static <T> void run(Callable<T> job, BiConsumer<T, Exception> done) {
        Handler main = new Handler(Looper.getMainLooper());
        try {
            WORKER.execute(() -> {
                Trace.beginSection("Browser.rules.rebuild");
                try {
                    T result = job.call();
                    main.post(() -> done.accept(result, null));
                } catch (Exception failed) {
                    main.post(() -> done.accept(null, failed));
                } finally { Trace.endSection(); }
            });
        } catch (RejectedExecutionException busy) {
            main.post(() -> done.accept(null, new IOException("Rule editor is busy. Try again.")));
        }
    }

    public static String message(Exception error) {
        if (error instanceof IllegalArgumentException && error.getMessage() != null) return error.getMessage();
        // File errors can contain private paths. Surface only the named conflict where it is useful.
        if (error instanceof IOException && error.getMessage() != null) {
            String message = error.getMessage();
            if (message.startsWith("Local rules changed") || message.startsWith("Rule editor is busy")) return message;
        }
        return "Could not write local rules. Reopen the editor or try again.";
    }
}

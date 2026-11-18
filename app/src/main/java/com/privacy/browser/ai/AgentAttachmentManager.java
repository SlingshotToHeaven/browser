package com.privacy.browser.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Bounded local display/deletion of old references only. No attachment upload path. */
final class AgentAttachmentManager {
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor DISK = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private static final AtomicBoolean CLEANING = new AtomicBoolean();
    static { DISK.allowCoreThreadTimeOut(true); }

    private AgentAttachmentManager() { }

    static void preview(Context context, String path, Consumer<Bitmap> outcome) {
        Context app = context.getApplicationContext();
        try {
            DISK.execute(() -> {
                Bitmap bitmap = decode(app, path);
                MAIN.post(() -> outcome.accept(bitmap));
            });
        } catch (RejectedExecutionException full) { outcome.accept(null); }
    }

    private static Bitmap decode(Context context, String path) {
        try {
            Path file = AgentLegacyFiles.previewFile(context.getFilesDir(), path);
            if (file == null || Files.size(file) > MAX_BYTES) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.toString(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16_000_000L) return null;
            int sample = 1;
            while (width / sample > 480 || height / sample > 320) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.toString(), options);
        } catch (IOException | RuntimeException ignored) { return null; }
    }

    static void pruneDeletedSession(AgentDbHelper db, Set<String> candidates) {
        try {
            DISK.execute(() -> {
                try { db.pruneUnusedImages(candidates); }
                catch (RuntimeException ignored) { /* No reference snapshot: leave files for explicit cleanup. */ }
            });
        } catch (RejectedExecutionException full) { /* The explicit cleanup can retry. */ }
    }

    static boolean scheduleLegacyCleanup(AgentDbHelper db, Consumer<AgentDbHelper.LegacyCleanup> outcome) {
        if (!CLEANING.compareAndSet(false, true)) return false;
        try {
            DISK.execute(() -> {
                try {
                    AgentDbHelper.LegacyCleanup result = db.clearLegacyData();
                    MAIN.post(() -> outcome.accept(result));
                } finally { CLEANING.set(false); }
            });
            return true;
        } catch (RejectedExecutionException full) {
            CLEANING.set(false);
            return false;
        }
    }
}

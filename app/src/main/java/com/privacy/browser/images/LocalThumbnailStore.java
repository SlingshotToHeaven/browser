package com.privacy.browser.images;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** UI entry points for app-local thumbnails. One worker orders decodes, replacements and removals. */
public final class LocalThumbnailStore<K> {
    private static final int MAX_PENDING_SAVES = 8;

    private final Handler main = new Handler(Looper.getMainLooper());
    // The loader caps reads, and save() caps retained bitmaps. Explicit deletion/cleanup batches don't retain images.
    private final ThreadPoolExecutor disk = new ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());
    private final Supplier<File> directory;
    private final Function<K, String> filename;
    private final ThumbnailBudget budget;
    private final ThumbnailLoader<K, Bitmap> loader;
    private int saves;

    public LocalThumbnailStore(Supplier<File> directory, Function<K, String> filename, ThumbnailBudget budget,
                               int memoryBytes) {
        this.directory = directory;
        this.filename = filename;
        this.budget = budget;
        this.loader = new ThumbnailLoader<>(disk, main::post, this::decode, Bitmap::getAllocationByteCount, memoryBytes);
    }

    public Bitmap peek(K key) {
        return loader.peek(key);
    }

    public void load(K key, Consumer<Bitmap> ready) {
        loader.load(key, ready);
    }

    /** Best effort for automatic captures/icons; explicit edits can await a committed result. */
    public void save(K key, Bitmap bitmap, Bitmap.CompressFormat format, int quality, Consumer<Boolean> ready) {
        if (saves >= MAX_PENDING_SAVES || !acceptable(bitmap)) {
            if (ready != null) ready.accept(false);
            return;
        }
        saves++;
        loader.invalidate(Collections.singleton(key));
        disk.execute(() -> {
            boolean saved = write(key, bitmap, format, quality);
            main.post(() -> {
                saves--;
                if (ready != null) ready.accept(saved);
            });
        });
    }

    /** The ids are a snapshot, e.g. a committed bookmark deletion or a single cover reset. */
    public void delete(Set<K> keys, Consumer<Boolean> ready) {
        if (keys.isEmpty()) return;
        loader.invalidate(keys);
        disk.execute(() -> {
            boolean deleted = true;
            for (K key : keys) {
                try {
                    Files.deleteIfExists(file(key).toPath());
                } catch (IOException | RuntimeException e) {
                    deleted = false;
                }
            }
            if (ready != null) {
                boolean result = deleted;
                main.post(() -> ready.accept(result));
            }
        });
    }

    private File file(K key) {
        // Resolving getCacheDir()/getFilesDir() can itself access disk on Android.
        return new File(directory.get(), filename.apply(key));
    }

    private boolean acceptable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && budget.acceptsBitmap(bitmap.getWidth(), bitmap.getHeight(), bitmap.getAllocationByteCount());
    }

    private Bitmap decode(K key) {
        try {
            byte[] bytes = budget.read(file(key));
            if (bytes == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = budget.sampleSize(bounds.outWidth, bounds.outHeight);
            if (sample == 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (acceptable(bitmap)) return bitmap;
            if (bitmap != null) bitmap.recycle();
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            // The row keeps its placeholder; nothing is fetched, logged, or deleted on a miss.
        }
        return null;
    }

    private boolean write(K key, Bitmap bitmap, Bitmap.CompressFormat format, int quality) {
        File staging = null;
        try {
            File target = file(key);
            File parent = target.getParentFile();
            Files.createDirectories(parent.toPath());
            staging = File.createTempFile("thumbnail-", ".tmp", parent);
            try (FileOutputStream out = new FileOutputStream(staging)) {
                if (!bitmap.compress(format, quality, out)) return false;
            }
            long length = staging.length();
            if (length <= 0 || length > budget.maxEncodedBytes) return false;
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            return false;
        } finally {
            if (staging != null) staging.delete();
        }
    }
}

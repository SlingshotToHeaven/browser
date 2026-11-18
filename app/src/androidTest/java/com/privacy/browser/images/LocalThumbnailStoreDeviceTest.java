package com.privacy.browser.images;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import android.os.StrictMode;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Android codec, main-thread and file checks using only synthetic, isolated images. */
public class LocalThumbnailStoreDeviceTest {
    private Instrumentation instrumentation;
    private File directory;
    private final AtomicInteger pathsOnUi = new AtomicInteger();
    private final AtomicInteger paths = new AtomicInteger();

    @Before public void setup() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        directory = Files.createTempDirectory(instrumentation.getTargetContext().getCacheDir().toPath(),
                "thumbnail-fixture-").toFile();
    }

    @After public void cleanup() throws Exception {
        if (directory == null) return;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(directory.toPath());
    }

    @Test public void coldPeekDoesNotOpenCacheAndLegacyCoverIsSampledOffMain() throws Exception {
        seed("old", 1440, 960, Bitmap.CompressFormat.WEBP);
        AtomicReference<LocalThumbnailStore<String>> storeRef = new AtomicReference<>();
        AtomicReference<Bitmap> cold = new AtomicReference<>();
        onMainStrict(() -> {
            LocalThumbnailStore<String> store = store(ThumbnailBudget.BOOKMARK_COVER);
            storeRef.set(store);
            cold.set(store.peek("old"));
        });
        assertNull(cold.get());
        assertEquals(0, paths.get());

        LocalThumbnailStore<String> store = storeRef.get();
        Bitmap bitmap = load(store, "old");
        assertNotNull(bitmap);
        assertTrue(bitmap.getWidth() <= 480 && bitmap.getHeight() <= 480);
        assertTrue(bitmap.getAllocationByteCount() <= 480 * 480 * 4);
        AtomicReference<Bitmap> warm = new AtomicReference<>();
        onMainStrict(() -> warm.set(store.peek("old")));
        assertSame(bitmap, warm.get());
        assertSame(bitmap, load(store, "old"));
        assertEquals(1, paths.get());
        assertEquals(0, pathsOnUi.get());
    }

    @Test public void oversizedAndMalformedCoversStayOnDiskButAreNotDisplayed() throws Exception {
        seed("oversize-bytes", 24, 24, Bitmap.CompressFormat.PNG);
        try (RandomAccessFile file = new RandomAccessFile(file("oversize-bytes"), "rw")) {
            file.setLength(2 * 1024 * 1024 + 1);
        }
        seed("oversize-header", 4097, 2, Bitmap.CompressFormat.PNG);
        Files.write(file("broken").toPath(), new byte[]{1, 2, 3, 4});

        LocalThumbnailStore<String> store = store(ThumbnailBudget.BOOKMARK_COVER);
        for (String key : new String[]{"oversize-bytes", "oversize-header", "broken"}) {
            assertNull(load(store, key));
            assertTrue(file(key).isFile());
        }
        assertNull(load(store, "absent"));
        assertEquals(0, pathsOnUi.get());
    }

    @Test public void faviconSizesAreIndependentOfCoverSizes() throws Exception {
        seed("legacy-icon", 384, 240, Bitmap.CompressFormat.PNG);
        seed("oversize-icon", 513, 4, Bitmap.CompressFormat.PNG);
        LocalThumbnailStore<String> store = store(ThumbnailBudget.FAVICON);
        Bitmap icon = load(store, "legacy-icon");
        assertNotNull(icon);
        assertTrue(icon.getWidth() <= 96 && icon.getHeight() <= 96);
        assertTrue(icon.getAllocationByteCount() <= 96 * 96 * 4);
        assertNull(load(store, "oversize-icon"));
        assertEquals(0, pathsOnUi.get());
    }

    @Test public void replacementAndResetInvalidateEarlierDeliveries() throws Exception {
        seed("cover", 32, 32, Bitmap.CompressFormat.PNG);
        Bitmap replacement = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        replacement.eraseColor(Color.BLUE);
        LocalThumbnailStore<String> store = store(ThumbnailBudget.BOOKMARK_COVER);
        AtomicBoolean deliveredOld = new AtomicBoolean();
        Completion<Boolean> saved = new Completion<>(), removed = new Completion<>();
        Completion<Bitmap> fresh = new Completion<>(), afterReset = new Completion<>();
        try {
            onMainStrict(() -> {
                store.load("cover", value -> deliveredOld.set(true));
                store.save("cover", replacement, Bitmap.CompressFormat.PNG, 100, saved);
                store.load("cover", fresh);
            });
            assertTrue(saved.await());
            Bitmap bitmap = fresh.await();
            assertNotNull(bitmap);
            assertEquals(Color.BLUE, bitmap.getPixel(0, 0));
            assertFalse(deliveredOld.get());

            onMainStrict(() -> {
                store.delete(Collections.singleton("cover"), removed);
                store.load("cover", afterReset);
            });
            assertTrue(removed.await());
            assertNull(afterReset.await());
            assertFalse(file("cover").exists());
            assertEquals(0, pathsOnUi.get());
        } finally {
            replacement.recycle();
        }
    }

    @Test public void rejectedReplacementLeavesTheOldFile() throws Exception {
        seed("cover", 32, 32, Bitmap.CompressFormat.PNG);
        byte[] before = Files.readAllBytes(file("cover").toPath());
        Bitmap tooWide = Bitmap.createBitmap(481, 1, Bitmap.Config.ARGB_8888);
        try {
            LocalThumbnailStore<String> store = store(ThumbnailBudget.BOOKMARK_COVER);
            Completion<Boolean> saved = new Completion<>();
            onMainStrict(() -> store.save("cover", tooWide, Bitmap.CompressFormat.PNG, 100, saved));
            assertFalse(saved.await());
            assertArrayEquals(before, Files.readAllBytes(file("cover").toPath()));
            assertEquals(0, paths.get());
        } finally {
            tooWide.recycle();
        }
    }

    private LocalThumbnailStore<String> store(ThumbnailBudget budget) {
        return new LocalThumbnailStore<>(() -> {
            paths.incrementAndGet();
            if (Looper.myLooper() == Looper.getMainLooper()) pathsOnUi.incrementAndGet();
            return directory;
        }, key -> key + ".image", budget, 8 * 1024 * 1024);
    }

    private File file(String key) {
        return new File(directory, key + ".image");
    }

    private void seed(String key, int width, int height, Bitmap.CompressFormat format) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(file(key))) {
            bitmap.eraseColor(Color.RED);
            assertTrue(bitmap.compress(format, 90, out));
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap load(LocalThumbnailStore<String> store, String key) throws Exception {
        Completion<Bitmap> result = new Completion<>();
        onMainStrict(() -> store.load(key, result));
        return result.await();
    }

    private void onMainStrict(Runnable operation) {
        instrumentation.runOnMainSync(() -> {
            StrictMode.ThreadPolicy old = StrictMode.getThreadPolicy();
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .penaltyDeath().build());
            try {
                operation.run();
            } finally {
                StrictMode.setThreadPolicy(old);
            }
        });
    }

    private static final class Completion<T> implements Consumer<T> {
        private final CountDownLatch done = new CountDownLatch(1);
        private boolean ui;
        private T value;

        @Override public void accept(T result) {
            value = result;
            ui = Looper.myLooper() == Looper.getMainLooper();
            done.countDown();
        }

        T await() throws InterruptedException {
            assertTrue("thumbnail result timed out", done.await(8, TimeUnit.SECONDS));
            assertTrue("callback must run on main", ui);
            return value;
        }
    }
}

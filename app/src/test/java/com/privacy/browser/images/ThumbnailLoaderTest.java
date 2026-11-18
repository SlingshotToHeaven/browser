package com.privacy.browser.images;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.*;

public class ThumbnailLoaderTest {
    @Test public void coldPeekPerformsNoWorkAndDiskResultsSettleOnUi() {
        Fixture f = new Fixture(32);
        f.files.put(1, "cover");
        assertNull(f.loader.peek(1));
        assertEquals(0, f.disk.tasks.size());
        f.loader.load(1, f.delivered::add);
        assertEquals(0, f.decodes);
        assertTrue(f.delivered.isEmpty());
        f.disk.next();
        assertEquals(1, f.decodes);
        assertNull(f.loader.peek(1));
        assertTrue(f.delivered.isEmpty());
        f.ui.next();
        assertEquals("cover", f.loader.peek(1));
        f.loader.load(1, f.delivered::add);
        assertEquals(List.of("cover", "cover"), f.delivered);
        assertEquals(0, f.disk.tasks.size());
    }

    @Test public void duplicateColdRequestsShareOneReadAndHaveBoundedWaiters() {
        Fixture f = new Fixture(32);
        f.files.put(1, "icon");
        for (int i = 0; i < 40; i++) f.loader.load(1, f.delivered::add);
        assertEquals(1, f.disk.tasks.size());
        assertEquals(16, f.delivered.size());
        assertTrue(f.delivered.stream().allMatch(value -> value == null));
        f.drain();
        assertEquals(1, f.decodes);
        assertEquals(24, f.delivered.stream().filter("icon"::equals).count());
    }

    @Test public void admissionLimitCountsDecodedResultsWaitingForMainThread() {
        Fixture f = new Fixture(32);
        for (int key = 0; key < 24; key++) f.loader.load(key, f.delivered::add);
        f.disk.drain();
        f.loader.load(99, f.delivered::add);
        assertEquals(1, f.delivered.size());
        assertNull(f.delivered.get(0));
        assertEquals(0, f.disk.tasks.size());
        f.ui.next();
        f.loader.load(99, f.delivered::add);
        assertEquals(1, f.disk.tasks.size());
        f.drain();
    }

    @Test public void replacesOldPendingReadOnlyAfterTheOrderedWrite() {
        Fixture f = new Fixture(64);
        f.files.put(1, "old cover");
        f.loader.load(1, f.delivered::add);
        f.disk.next(); // Old bytes decoded; callback is queued, not visible yet.

        f.loader.invalidate(Set.of(1));
        f.disk.execute(() -> f.files.put(1, "new cover"));
        f.loader.load(1, f.delivered::add);
        f.ui.next();
        assertNull(f.loader.peek(1));
        assertTrue(f.delivered.isEmpty());
        f.drain();
        assertEquals(List.of("new cover"), f.delivered);
        assertEquals("new cover", f.loader.peek(1));
    }

    @Test public void invalidatedQueuedReadsDontDecodeAndStillCountTowardsAdmission() {
        Fixture f = new Fixture(32);
        for (int i = 0; i < 30; i++) {
            f.loader.load(1, f.delivered::add);
            f.loader.invalidate(Set.of(1));
        }
        assertEquals(24, f.disk.tasks.size());
        f.drain();
        assertEquals(0, f.decodes);
        assertEquals(6, f.delivered.size()); // Rejections only; retired waiters never run.
        f.files.put(1, "replacement");
        f.loader.load(1, f.delivered::add);
        f.drain();
        assertEquals("replacement", f.loader.peek(1));
    }

    @Test public void deletionDuringCallbackSuppressesOtherOldWaitersAndClearsMemory() {
        Fixture f = new Fixture(64);
        f.files.put(1, "removed cover");
        f.loader.load(1, image -> {
            f.delivered.add(image);
            f.loader.invalidate(Set.of(1));
            f.disk.execute(() -> f.files.remove(1));
        });
        f.loader.load(1, f.delivered::add);
        f.drain();
        assertEquals(List.of("removed cover"), f.delivered);
        assertNull(f.loader.peek(1));
        f.loader.load(1, f.delivered::add);
        f.drain();
        assertEquals(2, f.delivered.size());
        assertNull(f.delivered.get(1));
    }

    @Test public void lruEvictsByBytesAndKeepsTouchedEntries() {
        Fixture f = new Fixture(6);
        f.files.put(1, "one");
        f.files.put(2, "two");
        f.files.put(3, "new");
        f.loader.load(1, f.delivered::add);
        f.loader.load(2, f.delivered::add);
        f.drain();
        assertEquals("one", f.loader.peek(1));
        f.loader.load(3, f.delivered::add);
        f.drain();
        assertNull(f.loader.peek(2));
        assertEquals("one", f.loader.peek(1));
        assertEquals("new", f.loader.peek(3));
    }

    @Test public void rememberedMissesDoNotRepeatedlyGoToDiskAndHaveAKeyLimit() {
        Fixture f = new Fixture(32);
        f.loader.load(0, f.delivered::add);
        f.drain();
        f.loader.load(0, f.delivered::add);
        assertEquals(0, f.disk.tasks.size());
        for (int key = 1; key <= 128; key++) {
            f.loader.load(key, f.delivered::add);
            f.drain();
        }
        f.loader.load(128, f.delivered::add);
        assertEquals(0, f.disk.tasks.size());
        f.loader.load(0, f.delivered::add);
        assertEquals(1, f.disk.tasks.size());
    }

    @Test public void diskRejectionDoesNotLeaveACoalescingEntryBehind() {
        Fixture f = new Fixture(32);
        f.disk.rejectNext = true;
        f.loader.load(1, f.delivered::add);
        assertEquals(1, f.delivered.size());
        assertNull(f.delivered.get(0));
        f.files.put(1, "ready");
        f.loader.load(1, f.delivered::add);
        f.drain();
        assertEquals("ready", f.loader.peek(1));
    }

    private static final class Fixture {
        final StepQueue disk = new StepQueue(), ui = new StepQueue();
        final Map<Integer, String> files = new HashMap<>();
        final List<String> delivered = new ArrayList<>();
        final ThumbnailLoader<Integer, String> loader;
        int decodes;

        Fixture(int byteBudget) {
            loader = new ThumbnailLoader<>(disk, ui, key -> {
                decodes++;
                return files.get(key);
            }, String::length, byteBudget);
        }

        void drain() {
            while (!disk.tasks.isEmpty() || !ui.tasks.isEmpty()) {
                disk.drain();
                ui.drain();
            }
        }
    }

    private static final class StepQueue implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean rejectNext;

        @Override public void execute(Runnable task) {
            if (rejectNext) {
                rejectNext = false;
                throw new RejectedExecutionException("fixture");
            }
            tasks.add(task);
        }

        void next() { tasks.remove().run(); }
        void drain() { while (!tasks.isEmpty()) next(); }
    }
}

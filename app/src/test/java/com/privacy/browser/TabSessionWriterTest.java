package com.privacy.browser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.privacy.browser.TabSessionFilesTest.*;
import static org.junit.Assert.*;

public class TabSessionWriterTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void recreatedOwnerSeesQueuedStateAndOldOwnerCannotOverwriteIt() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        Queue<Runnable> worker = new ArrayDeque<>();
        TabSessionWriter writer = new TabSessionWriter(read(dir, legacy), worker::add, message -> fail(message));
        TabSessionWriter.Restore old = writer.open();
        TabSession.Row first = capture(A), second = capture(B);
        writer.submit(old.owner, frame(first.id, Map.of(first.filename(), ONE, second.filename(), TWO), first, second));
        writer.submit(old.owner, frame(second.id, Collections.emptyMap(), first, second));
        assertEquals(1, worker.size());
        assertFalse(Files.exists(dir.resolve("index.json"))); // no synchronous app-owned write
        TabSessionWriter.Restore next = writer.open();
        assertFalse(writer.owns(old.owner));
        assertSame(TabSession.StateRead.STALE, writer.readState(old.owner, first));
        assertEquals(second.id, next.index.selected);
        assertArrayEquals(ONE, writer.readState(next.owner, next.index.tabs.get(0)).bytes);
        writer.submit(old.owner, frame(first.id, Collections.emptyMap(), first));
        writer.release(old.owner);
        worker.remove().run();
        assertTrue(worker.isEmpty());
        assertTrue(writer.owns(next.owner));
        assertEquals(next.index, read(dir, legacy).load());
        assertArrayEquals(TWO, read(dir, legacy).readState(second).bytes);
    }

    @Test(timeout = 10_000) public void closeRacingAnIndexRenameCannotResurrectTheClosedTab() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        BlockingIndex gate = new BlockingIndex();
        TabSessionWriter writer = new TabSessionWriter(new TabSessionFiles(dir, legacy, gate, message -> fail(message)),
                worker, message -> fail(message));
        try {
            Object owner = writer.open().owner;
            TabSession.Row closing = capture(A), survivor = capture(A);
            writer.submit(owner, frame(closing.id, Map.of(closing.filename(), ONE, survivor.filename(), TWO), closing, survivor));
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            writer.submit(owner, frame(survivor.id, Collections.emptyMap(), survivor));
            TabSessionWriter.Restore recreation = writer.open();
            assertEquals(Collections.singletonList(survivor), recreation.index.tabs);
            assertArrayEquals(TWO, writer.readState(recreation.owner, survivor).bytes);
            gate.resume.countDown();
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS); // drain is ahead of this barrier
            TabSessionFiles restart = read(dir, legacy);
            assertEquals(recreation.index, restart.load());
            assertArrayEquals(TWO, restart.readState(survivor).bytes);
            assertFalse(Files.exists(dir.resolve(closing.filename())));
        } finally {
            gate.resume.countDown();
            worker.shutdownNow();
        }
    }

    @Test(timeout = 10_000) public void wipeWaitsForInFlightWriteDiscardsOldQueueAndAcceptsOnlyNewOwner() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        legacy.json = "[{\"url\":\"" + B + "\"}]";
        ExecutorService worker = Executors.newSingleThreadExecutor();
        BlockingIndex gate = new BlockingIndex();
        TabSessionWriter writer = new TabSessionWriter(new TabSessionFiles(dir, legacy, gate, message -> fail(message)),
                worker, message -> fail(message));
        try {
            Object preWipe = writer.open().owner;
            TabSession.Row inFlight = capture(A), queued = capture(B);
            writer.submit(preWipe, frame(inFlight.id, Map.of(inFlight.filename(), ONE), inFlight));
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            writer.submit(preWipe, frame(queued.id, Map.of(queued.filename(), TWO), queued));

            AtomicReference<Boolean> confirmed = new AtomicReference<>();
            AtomicReference<TabSession.Index> atCompletion = new AtomicReference<>();
            Object afterWipe = writer.wipe(preWipe, done -> {
                confirmed.set(done);
                atCompletion.set(read(dir, legacy).load());
            });
            assertNull(confirmed.get());
            assertFalse(writer.owns(preWipe));
            assertSame(TabSession.StateRead.STALE, writer.readState(preWipe, inFlight));
            assertTrue(writer.owns(afterWipe));
            TabSessionWriter.Restore recreation = writer.open();
            assertEquals(TabSession.EMPTY, recreation.index);
            writer.submit(preWipe, frame(queued.id, Map.of(queued.filename(), TWO), queued));
            TabSession.Row home = capture(InternalHome.URL).withState(null);
            writer.submit(recreation.owner, frame(home.id, Collections.emptyMap(), home));
            gate.resume.countDown();
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, confirmed.get());
            assertEquals(TabSession.EMPTY, atCompletion.get());
            assertNull(legacy.json);
            assertFalse(Files.exists(dir.resolve(inFlight.filename())));
            assertFalse(Files.exists(dir.resolve(queued.filename())));
            assertEquals(Collections.singletonList(home), read(dir, legacy).load().tabs);
        } finally {
            gate.resume.countDown();
            worker.shutdownNow();
        }
    }

    @Test public void legacyRemovalFailureDoesNotReturnASuccessfulWipe() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        legacy.canClear = false;
        Queue<Runnable> queue = new ArrayDeque<>();
        TabSessionWriter writer = new TabSessionWriter(read(dir, legacy), queue::add, message -> fail(message));
        Object owner = writer.open().owner;
        AtomicReference<Boolean> result = new AtomicReference<>();
        writer.wipe(owner, result::set);
        assertNull(result.get());
        queue.remove().run();
        assertEquals(Boolean.FALSE, result.get());
        assertEquals(TabSession.EMPTY, read(dir, legacy).load());
    }

    @Test public void lifecycleMayRetryFailedWriteWithAlreadyCapturedBytes() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        Queue<Runnable> worker = new ArrayDeque<>();
        List<String> warnings = new ArrayList<>();
        AtomicBoolean failIndex = new AtomicBoolean(true);
        TabSessionFiles files = new TabSessionFiles(dir, legacy, (stage, target) -> {
            if (target.getFileName().toString().equals("index.json") && failIndex.getAndSet(false)) throw new IOException("injected");
            rename(stage, target);
        }, warnings::add);
        TabSessionWriter writer = new TabSessionWriter(files, worker::add, warnings::add);
        Object owner = writer.open().owner;
        TabSession.Row first = capture(A);
        writer.submit(owner, frame(first.id, Map.of(first.filename(), ONE), first));
        worker.remove().run();
        assertFalse(Files.exists(dir.resolve("index.json")));
        assertEquals(1, warnings.size());
        assertTrue(worker.isEmpty());
        writer.submit(owner, frame(first.id, Collections.emptyMap(), first));
        worker.remove().run();
        assertArrayEquals(ONE, read(dir, legacy).readState(first).bytes);
        assertEquals(1, warnings.size());
    }

    @Test public void overflowCannotPublishAnAbsentSnapshotReference() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        Queue<Runnable> worker = new ArrayDeque<>();
        TabSessionWriter writer = new TabSessionWriter(read(dir, legacy), worker::add, message -> fail(message));
        Object owner = writer.open().owner;
        TabSession.Row a = capture(A), b = capture(A), c = capture(A), d = capture(A), newest = capture(B);
        byte[] max = new byte[TabSession.MAX_STATE];
        writer.submit(owner, frame(a.id, Map.of(a.filename(), max, b.filename(), max, c.filename(), max, d.filename(), max), a, b, c, d));
        writer.submit(owner, frame(newest.id, Map.of(newest.filename(), TWO), a, b, c, d, newest));
        assertEquals(1, worker.size());
        worker.remove().run();
        TabSessionFiles restart = read(dir, legacy);
        TabSession.Index loaded = restart.load();
        assertEquals(newest.id, loaded.selected);
        assertArrayEquals(TWO, restart.readState(newest).bytes);
        assertEquals(Arrays.asList(a, b, c, d.withState(null), newest), loaded.tabs);
    }

    private static final class BlockingIndex implements TabSessionFiles.Rename {
        final AtomicBoolean block = new AtomicBoolean(true);
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);

        @Override public void replace(Path stage, Path target) throws IOException {
            if ("index.json".equals(target.getFileName().toString()) && block.getAndSet(false)) {
                entered.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            rename(stage, target);
        }
    }
}

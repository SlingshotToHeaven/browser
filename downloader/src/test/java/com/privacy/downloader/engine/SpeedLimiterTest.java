package com.privacy.downloader.engine;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.*;

public class SpeedLimiterTest {
    @Test(timeout = 5000)
    public void oversizedBufferDrainsInPermitsNoLargerThanCapacity() throws Exception {
        ClockBucket bucket = new ClockBucket(4);
        InputStream body = bucket.body(12);
        byte[] target = new byte[64 * 1024];
        assertEquals(4, body.read(target));
        for (int i = 0; i < 2; i++) {
            try (WaitingRead<Integer> next = new WaitingRead<>(() -> body.read(target))) {
                next.awaitWait();
                bucket.advanceMillis(1000);
                assertEquals(4, (int) next.result());
            }
        }
        bucket.limiter.setMaxBytesPerSecond(0);
        assertEquals(-1, body.read(target));
    }

    @Test(timeout = 5000)
    public void changingToUnlimitedWakesWaitersAndReturningToLimitedHasNoUnlimitedCredit() throws Exception {
        ClockBucket bucket = new ClockBucket(1);
        InputStream body = bucket.body(1000);
        assertEquals(1, body.read(new byte[40]));
        try (WaitingRead<Integer> next = new WaitingRead<>(() -> body.read(new byte[20]))) {
            next.awaitWait();
            bucket.limiter.setMaxBytesPerSecond(0);
            assertEquals(20, (int) next.result());
        }
        bucket.advanceMillis(10_000);
        bucket.limiter.setMaxBytesPerSecond(1000);
        try (WaitingRead<Integer> next = new WaitingRead<>(() -> body.read(new byte[100]))) {
            next.awaitWait();
            // No refill using the time spent at the previous rate. Change a live limited rate again.
            bucket.limiter.setMaxBytesPerSecond(2000);
            assertFalse(next.future.isDone());
            bucket.advanceMillis(50);
            assertEquals(100, (int) next.result());
        }
    }

    @Test(timeout = 5000)
    public void concurrentReadersShareOneBudgetAndGetTurns() throws Exception {
        ClockBucket bucket = new ClockBucket(1000);
        assertEquals(1000, bucket.body(2000).read(new byte[2048]));
        byte[] direct = new byte[4096], stream = new byte[4096];
        try (WaitingRead<Integer> a = new WaitingRead<>(() -> bucket.body(4096).read(direct))) {
            a.awaitWait();
            try (WaitingRead<Integer> b = new WaitingRead<>(() -> bucket.body(4096).read(stream))) {
                b.awaitWait();
                bucket.advanceMillis(100);
                assertEquals(100, (int) a.result());
                assertFalse(b.future.isDone());
                bucket.advanceMillis(100);
                assertEquals(100, (int) b.result());
            }
        }
    }

    @Test(timeout = 5000)
    public void cancellationWakesAndRemovesAWaiterEvenWithNoClockProgress() throws Exception {
        ClockBucket bucket = new ClockBucket(1);
        AtomicBoolean stop = new AtomicBoolean();
        InputStream cancelled = bucket.limiter.limit(new ByteArrayInputStream(new byte[5]), stop::get);
        assertEquals(0, cancelled.read());
        try (WaitingRead<Boolean> a = new WaitingRead<>(() -> {
            assertThrows(InterruptedIOException.class, cancelled::read);
            return true;
        })) {
            a.awaitWait();
            try (WaitingRead<Integer> b = new WaitingRead<>(() -> bucket.body(10).read())) {
                b.awaitWait();
                stop.set(true);
                bucket.limiter.wakeWaiters();
                assertTrue(a.result());
                assertFalse(b.future.isDone());
                bucket.advanceMillis(1000);
                assertEquals(0, (int) b.result());
            }
        }
    }

    @Test(timeout = 5000)
    public void interruptionStopsAWaiterWithoutGrantingBytes() throws Exception {
        ClockBucket bucket = new ClockBucket(1);
        InputStream body = bucket.body(8);
        assertEquals(0, body.read());
        try (WaitingRead<Boolean> next = new WaitingRead<>(() -> {
            assertThrows(InterruptedIOException.class, body::read);
            return Thread.currentThread().isInterrupted();
        })) {
            next.awaitWait();
            next.thread.interrupt();
            assertTrue(next.result());
        }
        bucket.advanceMillis(1000);
        assertEquals(0, body.read());
    }

    @Test(timeout = 5000)
    public void shortReadsAndEofReturnUnusedCapacity() throws Exception {
        ClockBucket bucket = new ClockBucket(8);
        byte[] tooLarge = new byte[100];
        assertEquals(2, bucket.body(2).read(tooLarge));
        assertEquals(-1, bucket.body(0).read(tooLarge));
        assertEquals(6, bucket.body(10).read(tooLarge));
        // A failed/cancelled job must not receive data just because the bucket is unlimited.
        bucket.limiter.setMaxBytesPerSecond(0);
        InputStream stopped = bucket.limiter.limit(new ByteArrayInputStream(new byte[8]), () -> true);
        assertThrows(InterruptedIOException.class, stopped::read);
    }

    private static final class ClockBucket {
        final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 500); // System.nanoTime may wrap.
        final SpeedLimiter limiter;

        ClockBucket(int rate) { limiter = new SpeedLimiter(rate, now::get); }
        InputStream body(int bytes) { return limiter.limit(new ByteArrayInputStream(new byte[bytes]), () -> false); }
        void advanceMillis(long millis) {
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
            limiter.wakeWaiters();
        }
    }

    private static final class WaitingRead<T> implements AutoCloseable {
        final FutureTask<T> future;
        final Thread thread;

        WaitingRead(Callable<T> reader) {
            future = new FutureTask<>(reader);
            thread = new Thread(future, "bandwidth-fixture-reader");
            thread.setDaemon(true);
            thread.start();
        }

        void awaitWait() {
            long start = System.nanoTime();
            while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2)) {
                assertFalse("Reader finished without waiting for a permit", future.isDone());
                Thread.State state = thread.getState();
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            fail("Reader did not block in the limiter");
        }

        T result() throws Exception { return future.get(2, TimeUnit.SECONDS); }
        @Override public void close() { future.cancel(true); }
    }
}

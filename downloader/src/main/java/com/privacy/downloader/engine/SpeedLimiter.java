package com.privacy.downloader.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Aggregate token bucket for queued HTTP body reads. Never hold the bucket lock during I/O. */
public final class SpeedLimiter {
    private static final int MAX_READ = 64 * 1024;
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final LongSupplier clock;
    private final ArrayDeque<Object> waiters = new ArrayDeque<>();
    private long rate;
    private long lastRefill;
    private long generation;
    private double tokens;

    public SpeedLimiter(long bytesPerSecond) {
        this(bytesPerSecond, System::nanoTime);
    }

    SpeedLimiter(long bytesPerSecond, LongSupplier clock) {
        this.clock = clock;
        rate = Math.max(0, bytesPerSecond);
        lastRefill = clock.getAsLong();
        tokens = capacity();
    }

    public synchronized void setMaxBytesPerSecond(long bytesPerSecond) {
        long next = Math.max(0, bytesPerSecond);
        if (next == rate) return;
        refill();
        rate = next;
        generation++;
        tokens = Math.min(tokens, capacity()); // no accumulated credit while unlimited
        notifyAll();
    }

    public synchronized long getMaxBytesPerSecond() {
        return rate;
    }

    public synchronized void wakeWaiters() {
        notifyAll();
    }

    /** Each read takes at most one permit; oversized buffers drain over multiple fair turns. */
    public InputStream limit(InputStream in, BooleanSupplier stopped) {
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
                if (length == 0) return 0;
                Permit permit = acquire(Math.min(length, MAX_READ), stopped);
                checkActive(stopped);
                // Don't refund on failure: the underlying read might have consumed bytes.
                int count = in.read(bytes, offset, permit.bytes);
                refund(permit, permit.bytes - Math.max(count, 0));
                checkActive(stopped);
                return count;
            }

            @Override public void close() throws IOException { in.close(); }
        };
    }

    private synchronized Permit acquire(int requested, BooleanSupplier stopped) throws InterruptedIOException {
        checkActive(stopped);
        if (rate == 0) return new Permit(requested, generation, false);
        Object turn = new Object();
        waiters.addLast(turn);
        try {
            for (;;) {
                checkActive(stopped);
                if (rate == 0) return new Permit(requested, generation, false);
                if (waiters.peekFirst() != turn) {
                    wait();
                    continue;
                }
                refill();
                // Wait for up to 50 ms worth of data, or one byte at very low rates.
                long quantum = Math.min(requested, Math.min(capacity(), Math.max(1L, rate / 20)));
                if (tokens >= quantum) {
                    int bytes = Math.min(requested, (int) tokens);
                    tokens -= bytes;
                    return new Permit(bytes, generation, true);
                }
                long nanos = Math.max(1L, (long) Math.ceil((quantum - tokens) * NANOS_PER_SECOND / rate));
                wait(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Download stopped while waiting for bandwidth.");
        } finally {
            // Cancelled or interrupted waiters do not obstruct other jobs.
            if (waiters.remove(turn)) notifyAll();
        }
    }

    private synchronized void refund(Permit permit, int bytes) {
        if (!permit.limited || bytes <= 0 || permit.generation != generation) return;
        refill();
        tokens = Math.min(capacity(), tokens + bytes);
        notifyAll();
    }

    private long capacity() { return Math.min(rate, MAX_READ); }

    private void refill() {
        long now = clock.getAsLong();
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            tokens = Math.min(capacity(), tokens + elapsed * (rate / (double) NANOS_PER_SECOND));
            lastRefill = now;
        }
    }

    private static void checkActive(BooleanSupplier stopped) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || stopped.getAsBoolean()) {
            throw new InterruptedIOException("Download stopped while reading the HTTP body.");
        }
    }

    private static final class Permit {
        final int bytes;
        final long generation;
        final boolean limited;

        Permit(int bytes, long generation, boolean limited) {
            this.bytes = bytes;
            this.generation = generation;
            this.limited = limited;
        }
    }
}

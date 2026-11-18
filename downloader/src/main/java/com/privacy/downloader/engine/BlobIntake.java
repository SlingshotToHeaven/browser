package com.privacy.downloader.engine;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One confirmed job's in-memory source. The worker grants one frame at a time. */
public final class BlobIntake implements AutoCloseable {
    public static final int MAX_CHUNK_BYTES = 64 * 1024;
    public static final int MAX_FRAME_CHARS = 4 * ((MAX_CHUNK_BYTES + 2) / 3) + 32;
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    public static final long QUEUED_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2);
    public static final String SOURCE_GONE =
            "The blob is no longer available. Confirm a new download from the original page.";
    private static final String TIMED_OUT = "The blob transfer timed out before it finished.";

    public interface Page {
        void start();
        void acknowledge();
        void stop();
    }

    final long knownLength;
    private final LongSupplier nanoTime;
    private final long queuedAt;
    private Page page;
    private long startedAt;
    private long requestedAt;
    private boolean started;
    private boolean accepting;
    private String frame;
    private String failure;

    public BlobIntake(long knownLength, Page page) {
        this(knownLength, page, System::nanoTime);
    }

    BlobIntake(long knownLength, Page page, LongSupplier nanoTime) {
        if (knownLength < -1 || knownLength > MAX_BYTES) throw new IllegalArgumentException("Invalid blob length");
        this.knownLength = knownLength;
        this.page = page;
        this.nanoTime = nanoTime;
        this.queuedAt = nanoTime.getAsLong();
    }

    synchronized boolean isPending() {
        return page != null && !started && nanoTime.getAsLong() - queuedAt < TimeUnit.MILLISECONDS.toNanos(QUEUED_TIMEOUT_MS);
    }

    public synchronized boolean isActive() {
        return started && page != null && failure == null;
    }

    void start() throws IOException {
        Page target;
        synchronized (this) {
            if (!isPending()) throw new IOException(failure != null ? failure : SOURCE_GONE);
            started = true;
            startedAt = requestedAt = nanoTime.getAsLong();
            accepting = true;
            target = page;
        }
        target.start();
    }

    /** Called only after the WebView, committed document, origin and wire token match. */
    public synchronized void receive(String message) {
        if (!isActive()) return;
        if (!accepting || message == null || message.isEmpty() || message.length() > MAX_FRAME_CHARS) {
            failure = "The blob sent an invalid or oversized frame.";
            frame = null;
        } else {
            accepting = false;
            frame = message;
        }
        notifyAll();
    }

    synchronized String next() throws IOException, InterruptedException {
        for (;;) {
            long remaining = requireActive();
            if (frame != null) {
                String message = frame;
                frame = null;
                return message;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    void acknowledge() throws IOException {
        Page target;
        synchronized (this) {
            requireActive();
            accepting = true;
            requestedAt = nanoTime.getAsLong();
            target = page;
        }
        target.acknowledge();
    }

    private long requireActive() throws IOException {
        if (!isActive()) throw new IOException(failure != null ? failure : SOURCE_GONE);
        long now = nanoTime.getAsLong();
        long remaining = Math.min(TimeUnit.MINUTES.toNanos(10) - (now - startedAt),
                TimeUnit.SECONDS.toNanos(30) - (now - requestedAt));
        if (remaining <= 0) throw new IOException(TIMED_OUT);
        return remaining;
    }

    /** Order document revocation against the worker accepting the explicit successful end. */
    void seal() throws IOException {
        Page target;
        synchronized (this) {
            requireActive();
            target = retire();
        }
        target.stop();
    }

    public void abort(String reason) {
        Page target;
        synchronized (this) {
            if (page == null) return;
            if (failure == null) failure = reason;
            target = retire();
        }
        target.stop();
    }

    private Page retire() {
        Page target = page;
        page = null;
        frame = null;
        notifyAll();
        return target;
    }

    @Override
    public void close() {
        abort(SOURCE_GONE);
    }
}

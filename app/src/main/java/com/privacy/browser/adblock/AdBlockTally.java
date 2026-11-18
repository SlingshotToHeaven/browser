package com.privacy.browser.adblock;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** Process-wide blocked total, with a trailing write even if requests never go quiet. */
final class AdBlockTally {
    static final long WINDOW_MS = 2_000;

    interface Scheduler { void later(Runnable task, long delayMs); }

    private final AtomicLong blocked;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final Scheduler worker;
    private final LongConsumer sink;
    private final Runnable publish = this::publish;

    AdBlockTally(long blocked, Scheduler worker, LongConsumer sink) {
        this.blocked = new AtomicLong(blocked);
        this.worker = worker;
        this.sink = sink;
    }

    void block() { blocked.incrementAndGet(); signal(); }
    long blocked() { return blocked.get(); }

    private void signal() {
        if (!pending.get() && pending.compareAndSet(false, true)) worker.later(publish, WINDOW_MS);
    }

    private void publish() {
        long count = blocked.get();
        try { sink.accept(count); }
        finally {
            pending.set(false);
            // Do not orphan a count that arrived while the previous snapshot was being saved.
            if (count != blocked.get()) signal();
        }
    }
}

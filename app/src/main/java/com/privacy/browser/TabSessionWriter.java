package com.privacy.browser;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Process-wide, single ordered writer. At most one frame is writing and one newer frame is retained. */
final class TabSessionWriter {
    static final class Restore {
        final Object owner;
        final TabSession.Index index;
        private Restore(Object owner, TabSession.Index index) {
            this.owner = owner;
            this.index = index;
        }
    }

    private final TabSessionFiles files;
    private final Executor worker;
    private final Consumer<String> warning;
    private Object owner;
    private TabSession.Frame latest;
    private boolean submitted;
    private boolean queued;
    private boolean cleanupQueued;
    private boolean running;
    private boolean failed;
    private boolean wiping;
    private Consumer<Boolean> pendingWipe;

    TabSessionWriter(TabSessionFiles files, Executor worker, Consumer<String> warning) {
        this.files = files;
        this.worker = worker;
        this.warning = warning;
        latest = new TabSession.Frame(files.load(), Collections.emptyMap());
    }

    /** Takes ownership without waiting for disk. Recreation can restore even an unpublished checkpoint. */
    synchronized Restore open() {
        owner = new Object();
        return new Restore(owner, latest.index);
    }

    synchronized boolean owns(Object candidate) { return candidate != null && owner == candidate; }

    /** On a reader worker: recreation can race publication, so check the latest pending bytes first. */
    TabSession.StateRead readState(Object candidate, TabSession.Row row) {
        byte[] pending;
        synchronized (this) {
            if (!owns(candidate)) return TabSession.StateRead.STALE;
            pending = latest.state(row);
        }
        TabSession.StateRead result = pending != null ? TabSession.StateRead.loaded(pending) : files.readState(row);
        return owns(candidate) ? result : TabSession.StateRead.STALE;
    }

    synchronized void release(Object candidate) {
        if (owns(candidate)) owner = null;
    }

    void submit(Object candidate, TabSession.Frame frame) {
        boolean start;
        synchronized (this) {
            if (!owns(candidate) || (submitted && !failed && frame.index.equals(latest.index))) return;
            latest = frame.after(latest);
            submitted = true;
            queued = true;
            failed = false;
            start = arm();
        }
        if (start) worker.execute(this::drain);
    }

    /** Rejects all old owners and drops queued pre-wipe state immediately; runs after any in-flight rename. */
    Object wipe(Object candidate, Consumer<Boolean> completion) {
        boolean start;
        Object nextOwner;
        synchronized (this) {
            if (!owns(candidate) || wiping) throw new IllegalStateException("Tab session is no longer writable");
            owner = nextOwner = new Object();
            latest = new TabSession.Frame(TabSession.EMPTY, Collections.emptyMap());
            queued = false;
            submitted = false;
            failed = false;
            pendingWipe = completion;
            wiping = true;
            start = arm();
        }
        if (start) worker.execute(this::drain);
        return nextOwner;
    }

    void maintainWhenIdle(Object candidate) {
        boolean start;
        synchronized (this) {
            if (!owns(candidate)) return;
            cleanupQueued = true;
            start = arm();
        }
        if (start) worker.execute(this::drain);
    }

    private boolean arm() {
        if (running) return false;
        running = true;
        return true;
    }

    private void drain() {
        while (true) {
            Consumer<Boolean> wipe;
            TabSession.Frame frame = null;
            boolean maintain = false;
            synchronized (this) {
                wipe = pendingWipe;
                if (wipe != null) {
                    pendingWipe = null;
                } else if (queued) {
                    frame = latest;
                    queued = false;
                } else if (cleanupQueued) {
                    cleanupQueued = false;
                    maintain = true;
                } else {
                    running = false;
                    return;
                }
            }
            if (wipe != null) {
                boolean confirmed = files.wipe();
                synchronized (this) { wiping = false; }
                try { wipe.accept(confirmed); }
                catch (RuntimeException failedCallback) { warning.accept("Could not deliver tab cleanup result"); }
            } else if (maintain) {
                files.maintain();
            } else {
                publish(frame);
            }
        }
    }

    private void publish(TabSession.Frame frame) {
        final TabSession.Index committed;
        try {
            committed = files.publish(frame);
        } catch (IOException | RuntimeException error) {
            // Retry on the next meaningful change or lifecycle checkpoint, not in a hot loop.
            synchronized (this) { failed = true; }
            files.requestSweep();
            warning.accept("Could not checkpoint tab session");
            return;
        }
        synchronized (this) {
            failed = false;
            if (latest == frame) {
                latest = new TabSession.Frame(committed, Collections.emptyMap());
            } else if (!latest.states.isEmpty()) {
                Map<String, byte[]> remaining = new LinkedHashMap<>(latest.states);
                for (TabSession.Row row : committed.tabs) remaining.remove(row.filename());
                latest = new TabSession.Frame(latest.index, remaining);
            }
        }
    }
}

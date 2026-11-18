package com.privacy.downloader.engine;

import com.privacy.downloader.model.ActiveDownloads;

import java.util.Collections;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** At most one trailing refresh from any number of worker events. No recurring runnable. */
final class ActiveDownloadPublisher {
    static final long INTERVAL_MS = 1000;

    interface Delay {
        /** Schedule on the queue controller, returning a cancellation action. */
        Runnable schedule(Runnable action, long milliseconds);
    }

    private final Delay delay;
    private final Supplier<ActiveDownloads> capture;
    private final Consumer<ActiveDownloads> publish;
    private Object ticket;
    private Runnable cancel;
    private volatile ActiveDownloads current = new ActiveDownloads(false, 0, Collections.emptyList());

    ActiveDownloadPublisher(Delay delay, Supplier<ActiveDownloads> capture, Consumer<ActiveDownloads> publish) {
        this.delay = delay;
        this.capture = capture;
        this.publish = publish;
    }

    ActiveDownloads current() { return current; }

    synchronized void changed() {
        // The controller publishes admission from idle, even if a worker runs before that publication.
        if (ticket != null || current.owners == 0) return;
        Object ownTicket = new Object();
        ticket = ownTicket;
        cancel = delay.schedule(() -> flush(ownTicket), INTERVAL_MS);
    }

    // Only the controller can publish. Entering/leaving the foreground lifetime must not wait for progress.
    void reconciled(boolean checked, int owners) {
        ActiveDownloads last = current;
        if (last.checked == checked && (last.owners == 0) == (owners == 0)) {
            changed();
            return;
        }
        synchronized (this) {
            ticket = null;
            if (cancel != null) cancel.run();
            cancel = null;
        }
        captureAndPublish();
    }

    private void flush(Object ownTicket) {
        synchronized (this) {
            if (ticket != ownTicket) return;
            ticket = null;
            cancel = null;
        }
        captureAndPublish();
    }

    private void captureAndPublish() {
        ActiveDownloads next = capture.get();
        if (next.equals(current)) return;
        current = next;
        publish.accept(next);
    }
}

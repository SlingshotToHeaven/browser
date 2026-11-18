package com.privacy.downloader.engine;

import com.privacy.downloader.model.ActiveDownloads;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ActiveDownloadPublisherTest {
    @Test public void burstsHaveOneTrailingCaptureAndIdenticalValuesAreNotPublished() {
        Delay timer = new Delay();
        AtomicInteger captures = new AtomicInteger();
        List<ActiveDownloads> delivered = new ArrayList<>();
        DownloadItem worker = new DownloadItem();
        worker.setId("one");
        worker.setAttemptToken("attempt");
        worker.setStatus(DownloadStatus.RUNNING);
        AtomicReference<ActiveDownloads> source = new AtomicReference<>(from(worker));
        ActiveDownloadPublisher publisher = new ActiveDownloadPublisher(timer, () -> {
            captures.incrementAndGet();
            return source.get();
        }, delivered::add);
        publisher.reconciled(true, 1);
        assertEquals(1, delivered.size());
        assertNull(timer.pending);
        for (int i = 0; i < 500; i++) {
            worker.setDownloadedBytes(i);
            source.set(from(worker));
            publisher.changed();
        }
        assertEquals(1, captures.get());
        assertEquals(1, timer.scheduled);
        assertEquals(0, delivered.get(0).downloadedBytes);
        timer.fire();
        assertEquals(2, captures.get());
        assertEquals(499, delivered.get(1).downloadedBytes);
        assertNull(timer.pending); // No event, no later refresh.
        source.set(from(worker)); // Equal value, independent object.
        publisher.changed();
        timer.fire();
        assertEquals(2, delivered.size());
    }

    @Test public void idlePublishesImmediatelyAndCancelsEvenAnAlreadyDequeuedProgressTicket() {
        Delay timer = new Delay();
        AtomicInteger captures = new AtomicInteger();
        AtomicReference<ActiveDownloads> source = new AtomicReference<>(new ActiveDownloads(true, 0, Collections.emptyList()));
        ActiveDownloadPublisher publisher = new ActiveDownloadPublisher(timer, () -> {
            captures.incrementAndGet();
            return source.get();
        }, ignored -> {});
        publisher.reconciled(true, 0);
        assertTrue(publisher.current().idle());
        for (int i = 0; i < 100; i++) publisher.changed();
        assertEquals(0, timer.scheduled);
        source.set(new ActiveDownloads(true, 1, Collections.emptyList())); // still draining
        publisher.reconciled(true, 1);
        assertFalse(publisher.current().idle());
        publisher.changed();
        Runnable stale = timer.pending;
        source.set(new ActiveDownloads(true, 0, Collections.emptyList()));
        publisher.reconciled(true, 0);
        assertTrue(publisher.current().idle());
        assertNull(timer.pending);
        stale.run();
        assertEquals(3, captures.get());
    }

    private static ActiveDownloads from(DownloadItem item) {
        return new ActiveDownloads(true, 1, Collections.singletonList(new ActiveDownloads.Transfer(item)));
    }

    private static final class Delay implements ActiveDownloadPublisher.Delay {
        Runnable pending;
        int scheduled;
        @Override public Runnable schedule(Runnable action, long milliseconds) {
            assertEquals(1000, milliseconds);
            assertNull(pending);
            pending = action;
            scheduled++;
            return () -> { if (pending == action) pending = null; };
        }
        void fire() {
            Runnable action = pending;
            pending = null;
            action.run();
        }
    }
}

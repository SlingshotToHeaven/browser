package com.privacy.browser.media;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MediaSnifferTest {
    private static final String ORIGIN = "https://media.invalid";
    private static final String VIDEO = ORIGIN + "/Video.mp4?sig=Ab%2f&part=7";
    private final ThreadPoolExecutor pool = MediaSniffer.newPool();

    @After public void stopPool() throws Exception {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test public void passiveDiscoveryHasNoJobsEvenWithLotsOfNoiseAndDuplicates() {
        AtomicInteger changes = new AtomicInteger();
        MediaSniffer page = new MediaSniffer(pool, (s, t, b) -> failLookup());
        page.observe(changes::incrementAndGet);
        for (int i = 0; i < 500; i++) {
            page.requestHint(ORIGIN + "/image.png", "/image.png", "Noise");
            page.requestHint(ORIGIN + "/Segment-01.mp4", "/Segment-01.mp4", "Chunk");
            page.requestHint(ORIGIN + "/piece.mp4?range=0-1", "/piece.mp4", "Chunk");
        }
        assertFalse(enrich(page, null, () -> fail("No-media batch delivered")));
        for (int i = 0; i < 100; i++) page.requestHint(VIDEO, "/Video.mp4", "Movie");
        page.elementHint(VIDEO + "#t=2", "video", "Same movie");
        assertEquals(1, changes.get());
        assertEquals(1, page.count());
        assertEquals(-1, page.snapshot().get(0).getFileSize());
        assertEquals(0, pool.getTaskCount());
        assertEquals(0, pool.getLargestPoolSize());
    }

    @Test public void signedUrlsAreNotMergedByTitleQualityOrCaseSensitiveComponents() {
        MediaSniffer page = new MediaSniffer(pool, (s, t, b) -> failLookup());
        page.observe(null);
        page.requestHint(VIDEO, "/Video.mp4", "Same title");
        page.elementHint(VIDEO.replace("media.invalid", "MEDIA.INVALID:443") + "#t=3", "video", "Same title");
        page.elementHint(VIDEO.replace("Video.mp4", "video.mp4"), "video", "Same title");
        page.elementHint(VIDEO.replace("sig=Ab", "sig=ab"), "video", "Same title");
        page.elementHint(VIDEO.replace("sig=Ab%2f&part=7", "part=7&sig=Ab%2f"), "video", "Same title");
        page.elementHint(VIDEO.replace("%2f", "%2F"), "video", "Same title");
        assertEquals(5, page.count());
        assertEquals(VIDEO, page.snapshot().get(0).getUrl());
    }

    @Test public void settingStopsRecordingAndEnrichmentAndAClosedPageCannotBeReopened() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MediaSniffer.Resolver resolver = (s, t, b) -> {
            calls.incrementAndGet();
            return Collections.singletonList(MediaMetadata.sourceRow(s, t, 50_000_000));
        };
        MediaSniffer a = new MediaSniffer(pool, resolver);
        a.requestHint(VIDEO, "/Video.mp4", "Off");
        a.elementHint(VIDEO, "video", "Off");
        assertFalse(enrich(a, null, () -> fail("Off")));
        assertEquals(0, a.count());
        a.observe(null);
        a.elementHint(VIDEO, "video", "On");
        enrichAndWait(a, null);
        assertEquals("47.7 MB", a.snapshot().get(0).getFormattedSize());
        a.suspend();
        a.elementHint(ORIGIN + "/other.mp4", "video", "Off again");
        assertEquals(1, a.count());
        assertFalse(enrich(a, null, () -> fail("Suspended")));
        a.close();
        a.observe(null);
        assertFalse(a.isObserving());
        assertEquals(0, a.count());

        // New regular document (even at the same URL) cannot reuse an older document's options.
        MediaSniffer b = new MediaSniffer(pool, resolver);
        b.observe(null);
        b.elementHint(VIDEO, "video", "Replacement");
        enrichAndWait(b, null);
        assertEquals(2, calls.get());
        assertFalse(enrich(b, null, () -> fail("Already tried")));
    }

    @Test public void oneReservedBatchCoalescesConcurrentAndRepeatedOpens() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        MediaSniffer page = new MediaSniffer(pool, (s, t, b) -> {
            calls.incrementAndGet();
            started.countDown();
            assertTrue(release.await(4, TimeUnit.SECONDS));
            return Collections.singletonList(MediaMetadata.sourceRow(s, t, 1024));
        });
        page.observe(null);
        page.elementHint(VIDEO, "video", "Movie");
        assertTrue(enrich(page, null, done::countDown));
        try {
            assertTrue(started.await(4, TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++) {
                page.requestHint(VIDEO, "/Video.mp4", "Same resource");
                assertFalse(enrich(page, null, () -> fail("Duplicate open")));
            }
        } finally { release.countDown(); }
        assertTrue(done.await(4, TimeUnit.SECONDS));
        assertFalse(enrich(page, null, () -> fail("Repeat probe")));
        assertEquals(1, calls.get());
        assertEquals(1, pool.getTaskCount());
        assertEquals(1024, page.snapshot().get(0).getFileSize());
    }

    @Test public void capsHintRowsAndPlansEvenAfterRepeatedUserActions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MediaSniffer page = new MediaSniffer(pool, (s, t, b) -> {
            calls.incrementAndGet();
            List<MediaSniffer.SniffedMedia> many = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                MediaSource source = MediaSource.element(s.url + "?row=" + i, "video");
                many.add(MediaMetadata.sourceRow(source, t, 100));
            }
            return many;
        });
        page.observe(null);
        for (int i = 0; i < 300; i++) page.elementHint(ORIGIN + "/video-" + i + ".mp4", "video", "Movie");
        assertEquals(MediaSniffer.MAX_HINTS, page.count());
        enrichAndWait(page, null);
        assertEquals(MediaSniffer.PLANS_PER_OPEN, calls.get());
        assertEquals(MediaSniffer.MAX_ROWS, page.count());
        enrichAndWait(page, null);
        enrichAndWait(page, null);
        assertEquals(MediaSniffer.PLANS_PER_PAGE, calls.get());
        assertFalse(enrich(page, null, () -> fail("More than page budget")));
        assertEquals(MediaSniffer.MAX_ROWS, page.count());
    }

    @Test public void aBlockedSourceIsNotAdmittedAndProviderAliasesShareOnePlan() throws Exception {
        List<String> calls = new ArrayList<>();
        MediaSniffer page = new MediaSniffer(pool, (s, t, b) -> { calls.add(s.key); return Collections.emptyList(); });
        page.observe(null);
        page.elementHint(VIDEO, "video", "Filtered");
        assertFalse(page.enrich(null, "Page", Collections.emptyList(), u -> !u.equals(VIDEO), () -> fail("Filtered")));
        CountDownLatch settled = new CountDownLatch(1);
        String id = "abc12345678";
        assertTrue(page.enrich("https://youtu.be/" + id, "Player", List.of("https://www.youtube.com/embed/" + id),
                u -> !u.equals(VIDEO), settled::countDown));
        assertTrue(settled.await(4, TimeUnit.SECONDS));
        assertFalse(page.enrich("https://www.youtube.com/watch?v=" + id, "Player", Collections.emptyList(),
                u -> !u.equals(VIDEO), () -> fail("Same player")));
        assertEquals(Collections.singletonList("youtube:" + id), calls);
    }

    @Test public void retirementRemovesQueuedWorkAndReclaimsBoundedAdmission() throws Exception {
        CountDownLatch started = new CountDownLatch(MediaSniffer.WORKERS), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(), callbacks = new AtomicInteger();
        MediaSniffer.Resolver stalled = (s, t, b) -> {
            calls.incrementAndGet();
            started.countDown();
            release.await(4, TimeUnit.SECONDS);
            return Collections.emptyList();
        };
        List<MediaSniffer> pages = new ArrayList<>();
        try {
            for (int i = 0; i < MediaSniffer.WORKERS + MediaSniffer.QUEUE_SIZE; i++) {
                MediaSniffer page = withVideo(stalled);
                pages.add(page);
                assertTrue(enrich(page, null, callbacks::incrementAndGet));
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(2, pool.getLargestPoolSize());
            assertEquals(4, pool.getQueue().size());
            MediaSniffer denied = withVideo(stalled);
            pages.add(denied);
            assertFalse(enrich(denied, null, callbacks::incrementAndGet));
            pages.get(2).close(); // queued, never started
            assertEquals(3, pool.getQueue().size());
            assertTrue(enrich(denied, null, callbacks::incrementAndGet));
        } finally {
            // Revoke queued entries before releasing the two active workers.
            for (int i = pages.size() - 1; i >= 0; i--) pages.get(i).close();
            release.countDown();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(4, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        assertEquals(0, callbacks.get());
    }

    @Test public void activeCancellationStopsTheNextRequestAndDiscardsAnUninterruptibleLateResult() throws Exception {
        AtomicInteger calls = new AtomicInteger(), callbacks = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), canceled = new CountDownLatch(1), returned = new CountDownLatch(1);
        MediaSniffer page = withVideo((s, t, b) -> {
            calls.incrementAndGet();
            started.countDown();
            try { returned.await(4, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {
                if (b.stopped()) canceled.countDown();
                returned.await(4, TimeUnit.SECONDS); // emulate an I/O provider which does not return immediately
            }
            return Collections.singletonList(MediaMetadata.sourceRow(s, "Late metadata", 300));
        });
        page.elementHint(ORIGIN + "/next.mp4", "video", "Second plan");
        assertTrue(enrich(page, null, callbacks::incrementAndGet));
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            page.close();
            assertTrue(canceled.await(3, TimeUnit.SECONDS));
        } finally { returned.countDown(); }
        pool.shutdown();
        assertTrue(pool.awaitTermination(4, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertEquals(0, callbacks.get());
        assertEquals(0, page.count());
    }

    private MediaSniffer withVideo(MediaSniffer.Resolver resolver) {
        MediaSniffer page = new MediaSniffer(pool, resolver);
        page.observe(null);
        page.elementHint(VIDEO, "video", "Movie");
        return page;
    }

    private static List<MediaSniffer.SniffedMedia> failLookup() { throw new AssertionError("Unexpected lookup"); }

    private static boolean enrich(MediaSniffer page, String player, Runnable done) {
        return page.enrich(player, "Title", Collections.emptyList(), url -> true, done);
    }

    private static void enrichAndWait(MediaSniffer page, String player) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(enrich(page, player, done::countDown));
        assertTrue(done.await(4, TimeUnit.SECONDS));
    }
}

package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class BlobPublicationTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void explicitEndAndCompleteBytesArePublishedOnlyAfterAdmission() throws Exception {
        Page page = new Page(3, "begin:3", data(0, 1, 2), data(2, 3), "end:3");
        StubBlob task = newTask(page);
        page.intake.receive(data(0, 99)); // No worker, no start: ignore even a frame for this intake.
        assertEquals(0, page.starts);
        assertEquals(0, task.destination().getParent().toFile().list().length);
        task.run();
        assertEquals(DownloadStatus.COMPLETED, task.item.getStatus());
        assertEquals(1, task.commits);
        assertNull(task.item.getTempPath());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(task.destination()));
        assertFalse(Files.exists(task.staged));
        assertEquals(1, page.starts);
        assertEquals(3, page.acks);
        assertEquals(1, page.stops);
    }

    @Test
    public void unknownLengthAndExplicitlyEmptyBlobCanFinish() throws Exception {
        for (Page page : List.of(new Page(-1, "begin:-1", data(0, 1), "end:1"), new Page(0, "begin:0", "end:0"))) {
            StubBlob task = newTask(page);
            task.run();
            assertEquals(DownloadStatus.COMPLETED, task.item.getStatus());
            assertArrayEquals(page.intake.knownLength == 0 ? new byte[0] : new byte[]{1}, Files.readAllBytes(task.destination()));
            assertEquals(1, page.stops);
        }
    }

    @Test
    public void shortOrConflictingDeclarationsCannotPublish() throws Exception {
        for (Page page : List.of(new Page(3, "begin:3", data(0, 1), "end:1"),
                new Page(3, "begin:2", data(0, 1, 2), "end:2"), new Page(2, "begin:-1", data(0, 1), "end:1"))) {
            StubBlob task = newTask(page);
            task.run();
            assertFailed(task, page.intake.knownLength == 3 && page.acks == 0 ? "does not match" : "before all expected bytes");
            assertFalse(Files.exists(task.destination()));
            assertEquals(1, page.stops);
        }
    }

    @Test
    public void badOffsetMissingBeginOrInvalidEndIsNotAccepted() throws Exception {
        for (Page page : List.of(new Page(3, "begin:3", data(0, 1, 2), data(1, 3), "end:3"),
                new Page(0, "end:0"), new Page(1, "begin:1", data(0, 1), "end:0"),
                new Page(1, "begin:1", "data:0:!"), new Page(0, "begin:0", data(0, 1)))) {
            StubBlob task = newTask(page);
            Files.write(task.destination(), new byte[]{9});
            task.run();
            assertFailed(task, "expected bytes");
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(task.destination()));
            assertEquals(1, page.stops);
        }
    }

    @Test
    public void rejectsOversizeDeclarationsAndFramesBeforeWritingTheirBytes() throws Exception {
        verifyRejection(new Page(-1, "begin:" + (BlobIntake.MAX_BYTES + 1)), "download limit");
        String encoding = Base64.getEncoder().encodeToString(new byte[BlobIntake.MAX_CHUNK_BYTES + 1]);
        verifyRejection(new Page(-1, "begin:-1", "data:0:" + encoding), "expected bytes");
        verifyRejection(new Page(-1, "begin:-1", "x".repeat(BlobIntake.MAX_FRAME_CHARS + 1)), "oversized frame");
    }

    @Test
    public void aSecondFrameWithoutAnAckFailsAndDiscardsTheMailbox() throws Exception {
        Page page = new Page(1, "begin:1");
        page.startHook = () -> page.intake.receive(data(0, 1));
        verifyRejection(page, "invalid or oversized frame");
    }

    @Test
    public void idleTimeoutDoesNotPublishEvenWhenTheByteCountMatches() throws Exception {
        Page page = new Page(1, "begin:1", data(0, 1));
        page.noFrame = () -> page.clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        StubBlob task = newTask(page);
        task.run();
        assertFailed(task, "timed out");
        assertEquals(1, task.item.getDownloadedBytes());
        assertFalse(Files.exists(task.destination()));
        assertEquals(1, page.stops);
    }

    @Test
    public void slowProgressDoesNotExtendTheWholeTransferDeadline() throws Exception {
        String[] frames = new String[27];
        frames[0] = "begin:-1";
        for (int i = 1; i < frames.length; i++) frames[i] = data(i - 1, 1);
        Page page = new Page(-1, frames);
        page.beforeAck = () -> page.clock.addAndGet(TimeUnit.SECONDS.toNanos(24));
        StubBlob task = newTask(page);
        task.run();
        assertFailed(task, "timed out");
        assertTrue(task.item.getDownloadedBytes() > 0);
        assertFalse(Files.exists(task.destination()));
        assertEquals(1, page.stops);
    }

    @Test(timeout = 10000)
    public void stopOrPageCloseWakesTheWorkerAndIgnoresLateFrames() throws Exception {
        for (DownloadStatus stop : new DownloadStatus[]{DownloadStatus.CANCELLED, DownloadStatus.WAITING_FOR_BATTERY, null}) {
            Page page = new Page(2, "begin:2", data(0, 1));
            CountDownLatch waiting = new CountDownLatch(1);
            page.noFrame = waiting::countDown;
            StubBlob task = newTask(page);
            FutureTask<Void> execution = new FutureTask<>(task, null);
            Thread worker = new Thread(execution, "blob-intake-fixture");
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(waiting.await(3, TimeUnit.SECONDS));
            } finally {
                if (stop != null) task.stopFor(stop, "fixture stop");
                else page.intake.abort("The page was closed.");
            }
            page.intake.receive(data(1, 2));
            page.intake.receive("end:2");
            execution.get(3, TimeUnit.SECONDS);
            assertEquals(stop != null ? stop : DownloadStatus.FAILED, task.item.getStatus());
            assertEquals(stop != null ? "fixture stop" : "The page was closed.", task.item.getErrorMessage());
            assertEquals(1, task.item.getDownloadedBytes());
            assertEquals(0, task.commits);
            assertFalse(Files.exists(task.destination()));
            assertFalse(Files.exists(task.staged));
            assertEquals(1, page.stops);
        }
    }

    @Test
    public void publicationFailureCleansStagingAndNeverRearmsIntake() throws Exception {
        Page page = new Page(1, "begin:1", data(0, 1), "end:1");
        StubBlob task = newTask(page);
        task.rejectCommit = true;
        task.run();
        assertFailed(task, "publication rejected");
        assertFalse(Files.exists(task.destination()));
        assertEquals(1, page.stops);
        assertThrows(IOException.class, page.intake::start);
        page.intake.receive("end:1");
        assertFalse(page.intake.isActive());
    }

    @Test
    public void closedOrMissingSourceNeverStartsARead() throws Exception {
        Page page = new Page(1, "begin:1", data(0, 1), "end:1");
        page.intake.close();
        StubBlob closed = newTask(page);
        closed.run();
        assertFailed(closed, "no longer available");
        assertEquals(0, page.starts);
        assertEquals(1, page.stops);
        StubBlob missing = newTask(null);
        missing.run();
        assertFailed(missing, "no longer available");
    }

    private void verifyRejection(Page page, String reason) throws IOException {
        StubBlob task = newTask(page);
        task.run();
        assertFailed(task, reason);
        assertEquals(0, task.item.getDownloadedBytes());
        assertFalse(Files.exists(task.destination()));
        assertEquals(1, page.stops);
    }

    private static String data(long offset, int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return "data:" + offset + ":" + Base64.getEncoder().encodeToString(bytes);
    }

    private StubBlob newTask(Page page) throws IOException {
        DownloadItem item = new DownloadItem();
        item.setId("fixture-blob");
        item.setUrl("blob:https://example.test/fixture");
        item.setFileName("name.bin");
        item.setDestinationPath(new File(files.newFolder(), "name.bin").getAbsolutePath());
        return new StubBlob(item, page == null ? null : page.intake);
    }

    private static void assertFailed(StubBlob task, String reason) {
        assertEquals(DownloadStatus.FAILED, task.item.getStatus());
        assertEquals(0, task.commits);
        assertTrue(task.item.getErrorMessage(), task.item.getErrorMessage().contains(reason));
        assertTrue(task.staged == null || !Files.exists(task.staged));
    }

    private static final class Page implements BlobIntake.Page {
        final AtomicLong clock = new AtomicLong();
        final BlobIntake intake;
        final Deque<String> frames;
        Runnable startHook = () -> {};
        Runnable beforeAck = () -> {};
        Runnable noFrame = () -> { throw new AssertionError("The worker requested an unexpected frame"); };
        int starts;
        int acks;
        int stops;

        Page(long length, String... frames) {
            this.frames = new ArrayDeque<>(List.of(frames));
            this.intake = new BlobIntake(length, this, clock::get);
        }

        @Override public void start() { starts++; deliver(); startHook.run(); }
        @Override public void acknowledge() { acks++; beforeAck.run(); deliver(); }
        @Override public void stop() { stops++; }
        void deliver() {
            if (frames.isEmpty()) noFrame.run();
            else intake.receive(frames.remove());
        }
    }

    private static final class StubBlob extends BlobDownloadTask {
        Path staged;
        int commits;
        boolean rejectCommit;

        StubBlob(DownloadItem item, BlobIntake intake) { super(item, null, null, null, intake); }
        Path destination() { return Path.of(item.getDestinationPath()); }
        @Override boolean persistStatus(DownloadStatus status, String errorMessage) {
            assertTrue("the worker must not rewrite a controller stop", status == DownloadStatus.RUNNING || status == DownloadStatus.FAILED);
            return true;
        }
        @Override void persistAttempt(String previousTempPath) { staged = Path.of(item.getTempPath()); }
        @Override void persistPublication(String destination, String staging, long size, String mime, long finishedAt, String identity) throws IOException {
            assertEquals(DownloadStatus.RUNNING, item.getStatus());
            assertEquals(size, Files.size(Path.of(destination)));
            if (rejectCommit) throw new IOException("publication rejected by fixture");
            commits++;
        }
    }
}

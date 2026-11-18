package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.RequestContextFixture;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static com.privacy.downloader.engine.LoopbackHttpFixture.chunked;
import static com.privacy.downloader.engine.LoopbackHttpFixture.fixed;
import static org.junit.Assert.*;

public class DownloadTaskTransferTest {
    private static final byte[] BODY = {11, 22, 33, 44, 55, 66, 77, 88};
    private static final byte[] PREFIX = Arrays.copyOf(BODY, 4);
    private static final String ETAG = "\"verified-fixture\"";
    private static final String INACTIVE_URL = "http://127.0.0.1:1/fixture";

    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    @Test(timeout = 5000)
    public void directRangeProbeCarriesItsNarrowedContextIntoTheActualTransfer() throws Exception {
        for (boolean foreignHop : new boolean[]{false, true}) {
            String[] target = new String[1];
            try (LoopbackHttpFixture link = new LoopbackHttpFixture(
                    request -> fixed(302, new byte[0], "Location: " + target[0]),
                    fixed(206, Arrays.copyOf(BODY, 1), "Content-Range: bytes 0-0/8", "ETag: " + ETAG),
                    fixed(206, BODY, "Content-Range: bytes 0-7/8", "ETag: " + ETAG));
                 LoopbackHttpFixture intermediary = new LoopbackHttpFixture(fixed(307, new byte[0], "Location: " + link.url("/file")))) {
                target[0] = foreignHop ? intermediary.url() : link.url("/file");
                DownloadItem item = newItem(link.url());
                RequestContext context = RequestContextFixture.browser(link.url("/account?secret=x"), "Browser fixture",
                        url -> url.endsWith("/file") ? "scoped=file" : "scoped=link");
                TestTask task = new TestTask(item, new Events(), context);
                task.run();
                assertTrue(item.getErrorMessage(), task.events.completed);
                assertEquals(link.url("/file"), item.getVerifiedRangeUrl());
                assertArrayEquals(BODY, Files.readAllBytes(destination(item).toPath()));
                List<LoopbackHttpFixture.Request> requests = link.requests();
                assertEquals("scoped=link", requests.get(0).header("Cookie"));
                assertEquals("bytes=0-0", requests.get(0).header("Range"));
                for (int i = 1; i < requests.size(); i++) {
                    assertEquals(foreignHop ? null : "scoped=file", requests.get(i).header("Cookie"));
                    assertEquals(foreignHop ? null : link.url("/"), requests.get(i).header("Referer"));
                }
                assertEquals(ETAG, requests.get(2).header("If-Range"));
                if (foreignHop) assertNull(intermediary.requests().get(0).header("Cookie"));
            }
        }
    }

    @Test(timeout = 10000)
    public void unverifiedProbeFallsBackToOneFullResponse() throws Exception {
        for (String validator : new String[]{"ETag: W/" + ETAG, "Last-Modified: Thu, 01 Jan 2026 00:00:00 GMT"}) {
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(
                    fixed(206, Arrays.copyOf(BODY, 1), "Content-Range: bytes 0-0/8", validator), fixed(200, BODY))) {
                DownloadItem item = newItem(server.url());
                TestTask task = run(item);
                assertTrue(task.events.completed);
                assertEquals(DownloadStatus.COMPLETED, item.getStatus());
                assertArrayEquals(BODY, Files.readAllBytes(destination(item).toPath()));
                assertNull(item.getVerifiedRangeUrl());
                assertEquals(1, task.savedSegments.size());
                assertNull(server.requests().get(1).header("Range"));
                assertNull(server.requests().get(1).header("If-Range"));
            }
        }
    }

    @Test(timeout = 10000)
    public void aSingleByteRangeMustWriteItsByteBeforeCompletion() throws Exception {
        byte[] only = Arrays.copyOf(BODY, 1);
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(
                fixed(206, only, "Content-Range: bytes 0-0/1", "ETag: " + ETAG),
                fixed(206, only, "Content-Range: bytes 0-0/1", "ETag: " + ETAG))) {
            DownloadItem item = newItem(server.url());
            item.setNumSegments(1);
            TestTask task = run(item);
            assertTrue(item.getErrorMessage(), task.events.completed);
            assertEquals(1, task.savedSegments.get(0).getCurrentByte());
            assertEquals(ETAG, server.requests().get(1).header("If-Range"));
            assertArrayEquals(only, Files.readAllBytes(destination(item).toPath()));
        }
    }

    @Test(timeout = 10000)
    public void theValidatorIsBoundToTheResourceThatAnsweredTheProbe() throws Exception {
        try (LoopbackHttpFixture resource = new LoopbackHttpFixture(
                fixed(206, Arrays.copyOf(BODY, 1), "Content-Range: bytes 0-0/8", "ETag: " + ETAG),
                fixed(206, BODY, "Content-Range: bytes 0-7/8", "ETag: " + ETAG));
             LoopbackHttpFixture link = new LoopbackHttpFixture(fixed(302, new byte[0], "Location: " + resource.url()))) {
            DownloadItem item = newItem(link.url());
            TestTask task = run(item);
            assertTrue(item.getErrorMessage(), task.events.completed);
            assertEquals(link.url(), item.getUrl());
            assertEquals(resource.url(), item.getVerifiedRangeUrl());
            assertNull(link.requests().get(0).header("If-Range"));
            assertEquals(ETAG, resource.requests().get(1).header("If-Range"));
            assertArrayEquals(BODY, Files.readAllBytes(destination(item).toPath()));
        }
    }

    @Test(timeout = 10000)
    public void aDirectLinkThatResolvesToDashMustNotPublishAManifest() throws Exception {
        for (boolean fullOnly : new boolean[]{false, true}) {
            byte[] manifest = "<MPD><Period/></MPD>".getBytes(StandardCharsets.UTF_8);
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(302, new byte[0], "Location: /resolved"),
                    fixed(200, manifest, "Content-Type: application/dash+xml"))) {
                DownloadItem item = newItem(server.url());
                item.setFullDownloadOnly(fullOnly);
                TestTask task = run(item);
                assertFailedWithoutDestination(item, task);
                assertEquals(StreamDetector.DASH_UNSUPPORTED, task.events.error);
                assertEquals("/resolved", server.requests().get(1).target);
            }
        }
    }

    @Test(timeout = 10000)
    public void validatedProbeAllowsParallelRangesAndPublishesExactBytes() throws Exception {
        int size = 1024 * 1024;
        int middle = size / 2;
        byte[] expected = new byte[size];
        for (int i = 0; i < size; i++) expected[i] = (byte) (17 * i + i / middle + 1);
        LoopbackHttpFixture.Reply slice = request -> {
            assertEquals(ETAG, request.header("If-Range"));
            String first = "bytes=0-" + (middle - 1);
            String second = "bytes=" + middle + "-" + (size - 1);
            boolean isFirst = first.equals(request.header("Range"));
            assertEquals(isFirst ? first : second, request.header("Range"));
            int start = isFirst ? 0 : middle;
            int limit = start + middle;
            return fixed(206, Arrays.copyOfRange(expected, start, limit),
                    "Content-Range: bytes " + start + "-" + (limit - 1) + "/" + size, "ETag: " + ETAG);
        };
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(
                fixed(206, Arrays.copyOf(expected, 1), "Content-Range: bytes 0-0/" + size, "ETag: " + ETAG), slice, slice)) {
            DownloadItem item = newItem(server.url());
            item.setNumSegments(2);
            TestTask task = run(item);
            assertTrue(item.getErrorMessage(), task.events.completed);
            assertEquals("bytes=0-0", server.requests().get(0).header("Range"));
            assertNull(server.requests().get(0).header("If-Range"));
            assertEquals(2, task.savedSegments.size());
            assertEquals(size, item.getDownloadedBytes());
            assertArrayEquals(expected, Files.readAllBytes(destination(item).toPath()));
            assertFalse(new File(task.createdStagingPath).exists());
        }
    }

    @Test(timeout = 10000)
    public void recoveredCheckpointReusesVerifiedBytesWithoutReprobing() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(206, Arrays.copyOfRange(BODY, 4, 8),
                "Content-Range: bytes 4-7/8", "ETag: " + ETAG))) {
            DownloadItem item = recordedPrefix(server.url());
            item.setSegments(Arrays.asList(
                    segment(item, 0, 0, 3, 4, DownloadStatus.COMPLETED),
                    segment(item, 1, 4, 7, 4, DownloadStatus.PENDING)));
            TestTask task = run(item);
            assertTrue(item.getErrorMessage(), task.events.completed);
            assertEquals(0, task.attemptsCreated);
            assertEquals("bytes=4-7", server.requests().get(0).header("Range"));
            assertEquals(ETAG, server.requests().get(0).header("If-Range"));
            assertArrayEquals(BODY, Files.readAllBytes(destination(item).toPath()));
        }
    }

    @Test(timeout = 10000)
    public void rangeRejectionLeavesTheTaskFailedAndPreservesThePartial() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, BODY))) {
            DownloadItem item = recordedPrefix(server.url());
            TestTask task = run(item);
            assertFailedWithoutDestination(item, task);
            assertTrue(task.events.error.contains("Start over"));
            assertArrayEquals(PREFIX, Files.readAllBytes(partial(item).toPath()));
            assertEquals(PREFIX.length, task.savedSegments.get(0).getCurrentByte());
            assertEquals("bytes=4-7", server.requests().get(0).header("Range"));
        }
    }

    @Test(timeout = 10000)
    public void knownLengthFullDownloadCannotPublishAShortBody() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, BODY), chunked(200, PREFIX))) {
            DownloadItem item = newItem(server.url());
            TestTask task = run(item);
            assertFailedWithoutDestination(item, task);
            assertTrue(task.events.error.contains("before all expected bytes"));
            assertArrayEquals(PREFIX, Files.readAllBytes(partial(item).toPath()));
            assertEquals(PREFIX.length, item.getDownloadedBytes());
            assertNull(server.requests().get(1).header("Range"));
        }
    }

    @Test(timeout = 10000)
    public void pauseSavesTheWrittenPrefixAndTheNextAttemptResumesIt() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(
                chunked(206, Arrays.copyOfRange(BODY, 4, 6), "Content-Range: bytes 4-7/8", "ETag: " + ETAG),
                fixed(206, Arrays.copyOfRange(BODY, 6, 8), "Content-Range: bytes 6-7/8", "ETag: " + ETAG))) {
            DownloadItem item = recordedPrefix(server.url());
            TestTask first = new TestTask(item, new Events());
            first.pauseAfterWrite = true;
            first.run();
            assertEquals(DownloadStatus.PAUSED, item.getStatus());
            assertFalse(destination(item).exists());
            assertArrayEquals(Arrays.copyOf(BODY, 6), Files.readAllBytes(partial(item).toPath()));
            assertEquals(6, first.savedSegments.get(0).getCurrentByte());

            item.setSegments(first.savedSegments);
            TestTask second = run(item);
            assertTrue(item.getErrorMessage(), second.events.completed);
            assertArrayEquals(BODY, Files.readAllBytes(destination(item).toPath()));
            assertEquals("bytes=6-7", server.requests().get(1).header("Range"));
            assertEquals(ETAG, server.requests().get(1).header("If-Range"));
        }
    }

    @Test(timeout = 10000)
    public void oldCompletedRowsCannotRecreateMissingOrTruncatedBytes() throws Exception {
        for (boolean missing : new boolean[]{true, false}) {
            DownloadItem item = recordedPrefix(INACTIVE_URL);
            item.setSegments(List.of(segment(item, 0, 0, 7, 8, DownloadStatus.COMPLETED)));
            if (missing) Files.delete(partial(item).toPath());
            TestTask task = run(item);
            assertFailedWithoutDestination(item, task);
            assertTrue(task.events.error.contains("Start over"));
            if (missing) assertFalse(partial(item).exists());
            else assertArrayEquals(PREFIX, Files.readAllBytes(partial(item).toPath()));
            assertEquals(0, task.attemptsCreated);
            assertNull(task.savedSegments);
        }
    }

    @Test(timeout = 10000)
    public void fullLengthFileCannotJustifyAnInvalidCoverageMap() throws Exception {
        for (int secondStart : new int[]{3, 4, 5}) {
            DownloadItem item = recordedPrefix(INACTIVE_URL);
            // Mimic a full-length sparse/preallocated file with unwritten bytes.
            byte[] padded = Arrays.copyOf(PREFIX, BODY.length);
            Files.write(partial(item).toPath(), padded);
            item.setSegments(Arrays.asList(
                    segment(item, 0, 0, 3, 4, DownloadStatus.COMPLETED),
                    segment(item, 1, secondStart, 7, secondStart, DownloadStatus.COMPLETED)));
            TestTask task = run(item);
            assertFailedWithoutDestination(item, task);
            assertTrue(task.events.error.contains("Start over"));
            assertArrayEquals(padded, Files.readAllBytes(partial(item).toPath()));
            assertNull(task.savedSegments);
        }
    }

    @Test(timeout = 10000)
    public void legacyAndChangedLinkCheckpointsAreNotReblessedByAProbe() throws Exception {
        for (boolean legacy : new boolean[]{true, false}) {
            DownloadItem item = recordedPrefix(INACTIVE_URL);
            if (legacy) item.setVerifiedRangeUrl(null);
            else item.setUrl(INACTIVE_URL + "?refreshed=1");
            assertNull(item.getVerifiedRangeUrl());
            TestTask task = run(item);
            assertFailedWithoutDestination(item, task);
            assertTrue(task.events.error.contains("could not be verified"));
            assertArrayEquals(PREFIX, Files.readAllBytes(partial(item).toPath()));
            assertNull(task.savedSegments);
        }
    }

    @Test(timeout = 10000)
    public void startOverUsesNewPartialAndNoRangeAndLeavesOldBytesAlone() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, BODY))) {
            DownloadItem item = recordedPrefix(server.url());
            File old = partial(item);
            TransferIntegrity.resetForFullDownload(item);
            assertNull(item.getVerifiedRangeUrl());
            assertEquals(0, item.getDownloadedBytes());
            assertTrue(item.getSegments().isEmpty());

            TestTask task = run(item);
            assertTrue(item.getErrorMessage(), task.events.completed);
            assertEquals(1, task.attemptsCreated);
            assertNotEquals(old, new File(task.createdStagingPath));
            assertNull(item.getTempPath());
            assertArrayEquals(PREFIX, Files.readAllBytes(old.toPath()));
            assertArrayEquals(BODY, Files.readAllBytes(destination(item).toPath()));
            assertNull(server.requests().get(0).header("Range"));
        }
    }

    @Test(timeout = 10000)
    public void existingDestinationIsNotOverwritten() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, BODY))) {
            DownloadItem item = newItem(server.url());
            TransferIntegrity.resetForFullDownload(item);
            Files.write(destination(item).toPath(), PREFIX);
            TestTask task = run(item);
            assertEquals(DownloadStatus.FAILED, item.getStatus());
            assertFalse(task.events.completed);
            assertTrue(task.events.error.contains("destination already exists"));
            assertArrayEquals(PREFIX, Files.readAllBytes(destination(item).toPath()));
            assertArrayEquals(BODY, Files.readAllBytes(partial(item).toPath()));
        }
    }

    @Test(timeout = 10000)
    public void checkpointFailureDoesNotPublishAFile() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, BODY))) {
            DownloadItem item = newItem(server.url());
            TransferIntegrity.resetForFullDownload(item);
            TestTask task = new TestTask(item, new Events());
            task.rejectCheckpoint = true;
            task.run();
            assertFailedWithoutDestination(item, task);
            assertEquals("fixture checkpoint failure", task.events.error);
            assertArrayEquals(BODY, Files.readAllBytes(partial(item).toPath()));
        }
    }

    @Test(timeout = 10000)
    public void rejectedPublicationRollsBackTheOutputAndKeepsTheWrittenCheckpoint() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(206, Arrays.copyOfRange(BODY, 4, 8),
                "Content-Range: bytes 4-7/8", "ETag: " + ETAG))) {
            DownloadItem item = recordedPrefix(server.url());
            TestTask task = new TestTask(item, new Events());
            task.rejectPublication = true;
            task.run();
            assertFailedWithoutDestination(item, task);
            assertEquals("fixture publication persistence failure", task.events.error);
            assertEquals(0, item.getFinishedTime());
            assertArrayEquals(BODY, Files.readAllBytes(partial(item).toPath()));

            item.setSegments(task.savedSegments);
            TestTask retry = run(item);
            assertTrue(item.getErrorMessage(), retry.events.completed);
            assertNull(item.getTempPath());
            assertArrayEquals(BODY, Files.readAllBytes(destination(item).toPath()));
            assertEquals(1, server.requests().size());
        }
    }

    @Test(timeout = 10000)
    public void wrongSuppliedSha256DoesNotPublishEvenACompleteTransfer() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, BODY))) {
            DownloadItem item = newItem(server.url());
            TransferIntegrity.resetForFullDownload(item);
            item.setExpectedSha256("00".repeat(32));
            TestTask task = run(item);
            assertFailedWithoutDestination(item, task);
            assertTrue(task.events.error.contains("SHA-256 mismatch"));
            assertArrayEquals(BODY, Files.readAllBytes(partial(item).toPath()));
        }
    }

    @Test(timeout = 10000)
    public void retryWillNotWriteThroughAStagingLinkToTheDestination() throws Exception {
        DownloadItem item = recordedPrefix(INACTIVE_URL);
        Files.createLink(destination(item).toPath(), partial(item).toPath());
        TestTask task = run(item);
        assertFalse(task.events.completed);
        assertEquals(DownloadStatus.FAILED, item.getStatus());
        assertTrue(task.events.error, task.events.error.contains("linked to the destination"));
        assertArrayEquals(PREFIX, Files.readAllBytes(destination(item).toPath()));
        assertArrayEquals(PREFIX, Files.readAllBytes(partial(item).toPath()));
    }

    @Test(timeout = 10000)
    public void dataUrlUsesTheSameVerifiedPublication() throws Exception {
        DownloadItem item = newItem("data:application/octet-stream,hello+%FF");
        File old = partial(item);
        Files.write(old.toPath(), PREFIX);
        TestTask task = run(item);
        assertTrue(item.getErrorMessage(), task.events.completed);
        assertArrayEquals(new byte[]{'h', 'e', 'l', 'l', 'o', '+', (byte) 0xff}, Files.readAllBytes(destination(item).toPath()));
        assertArrayEquals(PREFIX, Files.readAllBytes(old.toPath()));
        assertFalse(new File(task.createdStagingPath).exists());
        assertNull(item.getTempPath());
    }

    private DownloadItem newItem(String url) throws IOException {
        File dir = files.newFolder();
        DownloadItem item = new DownloadItem();
        item.setId("fixture-job");
        item.setFileName("fixture.bin");
        item.setUrl(url);
        item.setDestinationPath(new File(dir, "fixture.bin").getAbsolutePath());
        item.setTempPath(new File(dir, "fixture.bin.part").getAbsolutePath());
        return item;
    }

    private DownloadItem recordedPrefix(String url) throws IOException {
        DownloadItem item = newItem(url);
        item.setTotalBytes(BODY.length);
        item.setSupportsRange(true);
        item.setEtag(ETAG);
        item.setVerifiedRangeUrl(url);
        item.setDownloadedBytes(PREFIX.length);
        item.setSegments(List.of(segment(item, 0, 0, 7, PREFIX.length, DownloadStatus.PAUSED)));
        Files.write(partial(item).toPath(), PREFIX);
        return item;
    }

    private static DownloadSegment segment(DownloadItem item, int index, long start, long end, long position, DownloadStatus status) {
        return new DownloadSegment(item.getId(), index, start, end, position, status);
    }

    private static TestTask run(DownloadItem item) {
        TestTask task = new TestTask(item, new Events());
        task.run();
        return task;
    }

    private static File partial(DownloadItem item) {
        return new File(item.getTempPath());
    }

    private static File destination(DownloadItem item) {
        return new File(item.getDestinationPath());
    }

    private static void assertFailedWithoutDestination(DownloadItem item, TestTask task) {
        assertEquals(DownloadStatus.FAILED, item.getStatus());
        assertFalse(task.events.completed);
        assertEquals(item.getErrorMessage(), task.events.error);
        assertNotNull(task.events.error);
        assertFalse(destination(item).exists());
    }

    private static final class Events implements DownloadTask.TaskListener {
        String error;
        boolean completed;

        @Override public void onTaskUpdated(DownloadItem item) {}
        @Override public void onTaskCompleted(DownloadItem item) { completed = true; }
        @Override public void onTaskFailed(DownloadItem item, String reason) { error = reason; }
    }

    // Only the SQLite persistence calls are replaced. HTTP, staging, coverage, and
    // task transitions use DownloadTask/SegmentWorker exactly as in production.
    private static final class TestTask extends DownloadTask {
        private final Events events;
        List<DownloadSegment> savedSegments;
        int attemptsCreated;
        String createdStagingPath;
        boolean rejectPublication;
        boolean rejectCheckpoint;
        boolean pauseAfterWrite;

        TestTask(DownloadItem item, Events events) {
            this(item, events, RequestContext.anonymous());
        }

        TestTask(DownloadItem item, Events events, RequestContext context) {
            super(item, null, events, null, context);
            this.events = events;
        }

        @Override void persistAttempt(String previousTempPath) {
            attemptsCreated++;
            createdStagingPath = item.getTempPath();
        }

        @Override void persistPublication(String destination, String staging, long size, String mime, long finishedAt, String identity) throws IOException {
            assertNotEquals(DownloadStatus.COMPLETED, item.getStatus());
            assertEquals(staging, item.getTempPath());
            assertEquals(size, Files.size(new File(destination).toPath()));
            if (rejectPublication) throw new IOException("fixture publication persistence failure");
        }

        @Override void persistCheckpoint(String path, List<DownloadSegment> segments, long downloaded) throws IOException {
            assertEquals(item.getTempPath(), path);
            if (rejectCheckpoint) throw new IOException("fixture checkpoint failure");
            savedSegments = segments;
        }

        @Override boolean persistStatus(DownloadStatus status, String errorMessage) { return true; }

        @Override public void onBytesRead(int count) {
            super.onBytesRead(count);
            if (pauseAfterWrite) pause();
        }
    }
}

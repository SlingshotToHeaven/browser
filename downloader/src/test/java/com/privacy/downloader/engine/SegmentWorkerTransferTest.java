package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.privacy.downloader.engine.LoopbackHttpFixture.chunked;
import static com.privacy.downloader.engine.LoopbackHttpFixture.fixed;
import static org.junit.Assert.*;

public class SegmentWorkerTransferTest {
    private static final byte[] FIXTURE = {10, 20, 30, 40, 50, 60, 70, 80};
    private static final int OFFSET = 4;
    private static final String ETAG = "\"fixture-v1\"";
    private static final String RANGE_HEADER = "Content-Range: bytes 4-7/8";

    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    @Test(timeout = 10000)
    public void freshTransferAcceptsUnrangedResponseWithoutContentLength() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(chunked(200, FIXTURE))) {
            Transfer result = transfer(server, 0, -1);
            assertEquals(DownloadStatus.COMPLETED, result.segment.getStatus());
            assertEquals(FIXTURE.length, result.segment.getCurrentByte());
            assertArrayEquals(FIXTURE, result.bytes);
            assertNull(server.requests().get(0).header("Range"));
        }
    }

    @Test(timeout = 10000)
    public void validPartialResponseResumesAtTheSavedOffset() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(remainder(RANGE_HEADER, "ETag: " + ETAG))) {
            Transfer result = resume(server);
            assertTrue(result.segment.isComplete());
            assertEquals(FIXTURE.length, result.segment.getCurrentByte());
            assertArrayEquals(FIXTURE, result.bytes);
            assertConditionalRange(server);
        }
    }

    @Test(timeout = 10000)
    public void ignoredOrUnsatisfiableRangeLeavesSavedBytesUntouched() throws Exception {
        assertRejected(fixed(200, FIXTURE, "ETag: " + ETAG));
        assertRejected(fixed(416, new byte[0], "Content-Range: bytes */8"));
    }

    @Test(timeout = 10000)
    public void wrongMissingAndAmbiguousContentRangesLeaveSavedBytesUntouched() throws Exception {
        String[] bad = {"bytes 0-3/8", "bytes 4-6/8", "bytes 4-7/9", "bytes 4-7/*", "bytes 4-7/7",
                "bytes 4-7/99999999999999999999", "items 4-7/8"};
        for (String value : bad) assertRejected(remainder("Content-Range: " + value, "ETag: " + ETAG));
        assertRejected(remainder("ETag: " + ETAG));
        assertRejected(remainder(RANGE_HEADER, "Content-Range: bytes 0-3/8", "ETag: " + ETAG));
    }

    @Test(timeout = 10000)
    public void changedWeakAndMissingValidatorsLeaveSavedBytesUntouched() throws Exception {
        assertRejected(remainder(RANGE_HEADER, "ETag: \"fixture-v2\""));
        assertRejected(remainder(RANGE_HEADER, "ETag: W/" + ETAG));
        assertRejected(remainder(RANGE_HEADER));
    }

    @Test(timeout = 10000)
    public void encodedOrWrongLengthResponseDoesNotWrite() throws Exception {
        assertRejected(remainder(RANGE_HEADER, "ETag: " + ETAG, "Content-Encoding: gzip"));
        assertRejected(fixed(206, Arrays.copyOfRange(FIXTURE, OFFSET, OFFSET + 2), RANGE_HEADER, "ETag: " + ETAG));
    }

    @Test(timeout = 10000)
    public void redirectDoesNotCarryTheValidatorToAnotherResource() throws Exception {
        assertRejected(fixed(302, new byte[0], "Location: /another-resource"));
    }

    @Test(timeout = 10000)
    public void shortBodyCannotCompleteAndCountsOnlyBytesActuallyWritten() throws Exception {
        byte[] suffix = Arrays.copyOfRange(FIXTURE, OFFSET, OFFSET + 2);
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(chunked(206, suffix, RANGE_HEADER, "ETag: " + ETAG))) {
            Transfer result = resume(server);
            assertEquals(DownloadStatus.FAILED, result.segment.getStatus());
            assertFalse(result.segment.isComplete());
            assertEquals(OFFSET + suffix.length, result.segment.getCurrentByte());
            assertArrayEquals(Arrays.copyOf(FIXTURE, OFFSET + suffix.length), result.bytes);
            assertTrue(result.errors.get(0).contains("before all expected bytes"));
            assertConditionalRange(server);
        }
    }

    @Test(timeout = 10000)
    public void overlongChunkedBodyIsNotClippedAndMarkedComplete() throws Exception {
        byte[] suffix = Arrays.copyOf(Arrays.copyOfRange(FIXTURE, OFFSET, FIXTURE.length), 5);
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(chunked(206, suffix, RANGE_HEADER, "ETag: " + ETAG))) {
            Transfer result = resume(server);
            assertEquals(DownloadStatus.FAILED, result.segment.getStatus());
            assertFalse(result.segment.isComplete());
            assertTrue(result.bytes.length < FIXTURE.length);
            assertArrayEquals(Arrays.copyOf(FIXTURE, result.bytes.length), result.bytes);
            assertTrue(result.errors.get(0).contains("more bytes"));
        }
    }

    @Test(timeout = 10000)
    public void unsolicitedPartialResponseIsNotAFullDownload() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(remainder(RANGE_HEADER, "ETag: " + ETAG))) {
            Transfer result = transfer(server, 0, -1);
            assertEquals(DownloadStatus.FAILED, result.segment.getStatus());
            assertEquals(0, result.bytes.length);
            assertNull(server.requests().get(0).header("Range"));
        }
    }

    private static LoopbackHttpFixture.Response remainder(String... headers) {
        return fixed(206, Arrays.copyOfRange(FIXTURE, OFFSET, FIXTURE.length), headers);
    }

    private void assertRejected(LoopbackHttpFixture.Response response) throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(response)) {
            Transfer result = resume(server);
            assertEquals(DownloadStatus.FAILED, result.segment.getStatus());
            assertEquals(OFFSET, result.segment.getCurrentByte());
            assertArrayEquals(Arrays.copyOf(FIXTURE, OFFSET), result.bytes);
            assertEquals(1, result.errors.size());
            assertConditionalRange(server);
        }
    }

    private static void assertConditionalRange(LoopbackHttpFixture server) throws Exception {
        LoopbackHttpFixture.Request request = server.requests().get(0);
        assertEquals("bytes=4-7", request.header("Range"));
        assertEquals(ETAG, request.header("If-Range"));
        assertEquals("identity", request.header("Accept-Encoding"));
    }

    private Transfer resume(LoopbackHttpFixture server) throws Exception {
        return transfer(server, OFFSET, FIXTURE.length - 1);
    }

    private Transfer transfer(LoopbackHttpFixture server, int offset, long endByte) throws Exception {
        File partial = files.newFile();
        Files.write(partial.toPath(), Arrays.copyOf(FIXTURE, offset));
        DownloadItem item = new DownloadItem();
        item.setId("fixture-job");
        item.setFileName("fixture.bin");
        item.setTempPath(partial.getAbsolutePath());
        item.setUrl(server.url());
        if (endByte >= 0) {
            item.setTotalBytes(FIXTURE.length);
            item.setSupportsRange(true);
            item.setEtag(ETAG);
            item.setVerifiedRangeUrl(server.url());
        }
        DownloadSegment segment = new DownloadSegment(item.getId(), 0, 0, endByte, offset, DownloadStatus.PAUSED);
        List<String> errors = new ArrayList<>();
        new SegmentWorker(item, segment, new SegmentWorker.ProgressCallback() {
            @Override public void onBytesRead(int count) {}
            @Override public boolean isStopRequested() { return false; }
            @Override public SpeedLimiter getSpeedLimiter() { return null; }
            @Override public void onIntegrityFailure(String reason) { errors.add(reason); }
        }).run();
        return new Transfer(segment, Files.readAllBytes(partial.toPath()), errors);
    }

    private static final class Transfer {
        final DownloadSegment segment;
        final byte[] bytes;
        final List<String> errors;

        Transfer(DownloadSegment segment, byte[] bytes, List<String> errors) {
            this.segment = segment;
            this.bytes = bytes;
            this.errors = errors;
        }
    }
}

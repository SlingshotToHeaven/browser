package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.ScopedHttp;
import com.privacy.downloader.util.DownloadIntegrityVerifier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;

public class SegmentWorker implements Runnable {

    public interface ProgressCallback {
        void onBytesRead(int bytes);
        boolean isStopRequested();
        SpeedLimiter getSpeedLimiter();
        void onIntegrityFailure(String reason);
    }

    private final DownloadItem item;
    private final DownloadSegment segment;
    private final ProgressCallback callback;
    private final RequestContext context;
    private final SpeedLimiter limiter;
    private volatile boolean cancelled = false;
    private volatile boolean paused = false;

    private static final int BUFFER_SIZE = 65536; // 64 KB chunks for high-throughput downloads
    private static final int DEFAULT_MAX_RETRIES = 8;

    public SegmentWorker(DownloadItem item, DownloadSegment segment, ProgressCallback callback) {
        this(item, segment, callback, RequestContext.anonymous());
    }

    SegmentWorker(DownloadItem item, DownloadSegment segment, ProgressCallback callback, RequestContext context) {
        this.item = item;
        this.segment = segment;
        this.callback = callback;
        this.context = context;
        this.limiter = callback == null ? null : callback.getSpeedLimiter();
    }

    private int resolveMaxRetries() {
        try {
            DownloadEngine engine = DownloadEngine.getInstance(null);
            if (engine != null) {
                return engine.getMaxRetries();
            }
        } catch (Throwable ignored) {}
        return DEFAULT_MAX_RETRIES;
    }

    public void pause() {
        this.paused = true;
        if (limiter != null) limiter.wakeWaiters();
    }

    public void cancel() {
        this.cancelled = true;
        if (limiter != null) limiter.wakeWaiters();
    }

    @Override
    public void run() {
        if (segment.isComplete()) return;

        segment.setStatus(DownloadStatus.RUNNING);
        int retryCount = 0;
        int maxRetries = resolveMaxRetries();

        while (retryCount <= maxRetries && !shouldStop()) {
            try {
                executeTransfer();
                if (segment.isComplete()) return;
            } catch (TransferIntegrity.Failure e) {
                segment.setStatus(DownloadStatus.FAILED);
                if (callback != null) callback.onIntegrityFailure(e.getMessage());
                return;
            } catch (Exception e) {
                android.util.Log.e("SegmentWorker", "Segment " + segment.getIndex() + " retry " + retryCount + " error: " + e.getMessage());
                if (shouldStop()) break;
                retryCount++;
                if (retryCount > maxRetries) {
                    segment.setStatus(DownloadStatus.FAILED);
                    return;
                }
                // Backoff: 1s, 2s, 3s, 4s, capped at 5s
                try {
                    long backoffMs = Math.min(5000L, 1000L * retryCount);
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        segment.setStatus(cancelled ? DownloadStatus.CANCELLED : DownloadStatus.PAUSED);
    }

    private void executeTransfer() throws IOException {
        long start = segment.getCurrentByte();
        long end = segment.getEndByte();
        long total;
        String url;
        boolean ranged;
        String etag;
        synchronized (item) {
            total = item.getTotalBytes();
            ranged = TransferIntegrity.hasVerifiedRanges(item);
            url = ranged ? item.getVerifiedRangeUrl() : item.getUrl();
            etag = item.getEtag();
        }
        if (start < segment.getStartByte() || start < 0 || (end != -1 && end < start)) {
            throw TransferIntegrity.startOver("The saved byte range is invalid.");
        }
        if (ranged && (end < start || end >= total)) {
            throw TransferIntegrity.startOver("The requested range does not fit the verified file size.");
        }
        if (!ranged && (start != 0 || segment.getStartByte() != 0 || (end != -1 && end != total - 1))) {
            throw TransferIntegrity.startOver("The server did not provide a verifiable way to resume this download.");
        }
        File partial = new File(item.getTempPath());
        if (!partial.isFile() || (start > segment.getStartByte() && partial.length() < start)) {
            throw TransferIntegrity.startOver("The saved partial file is missing or truncated.");
        }

        try (ScopedHttp.Response response = HttpDownloadRequest.open(context, url,
                ranged ? "bytes=" + start + "-" + end : null, ranged ? etag : null, this::shouldStop)) {
            HttpURLConnection conn = response.connection;
            long expectedBytes = validateResponse(conn, ranged, start, end, total, etag);
            if (shouldStop()) return;
            // WRITE without CREATE: never recreate a missing partial under saved checkpoints.
            try (InputStream in = limitedBody(conn); FileChannel out = FileChannel.open(partial.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                long length = out.size();
                if ((start > segment.getStartByte() && length < start) || (!ranged && length != 0)) {
                    throw TransferIntegrity.startOver("The partial file no longer matches its saved position.");
                }
                out.position(start);
                copyResponse(conn, in, out, expectedBytes, start == 0);
            }
        }
    }

    private InputStream limitedBody(HttpURLConnection conn) throws IOException {
        InputStream body = conn.getInputStream();
        return limiter == null ? body : limiter.limit(body, this::shouldStop);
    }

    private static long validateResponse(HttpURLConnection conn, boolean ranged, long start, long end, long total, String etag)
            throws IOException {
        int status = conn.getResponseCode();
        if (ranged && (status == HttpURLConnection.HTTP_OK || status == 416)) {
            throw TransferIntegrity.startOver("The server did not honor the requested byte range.");
        }
        if (!ranged && status == HttpURLConnection.HTTP_PARTIAL) {
            throw new TransferIntegrity.Failure("The server returned partial content for a full download.");
        }
        if (status != (ranged ? HttpURLConnection.HTTP_PARTIAL : HttpURLConnection.HTTP_OK)) {
            throw new IOException("Unexpected HTTP response: " + status);
        }
        if (!TransferIntegrity.identityEncoding(HttpDownloadRequest.singleHeader(conn, "Content-Encoding"))) {
            throw new TransferIntegrity.Failure("The server returned an unexpected content encoding.");
        }
        String range = HttpDownloadRequest.singleHeader(conn, "Content-Range");
        if (ranged) {
            if (!etag.equals(HttpDownloadRequest.singleHeader(conn, "ETag"))) {
                throw TransferIntegrity.startOver("The server's file no longer matches the saved bytes.");
            }
            if (TransferIntegrity.matchingRangeTotal(range, start, end) != total || HttpDownloadRequest.isMultipartRange(conn)) {
                throw TransferIntegrity.startOver("The server returned a different byte range or file size.");
            }
        } else if (range != null) {
            throw new TransferIntegrity.Failure("The full download response unexpectedly has a byte range.");
        }
        long expected = end == -1 ? -1 : end - start + 1;
        long declared = HttpDownloadRequest.contentLength(conn);
        if (expected >= 0 && declared >= 0 && expected != declared) {
            throw new TransferIntegrity.Failure("The response length does not match the expected bytes.");
        }
        return expected >= 0 ? expected : declared;
    }

    private void copyResponse(HttpURLConnection conn, InputStream in, FileChannel out, long remaining, boolean firstChunk)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (!shouldStop()) {
            // The limiter (or the network) can split even a short HTML signature.
            int count = firstChunk ? readPrefix(in, buffer, remaining) : in.read(buffer);
            if (shouldStop()) return;
            if (count == -1) {
                if (remaining > 0) throw new TransferIntegrity.Failure("The response ended before all expected bytes arrived.");
                if (firstChunk) throw new TransferIntegrity.Failure("The server returned an empty file.");
                if (!shouldStop()) segment.setStatus(DownloadStatus.COMPLETED);
                return;
            }
            if (count == 0) continue;
            if (firstChunk && DownloadIntegrityVerifier.detectHtmlMasquerade(item.getFileName(), conn.getContentType(), buffer, count)) {
                throw new TransferIntegrity.Failure("The server returned an HTML page instead of the file.");
            }
            firstChunk = false;
            if (remaining >= 0 && count > remaining) {
                throw new TransferIntegrity.Failure("The server sent more bytes than requested.");
            }
            boolean last = count == remaining;
            if (last && in.read() != -1) {
                // Do not record the last chunk as complete until the HTTP body actually ends.
                throw new TransferIntegrity.Failure("The server sent more bytes than requested.");
            }
            if (shouldStop()) return;
            ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
            while (bytes.hasRemaining()) out.write(bytes);
            synchronized (segment) {
                segment.addProgress(count);
                if (last) segment.setStatus(DownloadStatus.COMPLETED);
            }
            if (callback != null) callback.onBytesRead(count);
            if (last) return;
            if (remaining >= 0) remaining -= count;
        }
    }

    private int readPrefix(InputStream in, byte[] buffer, long remaining) throws IOException {
        int limit = (int) (remaining > 0 ? Math.min(512, remaining) : 512);
        int length = 0;
        while (length < limit && !shouldStop()) {
            int count = in.read(buffer, length, limit - length);
            if (count == -1) break;
            length += count;
        }
        return length == 0 ? -1 : length;
    }

    private boolean shouldStop() {
        return paused || cancelled || Thread.currentThread().isInterrupted() || (callback != null && callback.isStopRequested());
    }
}

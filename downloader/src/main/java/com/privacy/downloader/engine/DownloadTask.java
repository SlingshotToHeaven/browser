package com.privacy.downloader.engine;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.ScopedHttp;
import com.privacy.downloader.util.DownloadIntegrityVerifier;
import com.privacy.downloader.util.PublishedFileIdentity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class DownloadTask implements Runnable, SegmentWorker.ProgressCallback {

    public interface TaskListener {
        void onTaskUpdated(DownloadItem item);
        // The queue does not use these as an ownership or publication signal.
        default void onTaskCompleted(DownloadItem item) {}
        default void onTaskFailed(DownloadItem item, String error) {}
    }

    protected final DownloadItem item;
    protected final DownloadDbHelper dbHelper;
    protected final TaskListener listener;
    protected final SpeedLimiter speedLimiter;
    protected final RequestContext requestContext;
    private RequestContext rangeContext;

    private final String attemptToken;
    private volatile DownloadStatus stopState;

    private final AtomicReference<String> integrityFailure = new AtomicReference<>();
    private ExecutorService workerPool;

    private final AtomicLong transferredSinceLastCheck = new AtomicLong(0);
    private long lastSpeedCheckTime = System.currentTimeMillis();
    private long smoothedSpeed = -1;

    public DownloadTask(DownloadItem item, DownloadDbHelper dbHelper, TaskListener listener, SpeedLimiter globalLimiter) {
        this(item, dbHelper, listener, globalLimiter, RequestContext.anonymous());
    }

    DownloadTask(DownloadItem item, DownloadDbHelper dbHelper, TaskListener listener, SpeedLimiter globalLimiter,
                 RequestContext context) {
        this.requestContext = context.forTarget(item.getUrl());
        // Only the current probe may authorize hops for ranges addressed directly to its final URL.
        this.rangeContext = RequestContext.anonymous();
        this.item = item;
        this.attemptToken = item.getAttemptToken();
        this.dbHelper = dbHelper;
        this.listener = listener;
        this.speedLimiter = globalLimiter;
    }

    public DownloadItem getItem() {
        return item;
    }

    public final void pause() {
        stopFor(DownloadStatus.PAUSED, null);
    }

    public final void cancel() {
        stopFor(DownloadStatus.CANCELLED, null);
    }

    /** The controller already persisted the stop; worker cleanup must not write a competing state. */
    final synchronized void stopFor(DownloadStatus state, String reason) {
        if (stopState == DownloadStatus.CANCELLED || item.getStatus() == DownloadStatus.COMPLETED) return;
        boolean first = stopState == null;
        stopState = state;
        item.setStatus(state);
        item.setErrorMessage(reason);
        item.setSpeedBytesPerSec(0);
        if (first) {
            if (speedLimiter != null) speedLimiter.wakeWaiters();
            onStopRequested();
        }
    }

    protected void onStopRequested() {
        if (workerPool != null) workerPool.shutdownNow();
    }

    @Override
    public void run() {
        File partial = null;
        boolean completed = false;
        try {
            if (isStopRequested()) {
                finishStopped(null);
            } else if (item.getUrl() != null && item.getUrl().startsWith("data:")) {
                completed = downloadDataUrl();
            } else {
                File destination = new File(item.getDestinationPath()).getAbsoluteFile();
                partial = preparePartial(destination);
                if (!isStopRequested()) {
                    markRunning(null);
                    runSegments(partial, item.getSegments());
                }
                if (stopState != null) {
                    finishStopped(partial);
                } else {
                    // A sparse file's length alone doesn't prove its bytes were written.
                    saveProgressToDb(partial);
                    String failure = integrityFailure.get();
                    if (failure != null) {
                        fail(failure);
                    } else if (!TransferIntegrity.readyToPublish(item, partial)) {
                        fail("Incomplete transfer or connection lost");
                    } else {
                        completed = publishOutput(partial, item.getDownloadedBytes(), item.getMimeType());
                        if (!completed) finishStopped(partial);
                    }
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (stopState != null) {
                try {
                    finishStopped(partial);
                } catch (IOException checkpointError) {
                    fail(checkpointError.getMessage());
                }
            } else {
                fail(e.getMessage() != null ? e.getMessage() : "Download interrupted");
            }
        }
        if (completed && listener != null) listener.onTaskCompleted(item);
    }

    private File preparePartial(File destination) throws IOException {
        String savedPath = item.getTempPath();
        File existing = savedPath == null ? null : new File(savedPath);
        if (TransferIntegrity.reuseCheckpoint(item, existing)) {
            // A process kill between linking and committing may have left both
            // names. Never resume writes through a path linked to the output.
            if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(existing.toPath(), destination.toPath())) {
                throw TransferIntegrity.startOver("The staged file is already linked to the destination.");
            }
            return existing;
        }

        // A new attempt always gets a different file. Never mark old bytes as verified
        // by refreshing metadata for a legacy, missing, or truncated checkpoint.
        long expectedLength = item.getTotalBytes();
        TransferIntegrity.clearRepresentation(item);
        if (!item.isFullDownloadOnly()) {
            fetchServerMetadata();
            if (item.getTotalBytes() < 0 && expectedLength > 0) item.setTotalBytes(expectedLength);
        }
        if (isStopRequested()) return null;
        File fresh = File.createTempFile("download-", ".part", outputDirectory());
        try {
            item.setSegments(createSegments());
            item.setDownloadedBytes(0);
            bindStagingFile(fresh);
        } catch (IOException | RuntimeException e) {
            fresh.delete();
            throw e;
        }
        return fresh;
    }

    private void runSegments(File partial, List<DownloadSegment> segments) throws IOException, InterruptedException {
        ExecutorService pool = null;
        try {
            synchronized (this) {
                if (isStopRequested()) return;
                pool = Executors.newFixedThreadPool(segments.size());
                workerPool = pool;
                for (DownloadSegment segment : segments) {
                    if (!segment.isComplete()) pool.execute(new SegmentWorker(item, segment, this,
                            TransferIntegrity.hasVerifiedRanges(item) ? rangeContext : requestContext));
                }
                pool.shutdown();
            }
            lastSpeedCheckTime = System.currentTimeMillis();
            transferredSinceLastCheck.set(0);
            smoothedSpeed = -1;
            long lastDbSaveTime = System.currentTimeMillis();
            while (!isStopRequested() && !pool.awaitTermination(600, TimeUnit.MILLISECONDS)) {
                calculateSpeedAndNotify();
                long now = System.currentTimeMillis();
                if (now - lastDbSaveTime >= 2000) {
                    saveProgressToDb(partial);
                    lastDbSaveTime = now;
                }
            }
        } finally {
            // Even partial worker submission must drain before the queue can give this checkpoint to a retry.
            if (pool != null) drain(pool);
        }
    }

    private static void drain(ExecutorService pool) {
        pool.shutdownNow();
        boolean interrupted = false;
        while (!pool.isTerminated()) {
            try {
                pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void finishStopped(File partial) throws IOException {
        // Cancellation retains the last safe checkpoint until explicit deletion.
        if (stopState != null && stopState != DownloadStatus.CANCELLED && partial != null) saveProgressToDb(partial);
    }

    protected final void fail(String reason) {
        String error = reason == null ? "Download interrupted." : reason;
        if (reportWorkerStatus(DownloadStatus.FAILED, error) && listener != null) listener.onTaskFailed(item, error);
    }

    private boolean downloadDataUrl() throws IOException {
        markRunning(null);
        String url = item.getUrl();
        int commaIdx = url.indexOf(',');
        if (commaIdx < 0) throw new IOException("Malformed data URL: missing comma.");
        String metadata = url.substring(5, commaIdx);
        byte[] data = decodeDataBytes(url.substring(commaIdx + 1));
        byte[] decoded = metadata.contains(";base64")
                ? android.util.Base64.decode(data, android.util.Base64.DEFAULT) : data;

        File temp = File.createTempFile("download-", ".data.part", outputDirectory());
        boolean published = false;
        try {
            bindStagingFile(temp);
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(decoded);
            }
            published = publishOutput(temp, decoded.length, item.getMimeType());
            return published;
        } finally {
            if (!published) temp.delete();
        }
    }

    private static byte[] decodeDataBytes(String data) throws IOException {
        byte[] encoded = data.getBytes(StandardCharsets.UTF_8);
        if (data.indexOf('%') < 0) return encoded;
        ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length);
        for (int i = 0; i < encoded.length; i++) {
            int value = encoded[i] & 0xff;
            // This is a URI payload, not form encoding: keep literal '+' and binary octets.
            if (value == '%') {
                if (i + 2 >= encoded.length) throw new IOException("Malformed data URL escape.");
                int hi = Character.digit((char) encoded[++i], 16);
                int lo = Character.digit((char) encoded[++i], 16);
                if (hi < 0 || lo < 0) throw new IOException("Malformed data URL escape.");
                value = hi * 16 + lo;
            }
            out.write(value);
        }
        return out.toByteArray();
    }

    private void fetchServerMetadata() throws IOException {
        try (ScopedHttp.Response response = HttpDownloadRequest.open(requestContext, item.getUrl(), "bytes=0-0", null,
                this::isStopRequested)) {
            HttpURLConnection conn = response.connection;
            int status = response.status;
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) return;
            boolean identity;
            long length;
            String range;
            String etag;
            try {
                identity = TransferIntegrity.identityEncoding(HttpDownloadRequest.singleHeader(conn, "Content-Encoding"));
                length = HttpDownloadRequest.contentLength(conn);
                range = HttpDownloadRequest.singleHeader(conn, "Content-Range");
                etag = HttpDownloadRequest.singleHeader(conn, "ETag");
            } catch (TransferIntegrity.Failure malformedProbe) {
                // The actual full response will be verified independently.
                return;
            }
            if (identity && status == HttpURLConnection.HTTP_PARTIAL && (length == 1 || length == -1)) {
                long total = TransferIntegrity.matchingRangeTotal(range, 0, 0);
                if (total > 0) {
                    item.setTotalBytes(total);
                    if (TransferIntegrity.isStrongEtag(etag) && !HttpDownloadRequest.isMultipartRange(conn)) {
                        item.setSupportsRange(true);
                        item.setEtag(etag);
                        item.setVerifiedRangeUrl(response.url());
                        rangeContext = response.context;
                    }
                }
            } else if (identity && status == HttpURLConnection.HTTP_OK && range == null) {
                item.setTotalBytes(length);
            }
            String disposition = conn.getHeaderField("Content-Disposition");
            item.setLastModified(conn.getHeaderField("Last-Modified"));
            boolean attachment = disposition != null && disposition.toLowerCase(Locale.ROOT).contains("attachment");
            if (!attachment && DownloadIntegrityVerifier.detectHtmlMasquerade(item.getFileName(), conn.getContentType(), null, 0)) {
                throw new HtmlMasqueradeException("The server returned an HTML page instead of the file.");
            }
        }
    }

    private List<DownloadSegment> createSegments() {
        List<DownloadSegment> list = new ArrayList<>();
        long total = item.getTotalBytes();
        int count = Math.min(TransferIntegrity.MAX_SEGMENTS, item.getNumSegments());

        // Single segment if server rejects ranges or small file (< 1MB) or unknown size
        if (!TransferIntegrity.hasVerifiedRanges(item) || total < 1024 * 1024 || count <= 1) {
            item.setNumSegments(1);
            list.add(new DownloadSegment(item.getId(), 0, 0, total > 0 ? total - 1 : -1, 0, DownloadStatus.PENDING));
            return list;
        }

        item.setNumSegments(count);
        long chunkSize = total / count;
        for (int i = 0; i < count; i++) {
            long start = i * chunkSize;
            long end = (i == count - 1) ? (total - 1) : ((i + 1) * chunkSize - 1);
            list.add(new DownloadSegment(item.getId(), i, start, end, start, DownloadStatus.PENDING));
        }
        return list;
    }

    protected final File outputDirectory() throws IOException {
        File directory = new File(item.getDestinationPath()).getAbsoluteFile().getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())) {
            throw new IOException("Could not create the download directory.");
        }
        return directory;
    }

    protected final synchronized void bindStagingFile(File file) throws IOException {
        if (isStopRequested()) throw new InterruptedIOException("Download stopped before publication.");
        String previous = item.getTempPath();
        item.setTempPath(file.getAbsolutePath());
        try {
            persistAttempt(previous);
        } catch (IOException | RuntimeException e) {
            item.setTempPath(previous);
            throw e;
        }
    }

    protected final boolean publishOutput(File staged, long size, String mime) throws IOException {
        if (isStopRequested()) return false;
        String source = staged.getAbsolutePath();
        String destination = item.getDestinationPath();
        if (!source.equals(item.getTempPath())) throw new IOException("The download staging path is no longer current.");
        try (FilePublication publication = FilePublication.open(staged.toPath(), new File(destination).toPath(), size,
                item.getExpectedSha256(), this::isStopRequested)) {
            // A potentially long copy/hash runs without this lock. The terminal
            // DB update is ordered with pause/cancel, and checks the current path.
            synchronized (this) {
                if (isStopRequested()) return false;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download stopped before publication.");
                String identity = dbHelper == null ? null : PublishedFileIdentity.capture(destination, size);
                publication.verifyEntry(size);
                long finishedAt = System.currentTimeMillis();
                persistPublication(destination, source, size, mime, finishedAt, identity);
                publication.commit();
                item.setDownloadedBytes(size);
                item.setTotalBytes(size);
                item.setSpeedBytesPerSec(0);
                item.setMimeType(mime);
                item.setTempPath(null);
                item.setFinishedTime(finishedAt);
                item.setErrorMessage(null);
                item.setStatus(DownloadStatus.COMPLETED);
                return true;
            }
        } catch (InterruptedIOException e) {
            if (isStopRequested()) return false;
            throw e;
        }
    }

    private void calculateSpeedAndNotify() {
        long now = System.currentTimeMillis();
        long timeDelta = now - lastSpeedCheckTime;
        if (timeDelta >= 400) {
            long bytesDelta = transferredSinceLastCheck.getAndSet(0);
            long instantSpeed = (bytesDelta * 1000) / Math.max(1, timeDelta);

            if (bytesDelta > 0) {
                if (smoothedSpeed < 0) {
                    smoothedSpeed = instantSpeed;
                } else {
                    smoothedSpeed = (long) (0.65 * smoothedSpeed + 0.35 * instantSpeed);
                }
            } else {
                smoothedSpeed = (long) (smoothedSpeed * 0.7);
            }

            item.setSpeedBytesPerSec(smoothedSpeed);
            lastSpeedCheckTime = now;

            // Recalculate downloaded bytes from segments
            long totalDownloaded = 0;
            if (item.getSegments() != null) {
                for (DownloadSegment s : item.getSegments()) {
                    totalDownloaded += s.getDownloadedBytes();
                }
            }
            item.setDownloadedBytes(totalDownloaded);

            if (listener != null) {
                listener.onTaskUpdated(item);
            }
        }
    }

    private void saveProgressToDb(File partial) throws IOException {
        List<DownloadSegment> checkpoint = new ArrayList<>();
        long downloaded = 0;
        for (DownloadSegment segment : item.getSegments()) {
            synchronized (segment) {
                checkpoint.add(new DownloadSegment(segment.getTaskId(), segment.getIndex(), segment.getStartByte(),
                        segment.getEndByte(), segment.getCurrentByte(), segment.getStatus()));
                downloaded += segment.getDownloadedBytes();
            }
        }
        // Snapshot positions before syncing. A worker can write ahead of a saved
        // checkpoint, but a checkpoint must not lead the bytes persisted on disk.
        try (FileChannel file = FileChannel.open(partial.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            file.force(true);
        }
        persistCheckpoint(partial.getAbsolutePath(), checkpoint, downloaded);
        item.setDownloadedBytes(downloaded);
    }

    void persistAttempt(String previousTempPath) throws IOException {
        if (!dbHelper.saveWorkerStaging(item, attemptToken, previousTempPath)) throw new IOException("The download attempt is no longer current.");
    }

    void persistPublication(String destination, String staging, long size, String mime, long finishedAt, String identity) throws IOException {
        if (!dbHelper.recordPublication(item.getId(), attemptToken, destination, staging, size, mime, finishedAt, identity)) {
            throw new IOException("The download attempt is no longer current.");
        }
    }

    void persistCheckpoint(String tempPath, List<DownloadSegment> checkpoint, long downloaded) throws IOException {
        if (!dbHelper.saveCheckpoint(item.getId(), attemptToken, tempPath, checkpoint, downloaded)) {
            throw new IOException("The download attempt is no longer current.");
        }
    }

    protected final void markRunning(String message) throws IOException {
        if (!reportWorkerStatus(DownloadStatus.RUNNING, message)) {
            throw new InterruptedIOException("The download attempt is no longer active.");
        }
    }

    private synchronized boolean reportWorkerStatus(DownloadStatus status, String errorMessage) {
        if (stopState != null || item.getStatus() == DownloadStatus.COMPLETED || !persistStatus(status, errorMessage)) return false;
        item.setStatus(status);
        item.setErrorMessage(errorMessage);
        if (status == DownloadStatus.FAILED) item.setSpeedBytesPerSec(0);
        if (listener != null) listener.onTaskUpdated(item);
        return true;
    }

    boolean persistStatus(DownloadStatus status, String errorMessage) {
        return dbHelper.updateWorkerStatus(item.getId(), attemptToken, status, errorMessage);
    }

    void persistProgress(long bytes) throws IOException {
        if (!dbHelper.updateWorkerProgress(item.getId(), attemptToken, bytes)) {
            throw new InterruptedIOException("The download attempt is no longer active.");
        }
    }

    @Override
    public void onBytesRead(int bytes) {
        transferredSinceLastCheck.addAndGet(bytes);
    }

    @Override
    public boolean isStopRequested() {
        return stopState != null || integrityFailure.get() != null;
    }

    protected final InputStream limitBody(InputStream body) {
        return speedLimiter == null ? body : speedLimiter.limit(body, this::isStopRequested);
    }

    @Override
    public SpeedLimiter getSpeedLimiter() {
        return speedLimiter;
    }

    @Override
    public void onIntegrityFailure(String reason) {
        if (integrityFailure.compareAndSet(null, reason) && speedLimiter != null) speedLimiter.wakeWaiters();
    }
}

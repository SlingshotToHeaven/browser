package com.privacy.downloader.engine;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.util.DownloadIntegrityVerifier;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DownloadTask implements Runnable, SegmentWorker.ProgressCallback {

    public interface TaskListener {
        void onTaskUpdated(DownloadItem item);
        void onTaskCompleted(DownloadItem item);
        void onTaskFailed(DownloadItem item, String error);
    }

    protected final DownloadItem item;
    protected final DownloadDbHelper dbHelper;
    protected final TaskListener listener;
    protected final SpeedLimiter speedLimiter;

    protected volatile boolean isPaused = false;
    protected volatile boolean isCancelled = false;

    private final List<SegmentWorker> workers = new ArrayList<>();
    private final List<Future<?>> workerFutures = new ArrayList<>();
    private ExecutorService workerPool;

    private final AtomicLong transferredSinceLastCheck = new AtomicLong(0);
    private long lastSpeedCheckTime = System.currentTimeMillis();
    private long smoothedSpeed = -1;

    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    public DownloadTask(DownloadItem item, DownloadDbHelper dbHelper, TaskListener listener, SpeedLimiter globalLimiter) {
        this.item = item;
        this.dbHelper = dbHelper;
        this.listener = listener;
        this.speedLimiter = globalLimiter;
    }

    public DownloadItem getItem() {
        return item;
    }

    public synchronized void pause() {
        isPaused = true;
        for (SegmentWorker w : workers) {
            w.pause();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    public synchronized void cancel() {
        isCancelled = true;
        for (SegmentWorker w : workers) {
            w.cancel();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    public synchronized void updateUrl(String newUrl) {
        item.setUrl(newUrl);
        dbHelper.updateUrl(item.getId(), newUrl);
    }

    @Override
    public void run() {
        try {
            if (item.getUrl() != null && item.getUrl().startsWith("data:")) {
                downloadDataUrl();
                return;
            }

            // Step 1: Query server headers (HEAD/GET range 0-0) if metadata is missing
            if (item.getTotalBytes() <= 0) {
                fetchServerMetadata();
            }

            if (isStopRequested()) return;

            // Step 2: Ensure destination directory and temp file exist
            File destFile = new File(item.getDestinationPath());
            File parentDir = destFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            File tempFile = new File(item.getTempPath());
            if (!tempFile.exists()) {
                tempFile.createNewFile();
            }

            // Step 3: Setup segments
            List<DownloadSegment> segments = item.getSegments();
            if (segments == null || segments.isEmpty()) {
                segments = createSegments();
                item.setSegments(segments);
                dbHelper.saveSegments(item.getId(), segments);
            }

            // Pre-allocate disk space if total length is known and range is supported
            if (item.getTotalBytes() > 0 && item.isSupportsRange()) {
                try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                    if (raf.length() < item.getTotalBytes()) {
                        raf.setLength(item.getTotalBytes());
                    }
                } catch (Exception e) {
                    android.util.Log.w("DownloadTask", "Pre-allocation skipped: " + e.getMessage());
                }
            }

            if (isStopRequested()) return;

            updateStatus(DownloadStatus.RUNNING, null);

            // Step 4: Launch worker pool
            int threadCount = Math.max(1, segments.size());
            workerPool = Executors.newFixedThreadPool(threadCount);
            workers.clear();
            workerFutures.clear();

            for (DownloadSegment seg : segments) {
                if (!seg.isComplete()) {
                    SegmentWorker worker = new SegmentWorker(item, seg, this);
                    workers.add(worker);
                    workerFutures.add(workerPool.submit(worker));
                }
            }

            // Step 5: Progress monitor loop
            lastSpeedCheckTime = System.currentTimeMillis();
            transferredSinceLastCheck.set(0);
            smoothedSpeed = -1;
            long lastDbSaveTime = System.currentTimeMillis();

            while (!isAllWorkersDone() && !isStopRequested()) {
                Thread.sleep(600);
                calculateSpeedAndNotify();
                long now = System.currentTimeMillis();
                if (now - lastDbSaveTime >= 2000) {
                    lastDbSaveTime = now;
                    saveProgressToDb();
                }
            }

            // Wait for workers to cleanly finish
            workerPool.shutdown();
            workerPool.awaitTermination(3, TimeUnit.SECONDS);

            if (isPaused) {
                updateStatus(DownloadStatus.PAUSED, null);
                saveProgressToDb();
                return;
            }

            if (isCancelled) {
                updateStatus(DownloadStatus.CANCELLED, null);
                // Clean up temp file on explicit cancel
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                return;
            }

            // Step 6: Verify completion & atomic finalize
            boolean allComplete = tempFile.exists() && tempFile.length() > 0;
            if (item.getTotalBytes() > 0 && tempFile.length() < item.getTotalBytes()) {
                allComplete = false;
            }
            if (item.getSegments() != null) {
                for (DownloadSegment seg : item.getSegments()) {
                    if (!seg.isComplete()) {
                        allComplete = false;
                        break;
                    }
                }
            }

            if (allComplete) {
                finalizeDownload(tempFile, destFile);
            } else {
                // Preserve tempFile and progress state so the download can be resumed!
                saveProgressToDb();
                updateStatus(DownloadStatus.FAILED, "Incomplete transfer or connection lost");
                if (listener != null) listener.onTaskFailed(item, "Incomplete transfer");
            }

        } catch (InterruptedException ie) {
            if (isPaused) {
                updateStatus(DownloadStatus.PAUSED, null);
            } else if (isCancelled) {
                updateStatus(DownloadStatus.CANCELLED, null);
            }
        } catch (HtmlMasqueradeException hme) {
            File tempFile = new File(item.getTempPath());
            if (tempFile.exists()) tempFile.delete();
            updateStatus(DownloadStatus.FAILED, hme.getMessage());
            if (listener != null) listener.onTaskFailed(item, hme.getMessage());
        } catch (Exception e) {
            if (!isPaused && !isCancelled) {
                File tempFile = new File(item.getTempPath());
                if (tempFile.exists() && tempFile.length() == 0) {
                    tempFile.delete();
                }
                updateStatus(DownloadStatus.FAILED, e.getMessage());
                if (listener != null) listener.onTaskFailed(item, e.getMessage());
            }
        }
    }

    private void downloadDataUrl() {
        try {
            updateStatus(DownloadStatus.RUNNING, null);
            String url = item.getUrl();
            int commaIdx = url.indexOf(',');
            if (commaIdx == -1) {
                updateStatus(DownloadStatus.FAILED, "Malformed data URL: missing comma");
                if (listener != null) listener.onTaskFailed(item, "Malformed data URL");
                return;
            }

            String metadata = url.substring(5, commaIdx); // after "data:"
            String dataPart = url.substring(commaIdx + 1);
            boolean isBase64 = metadata.contains(";base64");

            byte[] decoded;
            if (isBase64) {
                decoded = android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT);
            } else {
                decoded = java.net.URLDecoder.decode(dataPart, "UTF-8").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }

            item.setTotalBytes(decoded.length);

            File destFile = new File(item.getDestinationPath());
            File parentDir = destFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            File tempFile = new File(item.getTempPath());
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                fos.write(decoded);
                fos.flush();
            }

            finalizeDownload(tempFile, destFile);
        } catch (Exception e) {
            updateStatus(DownloadStatus.FAILED, "Data URL error: " + e.getMessage());
            if (listener != null) listener.onTaskFailed(item, e.getMessage());
        }
    }

    private HttpURLConnection openConnectionWithRedirects(URL url, boolean requestRange) throws Exception {
        int redirects = 0;
        URL currentUrl = url;

        while (redirects < 6) {
            HttpURLConnection conn = (HttpURLConnection) currentUrl.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);

            String ua = (item.getUserAgent() != null && !item.getUserAgent().trim().isEmpty())
                    ? item.getUserAgent()
                    : DEFAULT_USER_AGENT;
            conn.setRequestProperty("User-Agent", ua);

            // Fetch dynamic cookies from CookieManager for current host if available
            String activeCookies = item.getCookies();
            try {
                String dynamicCookies = android.webkit.CookieManager.getInstance().getCookie(currentUrl.toString());
                if (dynamicCookies != null && !dynamicCookies.isEmpty()) {
                    activeCookies = (activeCookies != null && !activeCookies.isEmpty())
                            ? activeCookies + "; " + dynamicCookies
                            : dynamicCookies;
                }
            } catch (Exception ignored) {}

            if (activeCookies != null && !activeCookies.trim().isEmpty()) {
                conn.setRequestProperty("Cookie", activeCookies);
            }
            if (item.getReferrer() != null && !item.getReferrer().trim().isEmpty()) {
                conn.setRequestProperty("Referer", item.getReferrer());
            }

            conn.setRequestProperty("Accept", "application/octet-stream, application/zip, application/x-zip-compressed, */*");
            conn.setRequestProperty("Accept-Encoding", "identity");

            if (requestRange) {
                conn.setRequestProperty("Range", "bytes=0-0");
            }

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308) {

                String location = conn.getHeaderField("Location");
                List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
                if (setCookies != null) {
                    try {
                        for (String sc : setCookies) {
                            android.webkit.CookieManager.getInstance().setCookie(currentUrl.toString(), sc);
                        }
                    } catch (Exception ignored) {}
                }

                conn.disconnect();
                if (location == null) {
                    throw new Exception("Redirect missing Location header");
                }
                currentUrl = new URL(currentUrl, location);
                item.setUrl(currentUrl.toString());
                redirects++;
                continue;
            }

            return conn;
        }

        throw new Exception("Too many redirects");
    }

    private void fetchServerMetadata() throws Exception {
        if (item.getTotalBytes() > 0 && item.isSupportsRange()) {
            // Already known from format inspection (e.g. YouTube contentLength or HEAD probe)
            // Do NOT overwrite with -1 or send redundant Range: bytes=0-0!
            return;
        }

        URL currentUrl = new URL(item.getUrl());
        HttpURLConnection conn = openConnectionWithRedirects(currentUrl, true);
        int code = conn.getResponseCode();

        boolean supportsRange = (code == HttpURLConnection.HTTP_PARTIAL);
        long totalLength = -1;

        if (supportsRange) {
            String rangeHeader = conn.getHeaderField("Content-Range");
            if (rangeHeader != null && rangeHeader.contains("/")) {
                try {
                    String totalStr = rangeHeader.substring(rangeHeader.lastIndexOf("/") + 1).trim();
                    totalLength = Long.parseLong(totalStr);
                } catch (Exception ignored) {}
            }
        } else if (code == HttpURLConnection.HTTP_OK) {
            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            supportsRange = "bytes".equalsIgnoreCase(acceptRanges);
            totalLength = conn.getContentLengthLong();
        } else {
            conn.disconnect();
            conn = openConnectionWithRedirects(currentUrl, false);
            code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                String acceptRanges = conn.getHeaderField("Accept-Ranges");
                supportsRange = "bytes".equalsIgnoreCase(acceptRanges);
                totalLength = conn.getContentLengthLong();
            }
        }

        item.setSupportsRange(supportsRange);
        if (totalLength > 0) {
            item.setTotalBytes(totalLength);
        }
        item.setEtag(conn.getHeaderField("ETag"));
        item.setLastModified(conn.getHeaderField("Last-Modified"));

        String disposition = conn.getHeaderField("Content-Disposition");
        if (item.getFileName() == null || item.getFileName().isEmpty() || item.getFileName().startsWith("download_")) {
            String extracted = extractFileName(disposition, item.getUrl());
            if (extracted != null && !extracted.isEmpty()) {
                item.setFileName(extracted);
            }
        }

        // Only reject early if Content-Disposition does NOT specify an explicit binary attachment,
        // or if server returned an error status. If it's a 200/206 response with attachment disposition,
        // let SegmentWorker verify real initial magic bytes.
        String contentType = conn.getContentType();
        boolean hasAttachmentDisposition = disposition != null && disposition.toLowerCase().contains("attachment");
        if (!hasAttachmentDisposition && DownloadIntegrityVerifier.detectHtmlMasquerade(item.getFileName(), contentType, null, 0)) {
            conn.disconnect();
            throw new HtmlMasqueradeException("HTML error page masquerading as download file (Server returned Content-Type: " + contentType + ")");
        }

        conn.disconnect();
        dbHelper.insertOrUpdate(item);
    }

    private List<DownloadSegment> createSegments() {
        List<DownloadSegment> list = new ArrayList<>();
        long total = item.getTotalBytes();
        int count = item.getNumSegments();

        // Single segment if server rejects ranges or small file (< 1MB) or unknown size
        if (!item.isSupportsRange() || total < 1024 * 1024 || count <= 1) {
            item.setNumSegments(1);
            list.add(new DownloadSegment(item.getId(), 0, 0, total > 0 ? total - 1 : -1, 0, DownloadStatus.PENDING));
            return list;
        }

        long chunkSize = total / count;
        for (int i = 0; i < count; i++) {
            long start = i * chunkSize;
            long end = (i == count - 1) ? (total - 1) : ((i + 1) * chunkSize - 1);
            list.add(new DownloadSegment(item.getId(), i, start, end, start, DownloadStatus.PENDING));
        }
        return list;
    }

    private void finalizeDownload(File tempFile, File destFile) throws Exception {
        if (destFile.exists()) {
            destFile.delete();
        }
        boolean renamed = tempFile.renameTo(destFile);
        if (!renamed) {
            // Fallback: file copy
            java.io.InputStream in = null;
            java.io.OutputStream out = null;
            try {
                in = new java.io.FileInputStream(tempFile);
                out = new java.io.FileOutputStream(destFile);
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
                tempFile.delete();
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (out != null) try { out.close(); } catch (Exception ignored) {}
            }
        }

        item.setDownloadedBytes(destFile.length());
        if (item.getTotalBytes() <= 0) {
            item.setTotalBytes(destFile.length());
        }
        item.setSpeedBytesPerSec(0);
        updateStatus(DownloadStatus.COMPLETED, null);

        if (listener != null) {
            listener.onTaskCompleted(item);
        }
    }

    private boolean isAllWorkersDone() {
        for (Future<?> f : workerFutures) {
            if (!f.isDone()) return false;
        }
        return true;
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

    private void saveProgressToDb() {
        dbHelper.updateProgress(item.getId(), item.getDownloadedBytes());
        if (item.getSegments() != null) {
            dbHelper.saveSegments(item.getId(), item.getSegments());
        }
    }

    protected void updateStatus(DownloadStatus status, String errorMessage) {
        item.setStatus(status);
        item.setErrorMessage(errorMessage);
        dbHelper.updateStatus(item.getId(), status, errorMessage);
        if (listener != null) {
            listener.onTaskUpdated(item);
        }
    }

    private String extractFileName(String contentDisposition, String urlStr) {
        if (contentDisposition != null) {
            int idx = contentDisposition.toLowerCase().indexOf("filename=");
            if (idx != -1) {
                String name = contentDisposition.substring(idx + 9).trim();
                if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                    name = name.substring(1, name.length() - 1);
                }
                return name;
            }
        }
        try {
            String path = new URL(urlStr).getPath();
            int slash = path.lastIndexOf('/');
            if (slash != -1 && slash < path.length() - 1) {
                return path.substring(slash + 1);
            }
        } catch (Exception ignored) {}
        return "download_" + System.currentTimeMillis();
    }

    @Override
    public void onBytesRead(int bytes) {
        transferredSinceLastCheck.addAndGet(bytes);
    }

    @Override
    public boolean isStopRequested() {
        return isPaused || isCancelled;
    }

    @Override
    public SpeedLimiter getSpeedLimiter() {
        return speedLimiter;
    }

    @Override
    public void onFatalError(String reason) {
        cancel();
        updateStatus(DownloadStatus.FAILED, reason);
        if (listener != null) {
            listener.onTaskFailed(item, reason);
        }
    }
}

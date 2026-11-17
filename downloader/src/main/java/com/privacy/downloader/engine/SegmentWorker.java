package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.util.DownloadIntegrityVerifier;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class SegmentWorker implements Runnable {

    public interface ProgressCallback {
        void onBytesRead(int bytes);
        boolean isStopRequested();
        SpeedLimiter getSpeedLimiter();
        void onFatalError(String reason);
    }

    private final DownloadItem item;
    private final DownloadSegment segment;
    private final ProgressCallback callback;
    private volatile boolean cancelled = false;
    private volatile boolean paused = false;

    private static final int BUFFER_SIZE = 65536; // 64 KB chunks for high-throughput downloads
    private static final int DEFAULT_MAX_RETRIES = 8;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    public SegmentWorker(DownloadItem item, DownloadSegment segment, ProgressCallback callback) {
        this.item = item;
        this.segment = segment;
        this.callback = callback;
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
    }

    public void cancel() {
        this.cancelled = true;
    }

    @Override
    public void run() {
        if (segment.isComplete()) {
            segment.setStatus(DownloadStatus.COMPLETED);
            return;
        }

        segment.setStatus(DownloadStatus.RUNNING);
        int retryCount = 0;
        int maxRetries = resolveMaxRetries();

        while (retryCount <= maxRetries && !shouldStop()) {
            try {
                executeTransfer();
                if (segment.isComplete() || segment.getEndByte() <= 0) {
                    segment.setStatus(DownloadStatus.COMPLETED);
                    return;
                }
            } catch (HtmlMasqueradeException hme) {
                android.util.Log.e("SegmentWorker", "Masquerade error: " + hme.getMessage());
                segment.setStatus(DownloadStatus.FAILED);
                if (callback != null) {
                    callback.onFatalError(hme.getMessage());
                }
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

        if (paused) {
            segment.setStatus(DownloadStatus.PAUSED);
        } else if (cancelled) {
            segment.setStatus(DownloadStatus.CANCELLED);
        }
    }

    private void executeTransfer() throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        RandomAccessFile out = null;

        try {
            URL downloadUrl = new URL(item.getUrl());
            long start = segment.getCurrentByte();
            long end = segment.getEndByte();
            conn = openConnectionWithRedirects(downloadUrl, start, end);

            int responseCode = conn.getResponseCode();

            // Range response is 206 Partial Content. Full response is 200 OK.
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw new Exception("Unexpected HTTP response: " + responseCode + " (" + conn.getResponseMessage() + ")");
            }

            File tempFile = new File(item.getTempPath());
            out = new RandomAccessFile(tempFile, "rw");
            out.seek(segment.getCurrentByte());

            in = conn.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            boolean firstChunk = (segment.getIndex() == 0 && segment.getCurrentByte() == 0);

            while (!shouldStop() && (bytesRead = in.read(buffer)) != -1) {
                if (firstChunk) {
                    firstChunk = false;
                    String contentType = conn.getContentType();
                    if (DownloadIntegrityVerifier.detectHtmlMasquerade(item.getFileName(), contentType, buffer, bytesRead)) {
                        throw new HtmlMasqueradeException("HTML error page masquerading as download file (Server returned HTML instead of binary content)");
                    }
                }

                // If segment has defined endByte, make sure we don't overrun it
                if (segment.getEndByte() > 0) {
                    long remaining = (segment.getEndByte() - segment.getCurrentByte()) + 1;
                    if (remaining <= 0) {
                        break;
                    }
                    if (bytesRead > remaining) {
                        bytesRead = (int) remaining;
                    }
                }

                out.write(buffer, 0, bytesRead);
                segment.addProgress(bytesRead);

                if (callback != null) {
                    callback.onBytesRead(bytesRead);
                    SpeedLimiter limiter = callback.getSpeedLimiter();
                    if (limiter != null) {
                        limiter.acquire(bytesRead);
                    }
                }

                if (segment.getEndByte() > 0 && segment.getCurrentByte() > segment.getEndByte()) {
                    break;
                }
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openConnectionWithRedirects(URL url, long startByte, long endByte) throws Exception {
        int redirects = 0;
        URL currentUrl = url;

        while (redirects < 6) {
            HttpURLConnection conn = (HttpURLConnection) currentUrl.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);

            String ua = (item.getUserAgent() != null && !item.getUserAgent().trim().isEmpty())
                    ? item.getUserAgent()
                    : DownloadTask.DEFAULT_USER_AGENT;
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
            if (item.getReferrer() != null && !item.getReferrer().isEmpty()) {
                conn.setRequestProperty("Referer", item.getReferrer());
            }
            conn.setRequestProperty("Accept", "application/octet-stream, application/zip, application/x-zip-compressed, */*");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Accept-Encoding", "identity");

            // Set Range header BEFORE connecting / calling getResponseCode
            if (startByte > 0) {
                if (endByte > 0 && startByte <= endByte) {
                    conn.setRequestProperty("Range", "bytes=" + startByte + "-" + endByte);
                } else {
                    conn.setRequestProperty("Range", "bytes=" + startByte + "-");
                }
            } else if (endByte > 0) {
                conn.setRequestProperty("Range", "bytes=" + startByte + "-" + endByte);
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
                redirects++;
                continue;
            }

            return conn;
        }

        throw new Exception("Too many redirects");
    }

    private boolean shouldStop() {
        return paused || cancelled || (callback != null && callback.isStopRequested());
    }
}

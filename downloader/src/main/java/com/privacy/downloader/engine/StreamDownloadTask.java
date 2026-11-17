package com.privacy.downloader.engine;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Downloads HLS (.m3u8) streams via high-speed Java HTTPS networking and remuxes
 * them into complete MP4 files using native offline FFmpeg (-c copy -bsf:a aac_adtstoasc).
 *
 * This completely resolves the "https protocol not found" error because all network I/O
 * is handled by Android's native TLS stack, while FFmpeg operates purely on local files.
 */
public class StreamDownloadTask extends DownloadTask {

    private static final String TAG = "StreamDownloadTask";

    private static final Pattern STREAM_INF_PATTERN =
            Pattern.compile("#EXT-X-STREAM-INF:([^\r\n]+)");
    private static final Pattern RESOLUTION_PATTERN =
            Pattern.compile("RESOLUTION=(\\d+x\\d+)");
    private static final Pattern BANDWIDTH_PATTERN =
            Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern AUDIO_GROUP_PATTERN =
            Pattern.compile("AUDIO=\"([^\"]+)\"");

    private static final Pattern MEDIA_AUDIO_PATTERN =
            Pattern.compile("#EXT-X-MEDIA:TYPE=AUDIO[^\r\n]*GROUP-ID=\"([^\"]+)\"[^\r\n]*URI=\"([^\"]+)\"");

    private static final Pattern MAP_PATTERN =
            Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"(?:,BYTERANGE=\"([^\"]+)\")?");
    private static final Pattern KEY_PATTERN =
            Pattern.compile("#EXT-X-KEY:METHOD=([A-Za-z0-9-]+)(?:,URI=\"([^\"]+)\")?(?:,IV=(0x[0-9a-fA-F]+))?");
    private static final Pattern BYTERANGE_PATTERN =
            Pattern.compile("#EXT-X-BYTERANGE:\\s*(\\d+)(?:@(\\d+))?");

    private long sessionId = -1;
    private long lastProgressUpdateTime = 0;
    private long lastDbUpdateTime = 0;

    public StreamDownloadTask(DownloadItem item, DownloadDbHelper dbHelper, TaskListener listener, SpeedLimiter globalLimiter) {
        super(item, dbHelper, listener, globalLimiter);
    }

    @Override
    public synchronized void pause() {
        isPaused = true;
        if (sessionId != -1) {
            try {
                FFmpegKitConfig.nativeFFmpegCancel(sessionId);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public synchronized void cancel() {
        isCancelled = true;
        if (sessionId != -1) {
            try {
                FFmpegKitConfig.nativeFFmpegCancel(sessionId);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void run() {
        File videoTempFile = null;
        File audioTempFile = null;
        File destFile = null;

        try {
            updateStatus(DownloadStatus.CONNECTING, null);

            // 1. Sanitize destination filename to guarantee .mp4 container
            String currentFileName = item.getFileName();
            String mp4FileName = StreamDetector.ensureMp4Extension(currentFileName);
            item.setFileName(mp4FileName);
            item.setMimeType("video/mp4");

            destFile = new File(item.getDestinationPath());
            if (!destFile.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                File parent = destFile.getParentFile();
                destFile = new File(parent, mp4FileName);
                item.setDestinationPath(destFile.getAbsolutePath());
                item.setTempPath(destFile.getAbsolutePath() + ".part");
            }

            File parentDir = destFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            dbHelper.insertOrUpdate(item);
            if (isPaused || isCancelled) return;

            if (item.getAudioUrl() != null && !item.getAudioUrl().trim().isEmpty()) {
                // Direct Dual-Stream Download (e.g. YouTube adaptive video + audio tracks)
                Log.i(TAG, "Executing dual-stream direct download for video: " + item.getUrl() + " and audio: " + item.getAudioUrl());
                videoTempFile = new File(destFile.getAbsolutePath() + ".v.raw");
                audioTempFile = new File(destFile.getAbsolutePath() + ".a.raw");
                if (videoTempFile.exists()) videoTempFile.delete();
                if (audioTempFile.exists()) audioTempFile.delete();

                updateStatus(DownloadStatus.RUNNING, "Downloading video track...");
                long videoBytes = downloadDirectTrack(item.getUrl(), videoTempFile, 0, true);
                if (isPaused) { updateStatus(DownloadStatus.PAUSED, null); return; }
                if (isCancelled) { updateStatus(DownloadStatus.CANCELLED, null); cleanupTempFiles(videoTempFile, audioTempFile); return; }

                updateStatus(DownloadStatus.RUNNING, "Downloading audio track...");
                downloadDirectTrack(item.getAudioUrl(), audioTempFile, videoBytes, true);
                if (isPaused) { updateStatus(DownloadStatus.PAUSED, null); return; }
                if (isCancelled) { updateStatus(DownloadStatus.CANCELLED, null); cleanupTempFiles(videoTempFile, audioTempFile); return; }

                updateStatus(DownloadStatus.RUNNING, "Remuxing tracks with FFmpeg...");
            } else {
                // 2. Fetch master or media playlist text
                String streamUrl = item.getUrl();
                Log.i(TAG, "Fetching playlist from " + streamUrl);
                String playlistText = fetchTextWithRedirects(streamUrl);

                if (playlistText == null || playlistText.trim().isEmpty()) {
                    throw new IOException("Empty response from playlist URL: " + streamUrl);
                }

                // Fallback for direct media file mistakenly routed here
                if (!playlistText.contains("#EXTM3U")) {
                    Log.w(TAG, "URL is not an HLS playlist, performing direct stream copy");
                    downloadDirectFile(streamUrl, destFile);
                    return;
                }

                // 3. Resolve Video and Audio media playlist URLs
                String videoPlaylistUrl = streamUrl;
                String audioPlaylistUrl = null;

                if (playlistText.contains("#EXT-X-STREAM-INF")) {
                    // Master playlist detected
                    MasterPlaylist master = parseMasterPlaylist(streamUrl, playlistText);
                    Variant selectedVariant = selectBestVariant(master.variants, item.getFileName());
                    if (selectedVariant != null) {
                        videoPlaylistUrl = selectedVariant.uri;
                        if (selectedVariant.audioGroupId != null && master.audioTracks.containsKey(selectedVariant.audioGroupId)) {
                            audioPlaylistUrl = master.audioTracks.get(selectedVariant.audioGroupId);
                        }
                        Log.i(TAG, "Selected HLS variant: " + selectedVariant.resolution + " (" + selectedVariant.bandwidth + " bps) -> " + videoPlaylistUrl);
                    } else if (!master.variants.isEmpty()) {
                        videoPlaylistUrl = master.variants.get(0).uri;
                    }
                }

                if (isPaused || isCancelled) return;

                // 4. Download video segments
                updateStatus(DownloadStatus.RUNNING, null);
                videoTempFile = new File(destFile.getAbsolutePath() + ".v.raw");
                if (videoTempFile.exists()) videoTempFile.delete();

                Log.i(TAG, "Downloading video segments from " + videoPlaylistUrl + " -> " + videoTempFile.getAbsolutePath());
                long totalVideoSegments = downloadMediaPlaylist(videoPlaylistUrl, videoTempFile, true);

                if (isPaused) {
                    updateStatus(DownloadStatus.PAUSED, null);
                    return;
                }
                if (isCancelled) {
                    updateStatus(DownloadStatus.CANCELLED, null);
                    cleanupTempFiles(videoTempFile, audioTempFile);
                    return;
                }

                // 5. Download audio segments (if separate audio stream is used)
                if (audioPlaylistUrl != null) {
                    audioTempFile = new File(destFile.getAbsolutePath() + ".a.raw");
                    if (audioTempFile.exists()) audioTempFile.delete();

                    Log.i(TAG, "Downloading separate audio segments from " + audioPlaylistUrl + " -> " + audioTempFile.getAbsolutePath());
                    downloadMediaPlaylist(audioPlaylistUrl, audioTempFile, false);

                    if (isPaused) {
                        updateStatus(DownloadStatus.PAUSED, null);
                        return;
                    }
                    if (isCancelled) {
                        updateStatus(DownloadStatus.CANCELLED, null);
                        cleanupTempFiles(videoTempFile, audioTempFile);
                        return;
                    }
                }
            }

            // 6. Native local FFmpeg remuxing (offline - no network needed)
            sessionId = FFmpegKitConfig.newSessionId();
            List<String> ffmpegArgs = new ArrayList<>();
            ffmpegArgs.add("-y");

            if (audioTempFile != null && audioTempFile.exists() && audioTempFile.length() > 0) {
                ffmpegArgs.add("-i");
                ffmpegArgs.add(videoTempFile.getAbsolutePath());
                ffmpegArgs.add("-i");
                ffmpegArgs.add(audioTempFile.getAbsolutePath());
                ffmpegArgs.add("-c:v");
                ffmpegArgs.add("copy");
                ffmpegArgs.add("-c:a");
                ffmpegArgs.add("copy");
            } else {
                ffmpegArgs.add("-i");
                ffmpegArgs.add(videoTempFile.getAbsolutePath());
                ffmpegArgs.add("-c");
                ffmpegArgs.add("copy");
                ffmpegArgs.add("-bsf:a");
                ffmpegArgs.add("aac_adtstoasc");
            }

            ffmpegArgs.add("-movflags");
            ffmpegArgs.add("+faststart");
            ffmpegArgs.add(destFile.getAbsolutePath());

            Log.i(TAG, "Executing native local FFmpeg remux: " + ffmpegArgs);
            int exitCode = FFmpegKitConfig.nativeFFmpegExecute(sessionId, ffmpegArgs.toArray(new String[0]));
            Log.i(TAG, "FFmpeg local remux finished with exitCode: " + exitCode);
            FFmpegKitConfig.unregisterSession(sessionId);

            // If copy failed and we have an audio track (e.g. Opus in MP4), retry by transcoding audio to AAC
            if (exitCode != 0 && audioTempFile != null && audioTempFile.exists() && audioTempFile.length() > 0 && !isCancelled) {
                Log.w(TAG, "FFmpeg copy failed with code " + exitCode + ", retrying with audio transcode (-c:v copy -c:a aac)...");
                if (destFile.exists()) destFile.delete();
                sessionId = FFmpegKitConfig.newSessionId();
                List<String> retryArgs = new ArrayList<>();
                retryArgs.add("-y");
                retryArgs.add("-i");
                retryArgs.add(videoTempFile.getAbsolutePath());
                retryArgs.add("-i");
                retryArgs.add(audioTempFile.getAbsolutePath());
                retryArgs.add("-c:v");
                retryArgs.add("copy");
                retryArgs.add("-c:a");
                retryArgs.add("aac");
                retryArgs.add("-b:a");
                retryArgs.add("192k");
                retryArgs.add("-movflags");
                retryArgs.add("+faststart");
                retryArgs.add(destFile.getAbsolutePath());
                exitCode = FFmpegKitConfig.nativeFFmpegExecute(sessionId, retryArgs.toArray(new String[0]));
                Log.i(TAG, "FFmpeg audio transcode retry finished with exitCode: " + exitCode);
                FFmpegKitConfig.unregisterSession(sessionId);
            }

            if (isCancelled) {
                updateStatus(DownloadStatus.CANCELLED, null);
                cleanupTempFiles(videoTempFile, audioTempFile);
                if (destFile.exists()) destFile.delete();
                return;
            }

            // 7. Verify result and finalize
            if (exitCode == 0 && destFile.exists() && destFile.length() > 0) {
                cleanupTempFiles(videoTempFile, audioTempFile);
                long finalSize = destFile.length();
                item.setTotalBytes(finalSize);
                item.setDownloadedBytes(finalSize);
                item.setSpeedBytesPerSec(0);
                updateStatus(DownloadStatus.COMPLETED, null);
                if (listener != null) {
                    listener.onTaskCompleted(item);
                }
            } else {
                // Robust Fallback: if FFmpeg exited non-zero, preserve downloaded raw video
                Log.w(TAG, "FFmpeg remux exited with code " + exitCode + ", falling back to direct rename");
                if (videoTempFile.exists() && videoTempFile.length() > 0) {
                    if (destFile.exists()) destFile.delete();
                    boolean ok = videoTempFile.renameTo(destFile);
                    if (!ok) {
                        copyFile(videoTempFile, destFile);
                        videoTempFile.delete();
                    }
                    if (audioTempFile != null && audioTempFile.exists()) {
                        audioTempFile.delete();
                    }
                    long finalSize = destFile.length();
                    item.setTotalBytes(finalSize);
                    item.setDownloadedBytes(finalSize);
                    item.setSpeedBytesPerSec(0);
                    updateStatus(DownloadStatus.COMPLETED, null);
                    if (listener != null) {
                        listener.onTaskCompleted(item);
                    }
                } else {
                    String err = "Stream muxing failed (exit code " + exitCode + ")";
                    updateStatus(DownloadStatus.FAILED, err);
                    if (listener != null) {
                        listener.onTaskFailed(item, err);
                    }
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "StreamDownloadTask failed: " + t.getMessage(), t);
            cleanupTempFiles(videoTempFile, audioTempFile);
            if (!isPaused && !isCancelled) {
                updateStatus(DownloadStatus.FAILED, t.getMessage());
                if (listener != null) {
                    listener.onTaskFailed(item, t.getMessage());
                }
            }
        }
    }

    private void cleanupTempFiles(File video, File audio) {
        if (video != null && video.exists()) video.delete();
        if (audio != null && audio.exists()) audio.delete();
    }

    /**
     * Downloads all segments in an HLS media playlist, concatenating them into a local file.
     */
    private long downloadMediaPlaylist(String playlistUrl, File outputFile, boolean trackProgress) throws Exception {
        String playlistText = fetchTextWithRedirects(playlistUrl);
        if (playlistText == null) throw new IOException("Could not fetch playlist: " + playlistUrl);

        List<HlsSegment> segments = parseMediaPlaylist(playlistUrl, playlistText);
        if (segments.isEmpty()) {
            throw new IOException("No media segments found in playlist: " + playlistUrl);
        }

        double totalDuration = 0;
        for (HlsSegment seg : segments) {
            totalDuration += seg.duration;
        }

        byte[] keyBytes = null;
        String currentKeyUri = null;

        long downloadedBytes = 0;
        long windowStartBytes = 0;
        long windowStartTime = System.currentTimeMillis();

        try (FileOutputStream out = new FileOutputStream(outputFile, true)) {
            for (int i = 0; i < segments.size(); i++) {
                if (isCancelled || isPaused) break;

                HlsSegment seg = segments.get(i);

                // Handle AES-128 key fetching if encrypted
                if (seg.keyUri != null && !seg.keyUri.equals(currentKeyUri)) {
                    currentKeyUri = seg.keyUri;
                    keyBytes = fetchBytesWithRetry(seg.keyUri, 3);
                }

                // Download segment with retry
                byte[] rawSegment = fetchSegmentBytesWithRetry(seg, 3);
                if (rawSegment == null || rawSegment.length == 0) {
                    Log.w(TAG, "Empty segment at index " + i + ": " + seg.url);
                    continue;
                }

                // Decrypt if AES-128
                byte[] finalBytes = rawSegment;
                if ("AES-128".equalsIgnoreCase(seg.keyMethod) && keyBytes != null) {
                    finalBytes = decryptAes128(rawSegment, keyBytes, seg.iv, seg.sequenceNumber);
                }

                out.write(finalBytes);
                downloadedBytes += finalBytes.length;

                // Update progress
                if (trackProgress) {
                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdateTime >= 400 || i == segments.size() - 1) {
                        lastProgressUpdateTime = now;

                        double progressRatio = (double) (i + 1) / segments.size();
                        if (progressRatio > 0) {
                            long estTotal = (long) (downloadedBytes / progressRatio);
                            item.setTotalBytes(estTotal);
                        }
                        item.setDownloadedBytes(downloadedBytes);

                        long timeDiff = now - windowStartTime;
                        if (timeDiff >= 1000) {
                            long bytesDiff = downloadedBytes - windowStartBytes;
                            long speed = (bytesDiff * 1000) / timeDiff;
                            item.setSpeedBytesPerSec(speed);
                            windowStartTime = now;
                            windowStartBytes = downloadedBytes;
                        }

                        if (now - lastDbUpdateTime >= 2000 || i == segments.size() - 1) {
                            lastDbUpdateTime = now;
                            dbHelper.updateProgress(item.getId(), downloadedBytes);
                        }
                        if (listener != null) {
                            listener.onTaskUpdated(item);
                        }
                    }
                }
            }
            out.flush();
        }

        return segments.size();
    }

    private byte[] decryptAes128(byte[] cipherText, byte[] key, byte[] iv, long seqNum) {
        try {
            byte[] ivBytes = iv;
            if (ivBytes == null || ivBytes.length == 0) {
                // Standard HLS: sequence number encoded as 16-byte big-endian integer
                ByteBuffer bb = ByteBuffer.allocate(16);
                bb.putLong(8, seqNum);
                ivBytes = bb.array();
            }

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(ivBytes));
            return cipher.doFinal(cipherText);
        } catch (Throwable t) {
            Log.e(TAG, "AES-128 decryption failed: " + t.getMessage());
            return cipherText;
        }
    }

    private byte[] fetchSegmentBytesWithRetry(HlsSegment seg, int maxRetries) throws IOException {
        IOException lastEx = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (isCancelled || isPaused) return null;
            try {
                HttpURLConnection conn = openConnection(seg.url);
                if (seg.byteRangeLength > 0) {
                    long start = seg.byteRangeOffset;
                    long end = start + seg.byteRangeLength - 1;
                    conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    try (InputStream in = conn.getInputStream();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            baos.write(buf, 0, len);
                        }
                        return baos.toByteArray();
                    }
                } else {
                    throw new IOException("HTTP " + code + " for segment: " + seg.url);
                }
            } catch (IOException e) {
                lastEx = e;
                try { Thread.sleep(500L * (attempt + 1)); } catch (InterruptedException ignored) {}
            }
        }
        throw lastEx != null ? lastEx : new IOException("Failed to download segment: " + seg.url);
    }

    private byte[] fetchBytesWithRetry(String urlStr, int maxRetries) throws IOException {
        IOException lastEx = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (isCancelled || isPaused) return null;
            try {
                HttpURLConnection conn = openConnection(urlStr);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    try (InputStream in = conn.getInputStream();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            baos.write(buf, 0, len);
                        }
                        return baos.toByteArray();
                    }
                } else {
                    throw new IOException("HTTP " + code + " for " + urlStr);
                }
            } catch (IOException e) {
                lastEx = e;
                try { Thread.sleep(500L * (attempt + 1)); } catch (InterruptedException ignored) {}
            }
        }
        throw lastEx != null ? lastEx : new IOException("Failed to fetch bytes: " + urlStr);
    }

    private String fetchTextWithRedirects(String urlStr) throws IOException {
        String currentUrl = urlStr;
        for (int redirect = 0; redirect < 5; redirect++) {
            HttpURLConnection conn = openConnection(currentUrl);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc != null) {
                    currentUrl = resolveUrl(currentUrl, loc);
                    conn.disconnect();
                    continue;
                }
            }
            if (code >= 200 && code < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    return sb.toString();
                } finally {
                    conn.disconnect();
                }
            }
            throw new IOException("HTTP " + code + " fetching " + currentUrl);
        }
        throw new IOException("Too many redirects: " + urlStr);
    }

    private void downloadDirectFile(String fileUrl, File destFile) throws Exception {
        HttpURLConnection conn = openConnection(fileUrl);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 400) {
            throw new IOException("Direct stream download HTTP error " + code);
        }

        long total = conn.getContentLengthLong();
        item.setTotalBytes(total);

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[65536];
            long downloaded = 0;
            int len;
            long lastUpdate = 0;
            while ((len = in.read(buf)) > 0) {
                if (isCancelled || isPaused) break;
                out.write(buf, 0, len);
                downloaded += len;

                long now = System.currentTimeMillis();
                if (now - lastUpdate >= 400) {
                    lastUpdate = now;
                    item.setDownloadedBytes(downloaded);
                    dbHelper.updateProgress(item.getId(), downloaded);
                    if (listener != null) listener.onTaskUpdated(item);
                }
            }
            out.flush();
        }

        if (isCancelled) {
            updateStatus(DownloadStatus.CANCELLED, null);
            if (destFile.exists()) destFile.delete();
            return;
        }
        if (isPaused) {
            updateStatus(DownloadStatus.PAUSED, null);
            return;
        }

        item.setDownloadedBytes(destFile.length());
        updateStatus(DownloadStatus.COMPLETED, null);
        if (listener != null) listener.onTaskCompleted(item);
    }

    private long downloadDirectTrack(String fileUrl, File destFile, long previousBytes, boolean trackProgress) throws Exception {
        HttpURLConnection conn = openConnection(fileUrl);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 400) {
            throw new IOException("HTTP error " + code + " downloading " + fileUrl);
        }

        long length = conn.getContentLengthLong();
        if (length > 0 && item.getTotalBytes() <= 0) {
            item.setTotalBytes(previousBytes + length);
        }

        long downloaded = 0;
        long windowStartBytes = 0;
        long windowStartTime = System.currentTimeMillis();

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                if (isCancelled || isPaused) break;
                out.write(buf, 0, len);
                downloaded += len;

                if (trackProgress) {
                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdateTime >= 400) {
                        lastProgressUpdateTime = now;
                        long totalDownloaded = previousBytes + downloaded;
                        item.setDownloadedBytes(totalDownloaded);

                        long timeDiff = now - windowStartTime;
                        if (timeDiff >= 1000) {
                            long bytesDiff = downloaded - windowStartBytes;
                            long speed = (bytesDiff * 1000) / timeDiff;
                            item.setSpeedBytesPerSec(speed);
                            windowStartTime = now;
                            windowStartBytes = downloaded;
                        }

                        if (now - lastDbUpdateTime >= 2000) {
                            lastDbUpdateTime = now;
                            dbHelper.updateProgress(item.getId(), totalDownloaded);
                        }
                        if (listener != null) listener.onTaskUpdated(item);
                    }
                }
            }
            out.flush();
        }
        long totalDownloaded = previousBytes + downloaded;
        item.setDownloadedBytes(totalDownloaded);
        dbHelper.updateProgress(item.getId(), totalDownloaded);
        if (listener != null) listener.onTaskUpdated(item);
        return downloaded;
    }

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        URL u = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);

        String ua = (item.getUserAgent() != null && !item.getUserAgent().trim().isEmpty())
                ? item.getUserAgent()
                : DEFAULT_USER_AGENT;
        conn.setRequestProperty("User-Agent", ua);

        String ref = item.getReferrer();
        if (ref != null && !ref.trim().isEmpty()) {
            conn.setRequestProperty("Referer", ref);
        } else if (urlStr.contains("vimeocdn.com") || urlStr.contains("vimeo.com")) {
            conn.setRequestProperty("Referer", "https://vimeo.com/");
        }

        if (item.getCookies() != null && !item.getCookies().trim().isEmpty()) {
            conn.setRequestProperty("Cookie", item.getCookies());
        }

        return conn;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }

    public static String resolveUrl(String baseUrl, String relativeUrl) {
        if (relativeUrl == null || relativeUrl.trim().isEmpty()) return baseUrl;
        String trimmed = relativeUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        try {
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, trimmed);
            return resolved.toString();
        } catch (Exception e) {
            return trimmed;
        }
    }

    // -------------------------------------------------------------
    // HLS Playlist Parsing
    // -------------------------------------------------------------

    private static class MasterPlaylist {
        final List<Variant> variants = new ArrayList<>();
        final Map<String, String> audioTracks = new HashMap<>(); // groupId -> URI
    }

    private static class Variant {
        String uri;
        String resolution = "";
        long bandwidth = 0;
        String audioGroupId = null;
    }

    private static class HlsSegment {
        String url;
        double duration = 0;
        long sequenceNumber = 0;
        long byteRangeLength = -1;
        long byteRangeOffset = 0;
        String keyMethod = null;
        String keyUri = null;
        byte[] iv = null;
    }

    private MasterPlaylist parseMasterPlaylist(String baseUrl, String content) {
        MasterPlaylist master = new MasterPlaylist();
        String[] lines = content.split("[\r\n]+");

        // First pass: extract audio track URIs
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                Matcher mm = MEDIA_AUDIO_PATTERN.matcher(line);
                if (mm.find()) {
                    String groupId = mm.group(1);
                    String audioUri = resolveUrl(baseUrl, mm.group(2));
                    master.audioTracks.put(groupId, audioUri);
                }
            }
        }

        // Second pass: extract variant stream tracks
        Variant currentVariant = null;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-STREAM-INF")) {
                currentVariant = new Variant();
                Matcher rm = RESOLUTION_PATTERN.matcher(line);
                if (rm.find()) currentVariant.resolution = rm.group(1);

                Matcher bm = BANDWIDTH_PATTERN.matcher(line);
                if (bm.find()) {
                    try { currentVariant.bandwidth = Long.parseLong(bm.group(1)); } catch (Exception ignored) {}
                }

                Matcher am = AUDIO_GROUP_PATTERN.matcher(line);
                if (am.find()) currentVariant.audioGroupId = am.group(1);

            } else if (!line.startsWith("#") && currentVariant != null) {
                currentVariant.uri = resolveUrl(baseUrl, line);
                master.variants.add(currentVariant);
                currentVariant = null;
            }
        }

        return master;
    }

    private Variant selectBestVariant(List<Variant> variants, String targetFileName) {
        if (variants == null || variants.isEmpty()) return null;

        // Try to match resolution specified in filename (e.g. "1080p", "720p")
        String fn = targetFileName != null ? targetFileName.toLowerCase(Locale.US) : "";
        String[] qualities = {"2160", "1440", "1080", "720", "540", "480", "360", "240"};
        String matchedQ = null;
        for (String q : qualities) {
            if (fn.contains(q)) {
                matchedQ = q;
                break;
            }
        }

        if (matchedQ != null) {
            for (Variant v : variants) {
                if (v.resolution.contains("x" + matchedQ) || v.resolution.endsWith(matchedQ)) {
                    return v;
                }
            }
        }

        // Otherwise pick the variant with the highest bandwidth
        Variant best = variants.get(0);
        for (Variant v : variants) {
            if (v.bandwidth > best.bandwidth) {
                best = v;
            }
        }
        return best;
    }

    private List<HlsSegment> parseMediaPlaylist(String baseUrl, String content) {
        List<HlsSegment> segments = new ArrayList<>();
        String[] lines = content.split("[\r\n]+");

        String currentKeyMethod = null;
        String currentKeyUri = null;
        byte[] currentIv = null;

        long currentSeq = 0;
        long nextByteRangeOffset = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    currentSeq = Long.parseLong(line.substring(22).trim());
                } catch (Exception ignored) {}
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Matcher km = KEY_PATTERN.matcher(line);
                if (km.find()) {
                    currentKeyMethod = km.group(1);
                    if (km.group(2) != null) {
                        currentKeyUri = resolveUrl(baseUrl, km.group(2));
                    }
                    if (km.group(3) != null) {
                        String hex = km.group(3).substring(2);
                        if (hex.length() % 2 != 0) hex = "0" + hex;
                        currentIv = new byte[hex.length() / 2];
                        for (int j = 0; j < hex.length(); j += 2) {
                            currentIv[j / 2] = (byte) ((Character.digit(hex.charAt(j), 16) << 4)
                                    + Character.digit(hex.charAt(j + 1), 16));
                        }
                    } else {
                        currentIv = null;
                    }
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Matcher mapM = MAP_PATTERN.matcher(line);
                if (mapM.find()) {
                    HlsSegment mapSeg = new HlsSegment();
                    mapSeg.url = resolveUrl(baseUrl, mapM.group(1));
                    mapSeg.duration = 0;
                    mapSeg.sequenceNumber = 0;
                    if (mapM.group(2) != null) {
                        String[] parts = mapM.group(2).split("@");
                        if (parts.length > 0) {
                            try { mapSeg.byteRangeLength = Long.parseLong(parts[0]); } catch (Exception ignored) {}
                        }
                        if (parts.length > 1) {
                            try { mapSeg.byteRangeOffset = Long.parseLong(parts[1]); } catch (Exception ignored) {}
                        }
                    }
                    mapSeg.keyMethod = currentKeyMethod;
                    mapSeg.keyUri = currentKeyUri;
                    mapSeg.iv = currentIv;
                    segments.add(mapSeg);
                }
            } else if (line.startsWith("#EXTINF:")) {
                double duration = 0;
                String durStr = line.substring(8);
                int comma = durStr.indexOf(',');
                if (comma != -1) durStr = durStr.substring(0, comma);
                try {
                    duration = Double.parseDouble(durStr.trim());
                } catch (Exception ignored) {}

                long brLen = -1;
                long brOffset = nextByteRangeOffset;

                // Look ahead for #EXT-X-BYTERANGE or segment URL
                for (int nextIdx = i + 1; nextIdx < lines.length; nextIdx++) {
                    String nextLine = lines[nextIdx].trim();
                    if (nextLine.startsWith("#EXT-X-BYTERANGE:")) {
                        Matcher brm = BYTERANGE_PATTERN.matcher(nextLine);
                        if (brm.find()) {
                            try { brLen = Long.parseLong(brm.group(1)); } catch (Exception ignored) {}
                            if (brm.group(2) != null) {
                                try { brOffset = Long.parseLong(brm.group(2)); } catch (Exception ignored) {}
                            }
                        }
                    } else if (!nextLine.startsWith("#") && !nextLine.isEmpty()) {
                        HlsSegment seg = new HlsSegment();
                        seg.url = resolveUrl(baseUrl, nextLine);
                        seg.duration = duration;
                        seg.sequenceNumber = currentSeq++;
                        seg.byteRangeLength = brLen;
                        seg.byteRangeOffset = brOffset;
                        seg.keyMethod = currentKeyMethod;
                        seg.keyUri = currentKeyUri;
                        seg.iv = currentIv;

                        if (brLen > 0) {
                            nextByteRangeOffset = brOffset + brLen;
                        }

                        segments.add(seg);
                        i = nextIdx;
                        break;
                    }
                }
            }
        }

        return segments;
    }
}

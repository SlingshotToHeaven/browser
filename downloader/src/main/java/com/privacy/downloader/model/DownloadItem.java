package com.privacy.downloader.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class DownloadItem implements Serializable {
    private String id;
    private String attemptToken;
    private String url;
    private String originalUrl;
    private String fileName;
    private String destinationPath;
    private String tempPath;
    private String mimeType;
    private String expectedSha256;
    private long totalBytes;
    private long downloadedBytes;
    private DownloadStatus status;
    private int numSegments;
    private boolean supportsRange;
    private boolean fullDownloadOnly;
    private long speedBytesPerSec;
    private String etag;
    // Only set for a representation checked by the direct HTTP range probe.
    private String verifiedRangeUrl;
    private String lastModified;
    private String audioUrl;
    private long audioBytes = -1;
    private long createdTime;
    private long finishedTime;
    private String errorMessage;
    private boolean wifiOnly;
    private List<DownloadSegment> segments;

    public DownloadItem() {
        this.segments = new ArrayList<>();
        this.status = DownloadStatus.PENDING;
        this.totalBytes = -1;
        this.numSegments = 4;
        this.wifiOnly = false;
        this.createdTime = System.currentTimeMillis();
    }

    public synchronized String getId() { return id; }
    public synchronized void setId(String id) { this.id = id; }

    public synchronized String getAttemptToken() { return attemptToken; }
    public synchronized void setAttemptToken(String attemptToken) { this.attemptToken = attemptToken; }

    public synchronized String getUrl() { return url; }
    public synchronized void setUrl(String url) {
        if (!Objects.equals(this.url, url)) verifiedRangeUrl = null;
        this.url = url;
    }

    public synchronized String getOriginalUrl() { return originalUrl; }
    public synchronized void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }

    public synchronized String getFileName() { return fileName; }
    public synchronized void setFileName(String fileName) { this.fileName = fileName; }

    public synchronized String getDestinationPath() { return destinationPath; }
    public synchronized void setDestinationPath(String destinationPath) { this.destinationPath = destinationPath; }

    public synchronized String getTempPath() { return tempPath; }
    public synchronized void setTempPath(String tempPath) { this.tempPath = tempPath; }

    public synchronized String getMimeType() { return mimeType; }
    public synchronized void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public synchronized String getExpectedSha256() { return expectedSha256; }
    public synchronized void setExpectedSha256(String expectedSha256) { this.expectedSha256 = expectedSha256; }

    public synchronized long getTotalBytes() { return totalBytes; }
    public synchronized void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public synchronized long getDownloadedBytes() { return downloadedBytes; }
    public synchronized void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }

    public synchronized DownloadStatus getStatus() { return status; }
    public synchronized void setStatus(DownloadStatus status) { this.status = status; }

    public synchronized int getNumSegments() { return numSegments; }
    public synchronized void setNumSegments(int numSegments) { this.numSegments = numSegments; }

    public synchronized boolean isSupportsRange() { return supportsRange; }
    public synchronized void setSupportsRange(boolean supportsRange) { this.supportsRange = supportsRange; }

    public synchronized boolean isFullDownloadOnly() { return fullDownloadOnly; }
    public synchronized void setFullDownloadOnly(boolean fullDownloadOnly) { this.fullDownloadOnly = fullDownloadOnly; }

    public synchronized long getSpeedBytesPerSec() { return speedBytesPerSec; }
    public synchronized void setSpeedBytesPerSec(long speedBytesPerSec) { this.speedBytesPerSec = speedBytesPerSec; }

    public synchronized String getEtag() { return etag; }
    public synchronized void setEtag(String etag) { this.etag = etag; }

    public synchronized String getVerifiedRangeUrl() { return verifiedRangeUrl; }
    public synchronized void setVerifiedRangeUrl(String url) { this.verifiedRangeUrl = url; }

    public synchronized String getLastModified() { return lastModified; }
    public synchronized void setLastModified(String lastModified) { this.lastModified = lastModified; }

    public synchronized long getCreatedTime() { return createdTime; }
    public synchronized void setCreatedTime(long createdTime) { this.createdTime = createdTime; }

    public synchronized long getFinishedTime() { return finishedTime; }
    public synchronized void setFinishedTime(long finishedTime) { this.finishedTime = finishedTime; }

    public synchronized String getErrorMessage() { return errorMessage; }
    public synchronized void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public synchronized boolean isWifiOnly() { return wifiOnly; }
    public synchronized void setWifiOnly(boolean wifiOnly) { this.wifiOnly = wifiOnly; }

    public synchronized List<DownloadSegment> getSegments() { return segments; }
    public synchronized void setSegments(List<DownloadSegment> segments) { this.segments = segments; }

    public synchronized int getProgressPercentage() {
        return progressPercentage(downloadedBytes, totalBytes);
    }

    public static int progressPercentage(long downloaded, long total) {
        return total <= 0 ? 0 : (int) Math.max(0, Math.min(100, 100.0 * downloaded / total));
    }

    public synchronized String getFormattedTotalSize() {
        return formatBytes(totalBytes);
    }

    public synchronized String getFormattedDownloadedSize() {
        return formatBytes(downloadedBytes);
    }

    public synchronized String getFormattedSpeed() {
        return formatSpeed(speedBytesPerSec);
    }

    public static String formatSpeed(long speed) {
        return formatBytes(speed) + "/s";
    }

    public synchronized String getFormattedEta() {
        return formatEta(downloadedBytes, totalBytes, speedBytesPerSec);
    }

    public static String formatEta(long downloaded, long total, long speed) {
        if (speed <= 0 || total <= 0 || downloaded >= total) {
            return "--:--";
        }
        long seconds = (total - Math.max(0, downloaded)) / speed;
        if (seconds <= 0) {
            return "< 1s";
        } else if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return String.format(Locale.US, "%dm %ds", seconds / 60, seconds % 60);
        } else {
            return String.format(Locale.US, "%dh %dm", seconds / 3600, (seconds % 3600) / 60);
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public synchronized String getAudioUrl() { return audioUrl; }
    public synchronized void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }

    public synchronized long getAudioBytes() { return audioBytes; }
    public synchronized void setAudioBytes(long audioBytes) { this.audioBytes = audioBytes; }
}

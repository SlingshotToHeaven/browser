package com.privacy.downloader.model;

import java.io.Serializable;

public class DownloadSegment implements Serializable {
    private final String taskId;
    private final int index;
    private final long startByte;
    private final long endByte;
    private long currentByte;
    private DownloadStatus status;

    public DownloadSegment(String taskId, int index, long startByte, long endByte, long currentByte, DownloadStatus status) {
        this.taskId = taskId;
        this.index = index;
        this.startByte = startByte;
        this.endByte = endByte;
        this.currentByte = currentByte;
        this.status = status != null ? status : DownloadStatus.PENDING;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getIndex() {
        return index;
    }

    public long getStartByte() {
        return startByte;
    }

    public long getEndByte() {
        return endByte;
    }

    public synchronized long getCurrentByte() {
        return currentByte;
    }

    public synchronized void setCurrentByte(long currentByte) {
        this.currentByte = currentByte;
    }

    public synchronized void addProgress(long bytes) {
        this.currentByte += bytes;
    }

    public synchronized DownloadStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(DownloadStatus status) {
        this.status = status;
    }

    public synchronized long getDownloadedBytes() {
        return Math.max(0, currentByte - startByte);
    }

    public long getTotalBytes() {
        if (endByte < startByte) return 0;
        return (endByte - startByte) + 1;
    }

    public synchronized boolean isComplete() {
        if (endByte < startByte && endByte > 0) {
            return false;
        }
        if (endByte <= 0) {
            return status == DownloadStatus.COMPLETED;
        }
        return currentByte > endByte || status == DownloadStatus.COMPLETED;
    }
}

package com.privacy.downloader.model;

public enum DownloadStatus {
    PENDING,
    CONNECTING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    WAITING_FOR_WIFI,
    WAITING_FOR_SCHEDULE;

    public boolean isActive() {
        return this == PENDING || this == CONNECTING || this == RUNNING;
    }

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}

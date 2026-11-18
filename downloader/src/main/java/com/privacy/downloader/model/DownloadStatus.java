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
    WAITING_FOR_SCHEDULE,
    WAITING_FOR_BATTERY;

    public boolean isWaiting() {
        return this == WAITING_FOR_WIFI || this == WAITING_FOR_SCHEDULE || this == WAITING_FOR_BATTERY;
    }

    public boolean isActive() {
        return this == PENDING || isTransferring();
    }

    public boolean isTransferring() {
        return this == CONNECTING || this == RUNNING;
    }

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}

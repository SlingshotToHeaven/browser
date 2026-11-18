package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

/** Queue admission uses one snapshot of all constraints, including when a worker drains. */
final class QueuePolicy {
    static final class Constraints {
        final boolean insideSchedule;
        final boolean batteryBlocked;
        final boolean wifiConnected;
        final boolean wifiOnly;
        final String scheduleReason;
        final int slots;

        Constraints(boolean insideSchedule, boolean batteryBlocked, boolean wifiConnected, boolean wifiOnly,
                    String scheduleReason, int slots) {
            this.insideSchedule = insideSchedule;
            this.batteryBlocked = batteryBlocked;
            this.wifiConnected = wifiConnected;
            this.wifiOnly = wifiOnly;
            this.scheduleReason = scheduleReason;
            this.slots = slots;
        }

        DownloadStatus eligibleState(DownloadItem item) {
            if (!insideSchedule) return DownloadStatus.WAITING_FOR_SCHEDULE;
            if (batteryBlocked) return DownloadStatus.WAITING_FOR_BATTERY;
            if ((wifiOnly || item.isWifiOnly()) && !wifiConnected) return DownloadStatus.WAITING_FOR_WIFI;
            return DownloadStatus.PENDING;
        }

        String reason(DownloadStatus state) {
            switch (state) {
                case WAITING_FOR_SCHEDULE: return scheduleReason;
                case WAITING_FOR_BATTERY: return "Waiting for power or battery above 15%";
                case WAITING_FOR_WIFI: return "Waiting for Wi-Fi network";
                default: return null;
            }
        }
    }

    private QueuePolicy() {}

    static boolean withinWindow(int now, int start, int end) {
        return start <= end ? now >= start && now < end : now >= start || now < end;
    }

    static boolean mayRetry(DownloadStatus state) {
        return state == DownloadStatus.PAUSED || state == DownloadStatus.FAILED || state.isWaiting();
    }

    static boolean automatic(DownloadStatus state) {
        return state.isActive() || state.isWaiting();
    }

    static boolean mayCancel(DownloadStatus state) {
        return automatic(state) || state == DownloadStatus.PAUSED;
    }
}

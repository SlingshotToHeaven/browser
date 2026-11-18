package com.privacy.downloader.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** A value snapshot of in-process transfer owners. No URLs, paths, checkpoints or history. */
public final class ActiveDownloads {
    public final boolean checked;
    public final int owners;
    public final List<Transfer> transfers;
    public final long downloadedBytes;
    public final long totalBytes;
    public final long speed;

    public ActiveDownloads(boolean checked, int owners, List<Transfer> transfers) {
        this.checked = checked;
        this.owners = owners;
        List<Transfer> ordered = new ArrayList<>(transfers);
        ordered.sort(Comparator.comparing(transfer -> transfer.id));
        this.transfers = Collections.unmodifiableList(ordered);
        long downloaded = 0, total = 0, rate = 0;
        for (Transfer transfer : ordered) {
            downloaded = sum(downloaded, transfer.downloadedBytes);
            if (transfer.totalBytes <= 0) total = -1;
            else if (total >= 0) total = sum(total, transfer.totalBytes);
            rate = sum(rate, transfer.speed);
        }
        downloadedBytes = downloaded;
        totalBytes = total;
        speed = rate;
    }

    public boolean idle() { return checked && owners == 0; }

    public Transfer find(String id) {
        for (Transfer transfer : transfers) if (transfer.id.equals(id)) return transfer;
        return null;
    }

    private static long sum(long a, long b) { return a + Math.min(Math.max(0, b), Long.MAX_VALUE - a); }

    @Override public boolean equals(Object object) {
        if (!(object instanceof ActiveDownloads)) return false;
        ActiveDownloads other = (ActiveDownloads) object;
        return checked == other.checked && owners == other.owners && transfers.equals(other.transfers);
    }

    @Override public int hashCode() { return Objects.hash(checked, owners, transfers); }

    public static final class Transfer {
        public final String id;
        public final String token;
        public final String name;
        public final DownloadStatus status;
        public final long downloadedBytes;
        public final long totalBytes;
        public final long speed;
        public final int threads;

        public Transfer(DownloadItem item) {
            synchronized (item) {
                id = item.getId();
                token = item.getAttemptToken();
                name = item.getFileName();
                status = item.getStatus();
                downloadedBytes = item.getDownloadedBytes();
                totalBytes = item.getTotalBytes();
                speed = Math.max(0, item.getSpeedBytesPerSec());
                threads = item.getNumSegments();
            }
        }

        @Override public boolean equals(Object object) {
            if (!(object instanceof Transfer)) return false;
            Transfer other = (Transfer) object;
            return id.equals(other.id) && Objects.equals(token, other.token) && Objects.equals(name, other.name)
                    && status == other.status && downloadedBytes == other.downloadedBytes && totalBytes == other.totalBytes
                    && speed == other.speed && threads == other.threads;
        }

        @Override public int hashCode() {
            return Objects.hash(id, token, name, status, downloadedBytes, totalBytes, speed, threads);
        }
    }
}

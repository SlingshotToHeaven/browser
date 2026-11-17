package com.privacy.downloader.engine;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BlobDownloadTask extends DownloadTask {

    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private FileOutputStream outputStream;
    private File activeTempFile;
    private volatile String errorReason = null;
    private long lastSpeedCheckTime = 0;
    private long lastTransferredBytes = 0;

    public BlobDownloadTask(DownloadItem item, DownloadDbHelper dbHelper, TaskListener listener, SpeedLimiter globalLimiter) {
        super(item, dbHelper, listener, globalLimiter);
    }

    public synchronized void setInitialMetadata(long totalSize) {
        if (totalSize > 0) {
            item.setTotalBytes(totalSize);
            dbHelper.insertOrUpdate(item);
            if (listener != null) {
                listener.onTaskUpdated(item);
            }
        }
    }

    public synchronized void appendChunk(byte[] chunkData, long offset, long totalSize) {
        if (isCancelled || isPaused) return;
        try {
            if (outputStream == null) {
                activeTempFile = new File(item.getTempPath());
                File parent = activeTempFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                outputStream = new FileOutputStream(activeTempFile, true);
            }

            outputStream.write(chunkData);
            outputStream.flush();

            long downloaded = offset + chunkData.length;
            item.setDownloadedBytes(downloaded);
            if (totalSize > 0) {
                item.setTotalBytes(totalSize);
            }

            long now = System.currentTimeMillis();
            if (lastSpeedCheckTime == 0) {
                lastSpeedCheckTime = now;
                lastTransferredBytes = downloaded;
            } else {
                long timeDiff = now - lastSpeedCheckTime;
                if (timeDiff >= 500) {
                    long bytesDiff = downloaded - lastTransferredBytes;
                    long speed = (bytesDiff * 1000) / timeDiff;
                    item.setSpeedBytesPerSec(speed);
                    lastSpeedCheckTime = now;
                    lastTransferredBytes = downloaded;
                }
            }

            if (listener != null) {
                listener.onTaskUpdated(item);
            }
        } catch (Exception e) {
            errorReason = "Failed writing blob data: " + e.getMessage();
            completionLatch.countDown();
        }
    }

    public synchronized void onStreamComplete() {
        closeStreamQuietly();
        completionLatch.countDown();
    }

    public synchronized void onStreamFailed(String error) {
        this.errorReason = error;
        closeStreamQuietly();
        completionLatch.countDown();
    }

    private void closeStreamQuietly() {
        if (outputStream != null) {
            try {
                outputStream.flush();
                outputStream.close();
            } catch (Exception ignored) {}
            outputStream = null;
        }
    }

    @Override
    public synchronized void pause() {
        isPaused = true;
        closeStreamQuietly();
        completionLatch.countDown();
    }

    @Override
    public synchronized void cancel() {
        isCancelled = true;
        closeStreamQuietly();
        if (activeTempFile != null && activeTempFile.exists()) {
            activeTempFile.delete();
        }
        completionLatch.countDown();
    }

    @Override
    public void run() {
        try {
            updateStatus(DownloadStatus.RUNNING, null);

            // Wait for JavaScript chunks to finish streaming (max 10 minutes)
            completionLatch.await(10, TimeUnit.MINUTES);

            closeStreamQuietly();

            if (isCancelled) {
                updateStatus(DownloadStatus.CANCELLED, null);
                if (activeTempFile != null && activeTempFile.exists()) activeTempFile.delete();
                return;
            }

            if (isPaused) {
                updateStatus(DownloadStatus.PAUSED, null);
                return;
            }

            if (errorReason != null) {
                updateStatus(DownloadStatus.FAILED, errorReason);
                if (activeTempFile != null && activeTempFile.exists()) activeTempFile.delete();
                if (listener != null) listener.onTaskFailed(item, errorReason);
                return;
            }

            // Move temp file to destination
            File dest = new File(item.getDestinationPath());
            if (activeTempFile != null && activeTempFile.exists()) {
                if (dest.exists()) dest.delete();
                activeTempFile.renameTo(dest);
            }

            item.setDownloadedBytes(dest.exists() ? dest.length() : item.getTotalBytes());
            item.setSpeedBytesPerSec(0);
            updateStatus(DownloadStatus.COMPLETED, null);

            if (listener != null) {
                listener.onTaskCompleted(item);
            }
        } catch (Exception e) {
            updateStatus(DownloadStatus.FAILED, e.getMessage());
            if (listener != null) listener.onTaskFailed(item, e.getMessage());
        }
    }
}

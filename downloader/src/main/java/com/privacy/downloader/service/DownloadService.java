package com.privacy.downloader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Trace;

import androidx.core.app.NotificationCompat;

import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.ActiveDownloads;
import com.privacy.downloader.model.DownloadItem;

public class DownloadService extends Service implements DownloadEngine.DownloadListener {

    public static final String CHANNEL_ID = "channel_downloads";
    public static final String CHANNEL_NAME = "Active Downloads";
    public static final String CHANNEL_COMPLETED_ID = "channel_downloads_completed";
    public static final String CHANNEL_COMPLETED_NAME = "Completed Downloads";
    private static final int NOTIFICATION_ID = 9001;

    private NotificationManager notificationManager;
    private DownloadEngine engine;
    private ActiveDownloads rendered;
    private PendingIntent openDownloads;
    private int lastStartId;
    private boolean foreground;
    private long sessionStartTime;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        Intent launch = new Intent(this, com.privacy.downloader.ui.DownloadsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openDownloads = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        engine = DownloadEngine.getInstance(this);
        engine.addListener(this);
        // Satisfy Android's startForegroundService deadline without waiting for a queue or DB read.
        enterForeground(engine.activeDownloads());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (!foreground) enterForeground(engine.activeDownloads());
        if (intent == null) engine.reconcile(); // Android recreated a previously active service.
        return stopIfIdle() ? START_NOT_STICKY : START_STICKY;
    }

    private boolean stopIfIdle() {
        // A draining worker still owns a slot. Don't stop a newer Android start request.
        if (lastStartId == 0 || !engine.activeDownloads().idle() || !stopSelfResult(lastStartId)) return false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.setCounter("Downloads.notificationOwners", 0);
        foreground = false;
        sessionStartTime = 0;
        return true;
    }

    @Override
    public void onDestroy() {
        engine.removeListener(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager != null) {
                NotificationChannel activeChannel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                );
                activeChannel.setDescription("Shows active download progress and speeds");
                activeChannel.setShowBadge(false);
                notificationManager.createNotificationChannel(activeChannel);

                NotificationChannel completeChannel = new NotificationChannel(
                        CHANNEL_COMPLETED_ID,
                        CHANNEL_COMPLETED_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                completeChannel.setDescription("Alerts when file downloads finish");
                notificationManager.createNotificationChannel(completeChannel);
            }
        }
    }

    private void enterForeground(ActiveDownloads active) {
        Trace.beginSection("Downloads.notification");
        try {
            Notification notification = buildProgressNotification(active);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
            rendered = active;
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onActiveDownloadsChanged(ActiveDownloads active) {
        if (active.idle()) {
            stopIfIdle();
            return;
        }
        if (!foreground || active.equals(rendered) || notificationManager == null) return;
        Trace.beginSection("Downloads.notification");
        try {
            notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(active));
            rendered = active;
        } finally {
            Trace.endSection();
        }
    }

    /** Formatting is constant work; the engine has already reduced the bounded owners to totals. */
    private Notification buildProgressNotification(ActiveDownloads active) {
        int count = active.transfers.size();
        String title = count == 1 ? "Downloading " + active.transfers.get(0).name
                : count == 0 ? "Download queue" : "Downloading " + count + " files";
        String speed = DownloadItem.formatSpeed(active.speed);
        int progress = DownloadItem.progressPercentage(active.downloadedBytes, active.totalBytes);
        boolean indeterminate = active.totalBytes <= 0;
        String text;
        if (count == 0) {
            text = !active.checked ? "Checking downloads…" : active.owners != 0 ? "Finishing download cleanup…" : "Idle";
        } else {
            text = DownloadItem.formatBytes(active.downloadedBytes) + " / "
                    + (indeterminate ? "Unknown size" : DownloadItem.formatBytes(active.totalBytes) + " (" + progress + "%)")
                    + " • " + speed;
            if (count == 1) text += " • ETA: " + DownloadItem.formatEta(active.downloadedBytes, active.totalBytes, active.speed);
        }
        if (sessionStartTime == 0) sessionStartTime = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.setCounter("Downloads.notificationOwners", active.owners);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(speed)
                .setSmallIcon(com.privacy.downloader.R.drawable.ic_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(sessionStartTime)
                .setShowWhen(false)
                .setProgress(100, progress, indeterminate)
                .setContentIntent(openDownloads)
                .build();
    }

    @Override
    public void onDownloadCompleted(DownloadItem item) {
        showCompletionNotification(item);
    }

    private void showCompletionNotification(DownloadItem item) {
        if (item == null) return;
        try {
            Intent launchIntent = new Intent(this, com.privacy.downloader.ui.DownloadsActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    (int) System.currentTimeMillis(),
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_COMPLETED_ID)
                    .setContentTitle("Download complete")
                    .setContentText(item.getFileName() + " (" + item.getFormattedTotalSize() + ")")
                    .setSmallIcon(com.privacy.downloader.R.drawable.ic_check_circle)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            int notifId = (int) (System.currentTimeMillis() % 100000) + 1000;
            if (notificationManager != null) {
                notificationManager.notify(notifId, builder.build());
            }
        } catch (Exception ignored) {}
    }
}

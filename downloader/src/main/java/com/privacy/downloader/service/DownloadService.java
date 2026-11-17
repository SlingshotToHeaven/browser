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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;

import androidx.core.app.NotificationCompat;

import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;

import java.util.List;

public class DownloadService extends Service implements DownloadEngine.DownloadListener {

    public static final String CHANNEL_ID = "channel_downloads";
    public static final String CHANNEL_NAME = "Active Downloads";
    public static final String CHANNEL_COMPLETED_ID = "channel_downloads_completed";
    public static final String CHANNEL_COMPLETED_NAME = "Completed Downloads";
    private static final int NOTIFICATION_ID = 9001;

    private NotificationManager notificationManager;
    private DownloadEngine engine;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private volatile long sessionStartTime = 0;

    private final Runnable notifTicker = new Runnable() {
        @Override
        public void run() {
            if (engine == null || engine.getActiveTaskCount() == 0) {
                sessionStartTime = 0;
                stopForeground(true);
                stopSelf();
                return;
            }
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, buildProgressNotification());
            }
            if (bgHandler != null) {
                bgHandler.postDelayed(this, 1000);
            }
        }
    };

    private void scheduleTicker() {
        if (bgHandler != null) {
            bgHandler.removeCallbacks(notifTicker);
            bgHandler.postDelayed(notifTicker, 1000);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        engine = DownloadEngine.getInstance(this);
        engine.addListener(this);

        bgThread = new HandlerThread("DownloadNotifThread", Process.THREAD_PRIORITY_BACKGROUND);
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        // CRITICAL FOR ANDROID 12/13/14: Call startForeground IMMEDIATELY in onCreate()
        // to prevent ForegroundServiceDidNotStartInTimeException crashes!
        startForegroundNotification();
        scheduleTicker();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();
        if (intent != null && "ACTION_STOP".equals(intent.getAction())) {
            sessionStartTime = 0;
            if (bgHandler != null) bgHandler.removeCallbacksAndMessages(null);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        scheduleTicker();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sessionStartTime = 0;
        if (engine != null) {
            engine.removeListener(this);
        }
        if (bgHandler != null) {
            bgHandler.removeCallbacksAndMessages(null);
        }
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
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

    private void startForegroundNotification() {
        Notification notification = buildProgressNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildProgressNotification() {
        List<DownloadItem> all = engine.getAllDownloads();
        int activeCount = 0;
        long totalDownloaded = 0;
        long totalExpected = 0;
        long aggregateSpeed = 0;
        DownloadItem sampleItem = null;

        for (DownloadItem item : all) {
            if (item.getStatus().isActive()) {
                activeCount++;
                sampleItem = item;
                totalDownloaded += item.getDownloadedBytes();
                if (item.getTotalBytes() > 0) {
                    totalExpected += item.getTotalBytes();
                }
                aggregateSpeed += item.getSpeedBytesPerSec();
            }
        }

        String title = (activeCount == 1 && sampleItem != null)
                ? "Downloading " + sampleItem.getFileName()
                : "Downloading " + activeCount + " files";

        String speedText = DownloadItem.formatBytes(aggregateSpeed) + "/s";

        int progress = 0;
        boolean indeterminate = true;
        if (totalExpected > 0) {
            progress = (int) Math.min(100, (totalDownloaded * 100) / totalExpected);
            indeterminate = false;
        }

        String contentText;
        if (activeCount == 0) {
            contentText = "Idle";
        } else if (activeCount == 1 && sampleItem != null) {
            contentText = sampleItem.getFormattedDownloadedSize() + " / " + sampleItem.getFormattedTotalSize()
                    + " (" + sampleItem.getProgressPercentage() + "%) • " + speedText
                    + " • ETA: " + sampleItem.getFormattedEta();
        } else {
            contentText = DownloadItem.formatBytes(totalDownloaded) + " / " + DownloadItem.formatBytes(totalExpected)
                    + " (" + progress + "%) • " + speedText;
        }

        Intent launchIntent = new Intent(this, com.privacy.downloader.ui.DownloadsActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        if (sessionStartTime == 0 && activeCount > 0) {
            sessionStartTime = System.currentTimeMillis();
        } else if (activeCount == 0) {
            sessionStartTime = 0;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSubText(speedText)
                .setSmallIcon(com.privacy.downloader.R.drawable.ic_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(sessionStartTime > 0 ? sessionStartTime : System.currentTimeMillis())
                .setShowWhen(false)
                .setProgress(100, progress, indeterminate)
                .setContentIntent(pendingIntent);

        return builder.build();
    }

    @Override
    public void onDownloadProgress(DownloadItem item) {
        // Handled cleanly by background notifTicker on DownloadNotifThread. Zero main thread overhead!
    }

    @Override
    public void onDownloadCompleted(DownloadItem item) {
        if (bgHandler != null) {
            bgHandler.post(() -> {
                if (notificationManager != null) {
                    notificationManager.notify(NOTIFICATION_ID, buildProgressNotification());
                }
                showCompletionNotification(item);
                if (engine == null || engine.getActiveTaskCount() == 0) {
                    stopForeground(true);
                    stopSelf();
                }
            });
        }
    }

    @Override
    public void onDownloadFailed(DownloadItem item, String error) {
        if (bgHandler != null) {
            bgHandler.post(() -> {
                if (notificationManager != null) {
                    notificationManager.notify(NOTIFICATION_ID, buildProgressNotification());
                }
                if (engine == null || engine.getActiveTaskCount() == 0) {
                    stopForeground(true);
                    stopSelf();
                }
            });
        }
    }

    @Override
    public void onQueueChanged() {
        if (bgHandler != null) {
            bgHandler.post(() -> {
                if (engine == null || engine.getActiveTaskCount() == 0) {
                    stopForeground(true);
                    stopSelf();
                } else {
                    if (notificationManager != null) {
                        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification());
                    }
                    scheduleTicker();
                }
            });
        }
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

package com.privacy.downloader.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.service.DownloadService;
import com.privacy.downloader.util.ArchiveExtractor;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadEngine implements DownloadTask.TaskListener {

    public interface DownloadListener {
        void onDownloadProgress(DownloadItem item);
        void onDownloadCompleted(DownloadItem item);
        void onDownloadFailed(DownloadItem item, String error);
        void onQueueChanged();
    }

    private static final String PREFS_NAME = "downloader_settings";
    private static final String KEY_WIFI_ONLY = "wifi_only_global";
    private static final String KEY_BATTERY_SAVER = "battery_saver";
    private static final String KEY_CATEGORY_SUBFOLDERS = "category_subfolders";
    private static final String KEY_MAX_CONCURRENT = "max_concurrent";
    private static final String KEY_SPEED_LIMIT = "speed_limit";
    private static final String KEY_SCHEDULE_ENABLED = "schedule_enabled";
    private static final String KEY_SCHEDULE_START_HOUR = "schedule_start_hour";
    private static final String KEY_SCHEDULE_START_MIN = "schedule_start_min";
    private static final String KEY_SCHEDULE_END_HOUR = "schedule_end_hour";
    private static final String KEY_SCHEDULE_END_MIN = "schedule_end_min";
    public static final String KEY_AUTO_EXTRACT_ZIP = "auto_extract_zip";
    public static final String KEY_DEFAULT_DOWNLOAD_DIR = "default_download_dir";
    public static final String KEY_MAX_RETRIES = "max_retries";

    private static DownloadEngine instance;
    private final Context context;
    private final DownloadDbHelper dbHelper;
    private final SpeedLimiter globalLimiter;
    private final ExecutorService queueExecutor;
    private final Handler mainHandler;
    private final SharedPreferences prefs;

    private int maxConcurrentDownloads = 3;
    private boolean wifiOnlyGlobal = false;
    private boolean batterySaverEnabled = false;
    private boolean categorySubfoldersEnabled = true;

    private boolean scheduleEnabled = false;
    private int scheduleStartHour = 2;
    private int scheduleStartMinute = 0;
    private int scheduleEndHour = 6;
    private int scheduleEndMinute = 0;
    private int maxRetries = 8;

    private final Map<String, DownloadTask> runningTasks = new ConcurrentHashMap<>();
    private final List<DownloadListener> listeners = Collections.synchronizedList(new ArrayList<>());

    public static synchronized DownloadEngine getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new DownloadEngine(context.getApplicationContext());
        }
        return instance;
    }

    private DownloadEngine(Context context) {
        this.context = context;
        this.dbHelper = DownloadDbHelper.getInstance(context);
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        this.wifiOnlyGlobal = prefs.getBoolean(KEY_WIFI_ONLY, false);
        this.batterySaverEnabled = prefs.getBoolean(KEY_BATTERY_SAVER, false);
        this.categorySubfoldersEnabled = prefs.getBoolean(KEY_CATEGORY_SUBFOLDERS, true);
        this.maxConcurrentDownloads = prefs.getInt(KEY_MAX_CONCURRENT, 3);
        this.maxRetries = prefs.getInt(KEY_MAX_RETRIES, 8);
        long savedSpeed = prefs.getLong(KEY_SPEED_LIMIT, 0);

        this.scheduleEnabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false);
        this.scheduleStartHour = prefs.getInt(KEY_SCHEDULE_START_HOUR, 2);
        this.scheduleStartMinute = prefs.getInt(KEY_SCHEDULE_START_MIN, 0);
        this.scheduleEndHour = prefs.getInt(KEY_SCHEDULE_END_HOUR, 6);
        this.scheduleEndMinute = prefs.getInt(KEY_SCHEDULE_END_MIN, 0);

        this.globalLimiter = new SpeedLimiter(savedSpeed);
        this.queueExecutor = Executors.newFixedThreadPool(6);
        this.mainHandler = new Handler(Looper.getMainLooper());

        startScheduleTicker();

        // Recover crashed / interrupted tasks
        dbHelper.resetActiveToPaused();
        registerNetworkAndBatteryReceivers();
    }

    public synchronized void addListener(DownloadListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(DownloadListener listener) {
        listeners.remove(listener);
    }

    public synchronized void enqueue(DownloadItem item) {
        dbHelper.insertOrUpdate(item);
        triggerQueueCheck();
        notifyQueueChanged();
    }

    public synchronized void pause(String taskId) {
        DownloadTask task = runningTasks.remove(taskId);
        if (task != null) {
            task.pause();
        } else {
            dbHelper.updateStatus(taskId, DownloadStatus.PAUSED, null);
        }
        triggerQueueCheck();
        notifyQueueChanged();
    }

    public synchronized void resume(String taskId) {
        DownloadItem item = dbHelper.getDownload(taskId);
        if (item != null && item.getStatus() != DownloadStatus.RUNNING) {
            item.setStatus(DownloadStatus.PENDING);
            dbHelper.insertOrUpdate(item);
            triggerQueueCheck();
            notifyQueueChanged();
        }
    }

    public synchronized void cancel(String taskId) {
        DownloadTask task = runningTasks.remove(taskId);
        if (task != null) {
            task.cancel();
        } else {
            dbHelper.updateStatus(taskId, DownloadStatus.CANCELLED, null);
        }
        triggerQueueCheck();
        notifyQueueChanged();
    }

    public synchronized void restart(String taskId) {
        DownloadItem item = dbHelper.getDownload(taskId);
        if (item != null) {
            cancel(taskId);
            // Delete partial file
            if (item.getTempPath() != null) {
                new File(item.getTempPath()).delete();
            }
            if (item.getDestinationPath() != null) {
                new File(item.getDestinationPath()).delete();
            }
            item.setDownloadedBytes(0);
            item.setSpeedBytesPerSec(0);
            item.setStatus(DownloadStatus.PENDING);
            item.setSegments(new ArrayList<>());
            dbHelper.insertOrUpdate(item);
            triggerQueueCheck();
            notifyQueueChanged();
        }
    }

    public synchronized void updateUrl(String taskId, String newUrl) {
        DownloadTask task = runningTasks.get(taskId);
        if (task != null) {
            task.updateUrl(newUrl);
        } else {
            dbHelper.updateUrl(taskId, newUrl);
        }
        resume(taskId);
    }

    public synchronized void delete(String taskId, boolean deleteFile) {
        DownloadTask task = runningTasks.remove(taskId);
        if (task != null) {
            task.cancel();
        }
        DownloadItem item = dbHelper.getDownload(taskId);
        if (item != null && deleteFile) {
            if (item.getTempPath() != null) new File(item.getTempPath()).delete();
            if (item.getDestinationPath() != null) new File(item.getDestinationPath()).delete();
        }
        dbHelper.deleteDownload(taskId);
        triggerQueueCheck();
        notifyQueueChanged();
    }

    public synchronized void setGlobalSpeedLimit(long bytesPerSec) {
        globalLimiter.setMaxBytesPerSecond(bytesPerSec);
        prefs.edit().putLong(KEY_SPEED_LIMIT, bytesPerSec).commit();
    }

    public synchronized void setMaxConcurrentDownloads(int max) {
        this.maxConcurrentDownloads = Math.max(1, max);
        prefs.edit().putInt(KEY_MAX_CONCURRENT, this.maxConcurrentDownloads).commit();
        triggerQueueCheck();
    }

    public synchronized int getMaxConcurrentDownloads() {
        return maxConcurrentDownloads;
    }

    public synchronized long getGlobalSpeedLimit() {
        return globalLimiter.getMaxBytesPerSecond();
    }

    public synchronized boolean isWifiOnlyGlobal() {
        return wifiOnlyGlobal;
    }

    public synchronized void setWifiOnlyGlobal(boolean enabled) {
        this.wifiOnlyGlobal = enabled;
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).commit();
        if (enabled && !isWifiConnected()) {
            for (Map.Entry<String, DownloadTask> entry : new ArrayList<>(runningTasks.entrySet())) {
                pauseForWifi(entry.getKey());
            }
        } else if (enabled && isWifiConnected()) {
            triggerQueueCheck();
        }
    }

    public synchronized boolean isBatterySaverEnabled() {
        return batterySaverEnabled;
    }

    public synchronized void setBatterySaverEnabled(boolean enabled) {
        this.batterySaverEnabled = enabled;
        prefs.edit().putBoolean(KEY_BATTERY_SAVER, enabled).commit();
    }

    public synchronized boolean isCategorySubfoldersEnabled() {
        return categorySubfoldersEnabled;
    }

    public synchronized void setCategorySubfoldersEnabled(boolean enabled) {
        this.categorySubfoldersEnabled = enabled;
        prefs.edit().putBoolean(KEY_CATEGORY_SUBFOLDERS, enabled).commit();
    }

    private String defaultDownloadDir = null;

    public synchronized String getDefaultDownloadDirectory() {
        return defaultDownloadDir;
    }

    public synchronized void setDefaultDownloadDirectory(String path) {
        this.defaultDownloadDir = path;
        if (path != null) {
            prefs.edit().putString(KEY_DEFAULT_DOWNLOAD_DIR, path).commit();
        } else {
            prefs.edit().remove(KEY_DEFAULT_DOWNLOAD_DIR).commit();
        }
    }

    public synchronized void reloadPreferences() {
        this.wifiOnlyGlobal = prefs.getBoolean(KEY_WIFI_ONLY, false);
        this.batterySaverEnabled = prefs.getBoolean(KEY_BATTERY_SAVER, false);
        this.categorySubfoldersEnabled = prefs.getBoolean(KEY_CATEGORY_SUBFOLDERS, true);
        this.maxConcurrentDownloads = prefs.getInt(KEY_MAX_CONCURRENT, 3);
        long savedSpeed = prefs.getLong(KEY_SPEED_LIMIT, 0);
        this.globalLimiter.setMaxBytesPerSecond(savedSpeed);
        this.scheduleEnabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false);
        this.scheduleStartHour = prefs.getInt(KEY_SCHEDULE_START_HOUR, 2);
        this.scheduleStartMinute = prefs.getInt(KEY_SCHEDULE_START_MIN, 0);
        this.scheduleEndHour = prefs.getInt(KEY_SCHEDULE_END_HOUR, 6);
        this.scheduleEndMinute = prefs.getInt(KEY_SCHEDULE_END_MIN, 0);
        this.defaultDownloadDir = prefs.getString(KEY_DEFAULT_DOWNLOAD_DIR, null);
        this.maxRetries = prefs.getInt(KEY_MAX_RETRIES, 8);
    }

    public synchronized int getMaxRetries() {
        return maxRetries;
    }

    public synchronized void setMaxRetries(int retries) {
        this.maxRetries = Math.max(1, Math.min(20, retries));
        prefs.edit().putInt(KEY_MAX_RETRIES, this.maxRetries).commit();
    }

    public synchronized DownloadItem getDownloadItem(String taskId) {
        DownloadTask task = runningTasks.get(taskId);
        if (task != null && task.getItem() != null) {
            return task.getItem();
        }
        DownloadItem item = dbHelper.getDownload(taskId);
        if (item != null && (item.getSegments() == null || item.getSegments().isEmpty())) {
            item.setSegments(dbHelper.getSegments(taskId));
        }
        return item;
    }

    public synchronized List<DownloadSegment> getTaskSegments(String taskId) {
        DownloadTask task = runningTasks.get(taskId);
        if (task != null && task.getItem() != null && task.getItem().getSegments() != null) {
            return new ArrayList<>(task.getItem().getSegments());
        }
        return dbHelper.getSegments(taskId);
    }

    public synchronized boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    public synchronized void setScheduleEnabled(boolean enabled) {
        this.scheduleEnabled = enabled;
        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).commit();
        onScheduleCheck();
    }

    public synchronized int getScheduleStartHour() { return scheduleStartHour; }
    public synchronized int getScheduleStartMinute() { return scheduleStartMinute; }
    public synchronized int getScheduleEndHour() { return scheduleEndHour; }
    public synchronized int getScheduleEndMinute() { return scheduleEndMinute; }

    public synchronized void setScheduleWindow(int startH, int startM, int endH, int endM) {
        this.scheduleStartHour = startH;
        this.scheduleStartMinute = startM;
        this.scheduleEndHour = endH;
        this.scheduleEndMinute = endM;
        prefs.edit()
                .putInt(KEY_SCHEDULE_START_HOUR, startH)
                .putInt(KEY_SCHEDULE_START_MIN, startM)
                .putInt(KEY_SCHEDULE_END_HOUR, endH)
                .putInt(KEY_SCHEDULE_END_MIN, endM)
                .commit();
        onScheduleCheck();
    }

    public synchronized boolean isWithinScheduleWindow() {
        if (!scheduleEnabled) return true;
        Calendar cal = Calendar.getInstance();
        int nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int startMinutes = scheduleStartHour * 60 + scheduleStartMinute;
        int endMinutes = scheduleEndHour * 60 + scheduleEndMinute;

        if (startMinutes <= endMinutes) {
            return nowMinutes >= startMinutes && nowMinutes < endMinutes;
        } else {
            // Over midnight (e.g. 23:00 to 06:00)
            return nowMinutes >= startMinutes || nowMinutes < endMinutes;
        }
    }

    private void startScheduleTicker() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (scheduleEnabled) {
                    onScheduleCheck();
                }
                mainHandler.postDelayed(this, 30000);
            }
        }, 30000);
    }

    private synchronized void onScheduleCheck() {
        if (!scheduleEnabled) return;
        boolean withinWindow = isWithinScheduleWindow();
        if (!withinWindow) {
            for (String taskId : new ArrayList<>(runningTasks.keySet())) {
                pauseForSchedule(taskId);
            }
        } else {
            List<DownloadItem> all = dbHelper.getAllDownloads();
            for (DownloadItem item : all) {
                if (item.getStatus() == DownloadStatus.WAITING_FOR_SCHEDULE) {
                    item.setStatus(DownloadStatus.PENDING);
                    item.setErrorMessage(null);
                    dbHelper.insertOrUpdate(item);
                }
            }
            triggerQueueCheck();
            notifyQueueChanged();
        }
    }

    private synchronized void pauseForSchedule(String taskId) {
        DownloadTask task = runningTasks.remove(taskId);
        if (task != null) {
            task.pause();
        }
        String windowDesc = String.format(Locale.US, "%02d:%02d - %02d:%02d", scheduleStartHour, scheduleStartMinute, scheduleEndHour, scheduleEndMinute);
        dbHelper.updateStatus(taskId, DownloadStatus.WAITING_FOR_SCHEDULE, "Scheduled window (" + windowDesc + ")");
        triggerQueueCheck();
        notifyQueueChanged();
    }

    private void registerNetworkAndBatteryReceivers() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        mainHandler.post(() -> onNetworkChanged());
                    }

                    @Override
                    public void onLost(Network network) {
                        mainHandler.post(() -> onNetworkChanged());
                    }
                });
            }
        } catch (Exception ignored) {}

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    onBatteryChanged(intent);
                }
            }, filter);
        } catch (Exception ignored) {}
    }

    public boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            return true;
        }
    }

    private synchronized void onNetworkChanged() {
        boolean isWifi = isWifiConnected();
        if (isWifi) {
            List<DownloadItem> all = dbHelper.getAllDownloads();
            boolean resumedAny = false;
            for (DownloadItem it : all) {
                if (it.getStatus() == DownloadStatus.WAITING_FOR_WIFI) {
                    it.setStatus(DownloadStatus.PENDING);
                    it.setErrorMessage(null);
                    dbHelper.insertOrUpdate(it);
                    resumedAny = true;
                }
            }
            if (resumedAny) {
                Toast.makeText(context, "Wi-Fi connected: Resuming downloads", Toast.LENGTH_SHORT).show();
                triggerQueueCheck();
                notifyQueueChanged();
            }
        } else {
            for (Map.Entry<String, DownloadTask> entry : new ArrayList<>(runningTasks.entrySet())) {
                DownloadItem it = entry.getValue().getItem();
                if (wifiOnlyGlobal || it.isWifiOnly()) {
                    pauseForWifi(entry.getKey());
                }
            }
        }
    }

    private synchronized void pauseForWifi(String taskId) {
        DownloadTask task = runningTasks.remove(taskId);
        if (task != null) {
            task.pause();
        }
        dbHelper.updateStatus(taskId, DownloadStatus.WAITING_FOR_WIFI, "Waiting for Wi-Fi network");
        triggerQueueCheck();
        notifyQueueChanged();
    }

    private synchronized void onBatteryChanged(Intent intent) {
        if (!batterySaverEnabled) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);

        if (level > 0 && scale > 0) {
            int batteryPct = (int) ((level / (float) scale) * 100);
            if (batteryPct <= 15 && !isCharging && !runningTasks.isEmpty()) {
                pauseAll();
                Toast.makeText(context, "Battery low (< 15%): Pausing downloads", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public synchronized void pauseAll() {
        for (String taskId : new ArrayList<>(runningTasks.keySet())) {
            pause(taskId);
        }
        for (DownloadItem item : dbHelper.getAllDownloads()) {
            if (item.getStatus().isActive() || item.getStatus() == DownloadStatus.PENDING) {
                dbHelper.updateStatus(item.getId(), DownloadStatus.PAUSED, null);
            }
        }
        triggerQueueCheck();
        notifyQueueChanged();
    }

    public synchronized void resumeAll() {
        boolean wifi = isWifiConnected();
        boolean withinSchedule = isWithinScheduleWindow();
        for (DownloadItem item : dbHelper.getAllDownloads()) {
            if (item.getStatus() == DownloadStatus.PAUSED || item.getStatus() == DownloadStatus.FAILED
                    || item.getStatus() == DownloadStatus.WAITING_FOR_WIFI || item.getStatus() == DownloadStatus.WAITING_FOR_SCHEDULE) {
                if (scheduleEnabled && !withinSchedule) {
                    item.setStatus(DownloadStatus.WAITING_FOR_SCHEDULE);
                    item.setErrorMessage("Scheduled window (" + String.format(Locale.US, "%02d:%02d - %02d:%02d", scheduleStartHour, scheduleStartMinute, scheduleEndHour, scheduleEndMinute) + ")");
                } else if ((wifiOnlyGlobal || item.isWifiOnly()) && !wifi) {
                    item.setStatus(DownloadStatus.WAITING_FOR_WIFI);
                    item.setErrorMessage("Waiting for Wi-Fi network");
                } else {
                    item.setStatus(DownloadStatus.PENDING);
                    item.setErrorMessage(null);
                }
                dbHelper.insertOrUpdate(item);
            }
        }
        triggerQueueCheck();
        notifyQueueChanged();
    }

    public synchronized void clearCompleted() {
        dbHelper.clearCompletedDownloads();
        notifyQueueChanged();
    }

    public synchronized List<DownloadItem> getAllDownloads() {
        List<DownloadItem> list = dbHelper.getAllDownloads();
        for (int i = 0; i < list.size(); i++) {
            DownloadItem dbItem = list.get(i);
            DownloadTask task = runningTasks.get(dbItem.getId());
            if (task != null) {
                list.set(i, task.getItem());
            }
        }
        return list;
    }

    public synchronized DownloadItem getDownload(String taskId) {
        DownloadTask task = runningTasks.get(taskId);
        if (task != null) {
            return task.getItem();
        }
        return dbHelper.getDownload(taskId);
    }

    private synchronized void triggerQueueCheck() {
        if (runningTasks.size() >= maxConcurrentDownloads) {
            updateForegroundService();
            return;
        }

        boolean withinSchedule = isWithinScheduleWindow();
        if (scheduleEnabled && !withinSchedule) {
            if (!runningTasks.isEmpty()) {
                for (String taskId : new ArrayList<>(runningTasks.keySet())) {
                    pauseForSchedule(taskId);
                }
            }
            updateForegroundService();
            return;
        }

        List<DownloadItem> all = dbHelper.getAllDownloads();
        boolean wifi = isWifiConnected();
        for (DownloadItem item : all) {
            if (runningTasks.size() >= maxConcurrentDownloads) break;
            if (item.getStatus() == DownloadStatus.PENDING && !runningTasks.containsKey(item.getId())) {
                if ((wifiOnlyGlobal || item.isWifiOnly()) && !wifi) {
                    item.setStatus(DownloadStatus.WAITING_FOR_WIFI);
                    item.setErrorMessage("Waiting for Wi-Fi network");
                    dbHelper.insertOrUpdate(item);
                    continue;
                }
                startTask(item);
            }
        }

        updateForegroundService();
    }

    private synchronized void startTask(DownloadItem item) {
        DownloadTask task;
        if (item.getUrl() != null && item.getUrl().startsWith("blob:")) {
            task = com.privacy.downloader.interceptor.BlobDownloadManager.getInstance(context).getOrCreateTask(item, dbHelper, this, globalLimiter);
        } else if (StreamDetector.isStream(item.getUrl(), item.getMimeType())
                || (item.getAudioUrl() != null && !item.getAudioUrl().trim().isEmpty())) {
            task = new StreamDownloadTask(item, dbHelper, this, globalLimiter);
        } else {
            task = new DownloadTask(item, dbHelper, this, globalLimiter);
        }
        runningTasks.put(item.getId(), task);
        queueExecutor.submit(task);
    }

    private void updateForegroundService() {
        Intent serviceIntent = new Intent(context, DownloadService.class);
        if (!runningTasks.isEmpty()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Throwable ignored) {}
        } else {
            try {
                serviceIntent.setAction("ACTION_STOP");
                context.startService(serviceIntent);
            } catch (Throwable ignored) {}
        }
    }

    private final Map<String, Long> lastTaskDispatchTime = new ConcurrentHashMap<>();

    @Override
    public void onTaskUpdated(DownloadItem item) {
        if (item == null) return;
        long now = System.currentTimeMillis();
        Long lastTime = lastTaskDispatchTime.get(item.getId());
        if (lastTime != null && now - lastTime < 500) {
            return;
        }
        lastTaskDispatchTime.put(item.getId(), now);
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (DownloadListener l : listeners) {
                    l.onDownloadProgress(item);
                }
            }
        });
    }

    @Override
    public void onTaskCompleted(DownloadItem item) {
        runningTasks.remove(item.getId());
        lastTaskDispatchTime.remove(item.getId());
        triggerQueueCheck();
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, "Download complete: " + item.getFileName(), Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
            synchronized (listeners) {
                for (DownloadListener l : listeners) {
                    l.onDownloadCompleted(item);
                }
            }
        });

        // Auto-extract ZIP archives if enabled
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoExtract = prefs.getBoolean(KEY_AUTO_EXTRACT_ZIP, false);
        if (autoExtract && item.getFileName() != null && item.getFileName().toLowerCase(Locale.US).endsWith(".zip")) {
            File zipFile = new File(item.getDestinationPath());
            if (zipFile.exists()) {
                String baseName = item.getFileName();
                int dotIdx = baseName.lastIndexOf('.');
                String folderName = (dotIdx != -1) ? baseName.substring(0, dotIdx) : baseName + "_extracted";
                File destDir = new File(zipFile.getParentFile(), folderName);

                ArchiveExtractor.extractZipAsync(zipFile, destDir, new ArchiveExtractor.ExtractionCallback() {
                    @Override
                    public void onProgress(String currentFile, int extractedCount) {}

                    @Override
                    public void onCompleted(File outputDir, int totalFiles) {
                        mainHandler.post(() -> {
                            try {
                                Toast.makeText(context, "Extracted " + totalFiles + " files to " + outputDir.getName(), Toast.LENGTH_LONG).show();
                            } catch (Exception ignored) {}
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            try {
                                Toast.makeText(context, "Extraction failed: " + message, Toast.LENGTH_SHORT).show();
                            } catch (Exception ignored) {}
                        });
                    }
                });
            }
        }
    }

    @Override
    public void onTaskFailed(DownloadItem item, String error) {
        runningTasks.remove(item.getId());
        lastTaskDispatchTime.remove(item.getId());
        triggerQueueCheck();
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, "Download failed: " + item.getFileName() + " (" + (error != null ? error : "Error") + ")", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
            synchronized (listeners) {
                for (DownloadListener l : listeners) {
                    l.onDownloadFailed(item, error);
                }
            }
        });
    }

    private void notifyQueueChanged() {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (DownloadListener l : listeners) {
                    l.onQueueChanged();
                }
            }
        });
    }

    public int getActiveTaskCount() {
        return runningTasks.size();
    }
}

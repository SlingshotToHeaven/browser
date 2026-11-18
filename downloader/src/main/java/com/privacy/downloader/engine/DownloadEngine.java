package com.privacy.downloader.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteException;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.util.Log;
import android.widget.Toast;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.db.DownloadHistory;
import com.privacy.downloader.model.ActiveDownloads;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.service.DownloadService;
import com.privacy.downloader.util.ArchiveExtractor;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class DownloadEngine {

    /** Main-thread delivery. Progress never invalidates history or delivers a live mutable task. */
    public interface DownloadListener {
        default void onActiveDownloadsChanged(ActiveDownloads active) {}
        default void onDownloadRowsChanged(Set<String> ids, boolean reset) {}
        default void onDownloadCompleted(DownloadItem item) {}
    }

    private static final String TAG = "DownloadEngine";
    private static final int WORKER_LIMIT = 10;
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
    private final ScheduledThreadPoolExecutor controller;
    private final ExecutorService detailsReader = Executors.newSingleThreadExecutor(r -> new Thread(r, "DownloadDetails"));
    private final Handler mainHandler;
    private final SharedPreferences prefs;
    private final DownloadQueue queue;
    private final CopyOnWriteArrayList<DownloadListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean checkQueued = new AtomicBoolean();
    private final AtomicBoolean activePosted = new AtomicBoolean();
    private final ActiveDownloadPublisher activePublisher;
    private final Set<String> changedRows = new HashSet<>(); // also the delivery lock
    private boolean resetRows;
    private boolean rowsPosted;
    private ActiveDownloads deliveredActive; // main only
    private ScheduledFuture<?> scheduleBoundary; // controller only
    private volatile boolean lowBatteryUnplugged;
    private boolean initialCheckFinished; // controller only

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
        this.globalLimiter = new SpeedLimiter(prefs.getLong(KEY_SPEED_LIMIT, 0));
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.controller = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "DownloadQueue"));
        controller.setRemoveOnCancelPolicy(true);
        this.queue = new DownloadQueue(dbHelper, Executors.newFixedThreadPool(WORKER_LIMIT), this::submitCommand,
                mainHandler::post, this::createTask, this::activeChanged, this::rowChanged, this::onTerminal);
        this.activePublisher = new ActiveDownloadPublisher((action, delay) -> {
            ScheduledFuture<?> pending = controller.schedule(action, delay, TimeUnit.MILLISECONDS);
            return () -> pending.cancel(false);
        }, this::captureActive, active -> postActive());

        registerConstraintEvents();
        reconcile();
    }

    public void addListener(DownloadListener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(DownloadListener listener) {
        listeners.remove(listener);
    }

    public void enqueue(DownloadItem item) throws IOException {
        enqueue(item, RequestContext.anonymous());
    }

    public void enqueue(DownloadItem item, RequestContext request) throws IOException {
        if (request.isPrivate()) throw new IOException(RequestContext.PRIVATE_UNAVAILABLE);
        if (DownloadQueue.isBlob(item.getUrl())) throw new IOException(BlobIntake.SOURCE_GONE);
        String url = item.getUrl();
        if (RequestContext.origin(url) == null && (url == null || !url.startsWith("data:"))) {
            throw new IOException("Use an HTTP or HTTPS download link without embedded credentials.");
        }
        if (item.getAudioUrl() != null && RequestContext.origin(item.getAudioUrl()) == null) {
            throw new IOException("The audio track must use an HTTP or HTTPS link without embedded credentials.");
        }
        synchronized (dbHelper) {
            reserveAndInsert(item);
            // Like blob intake, a browser context is assigned by job ID, atomically with admission.
            queue.registerRequest(item, request);
        }
        rowChanged(item.getId());
        reconcile();
    }

    /** Only the originating tab's confirmation can supply an ephemeral blob source. */
    public void enqueueBlob(DownloadItem item, BlobIntake intake) throws IOException {
        boolean accepted = false;
        try {
            if (!DownloadQueue.isBlob(item.getUrl()) || !intake.isPending()) throw new IOException(BlobIntake.SOURCE_GONE);
            synchronized (dbHelper) {
                reserveAndInsert(item);
                // The controller reads rows under the same DB monitor and cannot see this row
                // before its job ID is bound. A failed insert never registers an intake.
                queue.registerBlob(item.getId(), intake);
            }
            accepted = true;
        } finally {
            if (!accepted) intake.close();
        }
        rowChanged(item.getId());
        reconcile();
    }

    private void reserveAndInsert(DownloadItem item) throws IOException {
        try {
            dbHelper.enqueueDownload(item, DownloadQueue.requestedDestination(item, true));
        } catch (SQLiteException e) {
            throw new IOException("Could not reserve a destination for this download.", e);
        }
    }

    public void pause(String id) { submitCommand(() -> queue.pause(id)); }
    public void resume(String id) { submitCommand(() -> queue.resume(id)); }
    public void cancel(String id) { submitCommand(() -> queue.cancel(id)); }
    public void pauseAll() { submitCommand(queue::pauseAll); }
    public void resumeAll() { submitCommand(queue::resumeAll); }
    public void clearCompleted() { submitCommand(queue::clearCompleted); }

    public void delete(String id, boolean deleteFile) { delete(id, deleteFile, null); }

    /** Reports removal of the queue row, not a guarantee that external files could be unlinked. */
    public void delete(String id, boolean deleteFile, Consumer<Boolean> result) {
        submitCommand(() -> queue.delete(id, deleteFile, result == null ? null : removed -> mainHandler.post(() -> result.accept(removed))));
    }

    /** A user-selected batch is one controller command, not one queue reconciliation per row. */
    public void deleteSelected(List<String> ids, Consumer<Integer> result) {
        if (ids.isEmpty()) return;
        final List<String> selection = new ArrayList<>(ids);
        submitCommand(() -> {
            int[] remaining = {selection.size()}, removed = {0};
            Consumer<Boolean> onRemoved = success -> {
                if (success) removed[0]++;
                if (--remaining[0] == 0) mainHandler.post(() -> result.accept(removed[0]));
            };
            for (String id : selection) {
                try { queue.delete(id, true, onRemoved); }
                catch (RuntimeException e) { logQueueError(e); }
            }
        });
    }

    public static boolean canStartOver(DownloadItem item) { return DownloadQueue.canStartOver(item); }

    public void restart(String id, Consumer<Boolean> result) {
        submitResult(() -> queue.restart(id), result);
    }

    public void updateUrl(String id, String newUrl, Consumer<Boolean> result) {
        submitResult(() -> queue.updateUrl(id, newUrl), result);
    }

    private void submitResult(BooleanSupplier action, Consumer<Boolean> result) {
        submitCommand(() -> {
            boolean success = false;
            try {
                success = action.getAsBoolean();
            } finally {
                boolean accepted = success;
                mainHandler.post(() -> result.accept(accepted));
            }
        });
    }

    private void submitCommand(Runnable command) {
        controller.execute(() -> {
            try {
                command.run();
            } catch (RuntimeException e) {
                logQueueError(e);
            } finally {
                reconcileOnController();
            }
        });
    }

    public void reconcile() {
        if (!checkQueued.compareAndSet(false, true)) return;
        controller.execute(() -> {
            checkQueued.set(false);
            reconcileOnController();
        });
    }

    private void reconcileOnController() {
        Trace.beginSection("Downloads.reconcile");
        try {
            Map<String, ?> settings = prefs.getAll();
            DownloadSchedule schedule = new DownloadSchedule(Boolean.TRUE.equals(settings.get(KEY_SCHEDULE_ENABLED)),
                    minuteSetting(settings, KEY_SCHEDULE_START_HOUR, KEY_SCHEDULE_START_MIN, 2),
                    minuteSetting(settings, KEY_SCHEDULE_END_HOUR, KEY_SCHEDULE_END_MIN, 6));
            ZonedDateTime now = schedule.enabled ? ZonedDateTime.now() : null;
            QueuePolicy.Constraints constraints = new QueuePolicy.Constraints(!schedule.enabled || schedule.includes(now),
                    Boolean.TRUE.equals(settings.get(KEY_BATTERY_SAVER)) && lowBatteryUnplugged, isWifiConnected(),
                    Boolean.TRUE.equals(settings.get(KEY_WIFI_ONLY)), schedule.enabled ? scheduleReason(schedule) : null,
                    clampConcurrency(intSetting(settings, KEY_MAX_CONCURRENT, 3)));
            queue.reconcile(constraints);
            armScheduleBoundary(schedule, now);
        } catch (RuntimeException e) {
            logQueueError(e);
        } finally {
            initialCheckFinished = true;
            int owners = queue.ownerCount();
            activePublisher.reconciled(true, owners);
            // Every actual admission/reconcile can retry an Android-denied or racing service start.
            if (owners != 0) startForegroundService();
            Trace.endSection();
        }
    }

    private void armScheduleBoundary(DownloadSchedule schedule, ZonedDateTime now) {
        if (scheduleBoundary != null) scheduleBoundary.cancel(false);
        scheduleBoundary = null;
        boolean work = schedule.enabled && schedule.start != schedule.end
                && (queue.ownerCount() != 0 || dbHelper.hasAutomaticDownloads());
        Instant next = work ? schedule.nextBoundary(now, true) : null;
        // In-process and best effort: does not wake a suspended or stopped app.
        if (next != null) {
            scheduleBoundary = controller.schedule(() -> {
                Trace.beginSection("Downloads.scheduleBoundary");
                try {
                    reconcileOnController();
                } finally {
                    Trace.endSection();
                }
            }, Math.max(0, next.toEpochMilli() - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.setCounter("Downloads.scheduleArmed", next == null ? 0 : 1);
    }

    private static int minuteSetting(Map<String, ?> settings, String hour, String minute, int defaultHour) {
        return Math.floorMod(intSetting(settings, hour, defaultHour), 24) * 60
                + Math.floorMod(intSetting(settings, minute, 0), 60);
    }

    private static String scheduleReason(DownloadSchedule schedule) {
        return String.format(Locale.US, "Scheduled window (%02d:%02d - %02d:%02d)",
                schedule.start / 60, schedule.start % 60, schedule.end / 60, schedule.end % 60);
    }

    private static void logQueueError(RuntimeException error) {
        // DB/IO exceptions can include URLs or filenames. Keep them out of logcat.
        Log.w(TAG, "Download queue operation failed: " + error.getClass().getSimpleName());
    }

    private DownloadTask createTask(DownloadItem item, DownloadTask.TaskListener listener, BlobIntake blob,
                                    RequestContext request) {
        if (DownloadQueue.isBlob(item.getUrl())) {
            return new BlobDownloadTask(item, dbHelper, listener, globalLimiter, blob);
        }
        if (StreamDetector.needsMux(item)) return new StreamDownloadTask(item, dbHelper, listener, globalLimiter, request);
        return new DownloadTask(item, dbHelper, listener, globalLimiter, request);
    }

    private void registerConstraintEvents() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                        boolean low = batteryBlocked(intent);
                        if (low == lowBatteryUnplugged) return;
                        lowBatteryUnplugged = low;
                    }
                    reconcile();
                }
            };
            Intent current = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    : context.registerReceiver(receiver, filter);
            lowBatteryUnplugged = batteryBlocked(current);
        } catch (RuntimeException e) {
            logQueueError(e);
        }

        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm != null) cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { reconcile(); }
                @Override public void onLost(Network network) { reconcile(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { reconcile(); }
            });
        } catch (RuntimeException e) {
            logQueueError(e);
        }
    }

    private static boolean batteryBlocked(Intent intent) {
        if (intent == null) return false;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return level >= 0 && scale > 0 && level * 100L <= scale * 15L
                && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
                && status != BatteryManager.BATTERY_STATUS_CHARGING && status != BatteryManager.BATTERY_STATUS_FULL;
    }

    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int intSetting(Map<String, ?> settings, String key, int fallback) {
        Object value = settings.get(key);
        return value instanceof Integer ? (int) value : fallback;
    }

    public void setQueueConstraints(boolean wifiOnly, boolean batterySaver, int slots, boolean scheduleEnabled,
                                    int startHour, int startMinute, int endHour, int endMinute) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).putBoolean(KEY_BATTERY_SAVER, batterySaver)
                .putInt(KEY_MAX_CONCURRENT, clampConcurrency(slots)).putBoolean(KEY_SCHEDULE_ENABLED, scheduleEnabled)
                .putInt(KEY_SCHEDULE_START_HOUR, startHour).putInt(KEY_SCHEDULE_START_MIN, startMinute)
                .putInt(KEY_SCHEDULE_END_HOUR, endHour).putInt(KEY_SCHEDULE_END_MIN, endMinute).apply();
        reconcile();
    }

    private static int clampConcurrency(int slots) { return Math.max(1, Math.min(slots, WORKER_LIMIT)); }
    public int getMaxConcurrentDownloads() { return clampConcurrency(prefs.getInt(KEY_MAX_CONCURRENT, 3)); }
    public boolean isWifiOnlyGlobal() { return prefs.getBoolean(KEY_WIFI_ONLY, false); }
    public boolean isBatterySaverEnabled() { return prefs.getBoolean(KEY_BATTERY_SAVER, false); }
    public boolean isScheduleEnabled() { return prefs.getBoolean(KEY_SCHEDULE_ENABLED, false); }
    public int getScheduleStartHour() { return prefs.getInt(KEY_SCHEDULE_START_HOUR, 2); }
    public int getScheduleStartMinute() { return prefs.getInt(KEY_SCHEDULE_START_MIN, 0); }
    public int getScheduleEndHour() { return prefs.getInt(KEY_SCHEDULE_END_HOUR, 6); }
    public int getScheduleEndMinute() { return prefs.getInt(KEY_SCHEDULE_END_MIN, 0); }
    public int getMaxRetries() { return prefs.getInt(KEY_MAX_RETRIES, 8); }
    public boolean isCategorySubfoldersEnabled() { return prefs.getBoolean(KEY_CATEGORY_SUBFOLDERS, true); }
    public String getDefaultDownloadDirectory() { return prefs.getString(KEY_DEFAULT_DOWNLOAD_DIR, null); }
    public long getGlobalSpeedLimit() { return globalLimiter.getMaxBytesPerSecond(); }

    public void setCategorySubfoldersEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_CATEGORY_SUBFOLDERS, enabled).apply(); }
    public void setDefaultDownloadDirectory(String path) { prefs.edit().putString(KEY_DEFAULT_DOWNLOAD_DIR, path).apply(); }
    public void setMaxRetries(int retries) { prefs.edit().putInt(KEY_MAX_RETRIES, Math.max(1, Math.min(20, retries))).apply(); }
    public void setGlobalSpeedLimit(long bytesPerSec) {
        long rate = Math.max(0, bytesPerSec);
        globalLimiter.setMaxBytesPerSecond(rate);
        prefs.edit().putLong(KEY_SPEED_LIMIT, rate).apply();
    }

    public ActiveDownloads activeDownloads() { return activePublisher.current(); }

    public void readDownload(String id, boolean details, Consumer<DownloadItem> result) {
        readAsync(() -> {
            if (!details) return dbHelper.getDownload(id);
            DownloadItem live = queue.activeDetails(id);
            return live != null ? live : dbHelper.getDownloadWithSegments(id);
        }, result);
    }

    public void findIncompleteDownload(String name, Consumer<DownloadHistory.Duplicate> result) {
        readAsync(() -> dbHelper.findIncomplete(name), result);
    }

    private <T> void readAsync(Supplier<T> read, Consumer<T> result) {
        detailsReader.execute(() -> {
            T value = null;
            try {
                value = read.get();
            } catch (RuntimeException e) {
                logQueueError(e);
            }
            T snapshot = value;
            mainHandler.post(() -> result.accept(snapshot));
        });
    }

    private void startForegroundService() {
        try {
            context.startForegroundService(new Intent(context, DownloadService.class));
        } catch (IllegalStateException | SecurityException ignored) {
            // Android can reject background starts. The next foreground/event check can try again.
        }
    }

    private ActiveDownloads captureActive() {
        Trace.beginSection("Downloads.snapshotActive");
        try {
            return queue.snapshotActive(initialCheckFinished);
        } finally {
            Trace.endSection();
        }
    }

    private void activeChanged() { activePublisher.changed(); }

    private void postActive() {
        if (!activePosted.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            activePosted.set(false);
            ActiveDownloads snapshot = activeDownloads();
            if (snapshot.equals(deliveredActive)) return;
            deliveredActive = snapshot;
            for (DownloadListener listener : listeners) listener.onActiveDownloadsChanged(snapshot);
        });
    }

    private void onTerminal(DownloadItem item) {
        if (item.getStatus() == DownloadStatus.COMPLETED) {
            Toast.makeText(context, "Download complete: " + item.getFileName(), Toast.LENGTH_LONG).show();
            for (DownloadListener listener : listeners) listener.onDownloadCompleted(item);
            maybeExtractZip(item);
        } else {
            String error = item.getErrorMessage();
            Toast.makeText(context, "Download failed: " + item.getFileName() + " (" + (error != null ? error : "Error") + ")",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void maybeExtractZip(DownloadItem item) {
        String name = item.getFileName();
        if (!prefs.getBoolean(KEY_AUTO_EXTRACT_ZIP, false) || name == null || !name.toLowerCase(Locale.US).endsWith(".zip")) return;
        File zip = new File(item.getDestinationPath());
        File output = new File(zip.getParentFile(), name.substring(0, name.length() - 4) + "_extracted");
        ArchiveExtractor.extractZipAsync(zip, output, new ArchiveExtractor.ExtractionCallback() {
            @Override public void onProgress(String currentFile, int extractedCount) {}
            @Override public void onCompleted(File dir, int totalFiles) {
                mainHandler.post(() -> Toast.makeText(context, "Extracted " + totalFiles + " files to " + dir.getName(), Toast.LENGTH_LONG).show());
            }
            @Override public void onError(String message) {
                mainHandler.post(() -> Toast.makeText(context, "Extraction failed: " + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void rowChanged(String id) {
        synchronized (changedRows) {
            if (!resetRows) {
                if (id != null) changedRows.add(id);
                if (id == null || changedRows.size() > DownloadHistory.ID_BATCH) {
                    resetRows = true;
                    changedRows.clear();
                }
            }
            if (rowsPosted) return;
            rowsPosted = true;
        }
        mainHandler.postDelayed(this::deliverRows, 80);
    }

    private void deliverRows() {
        Set<String> ids;
        boolean reset;
        synchronized (changedRows) {
            reset = resetRows;
            ids = Collections.unmodifiableSet(new HashSet<>(changedRows));
            resetRows = false;
            rowsPosted = false;
            changedRows.clear();
        }
        for (DownloadListener listener : listeners) listener.onDownloadRowsChanged(ids, reset);
    }
}

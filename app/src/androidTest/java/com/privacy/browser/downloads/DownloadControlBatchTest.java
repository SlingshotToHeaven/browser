package com.privacy.browser.downloads;

import android.app.NotificationManager;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.service.DownloadService;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.Arrays;

import static com.privacy.downloader.model.DownloadStatus.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class DownloadControlBatchTest extends DownloadBatchSupport {
    private static final byte[] BYTES = new byte[LENGTH];
    static { Arrays.fill(BYTES, (byte) 'd'); }

    @Test public void wifiWaitUsesTheSavedRangeAndKeepsManualPause() throws Exception {
        downloads();
        shell("svc wifi enable");
        long until = SystemClock.uptimeMillis() + 12000;
        while (!wifi() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        assumeTrue("Emulator did not provide a default Wi-Fi transport", wifi());
        String manual = manuallyPause("wifi-paused.bin");
        constraints(true, false, 2, false, 0, 0);
        String name = "wifi-active.bin";
        DownloadItem item = item("/range/" + name, name);
        item.setWifiOnly(true);
        control("arm", name);
        String active = enqueue(item);
        onPrefix(active);
        try {
            shell("svc wifi disable");
            state(active, WAITING_FOR_WIFI);
            assertFalse(wifi());
            long offset = stopAndCheckpoint(active, name);
            constraints(false, false, 2, false, 0, 0);
            state(active, WAITING_FOR_WIFI); // per-job flag still applies
            assertEquals(2, requests("/range/" + name).length());
            state(manual, PAUSED);
            shell("svc wifi enable");
            finishFrom(active, name, offset);
            state(manual, PAUSED);
            assertEquals(2, requests("/range/wifi-paused.bin").length());
            evidence("active offset=" + offset + "; Wi-Fi-only persisted; manual pause unchanged");
        } finally {
            shell("svc wifi enable");
        }
    }

    @Test public void chargingAndScheduleReconcileAnAlreadyRunningJob() throws Exception {
        downloads();
        String name = "power-schedule.bin";
        try {
            shell("dumpsys battery set ac 1");
            shell("dumpsys battery set status 2");
            constraints(false, true, 2, false, 0, 0);
            control("arm", name);
            String id = range(name);
            onPrefix(id);
            // An equal start/end window is deliberately closed under the product's schedule policy.
            constraints(false, true, 2, true, 123, 123);
            state(id, WAITING_FOR_SCHEDULE);
            long offset = stopAndCheckpoint(id, name);
            shell("dumpsys battery unplug");
            shell("dumpsys battery set status 3");
            shell("dumpsys battery set level 10");
            LocalTime now = LocalTime.now();
            int minute = now.getHour() * 60 + now.getMinute();
            constraints(false, true, 2, true, (minute + 1435) % 1440, (minute + 5) % 1440);
            state(id, WAITING_FOR_BATTERY);
            assertEquals(2, requests("/range/" + name).length());
            shell("dumpsys battery set ac 1");
            shell("dumpsys battery set status 2");
            finishFrom(id, name, offset);
            evidence("running -> scheduled -> low battery -> charging; exact suffix from " + offset);
        } finally {
            shell("dumpsys battery reset");
        }
    }

    @Test public void twoTransfersCanChangeCapPauseAndCancelWithoutStarvation() throws Exception {
        downloads();
        constraints(false, false, 2, false, 0, 0);
        engine.setGlobalSpeedLimit(2048);
        String a = range("cap-keep.bin"), b = range("cap-pause.bin");
        waitFor("two actual HTTP bodies admitted at 2 KiB/s", () -> requests("/range/cap-keep.bin").length() == 2
                && requests("/range/cap-pause.bin").length() == 2 && engine.activeDownloads().owners == 2
                && engine.activeDownloads().find(a) != null && engine.activeDownloads().find(a).downloadedBytes > 0
                && engine.activeDownloads().find(b) != null && engine.activeDownloads().find(b).downloadedBytes > 0);
        assertEquals(2048, engine.getGlobalSpeedLimit());
        engine.pause(b);
        state(b, PAUSED);
        engine.setGlobalSpeedLimit(0);
        completed(a, LENGTH, digest());
        drain();
        assertTrue(db.getDownload(b).getDownloadedBytes() < LENGTH);
        assertEquals(2, requests("/range/cap-pause.bin").length());
        engine.setGlobalSpeedLimit(2048);
        constraints(false, false, 2, true, 0, 0);
        String cancelled = range("cap-cancel.bin");
        state(cancelled, WAITING_FOR_SCHEDULE);
        engine.cancel(cancelled);
        state(cancelled, CANCELLED);
        constraints(false, false, 2, false, 0, 0);
        engine.resume(b);
        waitFor("explicit Resume opened the paused body again", () -> requests("/range/cap-pause.bin").length() == 3);
        assertEquals(2048, engine.getGlobalSpeedLimit());
        engine.setGlobalSpeedLimit(0);
        completed(b, LENGTH, digest());
        assertEquals(0, requests("/range/cap-cancel.bin").length());
        notPublished(state(cancelled, CANCELLED));
        evidence("2 KiB/s -> unlimited -> capped -> unlimited; paused/resumed one and cancelled a waiting third; not wire-rate evidence");
    }

    @Test public void actualForegroundServiceCanBeRecreatedWithoutReplacingItsWorker() throws Exception {
        downloads();
        String name = "service-worker.bin";
        control("arm", name);
        String id = range(name);
        onPrefix(id);
        waitFor("foreground notification 9001", this::serviceNotification);
        String token = db.getDownload(id).getAttemptToken();
        assertNotNull(token);
        shell("input keyevent KEYCODE_HOME");
        waitFor("Downloads is backgrounded", () -> !ui(activity::hasWindowFocus));
        assertTrue(serviceNotification());
        assertTrue(app.stopService(new Intent(app, DownloadService.class)));
        waitFor("service notification removed", () -> !serviceNotification());
        downloads(); // foreground Activity resumes; the production reconciliation restarts the service
        waitFor("foreground service recreated", this::serviceNotification);
        assertEquals(token, db.getDownload(id).getAttemptToken());
        control("release", name);
        completed(id, LENGTH, digest());
        waitFor("idle service retired", () -> !serviceNotification());
        assertEquals(2, requests("/range/" + name).length());
        evidence("Home -> stop service -> relaunch Downloads -> service restored, same attempt, notification removed at idle");
    }

    /** Invoked alone by the host. The verified force-stop intentionally ends instrumentation. */
    @Test public void stageForHostForceStop() throws Exception {
        downloads();
        manuallyPause("relaunch-paused.bin");
        String active = "relaunch-active.bin";
        control("arm", active);
        String id = range(active);
        onPrefix(id);
        waitFor("active prefix persisted by the normal worker", () -> db.getDownload(id).getDownloadedBytes() == PREFIX);
        evidence("ready for host force-stop");
        SystemClock.sleep(25000);
        fail("Host did not force-stop the held active attempt");
    }

    private boolean wifi() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private boolean serviceNotification() {
        for (StatusBarNotification n : app.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (n.getId() == 9001 && DownloadService.CHANNEL_ID.equals(n.getNotification().getChannelId())) return true;
        }
        return false;
    }

    private String manuallyPause(String name) throws Exception {
        control("arm", name);
        String job = range(name);
        onPrefix(job);
        engine.pause(job);
        state(job, PAUSED);
        stopAndCheckpoint(job, name);
        return job;
    }

    private void onPrefix(String id) throws Exception {
        waitFor("initial prefix written by worker", () -> engine.activeDownloads().find(id) != null
                && engine.activeDownloads().find(id).downloadedBytes == PREFIX);
    }

    private long stopAndCheckpoint(String id, String name) throws Exception {
        // Release the held socket only after the queue has requested stop. Reads that
        // return afterwards must not append to the stage or mark the job complete.
        waitFor("stopped row left active progress", () -> engine.activeDownloads().find(id) == null);
        control("release", name);
        drain();
        DownloadItem row = db.getDownloadWithSegments(id);
        assertEquals(ETAG, row.getEtag());
        assertEquals(PREFIX, row.getDownloadedBytes());
        assertEquals(1, row.getSegments().size());
        DownloadSegment segment = row.getSegments().get(0);
        assertEquals(PREFIX, segment.getCurrentByte());
        assertArrayEquals(Arrays.copyOf(BYTES, PREFIX), Arrays.copyOf(Files.readAllBytes(Paths.get(row.getTempPath())), PREFIX));
        notPublished(row);
        return row.getDownloadedBytes();
    }

    private void finishFrom(String id, String name, long offset) throws Exception {
        completed(id, LENGTH, digest());
        JSONArray requests = requests("/range/" + name);
        assertEquals(3, requests.length());
        JSONObject resumed = requests.getJSONObject(2);
        assertEquals(offset, resumed.getLong("start"));
        assertEquals(LENGTH - 1, resumed.getInt("end"));
        assertTrue(resumed.getBoolean("conditional"));
        assertEquals(206, resumed.getInt("code"));
    }

    private static String digest() throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(BYTES)); }
}

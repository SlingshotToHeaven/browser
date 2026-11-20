package com.privacy.browser.downloads;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.ui.DownloadsActivity;
import com.privacy.downloader.util.PublishedFileIdentity;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Internal helper for the single disposable CI profile, not a test-profile reset API. */
public abstract class DownloadBatchSupport {
    protected static final String BASE = "http://127.0.0.1:8024";
    protected static final int LENGTH = 294917;
    protected static final int PREFIX = 65536;
    protected static final String ETAG = "\"download-batch-1\"";
    protected final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
    protected final Context app = ins.getTargetContext().getApplicationContext();
    protected final List<String> owned = new ArrayList<>();
    protected DownloadDbHelper db;
    protected DownloadEngine engine;
    protected Activity activity;
    protected Path directory;
    @Rule public final TestName labTest = new TestName();

    @Before public void setupBatch() throws Exception {
        boolean lab = DownloadDeviceLab.requested();
        if (lab) {
            DownloadDeviceLab.requireProfile(ins, getClass(), labTest.getMethodName(),
                    shell("getprop ro.kernel.qemu"), shell("getprop ro.boot.qemu"));
        } else {
            assumeTrue("The batch must use a disposable CI emulator", "ci-avd".equals(
                    InstrumentationRegistry.getArguments().getString("download_batch")));
            assumeTrue("Not an emulator", "1".equals(shell("getprop ro.kernel.qemu").trim())
                    || "1".equals(shell("getprop ro.boot.qemu").trim()));
        }
        assertEquals("com.browser", app.getPackageName());
        db = DownloadDbHelper.getInstance(app);
        if (lab) assumeTrue("Physical test needs a fresh synthetic download profile", db.countDownloads() == 0);
        directory = Files.createTempDirectory(app.getFilesDir().toPath(), "download-batch-").toRealPath();
        engine = DownloadEngine.getInstance(app);
        engine.setDefaultDownloadDirectory(directory.toString());
        engine.setCategorySubfoldersEnabled(false);
        engine.setMaxRetries(1);
        engine.setGlobalSpeedLimit(0);
        constraints(false, false, 3, false, 0, 0);
    }

    @After public void finishBatch() throws Exception {
        if (engine == null) return;
        for (String id : owned) engine.cancel(id);
        engine.setGlobalSpeedLimit(0);
        if (activity != null && !activity.isDestroyed()) ui(() -> { activity.finish(); return null; });
    }

    protected void constraints(boolean wifi, boolean saver, int slots, boolean schedule, int from, int to) {
        engine.setQueueConstraints(wifi, saver, slots, schedule, from / 60, from % 60, to / 60, to % 60);
    }

    protected void downloads() {
        activity = ins.startActivitySync(new Intent(app, DownloadsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    protected DownloadItem item(String route, String name) {
        DownloadItem item = new DownloadItem();
        item.setId(UUID.randomUUID().toString());
        item.setUrl(BASE + route);
        item.setFileName(name);
        item.setDestinationPath(directory.resolve(name).toString());
        item.setNumSegments(1);
        return item;
    }

    protected String enqueue(DownloadItem item) throws Exception {
        engine.enqueue(item);
        owned.add(item.getId());
        return item.getId();
    }

    protected String range(String name) throws Exception { return enqueue(item("/range/" + name, name)); }

    private DownloadItem byName(String name) {
        for (DownloadItem key : db.getDownloadsIn(DownloadStatus.values())) {
            // The queue projection intentionally has neither filename nor path.
            DownloadItem item = db.getDownload(key.getId());
            if (item != null && name.equals(item.getFileName())) return item;
        }
        return null;
    }

    protected String bind(String name) throws Exception {
        waitFor("synthetic job " + name, 9000, () -> byName(name) != null);
        DownloadItem row = byName(name);
        assertTrue("Prompt chose a different output folder", Files.isSameFile(directory, Path.of(row.getDestinationPath()).getParent()));
        owned.add(row.getId());
        return row.getId();
    }

    protected DownloadItem state(String id, DownloadStatus status) throws Exception {
        waitFor(status.name(), () -> {
            DownloadItem row = db.getDownload(id);
            if (row == null) return false;
            if (row.getStatus() == DownloadStatus.FAILED && status != DownloadStatus.FAILED) {
                fail("Download became FAILED: " + row.getErrorMessage());
            }
            return row.getStatus() == status;
        });
        return db.getDownload(id);
    }

    protected DownloadItem terminal(String id) throws Exception {
        waitFor("terminal download", () -> {
            DownloadStatus status = db.getDownload(id).getStatus();
            return status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED;
        });
        return db.getDownload(id);
    }

    protected void drain() throws Exception { waitFor("controller has no running or draining owner", () -> engine.activeDownloads().idle()); }

    protected Path completed(String id, int length, String digest) throws Exception {
        DownloadItem row = state(id, DownloadStatus.COMPLETED);
        DownloadDbHelper.PublishedDownload published = db.getPublishedDownload(id);
        assertNotNull("No committed publication identity", published);
        Path path = Path.of(row.getDestinationPath());
        assertEquals(directory, path.getParent().toRealPath());
        assertEquals(published.identity, PublishedFileIdentity.capture(path.toString(), length));
        assertEquals(length, Files.size(path));
        assertEquals(digest, hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        return path;
    }

    protected void notPublished(DownloadItem row) {
        assertNotEquals(DownloadStatus.COMPLETED, row.getStatus());
        assertNull(db.getPublishedDownload(row.getId()));
        assertFalse("Failed work left a destination", Files.exists(Path.of(row.getDestinationPath())));
    }

    protected static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }

    protected static void waitFor(String reason, Callable<Boolean> condition) throws Exception { waitFor(reason, 25000, condition); }

    protected static void waitFor(String reason, int milliseconds, Callable<Boolean> condition) throws Exception {
        long end = SystemClock.uptimeMillis() + milliseconds;
        do {
            if (condition.call()) return;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < end);
        fail("Timed out: " + reason);
    }

    protected <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action);
        ins.runOnMainSync(result);
        return result.get(10, TimeUnit.SECONDS);
    }

    protected String shell(String command) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(ins.getUiAutomation().executeShellCommand(command))) {
            return new String(read(in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    protected void control(String operation, String name) throws Exception { fetch("/control/" + operation + "/" + name); }
    protected JSONArray requests(String path) throws Exception { return new JSONArray(new String(fetch("/control/requests?" + path), StandardCharsets.UTF_8)); }

    protected byte[] fetch(String route) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + route).openConnection();
        conn.setConnectTimeout(2500);
        conn.setReadTimeout(2500);
        try (InputStream in = conn.getInputStream()) { return read(in, 512 * 1024); }
        finally { conn.disconnect(); }
    }

    protected static byte[] read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = in.read(buffer)) != -1;) {
            assertTrue("Synthetic response exceeded limit", out.size() + count <= max);
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    protected void evidence(String value) {
        Bundle status = new Bundle();
        status.putString("download_batch_evidence", value);
        ins.sendStatus(3, status);
    }
}

package com.privacy.browser.downloads;

import android.os.Build;
import android.os.Bundle;
import android.os.Process;

import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/** Opt-in hardware entry point for the same four positive tests as the translated run. */
public final class Arm64MuxRetest extends DownloadMediaBatchTest {
    private static final Map<String, String> NAMES = Map.of("separateTracks", "separate.mp4", "mpegTs", "transport.mp4",
            "hlsByteRanges", "fragments.mp4", "encryptedHls", "encrypted.mp4");
    private static final Map<String, String> INPUTS = Map.of("separate.mp4", "/video.mp4", "transport.mp4", "/mux-ts.m3u8",
            "fragments.mp4", "/mux-fragments.m3u8", "encrypted.mp4", "/encrypted.m3u8");

    @Rule public final TestName selectedTest = new TestName();

    @Override @Before public void setupBatch() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String method = selectedTest.getMethodName();
        assumeTrue("Select exactly one of the four positive hardware cases on a disposable install",
                "arm64-lab".equals(args.getString("download_batch")) && NAMES.containsKey(method)
                        && (getClass().getName() + "#" + method).equals(args.getString("class")));
        assumeTrue("Requires Android 14 or newer on a native 64-bit ARM device",
                Build.VERSION.SDK_INT >= 34 && Build.SUPPORTED_ABIS.length > 0
                        && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]) && Process.is64Bit()
                        && notEmulator(shell("getprop ro.kernel.qemu")) && notEmulator(shell("getprop ro.boot.qemu")));
        assertEquals("com.browser", app.getPackageName());

        // Do not let an earlier interrupted case, or an existing download profile,
        // resume work when the controller starts. No reset or clearing is needed.
        Path files = app.getFilesDir().toPath().toRealPath();
        db = DownloadDbHelper.getInstance(app);
        for (DownloadItem key : db.getDownloadsIn(DownloadStatus.values())) {
            DownloadItem row = db.getDownload(key.getId());
            Path parent = row == null || row.getDestinationPath() == null ? null : Path.of(row.getDestinationPath()).getParent();
            String route = row == null || row.getFileName() == null ? "" : INPUTS.getOrDefault(row.getFileName(), "");
            assumeTrue("Only earlier terminal lab cases may be present; leave existing data untouched",
                    parent != null && files.equals(parent.getParent()) && parent.getFileName().toString().startsWith("arm64-mux-")
                            && !route.isEmpty() && (BASE + route).equals(row.getUrl())
                            && !NAMES.get(method).equals(row.getFileName()) && terminal(row));
        }
        directory = Files.createTempDirectory(files, "arm64-mux-").toRealPath();
        engine = DownloadEngine.getInstance(app);
        engine.setDefaultDownloadDirectory(directory.toString());
        engine.setCategorySubfoldersEnabled(false);
        engine.setMaxRetries(1);
        engine.setGlobalSpeedLimit(0);
        constraints(false, false, 3, false, 0, 0);
    }

    @Override @After public void finishBatch() throws Exception {
        super.finishBatch();
        if (engine != null) {
            for (String id : owned) waitFor("lab row to reach a terminal state", 6000, () -> terminal(db.getDownload(id)));
        }
    }

    @Override protected void evidence(String value) {
        super.evidence(value.replaceFirst("^translated Android JNI MP4", "ARM64 Android JNI MP4"));
    }

    private static boolean notEmulator(String value) {
        String flag = value.trim();
        return flag.isEmpty() || "0".equals(flag);
    }

    private static boolean terminal(DownloadItem row) {
        return row != null && (row.getStatus() == DownloadStatus.COMPLETED
                || row.getStatus() == DownloadStatus.FAILED || row.getStatus() == DownloadStatus.CANCELLED);
    }
}

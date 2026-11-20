package com.privacy.browser;

import android.os.Environment;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.util.PublishedFileIdentity;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;

/** Prepare storage in the ordinary Activity process's UID/mount context, not with shell mkdir. */
public final class PublicCopyFixtureCiTest extends BrowserRecoveryCiSupport {
    private static final String FOLDER = "arena-mid-copy-fixture";
    private static final byte[] NEIGHBOR = "Unrelated synthetic file. Do not overwrite.\n".getBytes(StandardCharsets.US_ASCII);

    @Test public void preparePublicDownloadsFixture() throws Exception {
        Path folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath().resolve(FOLDER);
        assertEquals("/storage/emulated/0/Download/" + FOLDER, folder.toString());
        Path probe = folder.resolve("storage-probe.part"), neighbor = folder.resolve("unrelated.bin");
        assertFalse("Do not reuse an existing shared folder", Files.exists(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        try {
            Files.createDirectory(folder);
            Files.write(probe, NEIGHBOR, StandardOpenOption.CREATE_NEW);
            Files.move(probe, neighbor);
        } catch (IOException | SecurityException unavailable) {
            assumeNoException("Ordinary app process cannot create/write/rename under public Downloads", unavailable);
        }
        assertEquals(folder, folder.toRealPath());
        assertArrayEquals(NEIGHBOR, Files.readAllBytes(neighbor));
        assertEquals(0, DownloadDbHelper.getInstance(app).countDownloads());
        assertTrue(app.getSharedPreferences("downloader_settings", 0).edit()
                .putString(DownloadEngine.KEY_DEFAULT_DOWNLOAD_DIR, folder.toString())
                .putBoolean("category_subfolders", false).commit());
        String identity = PublishedFileIdentity.capture(neighbor.toString(), NEIGHBOR.length);
        assertNotNull("Ordinary app UID could not identify the public file it wrote", identity);
        observe("ordinary app UID wrote and renamed the synthetic neighbor; stat=" + identity);
    }
}

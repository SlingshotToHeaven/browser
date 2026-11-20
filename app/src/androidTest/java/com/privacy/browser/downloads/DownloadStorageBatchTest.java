package com.privacy.browser.downloads;

import android.os.Environment;

import com.privacy.downloader.engine.FilePublication;
import com.privacy.downloader.model.DownloadItem;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

public class DownloadStorageBatchTest extends DownloadBatchSupport {
    @Test public void filesystemWriterRejectsDocumentAndTreeUrisBeforeQueueing() throws Exception {
        long rows = db.countDownloads();
        for (String uri : new String[]{
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2Ffixture.bin",
                "content://com.android.externalstorage.documents/tree/primary%3ADownload"}) {
            DownloadItem item = item("/range/saf-refused.bin", "saf-refused.bin");
            item.setDestinationPath(uri);
            IOException error = assertThrows(IOException.class, () -> engine.enqueue(item));
            assertTrue(error.getMessage().contains("filesystem download folder"));
            assertNull(db.getDownload(item.getId()));
        }
        assertEquals(rows, db.countDownloads());
        assertEquals(0, requests("/range/saf-refused.bin").length());
        assertEquals(0, directory.toFile().list().length);
        evidence("document and tree refused before any row or request; no SAF grant requested");
    }

    @Test public void hardLinkPreflightAndProductionPublisher() throws Exception {
        Path staged = directory.resolve("fixture.stage"), probe = directory.resolve("hard-link.probe");
        Path target = directory.resolve("published.bin");
        Files.write(staged, new byte[]{1, 3, 5}, StandardOpenOption.CREATE_NEW);
        try {
            try {
                Files.createLink(probe, staged);
            } catch (IOException | UnsupportedOperationException | SecurityException denial) {
                assumeNoException("Ordinary app-UID same-directory hard link unavailable", denial);
            }
            assertTrue(Files.isSameFile(probe, staged));
            Files.delete(probe);
            try (FilePublication output = FilePublication.open(staged, target, 3, null, () -> false)) {
                assertTrue("Production publication used a copy, not a hard link", Files.isSameFile(staged, target));
            }
            assertFalse(Files.exists(target));
            assertArrayEquals(new byte[]{1, 3, 5}, Files.readAllBytes(staged));
            evidence("hard-link publisher took same-inode path; explicit rollback, not process death");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(staged);
        }
    }

    @Test public void appSpecificExternalStoragePublishesExactBytes() throws Exception {
        var external = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        assertNotNull("No app-specific external Downloads directory on this AVD", external);
        directory = Files.createTempDirectory(external.toPath(), "download-batch-").toRealPath();
        assertTrue(directory.toString().contains("/Android/data/com.browser/files/Download/"));
        downloads();
        String id = range("app-external.bin");
        completed(id, LENGTH, rangeDigest());
        drain();
        assertEquals(1, directory.toFile().list().length);
        evidence("verified publication in app-specific external Downloads, not shared/removable storage");
    }

    @Test public void publicDownloadsByTheOrdinaryAppUid() throws Exception {
        Path external = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath();
        assertTrue(external.toString().startsWith("/storage/") && external.endsWith("Download"));
        Path folder = null;
        try {
            try {
                folder = Files.createTempDirectory(external, "arena-download-batch-");
                Path before = folder.resolve("probe.part"), after = folder.resolve("public-file.bin");
                Files.write(before, "keep this fixture".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE_NEW);
                Files.move(before, after);
            } catch (IOException | SecurityException denial) {
                assumeNoException("Ordinary app process could not create/write/rename under public Downloads", denial);
            }
            directory = folder.toRealPath();
            downloads();
            String name = "public-file.bin";
            String id = enqueue(item("/range/" + name, name));
            Path result = completed(id, LENGTH, rangeDigest());
            assertEquals("public-file_1.bin", result.getFileName().toString());
            assertEquals("keep this fixture", new String(Files.readAllBytes(directory.resolve(name)), StandardCharsets.US_ASCII));
            evidence("public Downloads committed exact bytes at a suffixed name; neighboring entry preserved");
        } finally {
            if (folder != null) {
                // Only this test's unique synthetic folder; never clear the shared Downloads root.
                Files.deleteIfExists(folder.resolve("probe.part"));
                Files.deleteIfExists(folder.resolve("public-file.bin"));
                Files.deleteIfExists(folder.resolve("public-file_1.bin"));
                if (folder.toFile().list().length == 0) Files.delete(folder);
            }
        }
    }

    private static String rangeDigest() throws Exception {
        byte[] expected = new byte[LENGTH];
        java.util.Arrays.fill(expected, (byte) 'd');
        return hex(MessageDigest.getInstance("SHA-256").digest(expected));
    }
}

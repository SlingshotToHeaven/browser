package com.privacy.downloader;

import com.privacy.downloader.model.GrabbedLinkItem;
import com.privacy.downloader.util.ArchiveExtractor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class P1FeaturesTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testSha256ChecksumCalculation() throws Exception {
        byte[] data = "Privacy Browser Download Manager Verification".getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);

        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        String hex = sb.toString();

        assertNotNull(hex);
        assertEquals(64, hex.length()); // SHA-256 is 64 hex characters
    }

    @Test
    public void testGrabbedLinkCategorization() {
        GrabbedLinkItem video = new GrabbedLinkItem("https://example.com/movie.mp4", "Sample Movie", "video");
        assertEquals("Videos", video.getCategory());

        GrabbedLinkItem archive = new GrabbedLinkItem("https://example.com/files.zip", "Zip Files", "link");
        assertEquals("Archives", archive.getCategory());

        GrabbedLinkItem image = new GrabbedLinkItem("https://example.com/photo.png", "A Photo", "image");
        assertEquals("Images", image.getCategory());

        GrabbedLinkItem doc = new GrabbedLinkItem("https://example.com/paper.pdf", "Research Paper", "link");
        assertEquals("Documents", doc.getCategory());

        GrabbedLinkItem audio = new GrabbedLinkItem("https://example.com/sound.mp3", "Audio Track", "audio");
        assertEquals("Audio", audio.getCategory());

        GrabbedLinkItem other = new GrabbedLinkItem("https://example.com/page.html", "Web Page", "link");
        assertEquals("Links", other.getCategory());
    }

    @Test
    public void testSafeZipExtraction() throws Exception {
        File zipFile = tempFolder.newFile("test_archive.zip");
        File destDir = tempFolder.newFolder("extracted");

        // Create test zip with 2 files
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry entry1 = new ZipEntry("file1.txt");
            zos.putNextEntry(entry1);
            zos.write("Content of file 1".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry entry2 = new ZipEntry("nested/file2.txt");
            zos.putNextEntry(entry2);
            zos.write("Content of file 2".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger totalFilesExtracted = new AtomicInteger(0);
        AtomicBoolean success = new AtomicBoolean(false);

        ArchiveExtractor.extractZipAsync(zipFile, destDir, new ArchiveExtractor.ExtractionCallback() {
            @Override
            public void onProgress(String currentFile, int extractedCount) {}

            @Override
            public void onCompleted(File outputDir, int totalFiles) {
                totalFilesExtracted.set(totalFiles);
                success.set(true);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                latch.countDown();
            }
        });

        assertTrue("Extraction timed out", latch.await(5, TimeUnit.SECONDS));
        assertTrue("Extraction failed", success.get());
        assertEquals(2, totalFilesExtracted.get());

        File f1 = new File(destDir, "file1.txt");
        File f2 = new File(destDir, "nested/file2.txt");
        assertTrue(f1.exists());
        assertTrue(f2.exists());
    }

    @Test
    public void testZipSlipAttackBlocked() throws Exception {
        File zipFile = tempFolder.newFile("malicious.zip");
        File destDir = tempFolder.newFolder("target_dir");

        // Create zip with malicious path traversal entry
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry maliciousEntry = new ZipEntry("../escaped_file.txt");
            zos.putNextEntry(maliciousEntry);
            zos.write("Hacked file".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger extractedCount = new AtomicInteger(0);

        ArchiveExtractor.extractZipAsync(zipFile, destDir, new ArchiveExtractor.ExtractionCallback() {
            @Override
            public void onProgress(String currentFile, int count) {}

            @Override
            public void onCompleted(File outputDir, int totalFiles) {
                extractedCount.set(totalFiles);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // The malicious file outside destDir must NOT be created
        assertEquals(0, extractedCount.get());
        File outsideFile = new File(destDir.getParentFile(), "escaped_file.txt");
        assertFalse("Zip Slip vulnerability exploited!", outsideFile.exists());
    }

    @Test
    public void testScheduleWindowCalculation() {
        // Test same-day window: 02:00 to 06:00
        int startMin = 2 * 60;
        int endMin = 6 * 60;

        // Inside window
        int now1 = 3 * 60 + 30; // 03:30
        assertTrue(now1 >= startMin && now1 < endMin);

        // Outside window (before)
        int now2 = 1 * 60; // 01:00
        assertFalse(now2 >= startMin && now2 < endMin);

        // Outside window (after)
        int now3 = 7 * 60; // 07:00
        assertFalse(now3 >= startMin && now3 < endMin);

        // Test overnight window: 23:00 to 06:00
        int startNight = 23 * 60;
        int endNight = 6 * 60;

        // Inside window late night: 23:30
        int nowLate = 23 * 60 + 30;
        assertTrue(nowLate >= startNight || nowLate < endNight);

        // Inside window early morning: 04:15
        int nowEarly = 4 * 60 + 15;
        assertTrue(nowEarly >= startNight || nowEarly < endNight);

        // Outside window: 14:00
        int nowDay = 14 * 60;
        assertFalse(nowDay >= startNight || nowDay < endNight);
    }

    @Test
    public void testBatchUrlExtractionRegex() {
        String rawText = "Check these out:\n"
                + "http://example.com/file1.zip\n"
                + "Some notes: https://example.com/test/archive.7z, and another link:\n"
                + "https://downloads.example.org/ubuntu.iso; done!";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("https?://[^\\s\"',;<>]+");
        java.util.regex.Matcher matcher = pattern.matcher(rawText);

        java.util.List<String> found = new java.util.ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }

        assertEquals(3, found.size());
        assertEquals("http://example.com/file1.zip", found.get(0));
        assertEquals("https://example.com/test/archive.7z", found.get(1));
        assertEquals("https://downloads.example.org/ubuntu.iso", found.get(2));
    }

    @Test
    public void testStreamDetectorAndFilenameFormatting() {
        // HLS URL
        assertTrue(com.privacy.downloader.engine.StreamDetector.isStream("https://skyfire.vimeocdn.com/123/playlist.m3u8?token=xyz", null));
        // DASH URL
        assertTrue(com.privacy.downloader.engine.StreamDetector.isStream("https://skyfire.vimeocdn.com/123/master.json?base64_init=1", null));
        assertTrue(com.privacy.downloader.engine.StreamDetector.isStream("https://example.com/manifest.mpd", null));
        // MimeType stream
        assertTrue(com.privacy.downloader.engine.StreamDetector.isStream("https://example.com/stream", "application/vnd.apple.mpegurl"));
        // Regular direct files
        assertFalse(com.privacy.downloader.engine.StreamDetector.isStream("https://example.com/file.zip", "application/zip"));
        assertFalse(com.privacy.downloader.engine.StreamDetector.isStream("https://example.com/movie.mp4", "video/mp4"));

        // Filename conversion
        assertEquals("sample.mp4", com.privacy.downloader.engine.StreamDetector.ensureMp4Extension("sample.m3u8"));
        assertEquals("movie.mp4", com.privacy.downloader.engine.StreamDetector.ensureMp4Extension("movie.mp4"));
        assertTrue(com.privacy.downloader.engine.StreamDetector.ensureMp4Extension("playlist.m3u8").endsWith(".mp4"));
        assertFalse(com.privacy.downloader.engine.StreamDetector.ensureMp4Extension("playlist.m3u8").contains(".m3u8"));

        // GrabbedLinkItem video categorization and extension
        GrabbedLinkItem hlsItem = new GrabbedLinkItem("https://skyfire.vimeocdn.com/live/playlist.m3u8", "Action Film 1080p", "video");
        assertEquals("Videos", hlsItem.getCategory());
        assertTrue(hlsItem.getFileName().endsWith(".mp4"));

        // GrabbedLinkItem proxy.html exclusion from Videos
        GrabbedLinkItem proxyItem = new GrabbedLinkItem("https://f.vimeocdn.com/p/4.34.1/proxy.html", "Proxy", "video");
        assertEquals("Links", proxyItem.getCategory());
    }
}

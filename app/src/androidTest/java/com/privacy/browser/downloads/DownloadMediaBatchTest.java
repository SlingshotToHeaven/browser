package com.privacy.browser.downloads;

import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;

import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.util.PublishedFileIdentity;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Uses the installed APK's real JNI, not the host ffmpeg executable, to publish the file. */
public class DownloadMediaBatchTest extends DownloadBatchSupport {
    @Test public void separateTracks() throws Exception { good("/video.mp4", "/audio.mp4", "separate.mp4"); }
    @Test public void mpegTs() throws Exception { good("/mux-ts.m3u8", null, "transport.mp4"); }

    @Test public void hlsByteRanges() throws Exception {
        good("/mux-fragments.m3u8", null, "fragments.mp4");
        for (String path : new String[]{"/video.mp4", "/audio.mp4"}) {
            var log = requests(path);
            // Isolate the exact two 206 requests, not an earlier direct track request.
            int ranged = 0;
            int previous = -1;
            for (int i = 0; i < log.length(); i++) {
                var call = log.getJSONObject(i);
                if (call.getInt("code") != 206) continue;
                assertEquals(previous + 1, call.getInt("start"));
                previous = call.getInt("end");
                ranged++;
            }
            assertEquals(2, ranged);
        }
    }

    @Test public void encryptedHls() throws Exception {
        good("/encrypted.m3u8", null, "encrypted.mp4");
        assertEquals(1, requests("/fixture.key").length());
        assertEquals(1, requests("/encrypted.ts").length());
    }

    @Test public void refusesMissingAudio() throws Exception { bad("/mux-missing-audio.m3u8", "missing-audio.mp4", "muxing failed", true); }
    @Test public void refusesNonMedia() throws Exception { bad("/invalid.m3u8", "invalid.mp4", "error page instead of media", false); }
    @Test public void refusesShortMedia() throws Exception { bad("/short.m3u8", "short.mp4", "before all expected bytes", false); }
    @Test public void refusesMuxError() throws Exception { bad("/broken-mux.m3u8", "broken-mux.mp4", "muxing failed", true); }

    @Test public void refusesDashBeforeFetch() throws Exception {
        bad("/unsupported.mpd", "dash.mp4", "DASH (MPD) downloads are not supported", false);
        assertEquals(0, requests("/unsupported.mpd").length());
    }

    private void good(String video, String audio, String name) throws Exception {
        assumeTrue("Bundled ARM64 JNI unavailable in this emulator", FFmpegKitConfig.isLoaded());
        downloads();
        DownloadItem item = item(video, name);
        if (audio != null) item.setAudioUrl(BASE + audio);
        String id = enqueue(item);
        DownloadItem row = state(id, DownloadStatus.COMPLETED);
        drain();
        DownloadDbHelper.PublishedDownload published = db.getPublishedDownload(id);
        assertNotNull(published);
        Path path = Paths.get(row.getDestinationPath());
        long bytes = Files.size(path);
        assertTrue(bytes > 1024 && bytes < 2 * 1024 * 1024);
        assertEquals(directory, path.getParent().toRealPath());
        assertEquals(published.identity, PublishedFileIdentity.capture(path.toString(), bytes));
        assertEquals(1, directory.toFile().list().length);
        checkTracks(path);
        play(path);
        evidence("translated Android JNI MP4; decoded frame, AAC packets and MediaPlayer completion; bytes=" + bytes
                + "; sha256=" + hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }

    private static void checkTracks(Path path) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        int video = -1, audio = -1;
        try {
            extractor.setDataSource(path.toString());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if ("video/avc".equals(mime)) video = i;
                if ("audio/mp4a-latm".equals(mime)) audio = i;
            }
            assertTrue("Expected H.264 and AAC", video >= 0 && audio >= 0);
            extractor.selectTrack(audio);
            assertTrue("No AAC packets", extractor.readSampleData(ByteBuffer.allocate(8192), 0) > 0);
        } finally { extractor.release(); }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path.toString());
            Bitmap frame = retriever.getFrameAtTime(500000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            assertNotNull("Android could not decode a video frame", frame);
            assertEquals(160, frame.getWidth());
            assertEquals(96, frame.getHeight());
            frame.recycle();
        } finally { retriever.release(); }
    }

    private void play(Path path) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        MediaPlayer player = ui(MediaPlayer::new);
        try {
            ui(() -> {
                player.setOnErrorListener((p, what, extra) -> { error.set(what + "/" + extra); ended.countDown(); return true; });
                player.setOnPreparedListener(p -> p.start());
                player.setOnCompletionListener(p -> ended.countDown());
                player.setDataSource(path.toString());
                player.prepareAsync();
                return null;
            });
            assertTrue("Android playback never completed", ended.await(10, TimeUnit.SECONDS));
            assertNull("Android MediaPlayer error", error.get());
            assertTrue("Expected the synthetic clip to play through", ui(player::getCurrentPosition) > 1000);
        } finally {
            ui(() -> { player.release(); return null; });
        }
    }

    private void bad(String route, String name, String reason, boolean requiresNative) throws Exception {
        if (requiresNative) assumeTrue("Bundled ARM64 JNI unavailable in this emulator", FFmpegKitConfig.isLoaded());
        downloads();
        String id = enqueue(item(route, name));
        DownloadItem row = state(id, DownloadStatus.FAILED);
        drain();
        assertNotNull(row.getErrorMessage());
        assertTrue(row.getErrorMessage(), row.getErrorMessage().toLowerCase(Locale.ROOT).contains(reason.toLowerCase(Locale.ROOT)));
        notPublished(row);
        assertEquals(0, directory.toFile().list().length);
        evidence("refused; no published output or leftover stage");
    }
}

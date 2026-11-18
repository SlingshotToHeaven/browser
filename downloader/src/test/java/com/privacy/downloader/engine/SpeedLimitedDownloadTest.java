package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.privacy.downloader.engine.LoopbackHttpFixture.fixed;
import static org.junit.Assert.*;

public class SpeedLimitedDownloadTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    @Test(timeout = 8000)
    public void directAndHlsJobsSharePermitsAndPauseDrainsStreamWaiter() throws Exception {
        byte[] bytes = {11, 22, 33, 44};
        byte[] playlist = "#EXTM3U\n#EXTINF:1,\nclip.bin\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.US_ASCII);
        try (LoopbackHttpFixture http = new LoopbackHttpFixture(fixed(200, bytes), fixed(200, bytes));
             LoopbackHttpFixture hls = new LoopbackHttpFixture(fixed(200, playlist))) {
            SpeedLimiter shared = new SpeedLimiter(1, () -> 0); // one initial byte, no refill
            DownloadItem file = job(http.url("/file.bin"));
            DownloadTask direct = new DownloadTask(file, null, null, shared) {
                @Override void persistAttempt(String previous) {}
                @Override void persistCheckpoint(String path, List<DownloadSegment> segments, long downloaded) {}
                @Override boolean persistStatus(DownloadStatus status, String reason) { return true; }
                @Override void persistPublication(String path, String staging, long size, String mime, long when, String identity) {}
            };
            DownloadItem media = job(hls.url("/stream.m3u8"));
            StreamDownloadTask stream = new StreamDownloadTask(media, null, null, shared) {
                @Override boolean persistStatus(DownloadStatus status, String reason) { return true; }
                @Override int executeMux(List<String> args) { throw new AssertionError("Incomplete playlist reached mux"); }
            };
            FutureTask<Void> transfer = start(direct);
            FutureTask<Void> streaming = start(stream);
            try {
                assertEquals(2, http.requests().size());
                assertEquals(1, hls.requests().size());
                assertThrows(TimeoutException.class, () -> transfer.get(120, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> streaming.get(120, TimeUnit.MILLISECONDS));
                assertFalse(Files.exists(Path.of(file.getDestinationPath())));
                assertFalse(Files.exists(Path.of(media.getDestinationPath())));
                stream.pause();
                streaming.get(2, TimeUnit.SECONDS);
                assertEquals(DownloadStatus.PAUSED, media.getStatus());
                assertArrayEquals(new String[0], new File(media.getDestinationPath()).getParentFile().list());
                shared.setMaxBytesPerSecond(0);
                transfer.get(3, TimeUnit.SECONDS);
                assertEquals(file.getErrorMessage(), DownloadStatus.COMPLETED, file.getStatus());
                assertArrayEquals(bytes, Files.readAllBytes(Path.of(file.getDestinationPath())));
            } finally {
                stream.cancel();
                direct.cancel();
                shared.setMaxBytesPerSecond(0);
            }
        }
    }

    private DownloadItem job(String url) throws IOException {
        DownloadItem item = new DownloadItem();
        item.setId(url);
        item.setUrl(url);
        item.setStatus(DownloadStatus.CONNECTING);
        item.setFileName("out.bin");
        item.setDestinationPath(new File(dir.newFolder(), "out.bin").getPath());
        return item;
    }

    private static FutureTask<Void> start(Runnable transfer) {
        FutureTask<Void> future = new FutureTask<>(transfer, null);
        Thread worker = new Thread(future, "speed-limited-download-fixture");
        worker.setDaemon(true);
        worker.start();
        return future;
    }
}

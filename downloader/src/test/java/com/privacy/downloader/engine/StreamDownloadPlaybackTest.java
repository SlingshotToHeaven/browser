package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.privacy.downloader.engine.LoopbackHttpFixture.fixed;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Optional host check using actual FFmpeg commands and synthetic media; not a JNI test. */
public class StreamDownloadPlaybackTest {
    private static final String HLS = "application/vnd.apple.mpegurl";
    private static final String MASTER = "#EXTM3U\n"
            + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Fixture\",DEFAULT=YES,URI=\"audio.m3u8\"\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=96x64,AUDIO=\"a\"\nvideo.m3u8\n";
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private String ffmpeg;
    private Path log;

    @Before public void requireHostFfmpeg() throws IOException {
        ffmpeg = System.getenv("FFMPEG_TEST_BIN");
        assumeTrue("Set FFMPEG_TEST_BIN to a host FFmpeg with libx264 and AAC", ffmpeg != null && !ffmpeg.isEmpty());
        log = files.newFile("ffmpeg.log").toPath();
    }

    @Test(timeout = 40000)
    public void separateTracksAndRangedFmp4HlsPublishDecodableAv() throws Exception {
        byte[] video = Files.readAllBytes(video());
        byte[] audio = Files.readAllBytes(audio());
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, video), fixed(200, audio))) {
            DownloadItem job = job(server.url("/video"), "video/mp4");
            job.setAudioUrl(server.url("/audio"));
            job.setAudioBytes(audio.length);
            assertPlayable(run(job));
            assertEquals(2, server.requests().size());
        }
        int videoInit = initLength(video);
        int audioInit = initLength(audio);
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(MASTER), rangedPlaylist("video.mp4", videoInit, video.length),
                range(video, 0, videoInit), range(video, videoInit, video.length),
                rangedPlaylist("audio.mp4", audioInit, audio.length), range(audio, 0, audioInit), range(audio, audioInit, audio.length))) {
            assertPlayable(run(job(server.url("/master.m3u8"), HLS)));
            List<LoopbackHttpFixture.Request> requests = server.requests();
            assertEquals("bytes=0-" + (videoInit - 1), requests.get(2).header("Range"));
            assertEquals("bytes=" + audioInit + "-" + (audio.length - 1), requests.get(6).header("Range"));
        }
    }

    @Test(timeout = 40000)
    public void hlsTransportStreamWithRequiredInBandAudioPublishesDecodableAv() throws Exception {
        Path transport = files.newFile("media.ts").toPath();
        checked("-y", "-i", video().toString(), "-i", audio().toString(), "-map", "0:v:0", "-map", "1:a:0",
                "-c", "copy", "-f", "mpegts", transport.toString());
        String embedded = MASTER.replace(",URI=\"audio.m3u8\"", "");
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(embedded),
                playlist("#EXTM3U\n#EXTINF:1,\nmedia.ts\n#EXT-X-ENDLIST\n"), fixed(200, Files.readAllBytes(transport)))) {
            assertPlayable(run(job(server.url("/master.m3u8"), HLS)));
            assertEquals(3, server.requests().size());
        }
    }

    @Test(timeout = 40000)
    public void realMuxDoesNotPublishWhenSeparateOrInBandAudioIsMissing() throws Exception {
        byte[] silentVideo = Files.readAllBytes(video());
        for (boolean separate : new boolean[]{true, false}) {
            LoopbackHttpFixture.Reply[] responses = separate
                    ? new LoopbackHttpFixture.Reply[]{fixed(200, silentVideo), fixed(200, silentVideo)}
                    : new LoopbackHttpFixture.Reply[]{playlist(MASTER.replace(",URI=\"audio.m3u8\"", "")),
                            playlist("#EXTM3U\n#EXTINF:1,\nsilent.mp4\n#EXT-X-ENDLIST\n"), fixed(200, silentVideo)};
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(responses)) {
                DownloadItem job = job(server.url(separate ? "/video" : "/master.m3u8"), separate ? "video/mp4" : HLS);
                if (separate) job.setAudioUrl(server.url("/audio"));
                DownloadItem outcome = run(job);
                assertEquals(DownloadStatus.FAILED, outcome.getStatus());
                assertTrue(outcome.getErrorMessage(), outcome.getErrorMessage().contains("Stream muxing failed"));
                assertOnlyOutcome(outcome, false);
            }
        }
    }

    private Path video() throws Exception {
        Path file = files.newFile("video-input.mp4").toPath();
        checked("-y", "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=10", "-t", "1", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", "-preset", "ultrafast", "-movflags", "+frag_keyframe+empty_moov", file.toString());
        return file;
    }

    private Path audio() throws Exception {
        Path file = files.newFile("audio-input.mp4").toPath();
        checked("-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "aac",
                "-movflags", "+frag_keyframe+empty_moov", file.toString());
        return file;
    }

    private static int initLength(byte[] track) {
        ByteBuffer data = ByteBuffer.wrap(track);
        while (data.remaining() >= 8) {
            int start = data.position();
            int size = data.getInt();
            int type = data.getInt();
            if (type == 0x6d6f6f66 /* moof */) return start;
            assertTrue(size >= 8 && size <= track.length - start);
            data.position(start + size);
        }
        throw new AssertionError("No media fragment in fixture MP4");
    }

    private static LoopbackHttpFixture.Response rangedPlaylist(String track, int init, int length) {
        return playlist("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"" + track + "\",BYTERANGE=\"" + init
                + "@0\"\n#EXTINF:1,\n#EXT-X-BYTERANGE:" + (length - init) + "@" + init + "\n" + track + "\n#EXT-X-ENDLIST\n");
    }

    private static LoopbackHttpFixture.Response playlist(String text) {
        return fixed(200, text.getBytes(StandardCharsets.UTF_8), "Content-Type: " + HLS);
    }

    private static LoopbackHttpFixture.Response range(byte[] source, int start, int end) {
        return fixed(206, Arrays.copyOfRange(source, start, end), "Content-Range: bytes " + start + "-" + (end - 1) + "/" + source.length);
    }

    private DownloadItem job(String url, String mime) throws IOException {
        DownloadItem item = new DownloadItem();
        item.setId("playback-fixture");
        item.setUrl(url);
        item.setFileName("output.mp4");
        item.setDestinationPath(files.newFolder().toPath().resolve("output.mp4").toString());
        item.setMimeType(mime);
        return item;
    }

    private DownloadItem run(DownloadItem item) {
        new StreamDownloadTask(item, null, null, null) {
            @Override int executeMux(List<String> args) throws IOException { return execute(args); }
            @Override boolean persistStatus(DownloadStatus status, String reason) { return true; }
            @Override void persistProgress(long downloaded) {}
            @Override void persistAttempt(String previousTempPath) {}
            @Override void persistPublication(String destination, String staging, long size, String mime, long finishedAt, String identity) throws IOException {
                assertEquals(size, Files.size(Path.of(destination)));
            }
        }.run();
        return item;
    }

    private void assertPlayable(DownloadItem item) throws Exception {
        assertEquals(item.getErrorMessage() + "\n" + Files.readString(log), DownloadStatus.COMPLETED, item.getStatus());
        assertOnlyOutcome(item, true);
        checked("-xerror", "-abort_on", "empty_output+empty_output_stream", "-i", item.getDestinationPath(),
                "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-");
    }

    private static void assertOnlyOutcome(DownloadItem item, boolean complete) throws IOException {
        Path output = Path.of(item.getDestinationPath());
        try (Stream<Path> files = Files.list(output.getParent())) {
            assertEquals(complete ? List.of(output) : List.of(), files.toList());
        }
    }

    private void checked(String... args) throws IOException {
        int code = execute(List.of(args));
        assertEquals(Files.readString(log), 0, code);
    }

    private int execute(List<String> args) throws IOException {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error"));
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Fixture FFmpeg timed out");
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fixture", e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}

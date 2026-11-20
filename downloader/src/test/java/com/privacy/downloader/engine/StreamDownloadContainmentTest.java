package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.RequestContextFixture;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static com.privacy.downloader.engine.LoopbackHttpFixture.chunked;
import static com.privacy.downloader.engine.LoopbackHttpFixture.fixed;
import static org.junit.Assert.*;

public class StreamDownloadContainmentTest {
    private static final String HLS = "application/vnd.apple.mpegurl";
    private static final byte[] MPD = text("<?xml version=\"1.0\"?><dash:MPD xmlns:dash=\"urn:mpeg:dash:schema:mpd:2011\"><Period/></dash:MPD>");
    private static final byte[] VIDEO = {10, 20, 30, 40};
    private static final byte[] AUDIO = {50, 60, 70};
    private static final byte[] EXISTING = {100, 90};
    private static final String AUDIO_RENDITION =
            "#EXT-X-MEDIA:NAME=\"English, AAC\",URI=\"audio.m3u8\",TYPE=AUDIO,GROUP-ID=\"en\",DEFAULT=YES\n";
    // Valid top-level boxes, not playable media. Native execution is a separate gate.
    private static final byte[] MUX_OUTPUT = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0,
            0, 0, 0, 16, 'm', 'o', 'o', 'v', 0, 0, 0, 8, 'f', 'r', 'e', 'e',
            0, 0, 0, 9, 'm', 'd', 'a', 't', 7};

    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test(timeout = 5000)
    public void redirectedHlsAnonymizesChildPlaylistKeyAndSegmentEvenIfTheyPointBack() throws Exception {
        String[] other = new String[1];
        byte[] key = new byte[16];
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        byte[] encrypted = cipher.doFinal(VIDEO);
        try (LoopbackHttpFixture original = new LoopbackHttpFixture(
                req -> fixed(302, new byte[0], "Location: " + other[0]),
                playlist("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:2,\nsegment\n#EXT-X-ENDLIST\n"),
                fixed(200, key), fixed(200, encrypted));
             LoopbackHttpFixture foreign = new LoopbackHttpFixture(playlist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n"
                     + original.url("/nested/media.m3u8") + "\n"))) {
            other[0] = foreign.url("/root/master.m3u8");
            DownloadItem item = job(original.url("/a.m3u8"), HLS);
            RequestContext context = RequestContextFixture.browser(original.url("/reader?session=page"), "Browser fixture",
                    url -> "same=origin");
            TestTask task = new TestTask(item, new Events(), (args, target, attempt) -> {
                assertArrayEquals(VIDEO, Files.readAllBytes(Path.of(valuesAfter(args, "-i").get(0))));
                Files.write(target, MUX_OUTPUT);
                return 0;
            }, context);
            task.run();
            assertTrue(item.getErrorMessage(), task.events.completed);
            assertNull(foreign.requests().get(0).header("Cookie"));
            assertEquals(List.of("/a.m3u8", "/nested/media.m3u8", "/nested/key", "/nested/segment"), requestTargets(original));
            List<LoopbackHttpFixture.Request> requests = original.requests();
            assertEquals("same=origin", requests.get(0).header("Cookie"));
            assertEquals(original.url("/"), requests.get(0).header("Referer"));
            for (int i = 1; i < requests.size(); i++) {
                assertNull(requests.get(i).header("Cookie"));
                assertNull(requests.get(i).header("Referer"));
            }
        }
    }

    @Test(timeout = 10000)
    public void knownDashUrlAndMimeTypeFailBeforeStartingARequest() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture()) {
            for (String mime : new String[]{null, "application/DASH+xml; charset=utf-8"}) {
                DownloadItem job = job(server.url(mime == null ? "/manifest.MPD?token=fixture" : "/manifest"), mime);
                assertTrue(StreamDetector.isStream(job.getUrl(), mime));
                TestTask task = run(job, mustNotMux());
                verifyFailure(task, "DASH (MPD)");
                assertEquals(0, task.attemptSaves);
                assertEquals(mime, job.getMimeType());
            }
            assertTrue(server.requests().isEmpty());
        }
    }

    @Test(timeout = 10000)
    public void disguisedDashBodyIsNotCopiedToAnMp4() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, MPD))) {
            TestTask task = run(job(server.url(), HLS), mustNotMux());
            verifyFailure(task, "DASH (MPD)");
            assertEquals(HLS, task.getItem().getMimeType());
            assertEquals(1, server.requests().size());
        }
    }

    @Test(timeout = 10000)
    public void dashResponseAfterRedirectIsRejectedByUrlOrContentType() throws Exception {
        for (String target : List.of("/resolved.mpd", "/resolved")) {
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(302, new byte[0], "Location: " + target),
                    fixed(200, text(media("clip.bin")), "Content-Type: " + (target.endsWith(".mpd") ? HLS : "application/dash+xml")))) {
                verifyFailure(run(job(server.url("/entry.m3u8"), HLS), mustNotMux()), "DASH (MPD)");
                assertEquals(List.of("/entry.m3u8", target), requestTargets(server));
            }
        }
    }

    @Test(timeout = 10000)
    public void textThatOnlyContainsAnHlsMarkerIsNotAPlaylistOrFinishedMedia() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, text("<html>Not #EXTM3U</html>\n")))) {
            verifyFailure(run(job(server.url("/playlist.m3u8"), HLS), mustNotMux()), "did not return an HLS playlist");
        }
    }

    @Test(timeout = 10000)
    public void oversizedPlaylistDoesNotReachTheMuxer() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(
                fixed(200, text(media("video.bin")), 3L * 1024 * 1024, "Content-Type: " + HLS))) {
            verifyFailure(run(job(server.url("/playlist.m3u8"), HLS), mustNotMux()), "response is too large");
        }
    }

    @Test(timeout = 10000)
    public void missingEmptyOrShortRequiredDirectAudioNeverReachesTheMuxer() throws Exception {
        LoopbackHttpFixture.Response[] replies = {fixed(404, new byte[0]), fixed(200, new byte[0]),
                chunked(200, Arrays.copyOf(AUDIO, 2)), fixed(200, Arrays.copyOf(AUDIO, 2), AUDIO.length)};
        String[] reasons = {"HTTP 404", "empty", "before all expected bytes", null};
        for (int i = 0; i < replies.length; i++) {
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, VIDEO), replies[i])) {
                DownloadItem job = dualTrackJob(server);
                if (i == 2) job.setAudioBytes(AUDIO.length);
                TestTask task = run(job, mustNotMux());
                verifyFailure(task, "Required audio track failed");
                if (reasons[i] != null) assertTrue(task.events.error, task.events.error.contains(reasons[i]));
                assertEquals(List.of("/video", "/audio"), requestTargets(server));
            }
        }
    }

    @Test(timeout = 10000)
    public void aMasterWithAnUnresolvedRequiredAudioGroupFails() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(master("")))) {
            verifyFailure(run(job(server.url("/master.m3u8"), HLS), mustNotMux()), "missing the required audio group");
            assertEquals(List.of("/master.m3u8"), requestTargets(server));
        }
    }

    @Test(timeout = 10000)
    public void anEmptyOrShortHlsAudioSegmentIsNotSilentlySkipped() throws Exception {
        for (boolean ranged : new boolean[]{false, true}) {
            String audio = "#EXTM3U\n#EXTINF:2,\naudio1.bin\n#EXTINF:2,\n"
                    + (ranged ? "#EXT-X-BYTERANGE:3@8\n" : "") + "audio2.bin\n#EXT-X-ENDLIST\n";
            LoopbackHttpFixture.Reply invalidAudio = ranged
                    ? chunked(206, Arrays.copyOf(AUDIO, 2), "Content-Range: bytes 8-10/11") : fixed(200, new byte[0]);
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(master(AUDIO_RENDITION)),
                    playlist(media("video.bin")), fixed(200, VIDEO), playlist(audio), fixed(200, AUDIO), invalidAudio)) {
                TestTask task = run(job(server.url("/master.m3u8"), HLS), mustNotMux());
                verifyFailure(task, "Required audio track failed");
                assertTrue(task.events.error, task.events.error.contains(ranged ? "before all expected bytes" : "empty"));
                assertEquals(List.of("/master.m3u8", "/video.m3u8", "/video.bin", "/audio.m3u8", "/audio1.bin", "/audio2.bin"),
                        requestTargets(server));
                assertEquals(ranged ? "bytes=8-10" : null, server.requests().get(5).header("Range"));
            }
        }
    }

    @Test(timeout = 10000)
    public void aListedButMissingAudioSegmentFailsBeforeMux() throws Exception {
        for (String missing : List.of("#EXT-X-ENDLIST\n", "#EXT-X-GAP\nmissing.bin\n")) {
            String audio = "#EXTM3U\n#EXTINF:2,\naudio.bin\n#EXTINF:2,\n" + missing;
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(master(AUDIO_RENDITION)),
                    playlist(media("video.bin")), fixed(200, VIDEO), playlist(audio))) {
                TestTask task = run(job(server.url("/master.m3u8"), HLS), mustNotMux());
                verifyFailure(task, "Required audio track failed");
                assertTrue(task.events.error, task.events.error.contains(missing.contains("GAP") ? "as missing" : "without a URL"));
                assertEquals(4, server.requests().size());
            }
        }
    }

    @Test(timeout = 10000)
    public void redirectedPlaylistsResolveRelativeInputsAndRequireTheSelectedAudioRange() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(302, new byte[0], "Location: /master/index.m3u8"),
                playlist(master(AUDIO_RENDITION)), fixed(302, new byte[0], "Location: /video/selected.m3u8"),
                playlist(media("clip.bin")), fixed(200, VIDEO),
                playlist("#EXTM3U\n#EXTINF:2,\n#EXT-X-BYTERANGE:3@5\nsound.bin\n#EXT-X-ENDLIST\n"),
                fixed(206, AUDIO, "Content-Range: bytes 5-7/8"))) {
            DownloadItem item = job(server.url("/entry"), HLS);
            TestTask task = run(item, (args, target, attempt) -> {
                verifySeparateInputs(args, attempt);
                assertFalse(Files.exists(destination(item)));
                Files.write(target, MUX_OUTPUT);
                return 0;
            });
            verifyCompletion(task);
            assertEquals(List.of("/entry", "/master/index.m3u8", "/master/video.m3u8", "/video/selected.m3u8",
                    "/video/clip.bin", "/master/audio.m3u8", "/master/sound.bin"), requestTargets(server));
            assertEquals("bytes=5-7", server.requests().get(6).header("Range"));
        }
    }

    @Test(timeout = 10000)
    public void queryOnlyHlsReferencesKeepThePlaylistPath() throws Exception {
        String path = "/streams/asset.m3u8";
        String rendition = AUDIO_RENDITION.replace("audio.m3u8", "?part=audio%2Flist");
        String master = master(rendition).replace("video.m3u8", "?part=video%2Flist");
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(master),
                request -> request.target.equals(path + "?part=video%2Flist")
                        ? playlist(media("?part=video%2Fsegment")) : fixed(404, new byte[0]),
                fixed(200, VIDEO), playlist(media("?part=audio%2Fsegment")), fixed(200, AUDIO))) {
            TestTask task = run(job(server.url(path + "?part=master"), HLS), (args, target, attempt) -> {
                verifySeparateInputs(args, attempt);
                Files.write(target, MUX_OUTPUT);
                return 0;
            });
            verifyCompletion(task);
            assertEquals(List.of(path + "?part=master", path + "?part=video%2Flist", path + "?part=video%2Fsegment",
                    path + "?part=audio%2Flist", path + "?part=audio%2Fsegment"), requestTargets(server));
        }
    }

    @Test(timeout = 10000)
    public void hlsInitializationAndEncryptedMediaAreStagedInOrder() throws Exception {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        iv[15] = 7;
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        String playlist = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-MAP:BYTERANGE=\"2@0\",URI=\"init.bin\"\n"
                + "#EXTINF:2,\n#EXT-X-KEY:URI=\"key.bin\",METHOD=AES-128\nvideo.bin\n#EXT-X-ENDLIST\n";
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(playlist),
                fixed(206, new byte[]{1, 2}, "Content-Range: bytes 0-1/2"), fixed(200, key), fixed(200, cipher.doFinal(VIDEO)))) {
            TestTask task = run(job(server.url("/playlist.m3u8"), HLS), (args, target, attempt) -> {
                assertArrayEquals(new byte[]{1, 2, 10, 20, 30, 40}, Files.readAllBytes(Path.of(valuesAfter(args, "-i").get(0))));
                Files.write(target, MUX_OUTPUT);
                return 0;
            });
            verifyCompletion(task);
            assertEquals(List.of("/playlist.m3u8", "/init.bin", "/key.bin", "/video.bin"), requestTargets(server));
            assertEquals("bytes=0-1", server.requests().get(1).header("Range"));
        }
    }

    @Test(timeout = 10000)
    public void inBandHlsAudioIsStillRequiredWhenItsRenditionHasNoUri() throws Exception {
        String rendition = "#EXT-X-MEDIA:GROUP-ID=\"en\",NAME=\"English\",TYPE=AUDIO\n";
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(master(rendition)), playlist(media("av.bin")), fixed(200, VIDEO))) {
            TestTask task = run(job(server.url("/master.m3u8"), HLS), (args, target, attempt) -> {
                assertEquals(List.of("0:v:0?", "0:a:0"), valuesAfter(args, "-map"));
                assertArrayEquals(VIDEO, Files.readAllBytes(Path.of(valuesAfter(args, "-i").get(0))));
                Files.write(target, MUX_OUTPUT);
                return 0;
            });
            verifyCompletion(task);
            assertEquals(3, server.requests().size());
        }
    }

    @Test(timeout = 10000)
    public void nonzeroMuxExitDoesNotPublishRawInputsOrItsPartialOutput() throws Exception {
        for (boolean occupied : new boolean[]{false, true}) {
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, VIDEO), fixed(200, AUDIO))) {
                DownloadItem job = dualTrackJob(server);
                if (occupied) Files.write(destination(job), EXISTING);
                TestTask task = run(job, (args, target, attempt) -> {
                    verifySeparateInputs(args, attempt);
                    Files.write(target, MUX_OUTPUT);
                    return 17;
                });
                assertEquals(2, task.muxTargets.size());
                assertNotEquals(task.muxTargets.get(0), task.muxTargets.get(1));
                if (occupied) {
                    assertFailed(task, "Stream muxing failed");
                    assertArrayEquals(EXISTING, Files.readAllBytes(destination(job)));
                    assertOnlyDestinationInDirectory(job);
                } else {
                    verifyFailure(task, "Stream muxing failed");
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void aSuccessfulAudioTranscodePublishesOnlyTheNewMuxWithBothInputs() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(fixed(200, VIDEO), fixed(200, AUDIO))) {
            DownloadItem job = dualTrackJob(server);
            // The quality picker may know only the audio size.
            job.setTotalBytes(AUDIO.length);
            job.setAudioBytes(AUDIO.length);
            TestTask task = run(job, (args, target, attempt) -> {
                verifySeparateInputs(args, attempt);
                assertFalse(Files.exists(destination(job)));
                Files.write(target, attempt == 1 ? VIDEO : MUX_OUTPUT);
                return attempt == 1 ? 8 : 0;
            });
            verifyCompletion(task);
            assertEquals(2, task.muxTargets.size());
            assertNotEquals(task.muxTargets.get(0), task.muxTargets.get(1));
        }
    }

    @Test(timeout = 10000)
    public void zeroMuxExitWithoutMp4OutputStillFails() throws Exception {
        for (byte[] output : new byte[][]{new byte[0], MPD, Arrays.copyOf(MUX_OUTPUT, MUX_OUTPUT.length - 1)}) {
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(media("video.bin")), fixed(200, VIDEO))) {
                TestTask task = run(job(server.url("/playlist.m3u8"), HLS), (args, target, attempt) -> {
                    Files.write(target, output);
                    return 0;
                });
                verifyFailure(task, "Stream muxing failed");
                assertEquals(1, task.muxTargets.size());
            }
        }
    }

    @Test(timeout = 10000)
    public void evenASuccessfulMuxCannotOverwriteAnotherDestination() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(media("video.bin")), fixed(200, VIDEO))) {
            DownloadItem job = job(server.url("/playlist.m3u8"), HLS);
            Files.write(destination(job), EXISTING);
            TestTask task = run(job, (args, target, attempt) -> {
                Files.write(target, MUX_OUTPUT);
                return 0;
            });
            assertFailed(task, "destination already exists");
            assertArrayEquals(EXISTING, Files.readAllBytes(destination(job)));
            assertOnlyDestinationInDirectory(job);
        }
    }

    @Test(timeout = 10000)
    public void stoppingWhileMuxingCannotPublishEvenAZeroExitResult() throws Exception {
        for (DownloadStatus stopped : List.of(DownloadStatus.PAUSED, DownloadStatus.CANCELLED,
                DownloadStatus.WAITING_FOR_WIFI, DownloadStatus.WAITING_FOR_SCHEDULE, DownloadStatus.WAITING_FOR_BATTERY)) {
            boolean cancel = stopped == DownloadStatus.CANCELLED;
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(media("video.bin")), fixed(200, VIDEO))) {
                DownloadItem job = job(server.url("/playlist.m3u8"), HLS);
                if (cancel) Files.write(destination(job), EXISTING);
                CountDownLatch muxRunning = new CountDownLatch(1);
                CountDownLatch finishMux = new CountDownLatch(1);
                TestTask task = new TestTask(job, new Events(), (args, target, attempt) -> {
                    Files.write(target, MUX_OUTPUT);
                    muxRunning.countDown();
                    try {
                        assertTrue(finishMux.await(4, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    return 0;
                });
                FutureTask<Void> execution = new FutureTask<>(task, null);
                Thread worker = new Thread(execution, "stream-mux-fixture");
                worker.setDaemon(true);
                worker.start();
                try {
                    assertTrue("The task did not enter the muxer", muxRunning.await(4, TimeUnit.SECONDS));
                    task.stopFor(stopped, stopped.isWaiting() ? "fixture constraint" : null);
                } finally {
                    finishMux.countDown();
                }
                execution.get(4, TimeUnit.SECONDS);
                assertEquals(stopped, job.getStatus());
                assertEquals(stopped.isWaiting() ? "fixture constraint" : null, job.getErrorMessage());
                assertFalse(task.events.completed);
                assertNull(task.events.error);
                assertEquals(HLS, job.getMimeType());
                assertEquals(0, job.getFinishedTime());
                if (cancel) assertArrayEquals(EXISTING, Files.readAllBytes(destination(job)));
                else assertFalse(Files.exists(destination(job)));
                assertOnlyDestinationInDirectory(job);
            }
        }
    }

    @Test(timeout = 10000)
    public void anIgnoredHlsByteRangeDoesNotReachTheMuxer() throws Exception {
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(
                playlist("#EXTM3U\n#EXTINF:2,\n#EXT-X-BYTERANGE:2@1\nvideo.bin\n#EXT-X-ENDLIST\n"), fixed(200, VIDEO))) {
            verifyFailure(run(job(server.url("/playlist.m3u8"), HLS), mustNotMux()), "ignored the HLS byte range");
            assertEquals("bytes=1-2", server.requests().get(1).header("Range"));
        }
    }

    @Test(timeout = 10000)
    public void stagesAMultiBufferSegmentWhileItsHttpBodyIsStillOpen() throws Exception {
        Path folder = files.newFolder().toPath();
        byte[] segment = new byte[512 * 1024 + 13];
        Arrays.fill(segment, (byte) 0x5a);
        int first = 192 * 1024;
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(media("large.bin")),
                LoopbackHttpFixture.streamed(200, segment.length, out -> {
                    out.write(segment, 0, first);
                    out.flush();
                    // The server withholds the rest until it can observe the staged prefix.
                    // A whole-segment buffer cannot make progress at this boundary.
                    assertStagedBytes(folder, first);
                    out.write(segment, first, segment.length - first);
                }))) {
            DownloadItem job = job(server.url("/playlist.m3u8"), HLS);
            job.setDestinationPath(folder.resolve("clip.mp4").toString());
            TestTask task = run(job, (args, target, attempt) -> {
                assertArrayEquals(segment, Files.readAllBytes(Path.of(valuesAfter(args, "-i").get(0))));
                Files.write(target, MUX_OUTPUT);
                return 0;
            });
            verifyCompletion(task);
            assertEquals(2, server.requests().size());
        }
    }

    private static void assertStagedBytes(Path folder, long count) throws IOException {
        long started = System.nanoTime();
        while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3)) {
            File[] parts = folder.toFile().listFiles((dir, name) -> name.endsWith(".video.part"));
            if (parts != null && parts.length == 1 && parts[0].length() >= count) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        throw new AssertionError("The media prefix was not staged before EOF");
    }

    @Test(timeout = 10000)
    public void retryOfAnUnterminatedChunkedSegmentDoesNotAppendItsPartialBytesTwice() throws Exception {
        byte[] large = new byte[128 * 1024 + 13];
        Arrays.fill(large, (byte) 42);
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        iv[15] = 1; // sequence 1 follows the first unencrypted segment
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(large);
        String list = "#EXTM3U\n#EXTINF:2,\nfirst.bin\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXTINF:2,\nlarge.bin\n#EXT-X-ENDLIST\n";
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(list), fixed(200, VIDEO), fixed(200, key),
                chunked(200, Arrays.copyOf(encrypted, 96 * 1024), false), chunked(200, encrypted))) {
            TestTask task = run(job(server.url("/playlist.m3u8"), HLS), (args, target, attempt) -> {
                byte[] staged = Files.readAllBytes(Path.of(valuesAfter(args, "-i").get(0)));
                assertEquals(VIDEO.length + large.length, staged.length);
                assertArrayEquals(VIDEO, Arrays.copyOf(staged, VIDEO.length));
                assertArrayEquals(large, Arrays.copyOfRange(staged, VIDEO.length, staged.length));
                Files.write(target, MUX_OUTPUT);
                return 0;
            });
            verifyCompletion(task);
            assertEquals(List.of("/playlist.m3u8", "/first.bin", "/key.bin", "/large.bin", "/large.bin"), requestTargets(server));
        }
    }

    @Test(timeout = 10000)
    public void anInvalidFinalAesBlockOrDecryptedManifestIsNeverMuxed() throws Exception {
        byte[] key = new byte[16], iv = new byte[16];
        Cipher encoder = Cipher.getInstance("AES/CBC/PKCS5Padding");
        encoder.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] clear = new byte[96 * 1024];
        byte[] badPadding = encoder.doFinal(clear);
        badPadding[badPadding.length - 17] ^= 1; // make the last padding byte differ from the others
        byte[] html = encoder.doFinal(text("<!DOCTYPE html><html>fixture</html>"));
        for (byte[] wire : List.of(badPadding, html)) {
            String list = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:2,\nseg.bin\n#EXT-X-ENDLIST\n";
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(list), fixed(200, key), fixed(200, wire))) {
                verifyFailure(run(job(server.url("/playlist.m3u8"), HLS), mustNotMux()),
                        wire == badPadding ? "Could not decrypt" : "error page instead of media");
                assertEquals(3, server.requests().size());
            }
        }
    }

    @Test(timeout = 10000)
    public void incorrectRangeFramingCannotReachMuxOrLeaveStaging() throws Exception {
        String list = "#EXTM3U\n#EXTINF:2,\n#EXT-X-BYTERANGE:3@5\nclip.bin\n#EXT-X-ENDLIST\n";
        LoopbackHttpFixture.Response[] invalid = {
                fixed(206, AUDIO, "Content-Range: bytes 4-6/20"),
                fixed(206, AUDIO, "Content-Range: bytes 5-7/7"),
                fixed(206, AUDIO, "Content-Range: bytes 5-7/*"),
                fixed(206, AUDIO, "Content-Range: bytes 5-7/20", "Content-Range: bytes 5-7/20"),
                fixed(206, VIDEO, "Content-Range: bytes 5-7/20"),
                fixed(206, AUDIO, "Content-Range: bytes 5-7/20", "Content-Type: multipart/byteranges; boundary=fixture"),
                fixed(206, AUDIO, "Content-Range: bytes 5-7/20", "Content-Encoding: gzip"),
                fixed(206, AUDIO, "Content-Range: bytes 5-7/20", "Transfer-Encoding: chunked"),
                chunked(206, VIDEO, "Content-Range: bytes 5-7/20")
        };
        for (LoopbackHttpFixture.Response response : invalid) {
            try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(list), response)) {
                verifyFailure(run(job(server.url("/playlist.m3u8"), HLS), mustNotMux()), "");
                assertEquals("bytes=5-7", server.requests().get(1).header("Range"));
            }
        }
    }

    @Test(timeout = 10000)
    public void fullSegmentWithAShortDeclaredBodyIsNotPublished() throws Exception {
        byte[] partial = new byte[96 * 1024];
        LoopbackHttpFixture.Response truncated = fixed(200, partial, partial.length + 1L);
        try (LoopbackHttpFixture server = new LoopbackHttpFixture(playlist(media("clip.bin")), truncated, truncated, truncated)) {
            verifyFailure(run(job(server.url("/playlist.m3u8"), HLS), mustNotMux()), "");
        }
    }

    private DownloadItem job(String url, String mime) throws IOException {
        DownloadItem item = new DownloadItem();
        item.setId("fixture-stream");
        item.setUrl(url);
        item.setMimeType(mime);
        item.setFileName("clip.mp4");
        item.setDestinationPath(new File(files.newFolder(), "clip.mp4").getAbsolutePath());
        item.setTempPath(item.getDestinationPath() + ".part");
        return item;
    }

    private DownloadItem dualTrackJob(LoopbackHttpFixture server) throws IOException {
        DownloadItem item = job(server.url("/video"), "video/mp4");
        item.setAudioUrl(server.url("/audio"));
        return item;
    }

    private static String media(String file) {
        return "#EXTM3U\n#EXTINF:2,\n" + file + "\n#EXT-X-ENDLIST\n";
    }

    private static String master(String rendition) {
        return "#EXTM3U\n" + rendition + "#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1,mp4a\",AUDIO=\"en\"\nvideo.m3u8\n";
    }

    private static LoopbackHttpFixture.Response playlist(String contents) {
        return fixed(200, text(contents), "Content-Type: " + HLS);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> requestTargets(LoopbackHttpFixture server) throws Exception {
        return server.requests().stream().map(request -> request.target).collect(Collectors.toList());
    }

    private static Path destination(DownloadItem item) {
        return Path.of(item.getDestinationPath());
    }

    private static List<String> valuesAfter(List<String> args, String option) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i + 1 < args.size(); i++) if (option.equals(args.get(i))) values.add(args.get(i + 1));
        return values;
    }

    private static void verifySeparateInputs(List<String> args, int attempt) throws IOException {
        assertEquals(List.of("0:v:0", "1:a:0"), valuesAfter(args, "-map"));
        assertEquals(List.of(attempt == 1 ? "copy" : "aac"), valuesAfter(args, "-c:a"));
        List<String> inputs = valuesAfter(args, "-i");
        assertEquals(2, inputs.size());
        assertArrayEquals(VIDEO, Files.readAllBytes(Path.of(inputs.get(0))));
        assertArrayEquals(AUDIO, Files.readAllBytes(Path.of(inputs.get(1))));
    }

    private static void assertFailed(TestTask task, String reason) {
        assertEquals(DownloadStatus.FAILED, task.getItem().getStatus());
        assertEquals(0, task.getItem().getFinishedTime());
        assertFalse(task.events.completed);
        assertNotNull(task.events.error);
        assertEquals(task.getItem().getErrorMessage(), task.events.error);
        assertTrue(task.events.error, task.events.error.contains(reason));
    }

    private static void verifyFailure(TestTask task, String reason) throws IOException {
        assertFailed(task, reason);
        assertFalse(Files.exists(destination(task.getItem())));
        assertOnlyDestinationInDirectory(task.getItem());
    }

    private static void verifyCompletion(TestTask task) throws IOException {
        DownloadItem item = task.getItem();
        assertTrue(item.getErrorMessage(), task.events.completed);
        assertNull(task.events.error);
        assertEquals(DownloadStatus.COMPLETED, item.getStatus());
        assertEquals("video/mp4", item.getMimeType());
        assertEquals(MUX_OUTPUT.length, item.getDownloadedBytes());
        assertEquals(MUX_OUTPUT.length, item.getTotalBytes());
        assertTrue(item.getFinishedTime() > 0);
        assertEquals(1, task.attemptSaves);
        assertArrayEquals(MUX_OUTPUT, Files.readAllBytes(destination(item)));
        assertOnlyDestinationInDirectory(item);
    }

    private static void assertOnlyDestinationInDirectory(DownloadItem item) throws IOException {
        Path output = destination(item);
        try (Stream<Path> files = Files.list(output.getParent())) {
            assertEquals(Files.exists(output) ? List.of(output) : List.of(), files.collect(Collectors.toList()));
        }
    }

    private static TestTask run(DownloadItem item, Mux muxer) {
        TestTask task = new TestTask(item, new Events(), muxer);
        task.run();
        return task;
    }

    private static Mux mustNotMux() {
        return (args, target, attempt) -> {
            fail("An invalid input reached FFmpeg");
            return -1;
        };
    }

    private interface Mux {
        int execute(List<String> args, Path target, int attempt) throws IOException;
    }

    private static final class Events implements DownloadTask.TaskListener {
        boolean completed;
        String error;

        @Override public void onTaskUpdated(DownloadItem item) {}
        @Override public void onTaskCompleted(DownloadItem item) { completed = true; }
        @Override public void onTaskFailed(DownloadItem item, String error) { this.error = error; }
    }

    private static final class TestTask extends StreamDownloadTask {
        final Events events;
        final Mux muxer;
        final List<Path> muxTargets = new ArrayList<>();
        int attemptSaves;

        TestTask(DownloadItem item, Events events, Mux muxer) {
            this(item, events, muxer, RequestContext.anonymous());
        }

        TestTask(DownloadItem item, Events events, Mux muxer, RequestContext context) {
            super(item, null, events, null, context);
            this.events = events;
            this.muxer = muxer;
        }

        @Override void persistAttempt(String previousTempPath) { attemptSaves++; }
        @Override void persistPublication(String path, String staging, long size, String mime, long finishedAt, String identity) throws IOException {
            assertEquals(DownloadStatus.RUNNING, item.getStatus());
            assertArrayEquals(MUX_OUTPUT, Files.readAllBytes(Path.of(path)));
        }
        @Override boolean persistStatus(DownloadStatus status, String reason) {
            assertTrue("the worker must not rewrite a controller stop", status == DownloadStatus.RUNNING || status == DownloadStatus.FAILED);
            return true;
        }
        @Override void persistProgress(long downloaded) {}

        @Override int executeMux(List<String> args) throws IOException {
            Path output = Path.of(args.get(args.size() - 1));
            assertEquals(destination(item).getParent(), output.getParent());
            assertTrue(output.getFileName().toString().endsWith(".mux.part"));
            assertEquals(0, Files.size(output));
            assertEquals(List.of("mp4"), valuesAfter(args, "-f"));
            assertEquals(List.of("empty_output+empty_output_stream"), valuesAfter(args, "-abort_on"));
            assertTrue(args.contains("-xerror"));
            muxTargets.add(output);
            return muxer.execute(args, output, muxTargets.size());
        }
    }
}

package com.privacy.browser.media;

import com.privacy.browser.media.MediaSniffer.SniffedMedia;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class MediaMetadataTest {
    private static final String MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=640x360\n"
            + "Variants/Low?SIG=A%2F\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\n?SIG=B%2f\n";

    @Test public void noMediaAndPassiveDuplicatesCauseZeroNativeRequestsThenOneHeadAndOnePlaylistGet() throws Exception {
        try (Fixture net = new Fixture()) {
            ThreadPoolExecutor pool = MediaSniffer.newPool();
            try {
                MediaSniffer page = new MediaSniffer(pool, MediaMetadata::lookup);
                page.observe(null);
                page.requestHint(net.url("/ordinary.html"), "/ordinary.html", "Regular page");
                assertFalse(page.enrich(null, "Page", Collections.emptyList(), u -> true, () -> fail("No media")));
                String clip = net.url("/Clip.mp4?Signature=Ax%2F&n=1");
                String master = net.url("/Hls/master.m3u8?Signature=Bx%2f");
                for (int i = 0; i < 250; i++) {
                    page.requestHint(clip, "/Clip.mp4", "Page");
                    page.elementHint(clip + "#t=1", "video", "Page");
                    page.requestHint(master, "/Hls/master.m3u8", "Page");
                }
                assertTrue(net.requests.isEmpty());
                assertEquals(0, pool.getLargestPoolSize());
                CountDownLatch finished = new CountDownLatch(1);
                assertTrue(page.enrich(null, "Page", Collections.emptyList(), u -> true, finished::countDown));
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("GET /Hls/master.m3u8?Signature=Bx%2f", "HEAD /Clip.mp4?Signature=Ax%2F&n=1"), net.lines());
                assertEquals(4, page.count());
                SniffedMedia direct = page.snapshot().get(0);
                assertEquals(12345, direct.getFileSize());
                assertEquals(clip, direct.getUrl());
                assertEquals("360p", page.snapshot().get(2).getQuality());
                assertEquals(net.url("/Hls/Variants/Low?SIG=A%2F"), page.snapshot().get(2).getUrl());
                assertEquals(net.url("/Hls/master.m3u8?SIG=B%2f"), page.snapshot().get(3).getUrl());
                assertFalse(page.enrich(null, "Page", Collections.emptyList(), u -> true, () -> fail("Duplicate fetch")));
                assertEquals(2, net.requests.size()); // no variants or segments were fetched
                for (Request req : net.requests) req.assertAnonymous();
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }

    @Test public void oneRangeFallbackUsesOnlyContentRangeAndNeverUsesAnIgnoredRangeAsSize() throws Exception {
        try (Fixture net = new Fixture()) {
            List<SniffedMedia> ranged = lookup(net.url("/range.mp4"), "video", budget());
            assertEquals(9821, ranged.get(0).getFileSize());
            assertTrue(lookup(net.url("/ignored.mp4"), "video", budget()).isEmpty());
            assertEquals(List.of("HEAD /range.mp4", "GET /range.mp4", "HEAD /ignored.mp4", "GET /ignored.mp4"), net.lines());
            assertEquals("bytes=0-0", net.requests.get(1).header("Range"));
            assertEquals("bytes=0-0", net.requests.get(3).header("Range"));
            for (Request req : net.requests) req.assertAnonymous();
        }
    }

    @Test public void redirectsAreRecheckedAndNeverCarryCookiesEvenAcrossOrigins() throws Exception {
        try (Fixture a = new Fixture(); Fixture b = new Fixture()) {
            a.redirect = b.url("/Clip.mp4");
            MediaMetadata.Budget blocked = new MediaMetadata.Budget(() -> false, u -> !u.equals(a.redirect));
            assertThrows(IOException.class, () -> lookup(a.url("/redirect.mp4"), "video", blocked));
            assertTrue(b.requests.isEmpty());
            assertEquals(12345, lookup(a.url("/redirect.mp4"), "video", budget()).get(0).getFileSize());
            assertEquals(List.of("HEAD /redirect.mp4", "HEAD /redirect.mp4"), a.lines());
            assertEquals(List.of("HEAD /Clip.mp4"), b.lines());
            for (Request req : a.requests) req.assertAnonymous();
            b.requests.get(0).assertAnonymous();
        }
    }

    @Test public void redirectAndBatchRequestBudgetsBoundActualLoopbackTraffic() throws Exception {
        try (Fixture net = new Fixture()) {
            net.redirect = net.url("/redirect.mp4");
            MediaMetadata.Budget budget = budget();
            for (int i = 0; i < 4; i++) {
                int before = net.requests.size();
                assertThrows(IOException.class, () -> lookup(net.url("/redirect.mp4"), "video", budget));
                assertEquals(3, net.requests.size() - before); // Initial HEAD + two redirects, never a fourth hop.
            }
            assertTrue(budget.exhausted());
            assertThrows(IOException.class, () -> lookup(net.url("/Clip.mp4"), "video", budget));
            assertEquals(12, net.requests.size());
        }
    }

    @Test public void retiringWhileWaitingForHeadersPreventsTheNextRealMetadataRequest() throws Exception {
        try (Fixture net = new Fixture()) {
            ThreadPoolExecutor pool = MediaSniffer.newPool();
            try {
                MediaSniffer page = new MediaSniffer(pool, MediaMetadata::lookup);
                page.observe(null);
                page.elementHint(net.url("/hold.mp4"), "video", "Old page");
                page.elementHint(net.url("/next.mp4"), "video", "Old page");
                AtomicBoolean published = new AtomicBoolean();
                assertTrue(page.enrich(null, "Old page", Collections.emptyList(), u -> true, () -> published.set(true)));
                assertTrue(net.opened.await(3, TimeUnit.SECONDS));
                page.close();
                net.release.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(4, TimeUnit.SECONDS));
                assertEquals(Collections.singletonList("HEAD /hold.mp4"), net.lines());
                assertEquals(0, page.count());
                assertFalse(published.get());
            } finally { net.release.countDown(); pool.shutdownNow(); }
        }
    }

    @Test public void completeAndPrefixReadsHaveExactCeilingsIncludingOversizeByteAndBatchCap() throws Exception {
        for (int limit : new int[]{MediaMetadata.HLS_BYTES, MediaMetadata.PLAYER_BYTES}) {
            CountingBody big = new CountingBody(limit * 4);
            assertThrows(IOException.class, () -> MediaMetadata.readBody(big, limit, false, budget()));
            assertEquals(limit + 1, big.bytes);
            CountingBody fits = new CountingBody(limit);
            assertEquals(limit, MediaMetadata.readBody(fits, limit, false, budget()).length());
            assertEquals(limit, fits.bytes);
        }
        MediaMetadata.Budget batch = budget();
        CountingBody prefix = new CountingBody(4 * MediaMetadata.TOTAL_BYTES);
        for (int i = 0; i < 3; i++) MediaMetadata.readBody(prefix, MediaMetadata.EMBED_BYTES, true, batch);
        assertEquals(MediaMetadata.TOTAL_BYTES, prefix.bytes);
        assertTrue(batch.exhausted());
        assertThrows(IOException.class, () -> MediaMetadata.readBody(prefix, MediaMetadata.EMBED_BYTES, true, batch));
        assertEquals(MediaMetadata.TOTAL_BYTES, prefix.bytes);
    }

    @Test public void aCanceledReadCannotReturnContentOrStartAnotherRequest() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        MediaMetadata.Budget canceled = new MediaMetadata.Budget(stop::get, u -> true);
        CountingBody response = new CountingBody(10000) {
            @Override public int read(byte[] b, int offset, int length) {
                stop.set(true);
                return super.read(b, offset, length);
            }
        };
        assertThrows(java.io.InterruptedIOException.class, () -> MediaMetadata.readBody(response, 8192, false, canceled));
        assertEquals(4096, response.bytes);
        try (Fixture net = new Fixture()) {
            assertThrows(java.io.InterruptedIOException.class, () -> lookup(net.url("/Clip.mp4"), "video", canceled));
            assertTrue(net.requests.isEmpty());
        }
    }

    @Test public void hlsStaysWithItsManifestAndNeverListsSegmentsAsQualities() {
        StringBuilder body = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 100; i++) body.append("#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=8x8\n").append(i).append(".m3u8\n");
        assertEquals(MediaMetadata.VARIANTS, MediaMetadata.parseHls("https://media.invalid/stream", body.toString(), "Demo").size());
        assertTrue(MediaMetadata.parseHls("https://media.invalid/stream", "#EXTM3U\n#EXTINF:5\nclip-a.ts\n", "Demo").isEmpty());
        assertTrue(MediaMetadata.parseHls("https://media.invalid/stream", "<html>Signing in</html>", "Demo").isEmpty());
    }

    @Test public void recognizeOnlyPublicProviderShapeAndKeepSignedVimeoHashesSeparate() {
        for (String url : List.of("https://www.youtube.com/watch?v=abc12345678", "https://m.youtube.com/shorts/abc12345678",
                "https://youtu.be/abc12345678?start=1", "https://www.youtube-nocookie.com/embed/abc12345678")) {
            assertEquals("youtube:abc12345678", MediaSource.platform(url).key);
        }
        assertEquals(MediaSource.platform("https://vimeo.com/123?h=A1b2").key,
                MediaSource.platform("https://player.vimeo.com/video/123?h=A1b2#start").key);
        assertNotEquals(MediaSource.platform("https://vimeo.com/123?h=A1b2").key,
                MediaSource.platform("https://vimeo.com/123?h=a1b2").key);
        for (String invalid : List.of("https://youtu.be.example.invalid/abc12345678", "https://x:y@vimeo.com/123",
                "https://www.youtube.com:8080/watch?v=abc12345678", "https://www.youtube.com/results?v=abc12345678",
                "https://vimeo.com/123?h=bad%26x", "https://www.youtube.com/watch?v=morethan11chars")) {
            assertNull(MediaSource.platform(invalid));
        }
    }

    @Test public void platformParsersKeepProgressiveAndPlayableAdaptiveRowsAndFallbackManifests() throws Exception {
        String vimeo = """
                <script>window.playerConfig = {"video":{"title":"Vimeo {fixture}"},"request":{"files":{
                  "progressive":[{"url":"https://video.invalid/Low.mp4?SIG=A%2F","quality":"360p"},
                                 {"url":"https://video.invalid/High.mp4?SIG=B%2f","quality":"720p"}],
                  "hls":{"default_cdn":"chosen","cdns":{"chosen":{"url":"https://video.invalid/signed-manifest"}}}
                }}};</script>
                """;
        List<SniffedMedia> v = MediaMetadata.parseVimeo(MediaMetadata.extractJsonAfterMarker(vimeo, "window.playerConfig = "), "Fallback");
        assertEquals(3, v.size());
        assertEquals("Vimeo {fixture}", v.get(0).getTitle());
        assertEquals("360p", v.get(0).getQuality());
        assertEquals("https://video.invalid/High.mp4?SIG=B%2f", v.get(1).getUrl());
        assertEquals(MediaSniffer.HLS_MIME, v.get(2).getManifestMime());
        String youtube = """
                {"videoDetails":{"videoId":"abc12345678","title":"Public fixture"},"streamingData":{
                   "formats":[{"url":"https://video.invalid/progressive","qualityLabel":"360p","contentLength":"10485760"}],
                   "adaptiveFormats":[{"url":"https://video.invalid/audio?itag=1","mimeType":"audio/mp4; codecs=mp4a",
                     "bitrate":90000,"contentLength":"2500","itag":1},
                     {"url":"https://video.invalid/av1?itag=2","mimeType":"video/mp4; codecs=av01",
                      "height":2160,"contentLength":"5000","itag":2},
                     {"signatureCipher":"url=https%3A%2F%2Fvideo.invalid%2Funsigned","mimeType":"video/mp4"}],
                   "hlsManifestUrl":"https://video.invalid/manifest"
                }}
                """;
        List<SniffedMedia> y = MediaMetadata.parseYouTube("abc12345678", youtube, "Fallback");
        assertEquals(4, y.size());
        assertEquals("audio", y.get(0).getType());
        assertEquals("360p", y.get(1).getQuality());
        assertNull(y.get(1).getAudioUrl());
        assertEquals("2160p", y.get(2).getQuality());
        assertEquals("AV1", y.get(2).getCodec());
        assertEquals(y.get(0).getUrl(), y.get(2).getAudioUrl());
        assertEquals(2500, y.get(2).getAudioFileSize());
        assertEquals(MediaSniffer.HLS_MIME, y.get(3).getManifestMime());
        assertTrue(MediaMetadata.parseYouTube("zzz12345678", youtube, "Bad video").isEmpty());
        assertTrue(MediaMetadata.parseYouTube("abc12345678", "{\"nest\":" + "[".repeat(100) + "]".repeat(100) + "}", "Nested").isEmpty());
    }

    private static MediaMetadata.Budget budget() { return new MediaMetadata.Budget(() -> false, url -> true); }

    private static List<SniffedMedia> lookup(String url, String kind, MediaMetadata.Budget budget) throws Exception {
        return MediaMetadata.lookup(MediaSource.element(url, kind), "Fixture", budget);
    }

    private static class CountingBody extends InputStream {
        final int total;
        int bytes;
        CountingBody(int total) { this.total = total; }
        @Override public int read() { if (bytes == total) return -1; bytes++; return 'x'; }
        @Override public int read(byte[] b, int offset, int length) {
            int size = Math.min(total - bytes, length);
            if (size == 0) return -1;
            java.util.Arrays.fill(b, offset, offset + size, (byte) 'x');
            bytes += size;
            return size;
        }
    }

    private static final class Request {
        final String method, target;
        final Map<String, String> headers;
        Request(String method, String target, Map<String, String> headers) {
            this.method = method; this.target = target; this.headers = headers;
        }
        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
        void assertAnonymous() {
            for (String name : List.of("Cookie", "Authorization", "Referer", "Origin")) assertNull(name, header(name));
        }
    }

    /** Counts requests on the socket instead of inferring request count from returned rows. */
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        final FutureTask<Void> serving;
        volatile String redirect;
        final CountDownLatch opened = new CountDownLatch(1), release = new CountDownLatch(1);

        Fixture() throws IOException {
            serving = new FutureTask<>(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        handle(socket);
                    } catch (java.net.SocketException closed) {
                        if (!server.isClosed()) throw closed;
                    }
                }
                return null;
            });
            Thread t = new Thread(serving, "media-test-loopback");
            t.setDaemon(true);
            t.start();
        }

        String url(String path) { return "http://127.0.0.1:" + server.getLocalPort() + path; }
        List<String> lines() {
            synchronized (requests) {
                List<String> lines = new ArrayList<>();
                for (Request request : requests) lines.add(request.method + " " + request.target);
                return lines;
            }
        }

        private void handle(Socket socket) throws Exception {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String[] first = reader.readLine().split(" ");
            Map<String, String> headers = new HashMap<>();
            for (String header; (header = reader.readLine()) != null && !header.isEmpty();) {
                int colon = header.indexOf(':');
                if (colon > 0) headers.put(header.substring(0, colon).toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
            }
            Request req = new Request(first[0], first[1], headers);
            requests.add(req);
            String path = req.target.split("\\?", 2)[0];
            if (path.equals("/hold.mp4")) {
                opened.countDown();
                release.await(4, TimeUnit.SECONDS);
            }
            OutputStream out = socket.getOutputStream();
            String reply;
            if (path.equals("/redirect.mp4")) {
                reply = "302 Found\r\nLocation: " + redirect + "\r\nSet-Cookie: fixture=reject; Path=/\r\nContent-Length: 0\r\n\r\n";
            } else if (path.endsWith(".m3u8")) {
                reply = "200 OK\r\nContent-Length: " + MASTER.length() + "\r\n\r\n" + MASTER;
            } else if ((path.equals("/range.mp4") || path.equals("/ignored.mp4")) && req.method.equals("HEAD")) {
                reply = "405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n";
            } else if (path.equals("/range.mp4")) {
                reply = "206 Partial Content\r\nContent-Range: bytes 0-0/9821\r\nContent-Length: 1\r\n\r\nx";
            } else {
                reply = "200 OK\r\nContent-Length: 12345\r\n\r\n"; // no actual file payload needed
            }
            out.write(("HTTP/1.1 " + reply.replaceFirst("\r\n", "\r\nConnection: close\r\n")).getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override public void close() throws Exception { server.close(); serving.get(4, TimeUnit.SECONDS); }
    }
}

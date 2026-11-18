package com.privacy.browser.comic;

import com.privacy.downloader.net.RequestContext;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

public class ComicArchiverTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF3sAAAAASUVORK5CYII=");
    private static final byte[] GIF = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==");
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] OCCUPIED = {4, 2, 1};
    private static final String PROFILE_UA = "ComicFixture/1";
    private static final ComicArchiver.Progress IGNORE_PROGRESS = (index, total) -> {};
    private static final ComicArchiver.Verification NO_VERIFICATION = (url, scope, maxWait) -> {
        fail("Unexpected verification prompt");
        return false;
    };

    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test(timeout = 5000) public void realZipPreservesEveryPositionAndUsesTheImageBytesForExtensions() throws Exception {
        Paths p = new Paths();
        List<Integer> progress = new ArrayList<>();
        try (Server server = new Server((req, out) -> {
            if (req.target.startsWith("/a")) reply(out, 200, "image/gif", GIF);
            else reply(out, 200, "image/jpeg", PNG); // Don't infer type from the URL or header.
        })) {
            Files.write(new File(p.downloads, "Chapter.cbz").toPath(), OCCUPIED);
            ComicArchiver.Job job = new ComicArchiver.Job();
            File archive = build(p, "Chapter.CBR", List.of(server.url("/z.jpeg"), server.url("/a.gif"), server.url("/z.jpeg")),
                    job, (i, total) -> { assertEquals(3, total); progress.add(i); });
            assertEquals("Chapter_1.cbz", archive.getName());
            assertFalse("Closing after commit cannot undo a saved archive", job.cancel());
            try (ZipFile zip = new ZipFile(archive)) {
                List<? extends ZipEntry> entries = Collections.list(zip.entries());
                assertEquals(3, entries.size());
                assertEntry(zip, entries.get(0), "page_0001.png", PNG);
                assertEntry(zip, entries.get(1), "page_0002.gif", GIF);
                assertEntry(zip, entries.get(2), "page_0003.png", PNG);
            }
            assertEquals(List.of(1, 2, 3), progress);
            assertEquals(List.of("/z.jpeg", "/a.gif", "/z.jpeg"), server.targets());
            assertArrayEquals(OCCUPIED, Files.readAllBytes(new File(p.downloads, "Chapter.cbz").toPath()));
        }
        p.assertStagingEmpty();
    }

    @Test(timeout = 5000) public void missingMiddlePageFailsAtItsOriginalNumberWithoutFetchingTheTail() throws Exception {
        Paths p = new Paths();
        List<Integer> progress = new ArrayList<>();
        try (Server server = new Server((req, out) -> {
            if ("/missing".equals(req.target)) reply(out, 404, "text/html", EMPTY);
            else reply(out, 200, "image/png", PNG);
        })) {
            IOException failure = assertThrows(IOException.class, () -> build(p, "Chapter",
                    List.of(server.url("/first"), server.url("/missing"), server.url("/last")),
                    new ComicArchiver.Job(), (i, total) -> progress.add(i)));
            assertTrue(failure.getMessage(), failure.getMessage().startsWith("Page 2 of 3: HTTP 404"));
            assertEquals(List.of("/first", "/missing"), server.targets());
            assertEquals(List.of(1), progress);
        }
        p.assertEmpty();
    }

    @Test(timeout = 7000) public void partialEmptyNonImageAndOversizedResponsesNeverComplete() throws Exception {
        List<Reply> invalid = List.of(
                (req, out) -> reply(out, 206, "image/png", PNG),
                (req, out) -> reply(out, 200, "image/png", EMPTY),
                (req, out) -> reply(out, 200, "image/jpeg", html("<img src='/replacement-logo.png'>")),
                (req, out) -> reply(out, 200, "text/html", html("<img src='/replacement-logo.png'>")),
                (req, out) -> reply(out, 200, "text/html", html("<img class='reader-image' src='/a'><img class='reader-image' src='/b'>")),
                (req, out) -> reply(out, 200, "image/png", Arrays.copyOf(PNG, PNG.length - 5)), // Missing IEND despite exact length.
                (req, out) -> framed(out, 200, "image/png", PNG, PNG.length + 12), // Early EOF with a plausible image.
                (req, out) -> framed(out, 200, "image/png", EMPTY, 33L * 1024 * 1024));
        for (Reply response : invalid) {
            Paths p = new Paths();
            try (Server server = new Server(response)) {
                IOException failure = assertThrows(IOException.class, () -> build(p, "Rejected",
                        List.of(server.url("/bad?fixture-not-in-error")), new ComicArchiver.Job(), IGNORE_PROGRESS));
                assertTrue(failure.getMessage(), failure.getMessage().startsWith("Page 1 of 1:"));
                assertFalse(failure.getMessage().contains("fixture-not-in-error"));
                assertEquals(List.of("/bad?fixture-not-in-error"), server.targets());
            }
            p.assertEmpty();
        }
    }

    @Test(timeout = 5000) public void readerChildUsesTheActualReaderAddressAndScopedHeaders() throws Exception {
        Paths p = new Paths();
        try (Server server = new Server((req, out) -> {
            if (req.target.equals("/chapters/one/index")) {
                reply(out, 200, "text/html; charset=utf-8", html("<base href='https://fixture.invalid/'>"
                        + "<img class='reader-image' data-src='../page.png?w=700&amp;signature=fixture' src='/poster'>"));
            } else reply(out, 200, "image/png", PNG);
        })) {
            RequestContext scope = RequestContext.fromBrowser(server.url("/chapter?not-a-referer"), PROFILE_UA, false);
            ComicArchiver.buildArchive(p.cache, p.downloads, "Reader", List.of(server.url("/chapters/one/index")), scope,
                    new ComicArchiver.Job(), NO_VERIFICATION, IGNORE_PROGRESS);
            assertEquals(List.of("/chapters/one/index", "/chapters/page.png?w=700&signature=fixture"), server.targets());
            for (Request request : server.seen) {
                assertEquals(PROFILE_UA, request.headers.get("user-agent"));
                assertEquals(server.url("/"), request.headers.get("referer"));
                assertEquals("identity", request.headers.get("accept-encoding"));
            }
        }
        p.assertStagingEmpty();
    }

    @Test(timeout = 5000) public void foreignReaderCannotReattachSessionForAnImageOnTheOriginalOrigin() throws Exception {
        Paths p = new Paths();
        String[] otherUrl = new String[1];
        try (Server original = new Server((req, out) -> {
                if (req.target.equals("/redirect")) {
                    out.write(("HTTP/1.1 302 Found\r\nLocation: " + otherUrl[0] + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                } else reply(out, 200, "image/png", PNG);
            });
            Server foreign = new Server((req, out) -> reply(out, 200, "text/html",
                    html("<img class='reader-image' src='" + original.url("/returned") + "'>")))) {
            otherUrl[0] = foreign.url("/reader");
            ComicArchiver.buildArchive(p.cache, p.downloads, "Narrowed", List.of(original.url("/redirect")),
                    RequestContext.fromBrowser(original.url("/page"), PROFILE_UA, false), new ComicArchiver.Job(), NO_VERIFICATION, IGNORE_PROGRESS);
            assertEquals(List.of("/redirect", "/returned"), original.targets());
            assertEquals(PROFILE_UA, original.seen.get(0).headers.get("user-agent"));
            assertEquals(List.of("/reader"), foreign.targets());
            for (Request request : List.of(foreign.seen.get(0), original.seen.get(1))) {
                assertNull(request.headers.get("cookie"));
                assertNull(request.headers.get("referer"));
                assertEquals(RequestContext.DEFAULT_USER_AGENT, request.headers.get("user-agent"));
            }
        }
    }

    @Test(timeout = 5000) public void verificationUsesOnlyAnAuthorizedOriginAndOneBoundedWait() throws Exception {
        Paths p = new Paths();
        try (Server server = new Server((req, out) -> reply(out, 403, "text/html", EMPTY))) {
            RequestContext scope = RequestContext.fromBrowser(server.url("/chapter"), PROFILE_UA, false);
            AtomicLong waits = new AtomicLong();
            assertThrows(IOException.class, () -> ComicArchiver.buildArchive(p.cache, p.downloads, "Verify",
                    List.of(server.url("/challenge")), scope, new ComicArchiver.Job(), (url, request, cap) -> {
                        assertEquals(server.url("/challenge"), url);
                        assertSame(scope, request);
                        assertTrue(cap > 0 && cap <= 60_000);
                        waits.incrementAndGet();
                        return true; // The retry is still forbidden, and can never loop into another verification.
                    }, IGNORE_PROGRESS));
            assertEquals(1, waits.get());
            assertEquals(List.of("/challenge", "/challenge"), server.targets());

            assertThrows(IOException.class, () -> ComicArchiver.buildArchive(p.cache, p.downloads, "Anonymous",
                    List.of(server.url("/unauthorized")), RequestContext.fromBrowser("https://other.invalid/", PROFILE_UA, false),
                    new ComicArchiver.Job(), NO_VERIFICATION, IGNORE_PROGRESS));
            assertEquals(3, server.seen.size());
        }
        p.assertEmpty();
    }

    @Test(timeout = 5000) public void lateVerificationCannotRetryAfterJobCancellation() throws Exception {
        Paths p = new Paths();
        ComicArchiver.Job job = new ComicArchiver.Job(() -> 0, 200);
        try (Server server = new Server((req, out) -> reply(out, 403, "text/html", EMPTY))) {
            assertThrows(InterruptedIOException.class, () -> ComicArchiver.buildArchive(p.cache, p.downloads, "Verify",
                    List.of(server.url("/challenge")), RequestContext.fromBrowser(server.url("/chapter"), PROFILE_UA, false),
                    job, (url, request, cap) -> {
                        assertEquals(200, cap); // Remaining archive budget is shorter than the verification cap.
                        assertTrue(job.cancel());
                        return true;
                    }, IGNORE_PROGRESS));
            assertEquals(List.of("/challenge"), server.targets());
        }
        p.assertEmpty();
    }

    @Test(timeout = 6500) public void cancelDuringSecondResponseDiscardsStagingAndPreventsAThirdRequest() throws Exception {
        Paths p = new Paths();
        ComicArchiver.Job job = new ComicArchiver.Job();
        CountDownLatch bodyStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Server server = new Server((req, out) -> {
            if (!req.target.equals("/second")) { reply(out, 200, "image/png", PNG); return; }
            head(out, 200, "image/png", PNG.length);
            out.write(PNG, 0, 24);
            out.flush();
            bodyStarted.countDown();
            try { if (!release.await(4, TimeUnit.SECONDS)) throw new IOException("Fixture did not release"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try { out.write(PNG, 24, PNG.length - 24); }
            catch (java.net.SocketException disconnected) {
                // The canceled reader may have closed the connection before the late bytes are sent.
                if (!job.stopped()) throw disconnected;
            }
        })) {
            FutureTask<File> result = new FutureTask<>(() -> build(p, "Canceled",
                    List.of(server.url("/first"), server.url("/second"), server.url("/third")), job, IGNORE_PROGRESS));
            Thread worker = new Thread(result, "comic-cancel-fixture");
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(bodyStarted.await(3, TimeUnit.SECONDS));
                assertEquals(0, p.downloads.list().length);
                assertTrue(job.cancel());
            } finally { release.countDown(); }
            ExecutionException failed = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failed.getCause().toString(), failed.getCause() instanceof InterruptedIOException);
            assertEquals(List.of("/first", "/second"), server.targets());
        }
        p.assertEmpty();
    }

    @Test(timeout = 5000) public void stopDuringVerifiedCopyRollsBackTheUncommittedCbz() throws Exception {
        Paths p = new Paths();
        File target = new File(p.downloads, "Cancel copy.cbz");
        AtomicBoolean sawCandidate = new AtomicBoolean();
        AtomicReference<ComicArchiver.Job> handle = new AtomicReference<>();
        ComicArchiver.Job job = new ComicArchiver.Job(() -> {
            if (target.isFile() && sawCandidate.compareAndSet(false, true)) assertTrue(handle.get().cancel());
            return 0;
        }, 60_000);
        handle.set(job);
        try (Server server = new Server((req, out) -> reply(out, 200, "image/png", PNG))) {
            assertThrows(InterruptedIOException.class, () -> build(p, "Cancel copy", List.of(server.url("/one")), job, IGNORE_PROGRESS));
            assertTrue(sawCandidate.get());
        }
        p.assertEmpty();
    }

    @Test(timeout = 5000) public void occupiedDestinationAtCommitIsNeverReplaced() throws Exception {
        Paths p = new Paths();
        File target = new File(p.downloads, "Raced.cbz");
        try (Server server = new Server((req, out) -> reply(out, 200, "image/png", PNG))) {
            IOException failed = assertThrows(IOException.class, () -> build(p, "Raced", List.of(server.url("/one")),
                    new ComicArchiver.Job(), (i, total) -> {
                        try { Files.write(target.toPath(), OCCUPIED); }
                        catch (IOException unexpected) { throw new AssertionError(unexpected); }
                    }));
            assertTrue(failed.getMessage(), failed.getMessage().contains("destination already exists"));
            assertArrayEquals(OCCUPIED, Files.readAllBytes(target.toPath()));
            assertArrayEquals(new String[]{"Raced.cbz"}, p.downloads.list());
        }
        p.assertStagingEmpty();
    }

    @Test(timeout = 5000) public void stalledSocketReadFailsWithoutWaitingForAnUnfinishedPage() throws Exception {
        Paths p = new Paths();
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server((req, out) -> {
            head(out, 200, "image/png", PNG.length);
            out.write(PNG, 0, 24);
            out.flush();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        })) {
            try {
                IOException timeout = assertThrows(IOException.class, () -> build(p, "Stalled", List.of(server.url("/stall")),
                        new ComicArchiver.Job(() -> 0, 400), IGNORE_PROGRESS));
                assertTrue(timeout.getMessage(), timeout.getMessage().contains("The page did not arrive in time"));
                assertEquals(List.of("/stall"), server.targets());
            } finally { release.countDown(); }
        }
        p.assertEmpty();
    }

    @Test(timeout = 5000) public void archiveAndPageDeadlinesAreCheckedBeforeAnyLatePublication() throws Exception {
        Paths p = new Paths();
        AtomicLong now = new AtomicLong();
        try (Server server = new Server((req, out) -> reply(out, 200, "image/png", PNG))) {
            ComicArchiver.Job job = new ComicArchiver.Job(now::get, 3000);
            IOException timedOut = assertThrows(IOException.class, () -> build(p, "Too late",
                    List.of(server.url("/first"), server.url("/second")), job,
                    (i, total) -> now.set(TimeUnit.SECONDS.toNanos(3))));
            assertTrue(timedOut.getMessage(), timedOut.getMessage().contains("time limit"));
            assertEquals(List.of("/first"), server.targets());
        }
        p.assertEmpty();

        now.set(0);
        try (Server server = new Server((req, out) -> {
            now.set(TimeUnit.SECONDS.toNanos(46));
            reply(out, 200, "image/png", PNG);
        })) {
            IOException late = assertThrows(IOException.class, () -> build(p, "Slow page", List.of(server.url("/page")),
                    new ComicArchiver.Job(now::get, 60_000), IGNORE_PROGRESS));
            assertTrue(late.getMessage(), late.getMessage().startsWith("Page 1 of 1: The page did not arrive in time."));
        }
        p.assertEmpty();
    }

    @Test(timeout = 5000) public void invalidOrderedInputAndPrivateRequestsFailBeforeContactingTheServer() throws Exception {
        Paths p = new Paths();
        try (Server server = new Server((req, out) -> fail("Invalid plan made a request"))) {
            String valid = server.url("/valid");
            for (List<String> invalid : List.of(Arrays.asList(valid, null, valid), List.of(valid, "file:///tmp/not-a-page"),
                    Collections.nCopies(ComicManager.MAX_PAGES + 1, valid))) {
                assertThrows(IOException.class, () -> build(p, "Invalid", invalid, new ComicArchiver.Job(), IGNORE_PROGRESS));
            }
            assertThrows(IOException.class, () -> ComicArchiver.buildArchive(p.cache, p.downloads, "Private", List.of(valid),
                    RequestContext.fromBrowser(valid, PROFILE_UA, true), new ComicArchiver.Job(), NO_VERIFICATION, IGNORE_PROGRESS));
            assertTrue(server.seen.isEmpty());
        }
        p.assertEmpty();
    }

    private final class Paths {
        final File cache = files.newFolder(), downloads = files.newFolder();
        Paths() throws IOException {}
        void assertStagingEmpty() { assertEquals(0, cache.list().length); }
        void assertEmpty() { assertStagingEmpty(); assertEquals(0, downloads.list().length); }
    }

    private static File build(Paths p, String title, List<String> urls, ComicArchiver.Job job, ComicArchiver.Progress progress) throws IOException {
        return ComicArchiver.buildArchive(p.cache, p.downloads, title, urls, RequestContext.anonymous(), job, NO_VERIFICATION, progress);
    }

    private static void assertEntry(ZipFile zip, ZipEntry entry, String name, byte[] bytes) throws IOException {
        assertEquals(name, entry.getName());
        assertEquals(bytes.length, entry.getSize());
        try (java.io.InputStream in = zip.getInputStream(entry)) { assertArrayEquals(bytes, in.readAllBytes()); }
    }

    private static byte[] html(String content) { return content.getBytes(StandardCharsets.UTF_8); }
    private static void reply(OutputStream out, int code, String type, byte[] bytes) throws IOException {
        framed(out, code, type, bytes, bytes.length);
    }
    private static void framed(OutputStream out, int code, String type, byte[] bytes, long length) throws IOException {
        head(out, code, type, length);
        out.write(bytes);
    }
    private static void head(OutputStream out, int code, String type, long length) throws IOException {
        out.write(("HTTP/1.1 " + code + " Fixture\r\nContent-Type: " + type + "\r\nContent-Length: " + length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private interface Reply { void send(Request request, OutputStream output) throws IOException; }

    private static final class Request {
        final String target;
        final Map<String, String> headers = new HashMap<>();
        Request(String target) { this.target = target; }
    }

    private static final class Server implements AutoCloseable {
        final List<Request> seen = new CopyOnWriteArrayList<>();
        private final ServerSocket listener;
        private final Thread thread;
        private volatile Socket active;
        private volatile Throwable failed;

        Server(Reply handler) throws IOException {
            listener = new ServerSocket(0, 8, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            thread = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket client = listener.accept()) {
                        active = client;
                        client.setSoTimeout(3000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String line = in.readLine();
                        if (line == null || !line.startsWith("GET ")) throw new IOException("Expected fixture GET");
                        Request request = new Request(line.split(" ", 3)[1]);
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            int colon = line.indexOf(':');
                            if (colon > 0) request.headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                        }
                        seen.add(request);
                        handler.send(request, client.getOutputStream());
                        client.getOutputStream().flush();
                    } catch (IOException e) {
                        if (!listener.isClosed()) failed = e;
                    } catch (AssertionError e) { failed = e; }
                    finally { active = null; }
                }
            }, "comic-fixture-http");
            thread.setDaemon(true);
            thread.start();
        }

        String url(String path) { return "http://127.0.0.1:" + listener.getLocalPort() + path; }
        List<String> targets() {
            List<String> targets = new ArrayList<>();
            for (Request request : seen) targets.add(request.target);
            return targets;
        }
        @Override public void close() throws IOException, InterruptedException {
            listener.close();
            Socket closing = active;
            if (closing != null) closing.close();
            thread.join(1000);
            assertFalse("Fixture worker did not stop", thread.isAlive());
            if (failed != null) throw new AssertionError(failed);
        }
    }
}

package com.privacy.browser.comic;

import com.privacy.browser.InternalHome;
import com.privacy.downloader.engine.FilePublication;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.ScopedHttp;
import com.privacy.downloader.util.DownloadDestination;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** One ordered set of detected pages. It either publishes a complete CBZ of that set or fails. */
final class ComicArchiver {
    static final String MIME = "application/vnd.comicbook+zip";
    static final long JOB_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    static final long VERIFICATION_TIMEOUT_MS = 60_000;
    private static final int IO_TIMEOUT_MS = 8_000;
    private static final long PAGE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(45);
    private static final int HTML_LIMIT = 512 * 1024;
    private static final long PAGE_LIMIT = 32L * 1024 * 1024;
    private static final long TOTAL_LIMIT = 512L * 1024 * 1024;
    private static final String ACCEPT = "image/webp,image/avif,image/png,image/jpeg,image/gif,text/html;q=0.8,*/*;q=0.1";
    private static final String READER_IMAGES = "img#comic-image, img#main-image, img.comic-image, img.reader-image, "
            + "img.comic-page, img.manga-image, img.chapter-img, img.wp-manga-chapter-img, "
            + "#readerarea img, .readerarea img, .reading-content img, .comic-wrap img, .manga-read img";
    private static final String[] SOURCES = {"data-src", "data-original", "data-full-res", "data-highres",
            "data-zoom-src", "data-url", "data-lazy-src", "src"};

    interface Progress { void onPageSaved(int index, int total); }
    interface Verification { boolean await(String url, RequestContext request, long maxWaitMs) throws InterruptedException; }

    /** User cancellation and the final publication commit have a single ordering point. */
    static final class Job {
        private final LongSupplier clock;
        private final long deadline;
        private volatile boolean canceled;
        private boolean committed;

        Job() { this(System::nanoTime, JOB_TIMEOUT_MS); }
        Job(LongSupplier clock, long durationMs) {
            this.clock = clock;
            deadline = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        }

        synchronized boolean cancel() {
            if (committed || canceled) return false;
            canceled = true;
            return true;
        }

        boolean stopped() { return canceled || deadline - clock.getAsLong() <= 0; }
        void check() throws IOException {
            if (canceled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Comic archive canceled.");
            if (stopped()) throw new Failure("The comic archive exceeded its time limit.");
        }

        private synchronized void commit(FilePublication output) throws IOException {
            check();
            output.commit();
            committed = true;
        }
    }

    static final class Failure extends IOException {
        Failure(String message) { super(message); }
    }

    private ComicArchiver() {}

    static File buildArchive(File cache, File downloads, String title, List<String> orderedUrls, RequestContext request,
                             Job job, Verification verification, Progress progress) throws IOException {
        job.check();
        if (request.isPrivate()) throw new Failure(RequestContext.PRIVATE_UNAVAILABLE);
        if (orderedUrls.isEmpty() || orderedUrls.size() > ComicManager.MAX_PAGES) {
            throw new Failure("Choose a detected set of 1–" + ComicManager.MAX_PAGES + " pages.");
        }
        List<String> pages = new ArrayList<>(orderedUrls);
        for (int i = 0; i < pages.size(); i++) {
            if (!validUrl(pages.get(i))) throw pageFailure(i + 1, pages.size(), "No valid image or reader URL.");
        }
        Files.createDirectories(downloads.toPath());
        File target = DownloadDestination.choose(new File(downloads, fileName(title)), Collections.emptySet());
        Path staged = Files.createTempFile(cache.toPath(), "comic-", ".part");
        try {
            long bytes = 0;
            boolean usedVerification = false;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(staged)))) {
                zip.setLevel(Deflater.NO_COMPRESSION); // Image bytes are already compressed.
                for (int i = 0; i < pages.size(); i++) {
                    int number = i + 1;
                    for (;;) {
                        job.check();
                        try {
                            bytes += writePage(zip, number, pages.get(i), request, Math.min(PAGE_LIMIT, TOTAL_LIMIT - bytes), new PageRead(job));
                            break;
                        } catch (VerificationNeeded required) {
                            if (usedVerification || !required.request.hasWebSession()
                                    || !required.request.pageOrigin().equals(RequestContext.origin(required.url))) {
                                throw pageFailure(number, pages.size(), "Site verification required. Complete it in the browser and retry.");
                            }
                            usedVerification = true;
                            job.check();
                            long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(job.deadline - job.clock.getAsLong()));
                            try {
                                if (!verification.await(required.url, required.request, Math.min(VERIFICATION_TIMEOUT_MS, remainingMs))) {
                                    job.check();
                                    throw pageFailure(number, pages.size(), "Site verification was canceled, unavailable, or timed out.");
                                }
                            } catch (InterruptedException canceled) {
                                Thread.currentThread().interrupt();
                                job.check();
                            }
                        } catch (IOException failed) {
                            job.check();
                            String detail = failed instanceof Failure ? failed.getMessage() : failed instanceof SocketTimeoutException
                                    ? "The page did not arrive in time." : "Could not read a complete image.";
                            throw pageFailure(number, pages.size(), detail);
                        }
                    }
                    job.check();
                    progress.onPageSaved(number, pages.size());
                }
                zip.finish();
            }
            job.check();
            long size = Files.size(staged);
            try (FilePublication publication = FilePublication.open(staged, target.toPath(), size, null, job::stopped)) {
                job.commit(publication);
            }
            return target;
        } finally {
            try { Files.deleteIfExists(staged); } catch (IOException | SecurityException ignored) { /* App-private staging only. */ }
        }
    }

    private static boolean validUrl(String value) {
        return value != null && value.length() <= 8192 && RequestContext.origin(value) != null && !InternalHome.ownsHost(value);
    }

    private static Failure pageFailure(int number, int total, String detail) {
        return new Failure("Page " + number + " of " + total + ": " + detail + " No archive was saved.");
    }

    private static long writePage(ZipOutputStream zip, int number, String url, RequestContext request, long limit, PageRead time)
            throws IOException {
        String imageUrl;
        RequestContext child;
        try (ScopedHttp.Response response = open(url, request, time)) {
            long expected = requireFullBody(response);
            String header = header(response.connection, "Content-Type");
            String type = header == null ? "" : header.split(";", 2)[0].trim();
            if (!"text/html".equalsIgnoreCase(type) && !"application/xhtml+xml".equalsIgnoreCase(type)) {
                return writeImage(zip, number, response.connection, expected, limit, time);
            }
            Document reader = Jsoup.parse(readHtml(response.connection, expected, time)); // Never executes scripts.
            if (!reader.select("#challenge-form, #challenge-stage, .cf-turnstile, .g-recaptcha").isEmpty()
                    || reader.title().toLowerCase(Locale.ROOT).startsWith("just a moment")) {
                throw new VerificationNeeded(response);
            }
            imageUrl = readerImage(reader, response.url());
            child = response.context; // Keep authority narrowed by the reader's actual response chain.
        }
        try (ScopedHttp.Response response = open(imageUrl, child, time)) {
            return writeImage(zip, number, response.connection, requireFullBody(response), limit, time);
        }
    }

    private static ScopedHttp.Response open(String url, RequestContext request, PageRead time) throws IOException {
        time.check();
        int timeout = time.ioTimeoutMs();
        ScopedHttp.Response response = ScopedHttp.download(url, request, ACCEPT, timeout, timeout, null, null, time::stopped);
        try {
            time.check();
            return response;
        } catch (IOException stopped) {
            response.close();
            throw stopped;
        }
    }

    private static long requireFullBody(ScopedHttp.Response response) throws IOException {
        int status = response.status;
        if (status == 403 || status == 429 || status == 503) throw new VerificationNeeded(response);
        if (status != HttpURLConnection.HTTP_OK) throw new Failure("HTTP " + status + " (expected a whole page).");
        HttpURLConnection conn = response.connection;
        String encoding = header(conn, "Content-Encoding");
        if (header(conn, "Content-Range") != null || (encoding != null && !"identity".equalsIgnoreCase(encoding))) {
            throw new Failure("The server did not send an unencoded, whole page.");
        }
        String length = header(conn, "Content-Length"), transfer = header(conn, "Transfer-Encoding");
        if (transfer != null && (!transfer.equalsIgnoreCase("chunked") || length != null)) {
            throw new Failure("The server sent unsupported or conflicting body framing.");
        }
        if (length == null) return -1;
        try { if (length.matches("[0-9]+")) return Long.parseLong(length); }
        catch (NumberFormatException ignored) {}
        throw new Failure("The page length was invalid.");
    }

    private static String header(HttpURLConnection conn, String name) throws IOException {
        String result = null;
        for (Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
            if (!name.equalsIgnoreCase(header.getKey())) continue;
            if (result != null || header.getValue().size() != 1) throw new Failure("The page has ambiguous response headers.");
            result = header.getValue().get(0).trim();
        }
        return result;
    }

    private static final class VerificationNeeded extends IOException {
        final String url;
        final RequestContext request;
        VerificationNeeded(ScopedHttp.Response response) {
            url = response.url();
            request = response.context;
        }
    }

    private static final class PageRead {
        private final Job job;
        private final long deadline;
        PageRead(Job job) { this.job = job; deadline = job.clock.getAsLong() + PAGE_TIMEOUT_NS; }
        boolean stopped() { return job.stopped() || deadline - job.clock.getAsLong() <= 0; }
        void check() throws IOException {
            job.check();
            if (stopped()) throw new SocketTimeoutException("The page exceeded its time limit.");
        }
        int ioTimeoutMs() {
            long now = job.clock.getAsLong();
            long remaining = Math.min(deadline - now, job.deadline - now);
            return (int) Math.max(1, Math.min(IO_TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
        int read(InputStream in, byte[] buffer, int offset, int size) throws IOException {
            check();
            int count = in.read(buffer, offset, size);
            check();
            return count;
        }
    }

    private static String readHtml(HttpURLConnection conn, long expected, PageRead time) throws IOException {
        requireLimit(expected, HTML_LIMIT);
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            for (int n; (n = time.read(in, buffer, 0, buffer.length)) != -1; ) {
                checkLength((long) data.size() + n, expected, HTML_LIMIT);
                data.write(buffer, 0, n);
            }
            requireComplete(data.size(), expected);
            return data.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String readerImage(Document doc, String responseUrl) throws Failure {
        Elements images = doc.select(READER_IMAGES);
        if (images.size() != 1) throw new Failure("The reader did not identify exactly one page image.");
        Element image = images.first();
        for (String field : SOURCES) {
            String value = image.attr(field).trim();
            if (value.isEmpty()) continue;
            try {
                String resolved = new URL(new URL(responseUrl), value).toExternalForm();
                if (validUrl(resolved)) return resolved;
            } catch (IOException ignored) {}
            break; // Do not substitute another image for a known but unusable page URL.
        }
        throw new Failure("The reader did not provide a supported page image URL.");
    }

    private static long writeImage(ZipOutputStream zip, int number, HttpURLConnection conn, long expected, long limit, PageRead time)
            throws IOException {
        requireLimit(expected, limit);
        try (InputStream in = conn.getInputStream()) {
            byte[] prefix = new byte[64];
            int length = 0;
            while (length < prefix.length) {
                int n = time.read(in, prefix, length, prefix.length - length);
                if (n == -1) break;
                length += n;
                checkLength(length, expected, limit);
            }
            ComicImageFormat format = ComicImageFormat.detect(prefix, length);
            if (format == null) throw new Failure("The response is not a supported image.");
            zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "page_%04d.%s", number, format.extension)));
            byte[] tail = new byte[12];
            appendTail(tail, prefix, length);
            zip.write(prefix, 0, length);
            long bytes = length;
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = time.read(in, buffer, 0, buffer.length)) != -1; ) {
                bytes += n;
                checkLength(bytes, expected, limit);
                appendTail(tail, buffer, n);
                zip.write(buffer, 0, n);
            }
            requireComplete(bytes, expected);
            if (!format.complete(prefix, tail, bytes)) throw new Failure("The image has a missing or inconsistent end.");
            zip.closeEntry();
            return bytes;
        }
    }

    private static void appendTail(byte[] tail, byte[] data, int count) {
        int copied = Math.min(tail.length, count);
        System.arraycopy(tail, copied, tail, 0, tail.length - copied);
        System.arraycopy(data, count - copied, tail, tail.length - copied, copied);
    }

    private static void requireLimit(long expected, long limit) throws Failure {
        if (limit <= 0 || expected > limit) throw new Failure("The page or archive exceeds its size limit.");
        if (expected == 0) throw new Failure("The response was empty.");
    }

    private static void checkLength(long bytes, long expected, long limit) throws Failure {
        if (bytes > limit) throw new Failure("The page or archive exceeds its size limit.");
        if (expected >= 0 && bytes > expected) throw new Failure("The page was longer than declared.");
    }

    private static void requireComplete(long bytes, long expected) throws Failure {
        if (bytes == 0 || (expected >= 0 && bytes != expected)) throw new Failure("The page was empty or ended early.");
    }

    private static String fileName(String title) {
        String clean = title.replaceAll("[\\\\/:*?\"<>|\\p{Cc}\\p{Cf}]", "_").trim().replaceAll("\\s+", " ");
        clean = clean.replaceFirst("(?i)\\.(cbz|cbr)$", "").replaceAll("^[. ]+|[. ]+$", "");
        if (clean.codePointCount(0, clean.length()) > 64) clean = clean.substring(0, clean.offsetByCodePoints(0, 64)).trim();
        return (clean.isEmpty() ? "Comic" : clean) + ".cbz";
    }
}

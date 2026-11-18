package com.privacy.downloader.net;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** HTTP used by native fetchers, not a browser. Only this class applies browsing headers. */
public final class ScopedHttp {
    private static final int MAX_REDIRECTS = 5;

    private ScopedHttp() {}

    public static final class Response implements AutoCloseable {
        public final HttpURLConnection connection;
        public final int status;
        public final RequestContext context;

        private Response(HttpURLConnection connection, int status, RequestContext context) {
            this.connection = connection;
            this.status = status;
            this.context = context;
        }

        public String url() { return connection.getURL().toString(); }

        @Override public void close() { connection.disconnect(); }
    }

    public static final class ConditionalRedirect extends IOException {
        private ConditionalRedirect() { super("The server redirected a conditional range request."); }
    }

    public static Response get(String url, RequestContext context, String accept, int timeoutMs) throws IOException {
        return open(url, context, false, accept, timeoutMs, timeoutMs, null, null, MAX_REDIRECTS, null, () -> false);
    }

    public static Response head(String url, RequestContext context, String accept, int timeoutMs) throws IOException {
        return open(url, context, true, accept, timeoutMs, timeoutMs, null, null, MAX_REDIRECTS, null, () -> false);
    }

    public static Response download(String url, RequestContext context, String accept, int connectMs, int readMs,
                                    String range, String ifRange, BooleanSupplier stopped) throws IOException {
        return open(url, context, false, accept, connectMs, readMs, range, ifRange, MAX_REDIRECTS, null, stopped);
    }

    /** Metadata only: anonymous on every hop, with a smaller redirect budget than a download. */
    public static Response inspect(String url, boolean head, String range, String publicAgent,
                                   int timeoutMs, Predicate<String> admitHop, BooleanSupplier stopped) throws IOException {
        RequestContext context = publicAgent == null ? RequestContext.anonymous() : RequestContext.anonymous(publicAgent);
        return open(url, context, head, null, timeoutMs, timeoutMs, range, null, 2, admitHop, stopped);
    }

    private static Response open(String url, RequestContext context, boolean head, String accept,
                                 int connectMs, int readMs, String range, String ifRange, int redirects,
                                 Predicate<String> admitHop, BooleanSupplier stopped)
            throws IOException {
        if (context.isPrivate()) throw new IOException(RequestContext.PRIVATE_UNAVAILABLE);
        URI target = RequestContext.httpUri(url);
        for (int hop = 0; hop <= redirects; hop++) {
            if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("HTTP request stopped.");
            }
            if (target == null) throw new IOException("Expected an HTTP or HTTPS URL without embedded credentials.");
            // HttpURLConnection otherwise uses the process-wide Java cookie jar, including on
            // anonymous hops. This app uses WebView's separate CookieManager only, on demand.
            if (CookieHandler.getDefault() != null) throw new IOException("A process-wide HTTP cookie jar is not supported.");
            String actualUrl = target.toASCIIString();
            if (admitHop != null && !admitHop.test(actualUrl)) throw new IOException("Metadata hop not admitted.");
            context = context.forTarget(actualUrl);
            HttpURLConnection conn = (HttpURLConnection) URI.create(actualUrl).toURL().openConnection();
            boolean kept = false;
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setUseCaches(false);
                conn.setConnectTimeout(connectMs);
                conn.setReadTimeout(readMs);
                conn.setRequestMethod(head ? "HEAD" : "GET");
                conn.setRequestProperty("User-Agent", context.userAgent());
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (accept != null) conn.setRequestProperty("Accept", accept);
                if (context.hasWebSession()) {
                    String cookie = context.cookiesFor(actualUrl);
                    if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                    conn.setRequestProperty("Referer", context.pageOrigin() + "/");
                }
                if (range != null) conn.setRequestProperty("Range", range);
                if (ifRange != null) conn.setRequestProperty("If-Range", ifRange);

                if (admitHop != null && stopped.getAsBoolean()) throw new InterruptedIOException("HTTP request stopped.");
                int status = conn.getResponseCode();
                if (admitHop != null && stopped.getAsBoolean()) throw new InterruptedIOException("HTTP request stopped.");
                if (!isRedirect(status)) {
                    kept = true;
                    return new Response(conn, status, context);
                }
                // A strong ETag proves continuity for the verified URL only.
                if (ifRange != null) throw new ConditionalRedirect();
                String location = redirectLocation(conn);
                try {
                    if (location == null || (admitHop != null && location.length() > 8192) || location.trim().isEmpty()) {
                        throw new IllegalArgumentException();
                    }
                    URI reference = URI.create(location.trim());
                    if (!reference.isAbsolute() && reference.getRawAuthority() == null
                            && reference.getRawPath().isEmpty() && reference.getRawQuery() != null) {
                        // Retain the final path for a query-only redirect (URI.resolve removes it).
                        int query = actualUrl.indexOf('?');
                        String path = query < 0 ? actualUrl : actualUrl.substring(0, query);
                        target = RequestContext.httpUri(path + "?" + reference.getRawQuery());
                    } else {
                        target = RequestContext.httpUri(target.resolve(reference).toASCIIString());
                    }
                } catch (IllegalArgumentException e) {
                    throw new IOException("The server sent an invalid redirect.");
                }
                // Never import Set-Cookie into WebView; the target will get a fresh scoped lookup.
            } finally {
                if (!kept) conn.disconnect();
            }
        }
        throw new IOException("Too many HTTP redirects.");
    }

    private static String redirectLocation(HttpURLConnection conn) throws IOException {
        String location = null;
        for (Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
            if (!"Location".equalsIgnoreCase(header.getKey())) continue;
            if (location != null || header.getValue().size() != 1) throw new IOException("Ambiguous redirect location.");
            location = header.getValue().get(0);
        }
        return location;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }
}

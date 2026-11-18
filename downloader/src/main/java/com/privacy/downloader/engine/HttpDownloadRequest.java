package com.privacy.downloader.engine;

import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.ScopedHttp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Shared wire settings for the direct HTTP probe and transfer. */
final class HttpDownloadRequest {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private HttpDownloadRequest() {}

    static ScopedHttp.Response open(RequestContext context, String url, String range, String ifRange,
                                    BooleanSupplier stopped) throws IOException {
        try {
            ScopedHttp.Response response = ScopedHttp.download(url, context,
                    "application/octet-stream, application/zip, application/x-zip-compressed, */*",
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, range, ifRange, stopped);
            // The link may not have looked like a manifest before the redirect.
            if (response.status >= 200 && response.status < 300
                    && StreamDetector.isDash(response.url(), response.connection.getContentType())) {
                response.close();
                throw new TransferIntegrity.Failure(StreamDetector.DASH_UNSUPPORTED);
            }
            return response;
        } catch (ScopedHttp.ConditionalRedirect e) {
            throw TransferIntegrity.startOver("The server redirected a verified download to another link.");
        }
    }

    static String singleHeader(HttpURLConnection conn, String name) throws TransferIntegrity.Failure {
        String value = null;
        for (Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
            if (!name.equalsIgnoreCase(header.getKey())) continue;
            if (value != null || header.getValue().size() != 1) {
                throw new TransferIntegrity.Failure("The response has ambiguous " + name + " headers.");
            }
            value = header.getValue().get(0);
        }
        return value;
    }

    static boolean isMultipartRange(HttpURLConnection conn) {
        String type = conn.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/byteranges");
    }

    static long contentLength(HttpURLConnection conn) throws TransferIntegrity.Failure {
        String value = singleHeader(conn, "Content-Length");
        String transfer = singleHeader(conn, "Transfer-Encoding");
        if (transfer != null && !"chunked".equalsIgnoreCase(transfer.trim())) {
            throw new TransferIntegrity.Failure("The response has an unsupported transfer encoding.");
        }
        if (value == null) return -1;
        if (transfer != null) throw new TransferIntegrity.Failure("The response has conflicting length information.");
        value = value.trim();
        try {
            if (value.matches("[0-9]+")) return Long.parseLong(value);
        } catch (NumberFormatException ignored) {}
        throw new TransferIntegrity.Failure("The response has an invalid Content-Length.");
    }
}

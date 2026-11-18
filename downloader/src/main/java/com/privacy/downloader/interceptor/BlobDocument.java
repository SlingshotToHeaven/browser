package com.privacy.downloader.interceptor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.UUID;

/** Identity of one committed top-level document; never inferred from a blob URL alone. */
final class BlobDocument<T> {
    final String origin;
    final String challenge = UUID.randomUUID().toString();
    private final Object webView;
    private T endpoint;

    BlobDocument(Object webView, String origin) {
        this.webView = webView;
        this.origin = origin;
    }

    boolean acceptsFrame(Object view, String sourceOrigin, boolean mainFrame) {
        return mainFrame && webView == view && origin.equals(webOrigin(sourceOrigin));
    }

    boolean bind(String message, T replyEndpoint) {
        if (endpoint != null || !("ready:" + challenge).equals(message)) return false;
        endpoint = replyEndpoint;
        return true;
    }

    T endpoint() {
        return endpoint;
    }

    boolean owns(T replyEndpoint) {
        return endpoint != null && endpoint == replyEndpoint;
    }

    static String webOrigin(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return null;
            int defaultPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            int port = uri.getPort();
            if (port > 65535) return null;
            return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                    + (port < 0 || port == defaultPort ? "" : ":" + port);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}

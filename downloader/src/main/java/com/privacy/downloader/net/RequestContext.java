package com.privacy.downloader.net;

import android.webkit.CookieManager;

import java.net.URI;
import java.util.Locale;

/** In-memory authority for a regular browser page; never contains a cookie snapshot or page path. */
public final class RequestContext {
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    public static final String PRIVATE_UNAVAILABLE = "Downloads and native media tools are unavailable in private tabs until storage is isolated.";

    interface Cookies {
        String forUrl(String url);
    }

    private static final RequestContext ANONYMOUS = new RequestContext(null, DEFAULT_USER_AGENT, null, false);
    private static final RequestContext PRIVATE = new RequestContext(null, DEFAULT_USER_AGENT, null, true);

    private final String origin;
    private final String userAgent;
    private final Cookies cookies;
    private final boolean privateTab;

    private RequestContext(String origin, String userAgent, Cookies cookies, boolean privateTab) {
        this.origin = origin;
        this.userAgent = validHeader(userAgent) && !userAgent.trim().isEmpty() ? userAgent : DEFAULT_USER_AGENT;
        this.cookies = cookies;
        this.privateTab = privateTab;
    }

    public static RequestContext anonymous() {
        return ANONYMOUS;
    }

    /** Use only a fixed, public user agent for a provider-specific anonymous request. */
    public static RequestContext anonymous(String publicUserAgent) {
        return new RequestContext(null, publicUserAgent, null, false);
    }

    public static RequestContext fromBrowser(String committedPage, String userAgent, boolean privateTab) {
        return fromBrowser(committedPage, userAgent, privateTab,
                url -> CookieManager.getInstance().getCookie(url));
    }

    static RequestContext fromBrowser(String page, String userAgent, boolean privateTab, Cookies cookies) {
        if (privateTab) return PRIVATE;
        String origin = origin(page);
        return origin == null ? ANONYMOUS : new RequestContext(origin, userAgent, cookies, false);
    }

    public boolean isPrivate() {
        return privateTab;
    }

    public boolean hasWebSession() {
        return origin != null;
    }

    public String pageOrigin() {
        return origin;
    }

    public String userAgent() {
        return userAgent;
    }

    /** Authority only narrows. A redirect or child resource cannot reattach the browser session. */
    public RequestContext forTarget(String url) {
        return origin == null || origin.equals(origin(url)) ? this : ANONYMOUS;
    }

    String cookiesFor(String url) {
        if (cookies == null || !origin.equals(origin(url))) return null;
        try {
            String value = cookies.forUrl(url);
            return validHeader(value) && !value.trim().isEmpty() ? value : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    static URI httpUri(String value) {
        if (value == null) return null;
        try {
            URI uri = new URI(value);
            if (uri.getHost() != null && uri.getRawUserInfo() == null && uri.getPort() <= 65535
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                // A fragment is never part of the request or cookie lookup.
                String ascii = uri.toASCIIString();
                int fragment = ascii.indexOf('#');
                return fragment < 0 ? uri : URI.create(ascii.substring(0, fragment));
            }
        } catch (IllegalArgumentException | java.net.URISyntaxException ignored) {}
        return null;
    }

    public static String origin(String value) {
        URI uri = httpUri(value);
        if (uri == null) return null;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (port < 0 || port == defaultPort ? "" : ":" + port);
    }

    private static boolean validHeader(String value) {
        return value != null && value.length() <= 32 * 1024 && value.chars().noneMatch(Character::isISOControl);
    }
}

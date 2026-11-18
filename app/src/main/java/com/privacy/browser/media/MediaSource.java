package com.privacy.browser.media;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cheap hints and stable identities, not authorization to fetch or to use a browser session. */
final class MediaSource {
    static final int MAX_URL = 8192;
    enum Kind { VIDEO, AUDIO, HLS, DASH, VIMEO, YOUTUBE }

    private static final String[] VIDEO = {".mp4", ".m4v", ".webm", ".ogv", ".mkv", ".mov", ".avi"};
    private static final String[] AUDIO = {".mp3", ".m4a", ".flac", ".wav", ".ogg", ".aac", ".opus"};
    private static final Pattern CHUNK = Pattern.compile("(?:^|[/._-])(?:seg(?:ment)?|chunk|frag(?:ment)?|slice|part|init)[/._0-9-]", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile("(?:^|&)range=", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIMEO = Pattern.compile("/(?:video/|channels/[^/]+/|groups/[^/]+/videos/|album/[0-9]+/video/)?([0-9]{1,24})/?");
    private static final Pattern HASH = Pattern.compile("[a-fA-F0-9]{1,64}");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    final Kind kind;
    final String key;
    final String url;
    final String id;
    final String hash;

    private MediaSource(Kind kind, String key, String url, String id, String hash) {
        this.kind = kind;
        this.key = key;
        this.url = url;
        this.id = id;
        this.hash = hash;
    }

    /** Called for a genuine per-view GET. The common non-media path never parses a URI. */
    static MediaSource request(String url, String encodedPath) {
        if (url == null || url.length() > MAX_URL) return null;
        Kind kind = extension(encodedPath);
        return kind == null ? null : media(httpUri(url), kind, true);
    }

    static MediaSource element(String url, String tagType) {
        URI uri = httpUri(url);
        if (uri == null) return null;
        Kind kind = "hls".equals(tagType) ? Kind.HLS : "dash".equals(tagType) ? Kind.DASH : extension(uri.getPath());
        if (kind == null) {
            switch (tagType) {
                case "video": kind = Kind.VIDEO; break;
                case "audio": kind = Kind.AUDIO; break;
                default: return null;
            }
        }
        return media(uri, kind, false);
    }

    private static Kind extension(String path) {
        if (path == null || path.length() < 4) return null;
        if (ends(path, ".m3u8")) return Kind.HLS;
        if (ends(path, ".mpd")) return Kind.DASH;
        for (String ext : VIDEO) if (ends(path, ext)) return Kind.VIDEO;
        for (String ext : AUDIO) if (ends(path, ext)) return Kind.AUDIO;
        return null;
    }

    private static boolean ends(String path, String suffix) {
        return path.regionMatches(true, path.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static MediaSource media(URI uri, Kind kind, boolean passive) {
        if (uri == null) return null;
        String path = uri.getPath();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("googlevideo.com") || host.endsWith(".googlevideo.com") || path.contains("/videoplayback")
                || path.contains("/proxy.html") || ends(path, ".ts") || ends(path, ".m4s")
                || (passive && (kind == Kind.VIDEO || kind == Kind.AUDIO) && CHUNK.matcher(path).find())
                || (uri.getRawQuery() != null && RANGE.matcher(uri.getRawQuery()).find())) return null;
        String url = withoutFragment(uri);
        if (url.length() > MAX_URL) return null;
        String key = identity(uri);
        return new MediaSource(kind, key.equals(url) ? url : key, url, null, null);
    }

    static MediaSource platform(String url) {
        URI uri = httpUri(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath();
        String id;
        switch (host) {
            case "vimeo.com": case "www.vimeo.com": case "player.vimeo.com":
                Matcher match = VIMEO.matcher(path);
                if (!match.matches()) return null;
                id = match.group(1);
                String hash = query(uri, "h");
                if (hash != null && !HASH.matcher(hash).matches()) return null;
                return new MediaSource(Kind.VIMEO, "vimeo:" + id + ":" + (hash == null ? "" : hash), withoutFragment(uri), id, hash);
            case "youtube.com": case "www.youtube.com": case "m.youtube.com": case "www.youtube-nocookie.com":
                if (path.equals("/watch")) id = query(uri, "v");
                else if (path.startsWith("/embed/") || path.startsWith("/v/") || path.startsWith("/shorts/") || path.startsWith("/live/")) {
                    id = path.substring(path.lastIndexOf('/') + 1);
                } else return null;
                break;
            case "youtu.be":
                id = path.startsWith("/") ? path.substring(1) : null;
                break;
            default:
                return null;
        }
        return id != null && YOUTUBE_ID.matcher(id).matches()
                ? new MediaSource(Kind.YOUTUBE, "youtube:" + id, withoutFragment(uri), id, null) : null;
    }

    private static String query(URI uri, String name) {
        String raw = uri.getRawQuery();
        if (raw == null) return null;
        String prefix = name + "=";
        int start = 0;
        while (start < raw.length()) {
            int end = raw.indexOf('&', start);
            if (end < 0) end = raw.length();
            if (raw.startsWith(prefix, start)) return raw.substring(start + prefix.length(), end);
            start = end + 1;
        }
        return null;
    }

    static URI httpUri(String url) {
        if (url == null || url.length() > MAX_URL) return null;
        try {
            URI uri = new URI(url);
            if (uri.getHost() != null && uri.getRawUserInfo() == null && uri.getPort() != 0 && uri.getPort() <= 65535
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return uri;
        } catch (URISyntaxException ignored) {}
        return null;
    }

    static String withoutFragment(URI uri) {
        String ascii = uri.toASCIIString();
        int hash = ascii.indexOf('#');
        return hash < 0 ? ascii : ascii.substring(0, hash);
    }

    static String identity(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        URI ascii = uri.toString().equals(uri.toASCIIString()) ? uri : URI.create(uri.toASCIIString());
        String path = ascii.getRawPath();
        String query = ascii.getRawQuery();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (port < 0 || port == (scheme.equals("https") ? 443 : 80) ? "" : ":" + port)
                + (path.isEmpty() ? "/" : path) + (query == null ? "" : "?" + query);
    }
}

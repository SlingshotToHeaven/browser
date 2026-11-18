package com.privacy.browser.media;

import com.privacy.browser.media.MediaSniffer.SniffedMedia;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.ScopedHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Metadata-only network and parsers; called exclusively by an explicit options request. */
final class MediaMetadata {
    static final int HLS_BYTES = 128 * 1024;
    static final int EMBED_BYTES = 384 * 1024;
    static final int PLAYER_BYTES = 512 * 1024;
    static final int VARIANTS = 24;
    static final int TOTAL_BYTES = 1024 * 1024;
    static final int HTTP_STARTS = 12;
    private static final int FORMAT_LIMIT = 48;
    private static final long BATCH_MS = 6000;
    private static final int IO_TIMEOUT_MS = 1800;
    private static final String WEB_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String VIMEO_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String PLAYER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
    private static final Pattern LENGTH = Pattern.compile("bytes 0-0/([0-9]{1,19})");
    private static final Pattern RESOLUTION = Pattern.compile("(?:^|,)RESOLUTION=[0-9]{1,6}x([0-9]{1,6})(?:,|$)");
    private static final Pattern BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=([0-9]{1,10})(?:,|$)");
    private static final Pattern VISITOR = Pattern.compile("\"VISITOR_DATA\":\"([A-Za-z0-9_%=\\-]{1,512})\"");

    static final class Budget {
        private final BooleanSupplier canceled;
        private final Predicate<String> allowed;
        private final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BATCH_MS);
        private int requests;
        private int bytes;

        Budget(BooleanSupplier canceled, Predicate<String> allowed) {
            this.canceled = canceled;
            this.allowed = allowed;
        }
        boolean stopped() { return canceled.getAsBoolean() || Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0; }
        boolean exhausted() { return requests >= HTTP_STARTS || bytes >= TOTAL_BYTES; }
        boolean admit(String url) {
            if (stopped() || exhausted() || !allowed.test(url)) return false;
            requests++;
            return true;
        }
        void check() throws InterruptedIOException {
            if (stopped()) throw new InterruptedIOException("Media metadata request stopped");
        }
        int timeout() throws InterruptedIOException {
            check();
            return (int) Math.max(1, Math.min(IO_TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        }
    }

    static List<SniffedMedia> lookup(MediaSource source, String title, Budget budget) throws Exception {
        budget.check();
        switch (source.kind) {
            case VIDEO: case AUDIO:
                long size = probeSize(source.url, budget);
                return size > 0 ? Collections.singletonList(sourceRow(source, title, size)) : Collections.emptyList();
            case HLS:
                try (ScopedHttp.Response response = open(source.url, false, null, null, null, budget)) {
                    return response.status == 200
                            ? parseHls(response.url(), body(response.connection, HLS_BYTES, false, budget), title)
                            : Collections.emptyList();
                }
            case VIMEO:
                String embed = "https://player.vimeo.com/video/" + source.id + (source.hash == null ? "" : "?h=" + source.hash);
                try (ScopedHttp.Response response = open(embed, false, null, VIMEO_AGENT, "https://player.vimeo.com", budget)) {
                    if (response.status == 200) {
                        String html = body(response.connection, EMBED_BYTES, true, budget);
                        return parseVimeo(extractJsonAfterMarker(html, "window.playerConfig = "), title);
                    }
                }
                return Collections.emptyList();
            case YOUTUBE:
                return youtube(source.id, title, budget);
            default:
                return Collections.emptyList(); // DASH is an unprobed observed manifest, not a native GET here.
        }
    }

    static SniffedMedia sourceRow(MediaSource source, String title, long size) {
        String quality = source.kind == MediaSource.Kind.HLS ? "HLS Master" : source.kind == MediaSource.Kind.DASH ? "DASH"
                : source.kind == MediaSource.Kind.AUDIO ? "Direct Audio" : "Direct Video";
        return new SniffedMedia(source.url, title, source.kind == MediaSource.Kind.AUDIO ? "audio" : "video", quality, size,
                null, -1, 0, 0, null, -1, source.kind == MediaSource.Kind.HLS ? MediaSniffer.HLS_MIME
                        : source.kind == MediaSource.Kind.DASH ? MediaSniffer.DASH_MIME : null, null);
    }

    private static ScopedHttp.Response open(String url, boolean head, String range, String agent, String origin, Budget budget) throws IOException {
        return ScopedHttp.inspect(url, head, range, agent, budget.timeout(),
                hop -> (origin == null || origin.equals(RequestContext.origin(hop))) && budget.admit(hop), budget::stopped);
    }

    private static long probeSize(String url, Budget budget) throws IOException {
        int status;
        try (ScopedHttp.Response response = open(url, true, null, null, null, budget)) {
            budget.check();
            status = response.status;
            if (status >= 200 && status < 300) return response.connection.getContentLengthLong();
        }
        if (status != 403 && status != 405) return -1;
        try (ScopedHttp.Response response = open(url, false, "bytes=0-0", null, null, budget)) {
            budget.check();
            String header = response.connection.getHeaderField("Content-Range");
            if (response.status == 206 && header != null && header.length() <= 32) {
                Matcher m = LENGTH.matcher(header);
                if (m.matches()) try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {}
            }
            return -1; // No body read, even if a server ignored Range and sent a large 200.
        }
    }

    private static String body(HttpURLConnection conn, int limit, boolean prefix, Budget budget) throws IOException {
        budget.check();
        long declared = conn.getContentLengthLong();
        if (!prefix && declared > Math.min(limit, TOTAL_BYTES - budget.bytes)) {
            throw new IOException("Media metadata response too large");
        }
        int before = budget.bytes;
        try (InputStream in = conn.getInputStream()) {
            String text = readBody(in, limit, prefix, budget);
            if (!prefix && declared >= 0 && declared != budget.bytes - before) {
                throw new IOException("Incomplete media metadata response");
            }
            return text;
        }
    }

    static String readBody(InputStream in, int limit, boolean prefix, Budget budget) throws IOException {
        int remaining = TOTAL_BYTES - budget.bytes;
        // Reserve one byte for the oversize check when a complete body is required.
        limit = Math.min(limit, remaining - (prefix ? 0 : 1));
        if (limit <= 0) throw new IOException("Media metadata byte budget exhausted");
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(4096, limit));
        byte[] chunk = new byte[4096];
        while (out.size() < limit) {
            budget.check();
            int count = in.read(chunk, 0, Math.min(chunk.length, limit - out.size()));
            if (count > 0) budget.bytes += count;
            budget.check();
            if (count < 0) return new String(out.toByteArray(), StandardCharsets.UTF_8);
            out.write(chunk, 0, count);
        }
        budget.check();
        if (!prefix && in.read() != -1) {
            budget.bytes++;
            throw new IOException("Media metadata response too large");
        }
        budget.check();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static List<SniffedMedia> parseHls(String url, String playlist, String title) {
        URI base = MediaSource.httpUri(url);
        if (base == null || playlist == null || playlist.length() > HLS_BYTES || !playlist.startsWith("#EXTM3U")) return Collections.emptyList();
        List<SniffedMedia> rows = new ArrayList<>();
        String meta = null;
        for (String line : playlist.split("\n")) {
            line = line.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) meta = line.substring(18);
            else if (meta != null && !line.isEmpty() && !line.startsWith("#")) {
                String variant = null;
                if (line.length() <= MediaSource.MAX_URL) {
                    try {
                        // URI.resolve treats query-only references like sibling files. Keep the exact signed path.
                        if (line.startsWith("?")) {
                            String full = MediaSource.withoutFragment(base);
                            int q = full.indexOf('?');
                            variant = (q < 0 ? full : full.substring(0, q)) + line;
                        } else variant = base.resolve(line).toASCIIString();
                    } catch (IllegalArgumentException ignored) {}
                }
                Matcher res = RESOLUTION.matcher(meta), speed = BANDWIDTH.matcher(meta);
                add(rows, variant, title, "video", res.find() ? res.group(1) + "p" : "HLS Variant",
                        -1, null, speed.find() ? Long.parseLong(speed.group(1)) : -1, 0, 0, null, -1, MediaSniffer.HLS_MIME);
                if (rows.size() >= VARIANTS) break;
                meta = null;
            }
        }
        return rows;
    }

    private static List<SniffedMedia> youtube(String id, String title, Budget budget) throws Exception {
        String visitor = null;
        String origin = "https://www.youtube.com";
        try (ScopedHttp.Response response = open(origin + "/watch?v=" + id, false, null, WEB_AGENT, origin, budget)) {
            if (response.status == 200) {
                Matcher m = VISITOR.matcher(body(response.connection, EMBED_BYTES, true, budget));
                if (m.find()) visitor = m.group(1);
            }
        } catch (IOException unavailable) {
            budget.check(); // Watch page may have no public visitor information.
        }
        // Fixed endpoint, fixed public client, no session headers and no redirects for the POST.
        String player = origin + "/youtubei/v1/player";
        if (CookieHandler.getDefault() != null || !budget.admit(player)) return Collections.emptyList();
        HttpURLConnection conn = (HttpURLConnection) new URL(player).openConnection();
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(budget.timeout());
            conn.setReadTimeout(budget.timeout());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", PLAYER_AGENT);
            conn.setRequestProperty("X-YouTube-Client-Name", "101");
            conn.setRequestProperty("X-YouTube-Client-Version", "1.02");
            conn.setRequestProperty("Origin", "https://www.youtube.com");
            JSONObject client = new JSONObject().put("clientName", "VISIONOS").put("clientVersion", "1.02")
                    .put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1").put("osName", "visionOS")
                    .put("osVersion", "26.5.23O471").put("hl", "en").put("gl", "US");
            if (visitor != null) {
                client.put("visitorData", visitor);
                conn.setRequestProperty("X-Goog-Visitor-Id", visitor);
            }
            byte[] json = new JSONObject().put("videoId", id).put("context", new JSONObject().put("client", client))
                    .toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(json.length);
            budget.check();
            try (OutputStream out = conn.getOutputStream()) { out.write(json); }
            budget.check();
            return conn.getResponseCode() == 200 ? parseYouTube(id, body(conn, PLAYER_BYTES, false, budget), title) : Collections.emptyList();
        } finally {
            conn.disconnect();
        }
    }

    static List<SniffedMedia> parseVimeo(String json, String fallbackTitle) throws Exception {
        if (!boundedJson(json)) return Collections.emptyList();
        JSONObject root = new JSONObject(json);
        JSONObject files = root.getJSONObject("request").getJSONObject("files");
        JSONObject video = root.optJSONObject("video");
        String title = video == null ? fallbackTitle : video.optString("title", fallbackTitle);
        List<SniffedMedia> rows = new ArrayList<>();
        JSONArray progressive = files.optJSONArray("progressive");
        for (int i = 0; progressive != null && i < Math.min(progressive.length(), FORMAT_LIMIT); i++) {
            JSONObject item = progressive.optJSONObject(i);
            if (item != null) add(rows, item.optString("url"), title, "video", item.optString("quality", "MP4"),
                    item.optLong("size", -1), null, -1, 0, 0, null, -1);
        }
        add(rows, cdn(files.optJSONObject("hls")), title, "video", "HLS Master", -1, null, -1, 0, 0, null, -1, MediaSniffer.HLS_MIME);
        if (rows.isEmpty()) add(rows, cdn(files.optJSONObject("dash")), title, "video", "DASH", -1, null, -1, 0, 0, null, -1, MediaSniffer.DASH_MIME);
        return rows;
    }

    private static String cdn(JSONObject config) {
        if (config == null) return null;
        JSONObject cdns = config.optJSONObject("cdns");
        if (cdns == null) return config.optString("url");
        JSONObject preferred = cdns.optJSONObject(config.optString("default_cdn"));
        if (preferred != null && MediaSource.httpUri(preferred.optString("url")) != null) return preferred.optString("url");
        java.util.Iterator<String> keys = cdns.keys();
        for (int i = 0; i < 16 && keys.hasNext(); i++) {
            JSONObject candidate = cdns.optJSONObject(keys.next());
            if (candidate != null && MediaSource.httpUri(candidate.optString("url")) != null) return candidate.optString("url");
        }
        return null;
    }

    static List<SniffedMedia> parseYouTube(String videoId, String json, String fallbackTitle) throws Exception {
        if (!boundedJson(json)) return Collections.emptyList();
        JSONObject root = new JSONObject(json);
        JSONObject details = root.optJSONObject("videoDetails");
        if (details != null && !videoId.equals(details.optString("videoId", videoId))) return Collections.emptyList();
        String title = details == null ? fallbackTitle : details.optString("title", fallbackTitle);
        JSONObject data = root.optJSONObject("streamingData");
        if (data == null) return Collections.emptyList();
        List<SniffedMedia> rows = new ArrayList<>();
        SniffedMedia best = null;
        JSONArray adaptive = data.optJSONArray("adaptiveFormats");
        for (int i = 0; adaptive != null && i < Math.min(adaptive.length(), FORMAT_LIMIT); i++) {
            JSONObject f = adaptive.optJSONObject(i);
            String mime = f == null ? "" : f.optString("mimeType");
            if (!mime.startsWith("audio/")) continue;
            String codec = mime.contains("mp4a") ? "AAC" : mime.contains("opus") ? "Opus" : "Audio";
            long bitrate = f.optLong("bitrate", -1);
            SniffedMedia track = add(rows, f.optString("url"), title, "audio", bitrate > 0 ? bitrate / 1000 + " kbps" : "Audio",
                    f.optLong("contentLength", -1), codec, bitrate, 0, f.optInt("itag"), null, -1, null, videoId);
            if (track == null) continue;
            if (best == null || ("AAC".equals(codec) && !"AAC".equals(best.getCodec()))
                    || (codec.equals(best.getCodec()) && bitrate > best.getBitrate())) best = track;
        }
        videos(rows, data.optJSONArray("formats"), title, null, false);
        videos(rows, adaptive, title, best, true);
        add(rows, data.optString("hlsManifestUrl"), title, "video", "HLS Master", -1, null, -1, 0, 0, null, -1, MediaSniffer.HLS_MIME);
        return rows;
    }

    private static void videos(List<SniffedMedia> rows, JSONArray array, String title, SniffedMedia audio, boolean adaptive) {
        for (int i = 0; array != null && i < Math.min(array.length(), FORMAT_LIMIT); i++) {
            JSONObject f = array.optJSONObject(i);
            if (f == null) continue;
            String mime = f.optString("mimeType");
            if (adaptive && !mime.startsWith("video/")) continue;
            String codec = mime.contains("av01") ? "AV1" : mime.contains("vp9") || mime.contains("vp09") ? "VP9" : "H.264";
            String quality = f.optString("qualityLabel", f.optInt("height") > 0 ? f.optInt("height") + "p" : "MP4");
            add(rows, f.optString("url"), title, "video", quality, f.optLong("contentLength", -1), codec,
                    f.optLong("bitrate", -1), f.optInt("fps", 30), f.optInt("itag"),
                    audio == null ? null : audio.getUrl(), audio == null ? -1 : audio.getFileSize(), null,
                    audio == null ? null : audio.getAudioGroup());
        }
    }

    private static SniffedMedia add(List<SniffedMedia> rows, String url, String title, String type, String quality, long size,
                                    String codec, long bitrate, int fps, int formatId, String audio, long audioSize) {
        return add(rows, url, title, type, quality, size, codec, bitrate, fps, formatId, audio, audioSize, null);
    }

    private static SniffedMedia add(List<SniffedMedia> rows, String url, String title, String type, String quality, long size,
                                    String codec, long bitrate, int fps, int formatId, String audio, long audioSize, String mime) {
        return add(rows, url, title, type, quality, size, codec, bitrate, fps, formatId, audio, audioSize, mime, null);
    }

    private static SniffedMedia add(List<SniffedMedia> rows, String url, String title, String type, String quality, long size,
                                    String codec, long bitrate, int fps, int formatId, String audio, long audioSize, String mime,
                                    String group) {
        URI uri = MediaSource.httpUri(url);
        if (uri == null || rows.size() >= MediaSniffer.MAX_ROWS) return null;
        String target = MediaSource.withoutFragment(uri);
        if (target.length() > MediaSource.MAX_URL) return null;
        SniffedMedia row = new SniffedMedia(target, title, type, quality, size, codec, bitrate, fps, formatId, audio, audioSize, mime, group);
        rows.add(row);
        return row;
    }

    // Bound nesting before invoking Android's recursive JSON parser. Ignore cipher-only entries;
    // no new provider behavior, signature processing, or browser credentials are introduced here.
    private static boolean boundedJson(String text) {
        if (text == null || text.length() > PLAYER_BYTES || !text.trim().startsWith("{")) return false;
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '\\') i++;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == '{' || ch == '[') { if (++depth > 48) return false; }
            else if (ch == '}' || ch == ']') { if (--depth < 0) return false; }
            else if (ch == '\'' || ch == '/' || ch == '#' || ch == '\\' || ch == ';' || ch == '=') return false;
        }
        return !quoted && depth == 0;
    }

    static String extractJsonAfterMarker(String text, String marker) {
        if (text == null || text.length() > EMBED_BYTES) return null;
        int markerAt = text.indexOf(marker);
        int start = markerAt < 0 ? -1 : text.indexOf('{', markerAt + marker.length());
        if (start < 0) return null;
        int depth = 0;
        boolean quoted = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '\\') i++;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return text.substring(start, i + 1);
        }
        return null;
    }
}

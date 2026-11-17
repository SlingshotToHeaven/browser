package com.privacy.browser.media;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sniffs and captures media streams (HLS/m3u8, DASH/mpd, direct MP4/WebM, and Vimeo/video platforms)
 * requested at the network, DOM, or player configuration layer.
 */
public class MediaSniffer {

    private static final String TAG = "MediaSniffer";

    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    public interface MediaDetectionListener {
        void onMediaDetected(String tabId, int totalCount);
    }

    private static MediaDetectionListener detectionListener;

    public static void setMediaDetectionListener(MediaDetectionListener listener) {
        detectionListener = listener;
    }

    public static class SniffedMedia implements Serializable {
        private final String url;
        private final String title;
        private final String type; // "video", "audio", etc.
        private final String quality; // "1080p", "720p", "HLS Stream", etc.
        private final String referrer;
        private final long fileSize; // in bytes, or -1 if unknown
        private final long detectedTime;
        private final String codec; // "AV1", "H.264", "VP9", "AAC", "Opus"
        private final long bitrate; // in bps, or -1 if unknown
        private final int fps; // 30, 60
        private final int formatId; // itag or format ID
        private final String audioUrl; // companion audio url if paired
        private final long audioFileSize; // companion audio size

        public SniffedMedia(String url, String title, String type, String quality) {
            this(url, title, type, quality, null, -1, null, -1, 0, 0, null, -1);
        }

        public SniffedMedia(String url, String title, String type, String quality, String referrer) {
            this(url, title, type, quality, referrer, -1, null, -1, 0, 0, null, -1);
        }

        public SniffedMedia(String url, String title, String type, String quality, String referrer, long fileSize) {
            this(url, title, type, quality, referrer, fileSize, null, -1, 0, 0, null, -1);
        }

        public SniffedMedia(String url, String title, String type, String quality, String referrer, long fileSize,
                            String codec, long bitrate, int fps, int formatId, String audioUrl, long audioFileSize) {
            this.url = url;
            this.title = title != null ? title.trim() : "Video Stream";
            this.type = type != null ? type : "video";
            this.quality = quality != null ? quality : "Auto";
            this.referrer = referrer;
            this.fileSize = fileSize;
            this.codec = codec;
            this.bitrate = bitrate;
            this.fps = fps;
            this.formatId = formatId;
            this.audioUrl = audioUrl;
            this.audioFileSize = audioFileSize;
            this.detectedTime = System.currentTimeMillis();
        }

        public String getUrl() { return url; }
        public String getTitle() { return title; }
        public String getType() { return type; }
        public String getQuality() { return quality; }
        public String getReferrer() { return referrer; }
        public long getFileSize() { return fileSize; }
        public long getDetectedTime() { return detectedTime; }
        public String getCodec() { return codec; }
        public long getBitrate() { return bitrate; }
        public int getFps() { return fps; }
        public int getFormatId() { return formatId; }
        public String getAudioUrl() { return audioUrl; }
        public long getAudioFileSize() { return audioFileSize; }

        public String getFormattedBitrate() {
            if (bitrate <= 0) return "";
            if (bitrate >= 1000000) {
                return String.format(Locale.US, "%.1f Mbps", bitrate / 1000000.0);
            } else {
                return (bitrate / 1000) + " kbps";
            }
        }

        public String getDisplayDescription() {
            StringBuilder sb = new StringBuilder();
            if (codec != null && !codec.isEmpty()) {
                sb.append(codec);
            }
            if (fps > 0 && fps != 30) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append(fps).append("fps");
            }
            if (bitrate > 0) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append(getFormattedBitrate());
            }
            if (sb.length() == 0) {
                if (url != null && url.contains(".m3u8")) return "HLS Stream • Native Remux";
                if (url != null && (url.contains(".mpd") || url.contains(".json"))) return "DASH Stream";
                return "Direct Media Track";
            }
            return sb.toString();
        }

        public String getFormattedSize() {
            if (fileSize <= 0) {
                if (url.contains(".m3u8")) return "HLS Stream";
                if (url.contains(".mpd") || url.contains(".json")) return "DASH Stream";
                return "Video Stream";
            }
            final String[] units = new String[]{"B", "KB", "MB", "GB"};
            int digitGroups = (int) (Math.log10(fileSize) / Math.log10(1024));
            if (digitGroups >= units.length) digitGroups = units.length - 1;
            return String.format(Locale.US, "%.1f %s", fileSize / Math.pow(1024, digitGroups), units[digitGroups]);
        }
    }

    // Map tabId -> List of SniffedMedia
    private static final Map<String, List<SniffedMedia>> tabMediaMap = new ConcurrentHashMap<>();
    // Map tabId -> active video identifier to auto-flush stale catches across video navigations
    private static final Map<String, String> tabVideoIdMap = new ConcurrentHashMap<>();
    // Map tabId -> active page URL for CDN referer attribution
    private static final Map<String, String> tabUrlMap = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void setTabUrl(String tabId, String url) {
        if (tabId != null && url != null) {
            tabUrlMap.put(tabId, url);
        }
    }

    public static String getTabUrl(String tabId) {
        return tabId != null ? tabUrlMap.get(tabId) : null;
    }

    private static final Pattern VIMEO_URL_PATTERN =
            Pattern.compile("(?:vimeo\\.com/(?:video/|channels/(?:\\w+/)?|groups/[^/]+/videos/|album/(?:\\d+)/video/)?|player\\.vimeo\\.com/video/)(\\d+)");
    private static final Pattern VIMEO_HASH_PATTERN =
            Pattern.compile("[?&]h=([a-f0-9]+)");

    private static final Pattern YOUTUBE_URL_PATTERN =
            Pattern.compile("(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?|watch|shorts)/|.*[?&]v=)|youtu\\.be/)([a-zA-Z0-9_-]{11})");

    public static String extractVideoId(String url) {
        if (isEmpty(url)) return null;
        Matcher ym = YOUTUBE_URL_PATTERN.matcher(url);
        if (ym.find()) {
            return "yt_" + ym.group(1);
        }
        Matcher vm = VIMEO_URL_PATTERN.matcher(url);
        if (vm.find()) {
            return "vimeo_" + vm.group(1);
        }
        return null;
    }

    /**
     * Safely extracts balanced JSON object starting after the given marker in text.
     */
    public static String extractJsonAfterMarker(String text, String marker) {
        if (text == null || marker == null) return null;
        int idx = text.indexOf(marker);
        if (idx == -1) return null;
        int start = text.indexOf('{', idx + marker.length());
        if (start == -1) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }

    public static void clearTab(String tabId) {
        if (tabId != null) {
            tabMediaMap.remove(tabId);
            tabVideoIdMap.remove(tabId);
            if (detectionListener != null) {
                detectionListener.onMediaDetected(tabId, 0);
            }
        }
    }

    public static List<SniffedMedia> getMediaForTab(String tabId) {
        if (tabId == null) return Collections.emptyList();
        List<SniffedMedia> list = tabMediaMap.get(tabId);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    public static void registerMedia(String tabId, String url, String title, String type, String quality) {
        registerMedia(tabId, url, title, type, quality, null, -1);
    }

    public static void registerMedia(String tabId, String url, String title, String type, String quality, String referrer) {
        registerMedia(tabId, url, title, type, quality, referrer, -1);
    }

    public static void registerMedia(String tabId, String url, String title, String type, String quality, String referrer, long fileSize) {
        registerMedia(tabId, url, title, type, quality, referrer, fileSize, null, -1, 0, 0, null, -1);
    }

    public static void registerMedia(String tabId, String url, String title, String type, String quality, String referrer, long fileSize,
                                     String codec, long bitrate, int fps, int formatId, String audioUrl, long audioFileSize) {
        if (tabId == null || isEmpty(url)) return;
        if (url.startsWith("blob:") || url.startsWith("data:") || url.startsWith("javascript:")) return;
        if (url.contains("/proxy.html")) return;

        // Auto-assign referrer if missing
        if (isEmpty(referrer)) {
            if (url.contains("vimeocdn.com") || url.contains("vimeo.com")) {
                referrer = "https://vimeo.com/";
            } else {
                referrer = getTabUrl(tabId);
            }
        }

        List<SniffedMedia> list = tabMediaMap.computeIfAbsent(tabId, k -> Collections.synchronizedList(new ArrayList<>()));
        boolean added = false;
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                SniffedMedia existing = list.get(i);
                if (existing.getUrl().equalsIgnoreCase(url)) {
                    if (fileSize > 0 && existing.getFileSize() <= 0) {
                        String updatedTitle = (!isEmpty(title) && !title.equals("Video Stream") && !title.equals("Web Video"))
                                ? title : existing.getTitle();
                        list.set(i, new SniffedMedia(url, updatedTitle, type, quality, referrer, fileSize,
                                codec, bitrate, fps, formatId, audioUrl, audioFileSize));
                    }
                    return; // Already recorded
                }
                
                boolean sameType = (existing.getType() != null && existing.getType().equalsIgnoreCase(type));
                boolean sameQuality = (existing.getQuality() != null && existing.getQuality().equalsIgnoreCase(quality));
                boolean sameCodec = (existing.getCodec() == null && codec == null) ||
                                    (existing.getCodec() != null && existing.getCodec().equalsIgnoreCase(codec));
                boolean sameFps = (existing.getFps() == fps);

                if (sameType && sameQuality && sameCodec && sameFps && existing.getTitle().equalsIgnoreCase(title)) {
                    // Update with newer signed URL / size
                    list.set(i, new SniffedMedia(url, title, type, quality, referrer, fileSize,
                            codec, bitrate, fps, formatId, audioUrl, audioFileSize));
                    return;
                }
            }
            list.add(new SniffedMedia(url, title, type, quality, referrer, fileSize,
                    codec, bitrate, fps, formatId, audioUrl, audioFileSize));
            added = true;
        }

        if (added) {
            Log.d(TAG, "Sniffed " + type + " [" + quality + " " + (codec != null ? codec : "") + "] on tab " + tabId + ": " + url + " (" + fileSize + " bytes)");
            if (detectionListener != null) {
                detectionListener.onMediaDetected(tabId, list.size());
            }
        }
    }

    /**
     * Inspects video elements reported by JavaScript bridge.
     */
    public static void inspectVideoElement(String tabId, String pageUrl, String pageTitle, String videoSrc) {
        if (tabId == null) return;
        if (!isEmpty(pageUrl)) {
            setTabUrl(tabId, pageUrl);
        }

        // If on Vimeo, resolve full multi-resolution streams
        if (!isEmpty(pageUrl) && isVimeoUrl(pageUrl)) {
            resolveVimeoFromUrl(tabId, pageUrl, pageTitle, null);
            return;
        }

        // Direct video src found in DOM or network
        if (!isEmpty(videoSrc) && !videoSrc.startsWith("blob:") && !videoSrc.startsWith("data:")) {
            Uri uri = Uri.parse(videoSrc);
            inspectRequest(tabId, uri, pageTitle);
        }
    }

    /**
     * Inspects network requests intercepted by WebViewClient.
     * Detects HLS master playlists, DASH manifests, Vimeo configs, and direct video files.
     */
    public static void inspectRequest(String tabId, Uri uri, String pageTitle) {
        if (tabId == null || uri == null) return;
        String url = uri.toString();
        String path = uri.getPath() != null ? uri.getPath().toLowerCase(Locale.US) : "";
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.US) : "";

        if (url.contains("/proxy.html")) return;
        if (host.contains("googlevideo.com") || url.contains("/videoplayback")) {
            return; // Ignore raw MSE player chunks
        }

        // Ignore HLS/DASH segment fragments, slices, and init chunks (e.g. seg-1.ts, frag-2.m4s, chunk-3.mp4, range=)
        if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".key") ||
            url.contains(".ts?") || url.contains(".m4s?") ||
            url.contains("/segment") || url.contains("/chunk") || url.contains("/frag") ||
            url.contains("seg-") || url.contains("frag-") || url.contains("chunk-") ||
            url.contains("init.mp4") || url.contains("init-") || url.contains("range=")) {
            return;
        }

        // Check if a Vimeo player embed or config endpoint is requested over network
        if (url.contains("player.vimeo.com/video/")) {
            resolveVimeoFromUrl(tabId, url, pageTitle, null);
            return;
        }

        // 1. Check for HLS Master Playlists or segments (including PeerTube and generic /hls/ paths)
        if (path.endsWith(".m3u8") || url.contains(".m3u8") || url.contains("playlist.m3u8")
                || path.contains("/streaming-playlists/hls/") || url.contains("/hls/")
                || url.contains("mime=application%2fx-mpegurl") || url.contains("mime=application/vnd.apple.mpegurl")) {
            String title = !isEmpty(pageTitle) ? pageTitle : "HLS Video Stream";
            String ref = host.contains("vimeocdn.com") || host.contains("vimeo.com") ? "https://vimeo.com/" : getTabUrl(tabId);
            // Intercept and parse master playlist for sub-resolutions
            resolveGenericHlsMaster(tabId, url, title, ref);
            return;
        }

        // 2. Check for DASH MPD manifests
        if (path.endsWith(".mpd") || url.contains(".mpd") || url.contains("manifest.mpd")) {
            String title = !isEmpty(pageTitle) ? pageTitle + " (DASH Stream)" : "DASH Video Stream";
            registerMedia(tabId, url, title, "video", "DASH Master", getTabUrl(tabId), -1);
            return;
        }

        // 3. Vimeo Specific Stream Patterns
        if (host.contains("vimeocdn.com") || host.contains("vimeo.com")) {
            if (url.contains("/playlist.m3u8") || url.contains("master.json") || url.contains("playlist.json") || url.contains(".mp4")) {
                String title = !isEmpty(pageTitle) ? pageTitle : "Vimeo Video";
                registerMedia(tabId, url, title, "video", "Vimeo Stream", "https://vimeo.com/", -1);
                return;
            }
        }

        // 4. Direct video files requested via XHR/Fetch/media elements or query parameters
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") || path.endsWith(".mov") || path.endsWith(".avi")
                || url.contains(".mp4?") || url.contains(".webm?") || url.contains("mime=video%2fmp4") || url.contains("mime=video%2fwebm")) {
            String title = !isEmpty(pageTitle) ? pageTitle : "Web Video";
            probeMediaSizeAndRegister(tabId, url, title, "video", "Direct Video", getTabUrl(tabId));
            return;
        }

        // 5. Direct audio files
        if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".flac") || path.endsWith(".wav") || path.endsWith(".ogg") || path.endsWith(".aac")
                || url.contains(".mp3?") || url.contains(".m4a?") || url.contains("mime=audio%2f")) {
            String title = !isEmpty(pageTitle) ? pageTitle : "Audio Track";
            probeMediaSizeAndRegister(tabId, url, title, "audio", "Direct Audio", getTabUrl(tabId));
            return;
        }
    }

    public static boolean isVimeoUrl(String url) {
        if (isEmpty(url)) return false;
        return VIMEO_URL_PATTERN.matcher(url).find();
    }

    public static boolean isYouTubeUrl(String url) {
        if (isEmpty(url)) return false;
        return YOUTUBE_URL_PATTERN.matcher(url).find();
    }

    /**
     * Resolves real Vimeo streams (MP4 resolutions and HLS master) from a Vimeo video URL or embed.
     */
    public static void resolveVimeoFromUrl(String tabId, String url, String pageTitle, Runnable onComplete) {
        if (tabId == null || isEmpty(url)) {
            if (onComplete != null) onComplete.run();
            return;
        }

        Matcher m = VIMEO_URL_PATTERN.matcher(url);
        if (!m.find()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        String videoId = m.group(1);
        String hHash = null;
        Matcher mh = VIMEO_HASH_PATTERN.matcher(url);
        if (mh.find()) {
            hHash = mh.group(1);
        }

        resolveVimeoVideo(tabId, videoId, hHash, pageTitle, onComplete);
    }

    /**
     * Connects to Vimeo player API or player embed in the background and registers playable video streams.
     */
    public static void resolveVimeoVideo(String tabId, String videoId, String hHash, String pageTitle, Runnable onComplete) {
        if (tabId == null || isEmpty(videoId)) {
            if (onComplete != null) onComplete.run();
            return;
        }

        String vidKey = "vimeo_" + videoId;
        String prevKey = tabVideoIdMap.get(tabId);
        if (prevKey != null && !prevKey.equals(vidKey)) {
            clearTab(tabId);
        }
        tabVideoIdMap.put(tabId, vidKey);

        executor.execute(() -> {
            try {
                String userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
                String referrer = "https://vimeo.com/";

                String jsonContent = null;

                // Fetch player embed page directly (which contains window.playerConfig)
                String embedUrl = "https://player.vimeo.com/video/" + videoId;
                if (!isEmpty(hHash)) {
                    embedUrl += "?h=" + hHash;
                }
                try {
                    URL u = new URL(embedUrl);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("User-Agent", userAgent);
                    conn.setRequestProperty("Referer", referrer);
                    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line).append("\n");
                            }
                            String html = sb.toString();
                            int marker = html.indexOf("window.playerConfig = ");
                            if (marker != -1) {
                                int start = marker + "window.playerConfig = ".length();
                                int depth = 0;
                                boolean inStr = false;
                                boolean esc = false;
                                int end = start;
                                for (int i = start; i < html.length(); i++) {
                                    char c = html.charAt(i);
                                    if (esc) { esc = false; continue; }
                                    if (c == '\\') { esc = true; continue; }
                                    if (c == '"') { inStr = !inStr; continue; }
                                    if (!inStr) {
                                        if (c == '{') depth++;
                                        else if (c == '}') {
                                            depth--;
                                            if (depth == 0) {
                                                end = i + 1;
                                                break;
                                            }
                                        }
                                    }
                                }
                                jsonContent = html.substring(start, end);
                            }
                        }
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    Log.d(TAG, "Vimeo embed HTML fetch failed: " + e.getMessage());
                }

                if (!isEmpty(jsonContent)) {
                    parseVimeoConfig(tabId, jsonContent, pageTitle);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error resolving Vimeo streams: " + t.getMessage());
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    /**
     * Parses Vimeo player JSON config, extracting progressive MP4 URLs and HLS master playlist with quality tiers.
     */
    public static void parseVimeoConfig(String tabId, String jsonContent, String pageTitle) {
        if (tabId == null || isEmpty(jsonContent)) return;
        try {
            JSONObject root = new JSONObject(jsonContent);
            if (!root.has("request")) return;
            JSONObject req = root.getJSONObject("request");
            if (!req.has("files")) return;
            JSONObject files = req.getJSONObject("files");

            String vTitle = !isEmpty(pageTitle) ? pageTitle : "Vimeo Video";
            long duration = 0;
            if (root.has("video")) {
                JSONObject vidObj = root.getJSONObject("video");
                if (vidObj.has("title")) {
                    String t = vidObj.optString("title");
                    if (!isEmpty(t)) {
                        vTitle = t;
                    }
                }
                duration = vidObj.optLong("duration", 0);
            }

            // Clean title if ends with " on Vimeo"
            if (vTitle.endsWith(" on Vimeo")) {
                vTitle = vTitle.substring(0, vTitle.length() - 9).trim();
            }

            String referrer = "https://vimeo.com/";

            // 1. Progressive MP4s (direct 1080p, 720p, etc. if available)
            if (files.has("progressive")) {
                JSONArray prog = files.getJSONArray("progressive");
                for (int i = 0; i < prog.length(); i++) {
                    JSONObject item = prog.getJSONObject(i);
                    String vUrl = item.optString("url");
                    String quality = item.optString("quality", "MP4");
                    long size = item.optLong("size", -1);
                    if (size <= 0 && duration > 0) {
                        long estBitrate = "1080p".equalsIgnoreCase(quality) ? 4500000 : ("720p".equalsIgnoreCase(quality) ? 2200000 : 1200000);
                        size = (estBitrate / 8) * duration;
                    }
                    if (!isEmpty(vUrl)) {
                        registerMedia(tabId, vUrl, vTitle + " (" + quality + ")", "video", quality, referrer, size);
                    }
                }
            }

            // 2. HLS Master Playlist & Available Quality Profiles
            if (files.has("hls")) {
                JSONObject hls = files.getJSONObject("hls");
                String hlsUrl = null;
                JSONObject cdns = hls.optJSONObject("cdns");
                if (cdns != null) {
                    String defCdn = hls.optString("default_cdn");
                    if (!isEmpty(defCdn) && cdns.has(defCdn)) {
                        hlsUrl = cdns.getJSONObject(defCdn).optString("url");
                    }
                    if (isEmpty(hlsUrl)) {
                        for (Iterator<String> it = cdns.keys(); it.hasNext(); ) {
                            hlsUrl = cdns.getJSONObject(it.next()).optString("url");
                            if (!isEmpty(hlsUrl)) break;
                        }
                    }
                } else {
                    hlsUrl = hls.optString("url");
                }

                if (!isEmpty(hlsUrl)) {
                    // Extract streams array for discrete resolution choices
                    if (hls.has("streams")) {
                        JSONArray streams = hls.optJSONArray("streams");
                        if (streams != null && streams.length() > 0) {
                            for (int i = 0; i < streams.length(); i++) {
                                JSONObject st = streams.getJSONObject(i);
                                String q = st.optString("quality", "HD");
                                long estBitrate = 1500000;
                                if ("1080p".equalsIgnoreCase(q)) estBitrate = 4500000;
                                else if ("720p".equalsIgnoreCase(q)) estBitrate = 2200000;
                                else if ("540p".equalsIgnoreCase(q)) estBitrate = 1200000;
                                else if ("360p".equalsIgnoreCase(q)) estBitrate = 600000;
                                else if ("240p".equalsIgnoreCase(q)) estBitrate = 350000;
                                long estBytes = duration > 0 ? (estBitrate / 8) * duration : -1;

                                registerMedia(tabId, hlsUrl, vTitle + " (" + q + ")", "video", q, referrer, estBytes);
                            }
                        }
                    }
                    // Master auto stream
                    long autoEst = duration > 0 ? (2500000 / 8) * duration : -1;
                    registerMedia(tabId, hlsUrl, vTitle + " (Auto Stream)", "video", "Auto", referrer, autoEst);
                }
            }

            // 3. DASH Manifest fallback
            if (files.has("dash") && getMediaForTab(tabId).isEmpty()) {
                JSONObject dash = files.getJSONObject("dash");
                JSONObject cdns = dash.optJSONObject("cdns");
                if (cdns != null) {
                    for (Iterator<String> it = cdns.keys(); it.hasNext(); ) {
                        String cdnKey = it.next();
                        JSONObject cdnObj = cdns.getJSONObject(cdnKey);
                        String dashUrl = cdnObj.optString("url");
                        if (!isEmpty(dashUrl)) {
                            registerMedia(tabId, dashUrl, vTitle + " (DASH Stream)", "video", "DASH", referrer, -1);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse Vimeo config JSON: " + e.getMessage());
        }
    }

    /**
     * Probes media Content-Length in the background and registers it with accurate size.
     * Rejects tiny fragments and slices (< 300 KB) that often pollute MSE players.
     */
    public static void probeMediaSizeAndRegister(String tabId, String url, String title, String type, String quality, String referrer) {
        String effectiveReferrer = referrer != null ? referrer : getTabUrl(tabId);
        executor.execute(() -> {
            try {
                URL u = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                if (effectiveReferrer != null) {
                    conn.setRequestProperty("Referer", effectiveReferrer);
                }
                try {
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null && !cookies.trim().isEmpty()) {
                        conn.setRequestProperty("Cookie", cookies);
                    }
                } catch (Throwable ignored) {}

                int code = conn.getResponseCode();
                long length = -1;
                if (code >= 200 && code < 400) {
                    length = conn.getContentLengthLong();
                } else if (code == 403 || code == 405) {
                    // Fallback to GET with Range: bytes=0-0 if HEAD is blocked or disallowed by CDN
                    conn.disconnect();
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                    conn.setRequestProperty("Range", "bytes=0-0");
                    if (effectiveReferrer != null) {
                        conn.setRequestProperty("Referer", effectiveReferrer);
                    }
                    try {
                        String cookies = CookieManager.getInstance().getCookie(url);
                        if (cookies != null && !cookies.trim().isEmpty()) {
                            conn.setRequestProperty("Cookie", cookies);
                        }
                    } catch (Throwable ignored) {}
                    code = conn.getResponseCode();
                    String cr = conn.getHeaderField("Content-Range");
                    if (cr != null && cr.contains("/")) {
                        try {
                            length = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                        } catch (Exception ignored) {}
                    }
                }
                conn.disconnect();

                // If video is non-empty but smaller than 300 KB, it is an MSE fragment or tracking beacon; ignore it
                if ("video".equals(type) && length > 0 && length < 300 * 1024) {
                    Log.d(TAG, "Ignoring tiny video fragment (" + length + " bytes): " + url);
                    return;
                }

                registerMedia(tabId, url, title, type, quality, effectiveReferrer, length);
            } catch (Throwable t) {
                // If network probe fails, register as fallback with unknown size
                registerMedia(tabId, url, title, type, quality, effectiveReferrer, -1);
            }
        });
    }

    private static final Pattern HLS_STREAM_INF_PATTERN =
            Pattern.compile("#EXT-X-STREAM-INF:([^\r\n]+)[\r\n]+([^\r\n]+)");
    private static final Pattern RESOLUTION_PATTERN =
            Pattern.compile("RESOLUTION=(\\d+x\\d+)");
    private static final Pattern BANDWIDTH_PATTERN =
            Pattern.compile("BANDWIDTH=(\\d+)");

    /**
     * Resolves generic HLS master playlists into individual resolution stream options.
     */
    public static void resolveGenericHlsMaster(String tabId, String hlsUrl, String pageTitle, String referrer) {
        String effectiveReferrer = referrer != null ? referrer : getTabUrl(tabId);
        registerMedia(tabId, hlsUrl, pageTitle + " (Auto Stream)", "video", "Auto", effectiveReferrer, -1);

        executor.execute(() -> {
            try {
                URL u = new URL(hlsUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                if (effectiveReferrer != null) {
                    conn.setRequestProperty("Referer", effectiveReferrer);
                }
                try {
                    String cookies = CookieManager.getInstance().getCookie(hlsUrl);
                    if (cookies != null && !cookies.trim().isEmpty()) {
                        conn.setRequestProperty("Cookie", cookies);
                    }
                } catch (Throwable ignored) {}
                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                    }
                    String content = sb.toString();
                    if (content.contains("#EXT-X-STREAM-INF")) {
                        Matcher m = HLS_STREAM_INF_PATTERN.matcher(content);
                        int count = 0;
                        while (m.find()) {
                            String meta = m.group(1);
                            String streamPath = m.group(2).trim();
                            if (streamPath.isEmpty() || streamPath.startsWith("#")) continue;

                            String streamUrl;
                            if (streamPath.startsWith("http://") || streamPath.startsWith("https://")) {
                                streamUrl = streamPath;
                            } else {
                                // Resolve relative URL against playlist base URL
                                int lastSlash = hlsUrl.lastIndexOf('/');
                                if (lastSlash != -1) {
                                    streamUrl = hlsUrl.substring(0, lastSlash + 1) + streamPath;
                                } else {
                                    streamUrl = streamPath;
                                }
                            }

                            String res = "HD";
                            Matcher rm = RESOLUTION_PATTERN.matcher(meta);
                            if (rm.find()) {
                                String fullRes = rm.group(1); // e.g. 1920x1080
                                String[] parts = fullRes.split("x");
                                if (parts.length == 2) {
                                    res = parts[1] + "p"; // e.g. 1080p
                                } else {
                                    res = fullRes;
                                }
                            }

                            long estBytes = -1; // Unknown duration, displayed cleanly as HLS Stream rather than fabricated size
                            registerMedia(tabId, streamUrl, pageTitle + " (" + res + ")", "video", res, referrer != null ? referrer : hlsUrl, estBytes);
                            count++;
                        }
                        if (count > 0) {
                            Log.d(TAG, "Parsed " + count + " HLS stream variants for tab " + tabId);
                        }
                    }
                }
                conn.disconnect();
            } catch (Throwable t) {
                Log.d(TAG, "HLS master manifest parsing failed for " + hlsUrl + ": " + t.getMessage());
            }
        });
    }

    /**
     * Resolves YouTube video from page URL or embed by fetching player details via VISIONOS client.
     */
    public static void resolveYouTubeFromUrl(String tabId, String url, String pageTitle, Runnable onComplete) {
        if (tabId == null || isEmpty(url)) {
            if (onComplete != null) onComplete.run();
            return;
        }

        Matcher m = YOUTUBE_URL_PATTERN.matcher(url);
        if (!m.find()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        String videoId = m.group(1);
        String vidKey = "yt_" + videoId;

        // Auto-clear prior video's streams if navigating to a new YouTube video
        String prevKey = tabVideoIdMap.get(tabId);
        if (prevKey != null && !prevKey.equals(vidKey)) {
            clearTab(tabId);
        }
        tabVideoIdMap.put(tabId, vidKey);

        executor.execute(() -> {
            try {
                // 1. Fetch watch page HTML to obtain VISITOR_DATA & canonical title
                String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
                URL u = new URL(watchUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

                String visitorData = null;
                String canonicalTitle = pageTitle;

                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                    }
                    String html = sb.toString();
                    Matcher vm = Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"").matcher(html);
                    if (vm.find()) {
                        visitorData = vm.group(1);
                    }
                    Matcher tm = Pattern.compile("<title>(.*?)(?: - YouTube)?</title>").matcher(html);
                    if (tm.find()) {
                        String extracted = tm.group(1).trim();
                        if (!isEmpty(extracted) && !extracted.equalsIgnoreCase("YouTube")) {
                            canonicalTitle = extracted;
                        }
                    }
                }
                conn.disconnect();

                // 2. Query InnerTube player with VISIONOS client (which delivers direct signed URLs for 2160p..144p)
                URL playerApiUrl = new URL("https://www.youtube.com/youtubei/v1/player");
                HttpURLConnection apiConn = (HttpURLConnection) playerApiUrl.openConnection();
                apiConn.setRequestMethod("POST");
                apiConn.setDoOutput(true);
                apiConn.setConnectTimeout(8000);
                apiConn.setReadTimeout(8000);
                apiConn.setRequestProperty("Content-Type", "application/json");
                apiConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15");
                apiConn.setRequestProperty("X-YouTube-Client-Name", "101");
                apiConn.setRequestProperty("X-YouTube-Client-Version", "1.02");
                apiConn.setRequestProperty("Origin", "https://www.youtube.com");
                if (visitorData != null) {
                    apiConn.setRequestProperty("X-Goog-Visitor-Id", visitorData);
                }

                JSONObject clientObj = new JSONObject();
                clientObj.put("clientName", "VISIONOS");
                clientObj.put("clientVersion", "1.02");
                clientObj.put("deviceMake", "Apple");
                clientObj.put("deviceModel", "RealityDevice17,1");
                clientObj.put("osName", "visionOS");
                clientObj.put("osVersion", "26.5.23O471");
                clientObj.put("hl", "en");
                clientObj.put("gl", "US");
                if (visitorData != null) {
                    clientObj.put("visitorData", visitorData);
                }

                JSONObject contextObj = new JSONObject();
                contextObj.put("client", clientObj);

                JSONObject payloadObj = new JSONObject();
                payloadObj.put("videoId", videoId);
                payloadObj.put("context", contextObj);

                byte[] body = payloadObj.toString().getBytes("UTF-8");
                apiConn.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = apiConn.getOutputStream()) {
                    os.write(body);
                }

                int apiCode = apiConn.getResponseCode();
                if (apiCode == 200) {
                    StringBuilder respSb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(apiConn.getInputStream(), "UTF-8"))) {
                        String l;
                        while ((l = r.readLine()) != null) {
                            respSb.append(l).append("\n");
                        }
                    }
                    String apiJson = respSb.toString();
                    parseYouTubeData(tabId, url, canonicalTitle, apiJson);
                }
                apiConn.disconnect();
            } catch (Throwable t) {
                Log.d(TAG, "YouTube player resolution failed: " + t.getMessage());
            } finally {
                if (onComplete != null) onComplete.run();
            }
        });
    }

    /**
     * Parses ytInitialPlayerResponse JSON data to extract progressive MP4s, adaptive streams, and HLS.
     */
    public static void parseYouTubeData(String tabId, String pageUrl, String pageTitle, String jsonString) {
        if (tabId == null || isEmpty(jsonString)) return;
        try {
            JSONObject root = new JSONObject(jsonString);
            if (!root.has("streamingData")) return;
            JSONObject sd = root.getJSONObject("streamingData");

            String vTitle = !isEmpty(pageTitle) ? pageTitle : "YouTube Video";
            String videoId = null;
            if (root.has("videoDetails")) {
                JSONObject vd = root.getJSONObject("videoDetails");
                String t = vd.optString("title");
                if (!isEmpty(t)) {
                    vTitle = t;
                }
                videoId = vd.optString("videoId", null);
            }

            // Verify videoId matches pageUrl if pageUrl specifies a videoId
            if (videoId != null && !isEmpty(pageUrl)) {
                Matcher pm = YOUTUBE_URL_PATTERN.matcher(pageUrl);
                if (pm.find()) {
                    String pageVid = pm.group(1);
                    if (!videoId.equals(pageVid)) {
                        Log.d(TAG, "Ignoring stale YouTube playerResponse for video " + videoId + " on page " + pageUrl);
                        return;
                    }
                }
            }

            if (videoId != null) {
                String vidKey = "yt_" + videoId;
                String prevKey = tabVideoIdMap.get(tabId);
                if (prevKey != null && !prevKey.equals(vidKey)) {
                    clearTab(tabId);
                }
                tabVideoIdMap.put(tabId, vidKey);
            }

            if (vTitle.endsWith(" - YouTube")) {
                vTitle = vTitle.substring(0, vTitle.length() - 10).trim();
            }

            // Purge any stale streams with a different title from earlier video
            List<SniffedMedia> existingList = tabMediaMap.get(tabId);
            if (existingList != null && !existingList.isEmpty()) {
                synchronized (existingList) {
                    boolean hasDifferentTitle = false;
                    for (SniffedMedia sm : existingList) {
                        String st = sm.getTitle();
                        if (st.contains(" (")) {
                            st = st.substring(0, st.lastIndexOf(" (")).trim();
                        }
                        if (!st.equalsIgnoreCase(vTitle) && !st.equals("YouTube Video") && !vTitle.equals("YouTube Video")) {
                            hasDifferentTitle = true;
                            break;
                        }
                    }
                    if (hasDifferentTitle) {
                        existingList.clear();
                    }
                }
            }

            String effectiveReferrer = (!isEmpty(pageUrl) && pageUrl.startsWith("http")) ? pageUrl : "https://m.youtube.com/";

            String bestAudioUrl = null;
            long bestAudioSize = -1;
            long bestAudioBitrate = -1;
            String bestAudioCodec = null;

            // Pass 1: Parse all audio streams from adaptiveFormats
            if (sd.has("adaptiveFormats")) {
                JSONArray adapt = sd.getJSONArray("adaptiveFormats");
                for (int i = 0; i < adapt.length(); i++) {
                    JSONObject fmt = adapt.getJSONObject(i);
                    String mimeType = fmt.optString("mimeType", "");
                    if (mimeType.contains("audio/")) {
                        String directUrl = fmt.optString("url");
                        if (isEmpty(directUrl) && fmt.has("signatureCipher")) {
                            String sc = fmt.optString("signatureCipher");
                            for (String param : sc.split("&")) {
                                if (param.startsWith("url=")) {
                                    try {
                                        directUrl = URLDecoder.decode(param.substring(4), "UTF-8");
                                    } catch (Exception ignored) {}
                                    break;
                                }
                            }
                        }
                        if (!isEmpty(directUrl)) {
                            int itag = fmt.optInt("itag", 0);
                            long bitrate = fmt.optLong("bitrate", -1);
                            long contentLength = fmt.optLong("contentLength", -1);
                            if (contentLength <= 0 && fmt.has("contentLength")) {
                                try {
                                    contentLength = Long.parseLong(fmt.optString("contentLength"));
                                } catch (Exception ignored) {}
                            }
                            String codec = "Audio";
                            if (mimeType.contains("mp4a") || mimeType.contains("m4a")) {
                                codec = "AAC";
                            } else if (mimeType.contains("opus")) {
                                codec = "Opus";
                            }

                            String quality = (bitrate > 0) ? (bitrate / 1000) + " kbps" : "Audio Track";
                            String audioTitle = vTitle + " (Audio)";

                            // Prioritize high bitrate AAC for easy MP4 muxing, else highest bitrate audio
                            if (bestAudioUrl == null ||
                                (codec.equals("AAC") && (bestAudioCodec == null || !bestAudioCodec.equals("AAC") || bitrate >= bestAudioBitrate)) ||
                                (bestAudioCodec != null && !bestAudioCodec.equals("AAC") && bitrate > bestAudioBitrate)) {
                                bestAudioUrl = directUrl;
                                bestAudioSize = contentLength;
                                bestAudioBitrate = bitrate;
                                bestAudioCodec = codec;
                            }

                            registerMedia(tabId, directUrl, audioTitle, "audio", quality, effectiveReferrer, contentLength,
                                    codec, bitrate, 0, itag, null, -1);
                        }
                    }
                }
            }

            // Pass 2: Progressive Formats (Combined audio + video direct MP4s, typically 720p and 360p)
            if (sd.has("formats")) {
                JSONArray formats = sd.getJSONArray("formats");
                for (int i = 0; i < formats.length(); i++) {
                    JSONObject fmt = formats.getJSONObject(i);
                    String directUrl = fmt.optString("url");
                    if (isEmpty(directUrl) && fmt.has("signatureCipher")) {
                        String sc = fmt.optString("signatureCipher");
                        for (String param : sc.split("&")) {
                            if (param.startsWith("url=")) {
                                try {
                                    directUrl = URLDecoder.decode(param.substring(4), "UTF-8");
                                } catch (Exception ignored) {}
                                break;
                            }
                        }
                    }
                    if (!isEmpty(directUrl)) {
                        int itag = fmt.optInt("itag", 0);
                        String qualityLabel = fmt.optString("qualityLabel", fmt.optString("quality", "MP4"));
                        int fps = fmt.optInt("fps", 30);
                        long bitrate = fmt.optLong("bitrate", -1);
                        long contentLength = fmt.optLong("contentLength", -1);
                        if (contentLength <= 0 && fmt.has("contentLength")) {
                            try {
                                contentLength = Long.parseLong(fmt.optString("contentLength"));
                            } catch (Exception ignored) {}
                        }
                        String mimeType = fmt.optString("mimeType", "");
                        String codec = "H.264";
                        if (mimeType.contains("av01")) codec = "AV1";
                        else if (mimeType.contains("vp9") || mimeType.contains("vp09")) codec = "VP9";

                        // Progressive streams already contain audio, no need for separate companion audioUrl
                        registerMedia(tabId, directUrl, vTitle + " (" + qualityLabel + ")", "video", qualityLabel, effectiveReferrer, contentLength,
                                codec, bitrate, fps, itag, null, -1);
                    }
                }
            }

            // Pass 3: Adaptive Video Formats (Includes 2160p, 1440p, 1080p, 720p, 480p, 360p, 240p)
            if (sd.has("adaptiveFormats")) {
                JSONArray adapt = sd.getJSONArray("adaptiveFormats");
                for (int i = 0; i < adapt.length(); i++) {
                    JSONObject fmt = adapt.getJSONObject(i);
                    String mimeType = fmt.optString("mimeType", "");
                    if (mimeType.contains("video/")) {
                        String directUrl = fmt.optString("url");
                        if (isEmpty(directUrl) && fmt.has("signatureCipher")) {
                            String sc = fmt.optString("signatureCipher");
                            for (String param : sc.split("&")) {
                                if (param.startsWith("url=")) {
                                    try {
                                        directUrl = URLDecoder.decode(param.substring(4), "UTF-8");
                                    } catch (Exception ignored) {}
                                    break;
                                }
                            }
                        }
                        if (!isEmpty(directUrl)) {
                            int itag = fmt.optInt("itag", 0);
                            String qualityLabel = fmt.optString("qualityLabel", "");
                            if (isEmpty(qualityLabel)) {
                                int height = fmt.optInt("height", 0);
                                if (height > 0) {
                                    qualityLabel = height + "p";
                                } else {
                                    qualityLabel = "HD Video";
                                }
                            }
                            int fps = fmt.optInt("fps", 30);
                            long bitrate = fmt.optLong("bitrate", -1);
                            long contentLength = fmt.optLong("contentLength", -1);
                            if (contentLength <= 0 && fmt.has("contentLength")) {
                                try {
                                    contentLength = Long.parseLong(fmt.optString("contentLength"));
                                } catch (Exception ignored) {}
                            }

                            String codec = "H.264";
                            if (mimeType.contains("av01")) {
                                codec = "AV1";
                            } else if (mimeType.contains("vp9") || mimeType.contains("vp09")) {
                                codec = "VP9";
                            } else if (mimeType.contains("avc1") || mimeType.contains("h264")) {
                                codec = "H.264";
                            }

                            // Pair with best audio track as default companion
                            registerMedia(tabId, directUrl, vTitle + " (" + qualityLabel + ")", "video", qualityLabel, effectiveReferrer, contentLength,
                                    codec, bitrate, fps, itag, bestAudioUrl, bestAudioSize);
                        }
                    }
                }
            }

            // 3. HLS Manifest if available
            if (sd.has("hlsManifestUrl")) {
                String hlsUrl = sd.optString("hlsManifestUrl");
                if (!isEmpty(hlsUrl)) {
                    resolveGenericHlsMaster(tabId, hlsUrl, vTitle, effectiveReferrer);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}

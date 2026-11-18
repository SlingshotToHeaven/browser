package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility to identify whether a media URL corresponds to an HLS (.m3u8) or DASH (.mpd) stream.
 */
public class StreamDetector {
    static final String DASH_UNSUPPORTED = "DASH (MPD) downloads are not supported.";
    private static final Pattern MPD_TAG = Pattern.compile("<(?:[\\w.-]+:)?MPD(?=[\\s/>])", Pattern.CASE_INSENSITIVE);

    static boolean isDash(String url, String mimeType) {
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).contains("dash+xml")) return true;
        if (url == null) return false;
        try {
            String path = new URI(url.trim()).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".mpd");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    static boolean looksLikeDashManifest(CharSequence text) {
        int start = 0;
        while (start < text.length() && (Character.isWhitespace(text.charAt(start)) || text.charAt(start) == '\uFEFF')) start++;
        return start < text.length() && text.charAt(start) == '<' && MPD_TAG.matcher(text).find(start);
    }

    static boolean needsMux(DownloadItem item) {
        String url = item.getUrl();
        if (url != null && (url.startsWith("blob:") || url.startsWith("data:"))) return false;
        String audio = item.getAudioUrl();
        return isStream(url, item.getMimeType()) || (audio != null && !audio.trim().isEmpty());
    }

    public static boolean isStream(String url, String mimeType) {
        if (isDash(url, mimeType)) return true;
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).contains("mpegurl")) return true;

        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        String path = (q != -1) ? lower.substring(0, q) : lower;

        if (path.endsWith(".m3u8") || lower.contains(".m3u8") || lower.contains("playlist.m3u8")) {
            return true;
        }
        if (lower.contains("vimeocdn.com") && (lower.contains("master.json") || lower.contains("playlist.m3u8"))) {
            return true;
        }
        return false;
    }

    public static String ensureMp4Extension(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "Video_" + System.currentTimeMillis() + ".mp4";
        }
        String clean = fileName.trim();
        if (clean.toLowerCase(Locale.US).endsWith(".mp4")) {
            return clean;
        }
        int dot = clean.lastIndexOf('.');
        if (dot != -1) {
            clean = clean.substring(0, dot);
        }
        if (clean.equalsIgnoreCase("playlist") || clean.equalsIgnoreCase("master") || clean.equalsIgnoreCase("index") || clean.equalsIgnoreCase("manifest")) {
            clean = "Video_" + System.currentTimeMillis();
        }
        return clean + ".mp4";
    }
}

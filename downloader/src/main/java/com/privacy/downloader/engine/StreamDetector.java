package com.privacy.downloader.engine;

import java.util.Locale;

/**
 * Utility to identify whether a media URL corresponds to an HLS (.m3u8) or DASH (.mpd) stream.
 */
public class StreamDetector {

    public static boolean isStream(String url, String mimeType) {
        if (mimeType != null) {
            String m = mimeType.toLowerCase(Locale.US);
            if (m.contains("mpegurl") || m.contains("dash+xml") || m.contains("application/x-mpegurl")) {
                return true;
            }
        }

        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        String path = (q != -1) ? lower.substring(0, q) : lower;

        if (path.endsWith(".m3u8") || lower.contains(".m3u8") || lower.contains("playlist.m3u8")) {
            return true;
        }
        if (path.endsWith(".mpd") || lower.contains(".mpd") || lower.contains("manifest.mpd")) {
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

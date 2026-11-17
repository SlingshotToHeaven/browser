package com.privacy.downloader.model;

import java.io.Serializable;
import java.net.URI;
import java.util.Locale;

public class GrabbedLinkItem implements Serializable {
    private final String url;
    private final String title;
    private final String category;
    private boolean selected;
    private String status = "UNKNOWN"; // UNKNOWN, CHECKING, ONLINE, OFFLINE
    private long fileSize = -1;

    private String referrer;

    public GrabbedLinkItem(String url, String title, String originalType) {
        this(url, title, originalType, null);
    }

    public GrabbedLinkItem(String url, String title, String originalType, String referrer) {
        this.url = url;
        this.title = title != null ? title.trim() : "";
        this.category = determineCategory(url, originalType);
        this.referrer = referrer;
        this.selected = true; // Selected by default
    }

    public String getReferrer() {
        return referrer;
    }

    public void setReferrer(String referrer) {
        this.referrer = referrer;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getCategory() {
        return category;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileName() {
        // If title is meaningful (e.g. video title with quality label), construct sanitized filename
        if (title != null && !title.trim().isEmpty() &&
                !title.equalsIgnoreCase("video") &&
                !title.equalsIgnoreCase("link") &&
                !title.equalsIgnoreCase("image") &&
                !title.equalsIgnoreCase("audio") &&
                !title.equalsIgnoreCase("Embedded Video Player")) {
            String sanitized = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (sanitized.length() > 60) sanitized = sanitized.substring(0, 60).trim();

            String ext = "";
            try {
                String path = URI.create(url).getPath();
                if (path != null && path.contains(".")) {
                    ext = path.substring(path.lastIndexOf('.'));
                    if (ext.contains("/")) ext = "";
                }
            } catch (Exception ignored) {}

            if (ext.isEmpty() || ext.equalsIgnoreCase(".m3u8") || ext.equalsIgnoreCase(".mpd") || ext.equalsIgnoreCase(".json")) {
                if ("Videos".equals(category)) ext = ".mp4";
                else if ("Audio".equals(category)) ext = ".mp3";
                else if ("Images".equals(category)) ext = ".jpg";
            }

            if (!sanitized.toLowerCase(Locale.US).endsWith(ext.toLowerCase(Locale.US))) {
                sanitized += ext;
            }
            return sanitized;
        }

        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                if (slash != -1 && slash < path.length() - 1) {
                    String candidate = path.substring(slash + 1);
                    if ("Videos".equals(category) && (candidate.endsWith(".m3u8") || candidate.endsWith(".mpd") || candidate.endsWith(".json"))) {
                        int dot = candidate.lastIndexOf('.');
                        return (dot != -1 ? candidate.substring(0, dot) : candidate) + ".mp4";
                    }
                    return candidate;
                }
            }
        } catch (Exception ignored) {}
        return "file_" + Math.abs(url.hashCode()) + ("Videos".equals(category) ? ".mp4" : "");
    }

    public String getDomain() {
        try {
            String host = URI.create(url).getHost();
            if (host != null) return host;
        } catch (Exception ignored) {}
        return "";
    }

    private static String determineCategory(String url, String originalType) {
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains("?")) {
            lower = lower.substring(0, lower.indexOf("?"));
        }

        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".php") || lower.endsWith(".asp") || lower.endsWith(".jsp") || lower.contains("/proxy.html")) {
            return "Links";
        }

        if ("image".equalsIgnoreCase(originalType)) return "Images";
        if ("video".equalsIgnoreCase(originalType)) return "Videos";
        if ("audio".equalsIgnoreCase(originalType)) return "Audio";

        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ts")) {
            return "Videos";
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".aac")) {
            return "Audio";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".svg")) {
            return "Images";
        }
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".bin") || lower.endsWith(".iso")) {
            return "Archives";
        }
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".txt") || lower.endsWith(".csv")) {
            return "Documents";
        }
        return "Links";
    }
}

package com.privacy.downloader.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class DownloadIntegrityVerifier {

    private static final String[] BINARY_EXTENSIONS = new String[]{
            ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz", ".iso", ".dmg", ".bin",
            ".apk", ".xapk", ".apks", ".jar", ".exe", ".msi", ".deb", ".rpm",
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".webm",
            ".mp3", ".wav", ".flac", ".aac", ".m4a", ".ogg", ".opus",
            ".pdf", ".epub", ".mobi", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"
    };

    public static boolean isBinaryExtension(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.US);
        for (String ext : BINARY_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasKnownBinaryMagicBytes(byte[] sample, int sampleLength) {
        if (sample == null || sampleLength < 2) return false;

        // PK\x03\x04 or PK\x05\x06 or PK\x07\x08 (ZIP, APK, DOCX, XLSX, JAR)
        if (sampleLength >= 4 && sample[0] == 0x50 && sample[1] == 0x4B &&
                (sample[2] == 0x03 || sample[2] == 0x05 || sample[2] == 0x07)) {
            return true;
        }

        // Rar!\x1A\x07 (RAR)
        if (sampleLength >= 4 && sample[0] == 0x52 && sample[1] == 0x61 && sample[2] == 0x72 && sample[3] == 0x21) {
            return true;
        }

        // 7z (7z\xBC\xAF\x27\x1C)
        if (sampleLength >= 4 && sample[0] == 0x37 && sample[1] == 0x7A && (sample[2] & 0xFF) == 0xBC && (sample[3] & 0xFF) == 0xAF) {
            return true;
        }

        // %PDF (PDF)
        if (sampleLength >= 4 && sample[0] == 0x25 && sample[1] == 0x50 && sample[2] == 0x44 && sample[3] == 0x46) {
            return true;
        }

        // GZIP (\x1F\x8B)
        if ((sample[0] & 0xFF) == 0x1F && (sample[1] & 0xFF) == 0x8B) {
            return true;
        }

        // MP4 / MOV (ftyp)
        if (sampleLength >= 8 && sample[4] == 'f' && sample[5] == 't' && sample[6] == 'y' && sample[7] == 'p') {
            return true;
        }

        // Matroska / WebM (\x1A\x45\xDF\xA3)
        if (sampleLength >= 4 && (sample[0] & 0xFF) == 0x1A && (sample[1] & 0xFF) == 0x45 && (sample[2] & 0xFF) == 0xDF && (sample[3] & 0xFF) == 0xA3) {
            return true;
        }

        return false;
    }

    public static boolean detectHtmlMasquerade(String fileName, String contentType, byte[] sample, int sampleLength) {
        if (!isBinaryExtension(fileName)) {
            return false;
        }

        // If actual bytes are provided and match a known binary file magic signature, it is definitely NOT HTML!
        if (sample != null && sampleLength > 0 && hasKnownBinaryMagicBytes(sample, sampleLength)) {
            return false;
        }

        // If sample bytes are provided, inspect them for HTML tags
        if (sample != null && sampleLength > 0) {
            int len = Math.min(sampleLength, Math.min(sample.length, 512));
            String snippet = new String(sample, 0, len, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US).trim();

            if (snippet.startsWith("<!doctype html") ||
                snippet.startsWith("<html") ||
                snippet.contains("<html") ||
                snippet.contains("<head>") ||
                snippet.contains("<title>") ||
                snippet.contains("<body>") ||
                snippet.contains("<script")) {
                return true;
            }
        } else if (contentType != null) {
            // When only Content-Type header is available (e.g. during HEAD or 0-0 metadata probe)
            String lowerType = contentType.toLowerCase(Locale.US);
            if (lowerType.contains("text/html") || lowerType.contains("application/xhtml+xml") || lowerType.contains("text/vnd.wap.wml")) {
                return true;
            }
        }

        return false;
    }
}

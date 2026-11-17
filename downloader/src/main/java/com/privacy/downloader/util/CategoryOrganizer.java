package com.privacy.downloader.util;

import java.io.File;
import java.util.Locale;

public class CategoryOrganizer {

    public enum Category {
        VIDEO("Videos"),
        AUDIO("Music"),
        DOCUMENTS("Documents"),
        ARCHIVES("Archives"),
        IMAGES("Images"),
        OTHER("Other");

        private final String folderName;

        Category(String folderName) {
            this.folderName = folderName;
        }

        public String getFolderName() {
            return folderName;
        }
    }

    public static Category getCategory(String fileName) {
        if (fileName == null) return Category.OTHER;
        String lower = fileName.toLowerCase(Locale.US);

        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
            lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".flv")) {
            return Category.VIDEO;
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") ||
            lower.endsWith(".flac") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".opus")) {
            return Category.AUDIO;
        }
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
            lower.endsWith(".txt") || lower.endsWith(".epub") || lower.endsWith(".xls") ||
            lower.endsWith(".xlsx") || lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return Category.DOCUMENTS;
        }
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
            lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".apk") ||
            lower.endsWith(".iso") || lower.endsWith(".dmg")) {
            return Category.ARCHIVES;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg") || lower.endsWith(".bmp")) {
            return Category.IMAGES;
        }
        return Category.OTHER;
    }

    public static File getDestinationDirectory(File baseDir, String fileName, boolean useSubfolders) {
        if (!useSubfolders || baseDir == null) {
            return baseDir;
        }
        Category cat = getCategory(fileName);
        File subDir = new File(baseDir, cat.getFolderName());
        if (!subDir.exists()) {
            subDir.mkdirs();
        }
        return subDir;
    }
}

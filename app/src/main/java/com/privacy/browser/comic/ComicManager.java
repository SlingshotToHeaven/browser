package com.privacy.browser.comic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks detected comic page streams across active tabs.
 */
public class ComicManager {

    public static class ComicItem {
        public final String tabId;
        public final String title;
        public final List<String> imageUrls;
        public final String referer;

        public ComicItem(String tabId, String title, List<String> imageUrls, String referer) {
            this.tabId = tabId;
            this.title = title;
            this.imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
            this.referer = referer;
        }

        public int getPageCount() {
            return imageUrls.size();
        }
    }

    private static final Map<String, ComicItem> detectedComics = new ConcurrentHashMap<>();

    public static void setDetectedComic(@NonNull String tabId,
                                       @NonNull String title,
                                       @NonNull List<String> imageUrls,
                                       @Nullable String referer) {
        if (imageUrls.isEmpty()) return;
        detectedComics.put(tabId, new ComicItem(tabId, title, imageUrls, referer));
    }

    @Nullable
    public static ComicItem getDetectedComic(@Nullable String tabId) {
        if (tabId == null) return null;
        return detectedComics.get(tabId);
    }

    public static void clearTab(@Nullable String tabId) {
        if (tabId != null) {
            detectedComics.remove(tabId);
        }
    }
}

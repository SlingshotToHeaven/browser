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
    public static final int MAX_PAGES = 150;

    public static class ComicItem {
        public final String tabId;
        public final String title;
        public final List<String> imageUrls;

        public ComicItem(String tabId, String title, List<String> imageUrls) {
            this.tabId = tabId;
            this.title = title;
            this.imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
        }

        public int getPageCount() {
            return imageUrls.size();
        }
    }

    private static final Map<String, ComicItem> detectedComics = new ConcurrentHashMap<>();

    public static boolean setDetectedComic(@NonNull String tabId,
                                       @NonNull String title,
                                       @NonNull List<String> imageUrls) {
        if (imageUrls.isEmpty() || imageUrls.size() > MAX_PAGES) {
            return detectedComics.remove(tabId) != null;
        }
        ComicItem previous = detectedComics.get(tabId);
        if (previous != null && previous.imageUrls.equals(imageUrls)) return false;
        detectedComics.put(tabId, new ComicItem(tabId, title, imageUrls));
        return true;
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

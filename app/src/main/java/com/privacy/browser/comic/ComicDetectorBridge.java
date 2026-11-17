package com.privacy.browser.comic;

import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * JavaScript interface receiving real-time detection of comic pages and sequential galleries.
 */
public class ComicDetectorBridge {

    public interface ComicDetectionListener {
        void onComicDetected(@NonNull String tabId, @NonNull String title, @NonNull List<String> imageUrls);
    }

    private final String tabId;
    private final ComicDetectionListener listener;

    public ComicDetectorBridge(@Nullable String tabId, @Nullable ComicDetectionListener listener) {
        this.tabId = tabId != null ? tabId : "";
        this.listener = listener;
    }

    @JavascriptInterface
    public void onComicDetected(String title, String jsonImages) {
        if (jsonImages == null || jsonImages.isEmpty() || listener == null) return;
        try {
            JSONArray array = new JSONArray(jsonImages);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String u = array.optString(i);
                if (u != null && !u.isEmpty()) {
                    list.add(u);
                }
            }
            if (!list.isEmpty()) {
                String cleanTitle = (title != null && !title.isEmpty()) ? title : "Comic";
                listener.onComicDetected(tabId, cleanTitle, list);
            }
        } catch (Exception ignored) {}
    }
}

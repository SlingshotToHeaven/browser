package com.privacy.browser.history;

public class HistoryModel {
    private final long id;
    private final String title;
    private final String url;
    private final long visitedAt;

    public HistoryModel(long id, String title, String url, long visitedAt) {
        this.id = id;
        this.title = (title != null && !title.trim().isEmpty()) ? title.trim() : url;
        this.url = url;
        this.visitedAt = visitedAt;
    }

    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public long getVisitedAt() {
        return visitedAt;
    }
}

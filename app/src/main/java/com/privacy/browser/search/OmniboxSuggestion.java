package com.privacy.browser.search;

public class OmniboxSuggestion {

    public enum Type {
        BOOKMARK,
        HISTORY,
        BANG,
        SEARCH,
        CLIPBOARD
    }

    private final Type type;
    private final String title;
    private final String subtitle;
    private final String targetUrl;
    private final int iconResId;

    public OmniboxSuggestion(Type type, String title, String subtitle, String targetUrl, int iconResId) {
        this.type = type;
        this.title = title != null ? title : "";
        this.subtitle = subtitle != null ? subtitle : "";
        this.targetUrl = targetUrl != null ? targetUrl : "";
        this.iconResId = iconResId;
    }

    public Type getType() { return type; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getTargetUrl() { return targetUrl; }
    public int getIconResId() { return iconResId; }
}

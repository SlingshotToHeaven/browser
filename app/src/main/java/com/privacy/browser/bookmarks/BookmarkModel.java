package com.privacy.browser.bookmarks;

public class BookmarkModel {
    private final long id;
    private final String title;
    private final String url;
    private final long timestamp;
    private final long parentFolderId;
    private final boolean isFolder;
    private int itemCount;

    public BookmarkModel(long id, String title, String url, long timestamp) {
        this(id, title, url, timestamp, 0, false, 0);
    }

    public BookmarkModel(long id, String title, String url, long timestamp, long parentFolderId, boolean isFolder, int itemCount) {
        this.id = id;
        this.title = (title != null && !title.trim().isEmpty()) ? title.trim() : (url != null ? url : "Folder");
        this.url = url;
        this.timestamp = timestamp;
        this.parentFolderId = parentFolderId;
        this.isFolder = isFolder;
        this.itemCount = itemCount;
    }

    public long id() {
        return id;
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

    public long getTimestamp() {
        return timestamp;
    }

    public long getCreatedAt() {
        return timestamp;
    }

    public long getParentFolderId() {
        return parentFolderId;
    }

    public boolean isFolder() {
        return isFolder;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }
}

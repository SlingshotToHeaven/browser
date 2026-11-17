package com.privacy.downloader.interceptor;

import android.webkit.JavascriptInterface;

public class BlobDownloadBridge {

    public interface BlobListener {
        void onBlobReady(String blobId, long totalSize, String mimeType);
        void onBlobChunk(String blobId, String base64Chunk, long offset, long totalSize);
        void onBlobEnd(String blobId);
        void onBlobFailed(String blobId, String error);
    }

    private final BlobListener listener;

    public BlobDownloadBridge(BlobListener listener) {
        this.listener = listener;
    }

    @JavascriptInterface
    public void onBlobReady(String blobId, long totalSize, String mimeType) {
        if (listener != null) {
            listener.onBlobReady(blobId, totalSize, mimeType);
        }
    }

    @JavascriptInterface
    public void onBlobChunk(String blobId, String base64Chunk, long offset, long totalSize) {
        if (listener != null) {
            listener.onBlobChunk(blobId, base64Chunk, offset, totalSize);
        }
    }

    @JavascriptInterface
    public void onBlobEnd(String blobId) {
        if (listener != null) {
            listener.onBlobEnd(blobId);
        }
    }

    @JavascriptInterface
    public void onBlobFailed(String blobId, String error) {
        if (listener != null) {
            listener.onBlobFailed(blobId, error);
        }
    }
}

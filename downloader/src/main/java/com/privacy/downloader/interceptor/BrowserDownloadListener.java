package com.privacy.downloader.interceptor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.DownloadListener;
import android.widget.Toast;

import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.ui.DownloadPromptDialog;

public class BrowserDownloadListener implements DownloadListener {

    private final Context context;
    private final boolean privateTab;
    private final BlobDownloadManager blobDownloads;

    public BrowserDownloadListener(Context context, boolean privateTab, BlobDownloadManager blobDownloads) {
        this.context = context;
        this.privateTab = privateTab;
        this.blobDownloads = blobDownloads;
    }

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (url == null) return;
        if (privateTab) {
            Toast.makeText(context, RequestContext.PRIVATE_UNAVAILABLE, Toast.LENGTH_LONG).show();
            return;
        }
        if (url.startsWith("blob:")) {
            // WebView delivers its DownloadListener on the view thread. Don't post a
            // deferred lookup of the active tab/document, which may have changed.
            if (blobDownloads != null && Looper.myLooper() == Looper.getMainLooper()) {
                blobDownloads.showPrompt(url, contentDisposition, mimeType, contentLength);
            } else {
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,
                        "Open the blob in its original tab to download it.", Toast.LENGTH_LONG).show());
            }
            return;
        }

        // DownloadListener provides no initiating frame or document. It cannot authorize session cookies.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            DownloadPromptDialog.show(context, url, RequestContext.anonymous(), contentDisposition, mimeType, contentLength);
        }
    }
}

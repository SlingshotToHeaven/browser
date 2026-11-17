package com.privacy.downloader.interceptor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;

import com.privacy.downloader.ui.DownloadPromptDialog;

public class BrowserDownloadListener implements DownloadListener {

    public interface ReferrerProvider {
        String getActiveReferrer();
    }

    public interface WebViewProvider {
        android.webkit.WebView getActiveWebView();
    }

    private final Context context;
    private final ReferrerProvider referrerProvider;
    private final WebViewProvider webViewProvider;

    public BrowserDownloadListener(Context context, ReferrerProvider referrerProvider) {
        this(context, referrerProvider, null);
    }

    public BrowserDownloadListener(Context context, ReferrerProvider referrerProvider, WebViewProvider webViewProvider) {
        this.context = context;
        this.referrerProvider = referrerProvider;
        this.webViewProvider = webViewProvider;
    }

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (url != null && url.startsWith("blob:") && webViewProvider != null) {
            android.webkit.WebView wv = webViewProvider.getActiveWebView();
            if (wv != null) {
                BlobDownloadManager.getInstance(context).prepareBlob(wv, url);
            }
        }

        String cookies = CookieManager.getInstance().getCookie(url);
        String referrer = referrerProvider != null ? referrerProvider.getActiveReferrer() : null;

        new Handler(Looper.getMainLooper()).post(() -> {
            DownloadPromptDialog.show(context, url, userAgent, contentDisposition, mimeType, cookies, referrer, null);
        });
    }
}

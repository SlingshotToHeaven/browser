package com.privacy.browser.adblock.picker;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;

/**
 * JavaScript interface receiving picked element events from the web page.
 */
public class ElementPickerBridge {

    public interface ElementPickerCallback {
        void onElementPicked(@NonNull String nodesJson);
        void onElementZapped();
    }

    private final ElementPickerCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ElementPickerBridge(@NonNull ElementPickerCallback callback) {
        this.callback = callback;
    }

    @JavascriptInterface
    public void onElementPicked(String nodesJson) {
        if (nodesJson == null) return;
        mainHandler.post(() -> callback.onElementPicked(nodesJson));
    }

    @JavascriptInterface
    public void onElementZapped() {
        mainHandler.post(callback::onElementZapped);
    }
}

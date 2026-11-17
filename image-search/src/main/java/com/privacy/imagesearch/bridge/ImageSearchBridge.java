package com.privacy.imagesearch.bridge;

import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;

public class ImageSearchBridge {

    public interface BridgeCallback {
        void onImageSelected(@NonNull String imageUrl);
        void onPickerCancelled();
    }

    private final BridgeCallback callback;

    public ImageSearchBridge(@NonNull BridgeCallback callback) {
        this.callback = callback;
    }

    @JavascriptInterface
    public void onImageSelected(String imageUrl) {
        if (callback != null && imageUrl != null) {
            callback.onImageSelected(imageUrl);
        }
    }

    @JavascriptInterface
    public void onPickerCancelled() {
        if (callback != null) {
            callback.onPickerCancelled();
        }
    }
}

package com.privacy.browser;

import android.view.ViewGroup;
import android.webkit.WebView;

/** Safe for live and renderer-dead WebViews. Owners must clear their references first. */
public final class WebViewDisposal {
    private WebViewDisposal() {}

    public static void detachAndDestroy(WebView view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        view.destroy();
    }
}

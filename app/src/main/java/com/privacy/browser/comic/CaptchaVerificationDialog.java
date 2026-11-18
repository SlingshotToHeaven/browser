package com.privacy.browser.comic;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.R;
import com.privacy.browser.adblock.WebScriptPolicy;
import com.privacy.downloader.net.RequestContext;

/** One manual same-origin verification prompt, owned and cancellable by the comic dialog. */
final class CaptchaVerificationDialog {

    public interface OnVerificationCompleteListener {
        void onVerificationComplete(boolean confirmed);
    }

    // UI thread only. The caller owns dismissal on timeout, cancellation and Activity teardown.
    @Nullable static Dialog show(@NonNull Activity context,
                                 @NonNull String challengeUrl,
                                 @NonNull RequestContext request,
                                 @NonNull OnVerificationCompleteListener listener) {
        if (!request.hasWebSession() || !request.pageOrigin().equals(RequestContext.origin(challengeUrl))
                || context.isFinishing() || context.isDestroyed()) {
            listener.onVerificationComplete(false);
            return null;
        }
        if (!WebScriptPolicy.javaScriptEnabled(context)) {
            Toast.makeText(context, R.string.no_script_verification_unavailable, Toast.LENGTH_LONG).show();
            listener.onVerificationComplete(false);
            return null;
        }

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_captcha_verification, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        FrameLayout container = view.findViewById(R.id.captcha_webview_container);
        WebView webView = new WebView(context);
        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(request.userAgent());
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        boolean[] disposed = {false};
        Runnable dispose = () -> {
            if (disposed[0]) return;
            disposed[0] = true;
            com.privacy.browser.WebViewDisposal.detachAndDestroy(webView);
        };
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest target) {
                return target.isForMainFrame() && !sameOrigin(target.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !sameOrigin(url);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                dispose.run();
                dialog.dismiss();
                return true;
            }

            private boolean sameOrigin(String url) {
                return request.pageOrigin().equals(RequestContext.origin(url));
            }
        });

        boolean[] done = new boolean[1];
        boolean[] reported = {false};
        java.util.function.Consumer<Boolean> finish = confirmed -> {
            if (reported[0]) return;
            reported[0] = true;
            dispose.run();
            listener.onVerificationComplete(confirmed);
        };
        dialog.setOnDismissListener(d -> finish.accept(done[0]));
        ImageButton btnClose = view.findViewById(R.id.btn_close_captcha);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        Button btnDone = view.findViewById(R.id.btn_captcha_done);
        btnDone.setFilterTouchesWhenObscured(true);
        btnDone.setOnClickListener(v -> {
            done[0] = !disposed[0] && request.pageOrigin().equals(RequestContext.origin(webView.getUrl()));
            dialog.dismiss();
        });

        try {
            dialog.show();
            webView.loadUrl(challengeUrl);
            return dialog;
        } catch (RuntimeException unavailable) {
            finish.accept(false);
            dialog.dismiss();
            return null;
        }
    }
}

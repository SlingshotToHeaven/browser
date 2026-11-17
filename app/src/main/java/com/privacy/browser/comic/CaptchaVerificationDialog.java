package com.privacy.browser.comic;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
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

/**
 * Fullscreen challenge verification modal loading anti-bot / captcha challenges,
 * refreshing session cookies, and seamlessly resuming pending comic downloads upon completion.
 */
public class CaptchaVerificationDialog {

    public interface OnVerificationCompleteListener {
        void onVerificationComplete(String updatedCookies);
    }

    public static void show(@NonNull Context context,
                            @NonNull String challengeUrl,
                            @Nullable String userAgent,
                            @NonNull OnVerificationCompleteListener listener) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(context, "anti bot present, complete this verification", Toast.LENGTH_LONG).show();

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
            if (userAgent != null) {
                settings.setUserAgentString(userAgent);
            }

            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

            webView.setWebChromeClient(new WebChromeClient());
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    super.onPageFinished(v, url);
                    CookieManager.getInstance().flush();
                }
            });

            webView.loadUrl(challengeUrl);

            ImageButton btnClose = view.findViewById(R.id.btn_close_captcha);
            btnClose.setOnClickListener(v -> {
                dialog.dismiss();
                webView.destroy();
            });

            Button btnDone = view.findViewById(R.id.btn_captcha_done);
            btnDone.setOnClickListener(v -> {
                CookieManager.getInstance().flush();
                String cookies = CookieManager.getInstance().getCookie(challengeUrl);
                dialog.dismiss();
                webView.destroy();
                listener.onVerificationComplete(cookies);
            });

            dialog.show();
        });
    }
}

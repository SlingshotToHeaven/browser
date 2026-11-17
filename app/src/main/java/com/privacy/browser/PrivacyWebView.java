package com.privacy.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

/**
 * Hardened PrivacyWebView wrapping standard Android WebView.
 * Applies zero-telemetry and isolation defaults.
 */
public class PrivacyWebView extends WebView {

    public interface ToolbarCoordinator {
        boolean isToolbarLocked();
        int getToolbarHeight();
        float onToolbarDrag(float deltaY);
        void onToolbarRelease();
        void cancelToolbarAnimation();
    }

    public interface OnScrollListener {
        void onScroll(PrivacyWebView view, int scrollX, int scrollY, int oldScrollX, int oldScrollY);
    }

    private ToolbarCoordinator toolbarCoordinator;
    private OnScrollListener onScrollListener;

    private float touchDownX = 0f;
    private float touchDownY = 0f;
    private float lastTouchY = 0f;
    private boolean isTrackingTouch = false;
    private boolean isVerticalDrag = false;
    private int touchSlop = 0;

    public void setToolbarCoordinator(ToolbarCoordinator coordinator) {
        this.toolbarCoordinator = coordinator;
    }

    public void setOnScrollListener(OnScrollListener listener) {
        this.onScrollListener = listener;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (onScrollListener != null) {
            onScrollListener.onScroll(this, l, t, oldl, oldt);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getRawX();
                touchDownY = event.getRawY();
                lastTouchY = event.getRawY();
                isTrackingTouch = true;
                isVerticalDrag = false;
                if (toolbarCoordinator != null) {
                    toolbarCoordinator.cancelToolbarAnimation();
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isTrackingTouch) {
                    float currentRawX = event.getRawX();
                    float currentRawY = event.getRawY();
                    float dx = currentRawX - touchDownX;
                    float dy = currentRawY - touchDownY;

                    if (!isVerticalDrag) {
                        float absDx = Math.abs(dx);
                        float absDy = Math.abs(dy);
                        if (absDy > touchSlop && absDy > absDx) {
                            isVerticalDrag = true;
                            lastTouchY = currentRawY;
                        }
                    }

                    if (isVerticalDrag && toolbarCoordinator != null && !toolbarCoordinator.isToolbarLocked()) {
                        float deltaY = lastTouchY - currentRawY; // deltaY > 0: finger moving UP (collapsing)
                        lastTouchY = currentRawY;
                        toolbarCoordinator.onToolbarDrag(deltaY);
                    } else {
                        lastTouchY = currentRawY;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isTrackingTouch) {
                    isTrackingTouch = false;
                    isVerticalDrag = false;
                    if (toolbarCoordinator != null && !toolbarCoordinator.isToolbarLocked()) {
                        toolbarCoordinator.onToolbarRelease();
                    }
                }
                break;
        }
        return super.onTouchEvent(event);
    }

    public PrivacyWebView(@NonNull Context context) {
        super(context);
        initPrivacySettings();
    }

    public PrivacyWebView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPrivacySettings();
    }

    public PrivacyWebView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initPrivacySettings();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initPrivacySettings() {
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        // Enforce Pitch-Black AMOLED Canvas
        setBackgroundColor(Color.BLACK);

        WebSettings settings = getSettings();

        // Core Rendering & Scripting
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // Disallow Local File & Content Access
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        // Strict Geolocation Lockdown
        settings.setGeolocationEnabled(false);

        // Block Insecure Mixed Content
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Viewport & Scaling
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        applyPageZoom();

        // Modern Mobile Chrome User-Agent
        applyCleanMobileUserAgent(settings);

        // Hardware Acceleration Optimization
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Disable Google Safe Browsing Pings (Zero Google Telemetry)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, false);
        }

        // Web Content Appearance & Dark Theme Preference
        ThemeManager.applyWebTheme(this);

        // Strict Cookie Hardening
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(this, false);
    }

    @NonNull
    public static String getCleanMobileUserAgent(@NonNull WebSettings settings) {
        String defaultUa = settings.getUserAgentString();
        if (defaultUa == null || defaultUa.trim().isEmpty()) {
            return "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
        }
        return defaultUa.replace("; wv", "")
                        .replace("; wv;", ";")
                        .replaceAll("Version/\\d+\\.\\d+\\s*", "");
    }

    public static void applyCleanMobileUserAgent(@NonNull WebSettings settings) {
        settings.setUserAgentString(getCleanMobileUserAgent(settings));
    }

    public void applyPageZoom() {
        int zoom = com.privacy.browser.settings.SettingsActivity.getPageZoom(getContext());
        getSettings().setTextZoom(zoom);
    }
}

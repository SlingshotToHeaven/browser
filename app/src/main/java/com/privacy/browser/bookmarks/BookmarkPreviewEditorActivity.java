package com.privacy.browser.bookmarks;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.privacy.browser.R;

public class BookmarkPreviewEditorActivity extends Activity {

    public static final String EXTRA_BOOKMARK_ID = "com.privacy.browser.EXTRA_BOOKMARK_ID";
    public static final String EXTRA_BOOKMARK_URL = "com.privacy.browser.EXTRA_BOOKMARK_URL";
    public static final String EXTRA_BOOKMARK_TITLE = "com.privacy.browser.EXTRA_BOOKMARK_TITLE";

    private static final int COVER_SIZE = 480;

    private long bookmarkId = -1;
    private String bookmarkUrl = "";
    private CropStageContainer stageContainer;
    private FrameLayout webViewWrapper;
    private WebView webView;
    private CropOverlayView cropOverlayView;
    private ProgressBar progressBar;
    private View guideCaption;
    private int viewRevision;
    private boolean isCapturing = false;

    private float exactCardWidth = 0f;
    private float previewScale = 0.5f;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bookmark_preview_editor);

        bookmarkId = getIntent().getLongExtra(EXTRA_BOOKMARK_ID, -1);
        bookmarkUrl = getIntent().getStringExtra(EXTRA_BOOKMARK_URL);
        String title = getIntent().getStringExtra(EXTRA_BOOKMARK_TITLE);

        TextView tvTitle = findViewById(R.id.editor_title);
        if (tvTitle != null && title != null && !title.isEmpty()) {
            tvTitle.setText(title);
        }

        findViewById(R.id.editor_btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.editor_btn_save).setOnClickListener(v -> saveCurrentCropArea());

        progressBar = findViewById(R.id.editor_progress);
        stageContainer = findViewById(R.id.editor_stage_container);
        webViewWrapper = findViewById(R.id.editor_webview_wrapper);
        webView = findViewById(R.id.editor_webview);
        cropOverlayView = findViewById(R.id.editor_crop_overlay);
        guideCaption = findViewById(R.id.editor_guide_caption);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;
        float totalSpacing = 24f * density;
        exactCardWidth = (screenWidth - totalSpacing) / 2.0f;

        if (cropOverlayView != null) {
            cropOverlayView.setCustomSquareSize(exactCardWidth);
        }

        setupScaledWebViewLayout();
        setupWebView();

        if (bookmarkUrl != null && !bookmarkUrl.isEmpty()) {
            webView.loadUrl(bookmarkUrl);
        } else {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupScaledWebViewLayout() {
        if (stageContainer == null || webViewWrapper == null) return;

        stageContainer.bindViews(cropOverlayView, webViewWrapper, webView);

        stageContainer.post(() -> {
            if (webView == null) return;
            int stageWidth = stageContainer.getWidth();
            int stageHeight = stageContainer.getHeight();
            if (stageWidth <= 0 || stageHeight <= 0) return;

            // Render WebView at full device screen width so websites render naturally
            // without narrow column reflows, and scale it down to exactly match the card width.
            previewScale = exactCardWidth / (float) stageWidth;
            stageContainer.setPreviewScale(previewScale);

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) webViewWrapper.getLayoutParams();
            lp.width = stageWidth;
            lp.height = (int) (stageHeight / previewScale);
            webViewWrapper.setLayoutParams(lp);

            // Pivot at top-left
            webViewWrapper.setPivotX(0.0f);
            webViewWrapper.setPivotY(0.0f);
            webViewWrapper.setScaleX(previewScale);
            webViewWrapper.setScaleY(previewScale);

            // Center the scaled webview horizontally so it directly lines up with the centered crop overlay window
            float scaledWidth = stageWidth * previewScale;
            float leftOffset = (stageWidth - scaledWidth) / 2.0f;
            webViewWrapper.setTranslationX(leftOffset);
        });
    }

    private void setupWebView() {
        webView.setBackgroundColor(Color.BLACK);

        // Synchronize cookies & web storage with active browser session
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(com.privacy.browser.adblock.WebScriptPolicy.javaScriptEnabled(this));
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // Hardware Acceleration Optimization
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Match AMOLED dark theme
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (view != webView) return;
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view == webView) view.scrollTo(0, 0);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (view == webView) {
                    disposePreview();
                    Toast.makeText(BookmarkPreviewEditorActivity.this, "Preview stopped. Reopen the preview to try again.", Toast.LENGTH_LONG).show();
                    finish();
                }
                return true;
            }
        });
    }

    private void saveCurrentCropArea() {
        if (isCapturing) return;
        if (webView == null || webView.getWidth() <= 0 || webView.getHeight() <= 0) {
            Toast.makeText(this, "Page not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int stageWidth = stageContainer.getWidth();
            int stageHeight = stageContainer.getHeight();
            if (stageWidth <= 0 || stageHeight <= 0) {
                Toast.makeText(this, "Layout not ready", Toast.LENGTH_SHORT).show();
                return;
            }

            // Obtain the exact on-screen crop rect from the overlay
            RectF cropRect = cropOverlayView != null ? cropOverlayView.getCropRect() : null;
            int cropLeft = 0;
            int cropTop = 0;
            int cropSize = (int) exactCardWidth;

            if (cropRect != null && cropRect.width() > 0 && cropRect.height() > 0) {
                cropLeft = Math.max(0, (int) cropRect.left);
                cropTop = Math.max(0, (int) cropRect.top);
                cropSize = (int) cropRect.width();

            }
            cropSize = Math.min(cropSize, Math.min(stageWidth - cropLeft, stageHeight - cropTop));
            if (cropSize <= 0) {
                restoreEditor("No visible area to capture");
                return;
            }

            final int finalCropLeft = cropLeft;
            final int finalCropTop = cropTop;
            final int finalCropSize = cropSize;

            isCapturing = true;
            int revision = viewRevision;

            // Hide the crop overlay and floating guide caption so borders and L-bends do not appear in the window composited frame
            if (cropOverlayView != null) {
                cropOverlayView.setVisibility(View.INVISIBLE);
            }
            if (guideCaption != null) {
                guideCaption.setVisibility(View.INVISIBLE);
            }

            // Schedule capture on the next frame after the view tree renders without the overlay decorations
            stageContainer.post(() -> stageContainer.postOnAnimation(() -> {
                if (revision != viewRevision || isFinishing()) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    int[] stageLocation = new int[2];
                    stageContainer.getLocationInWindow(stageLocation);
                    int screenCropLeft = stageLocation[0] + finalCropLeft;
                    int screenCropTop = stageLocation[1] + finalCropTop;

                    Rect srcRect = new Rect(screenCropLeft, screenCropTop, screenCropLeft + finalCropSize, screenCropTop + finalCropSize);
                    Window window = getWindow();
                    if (window != null) {
                        Bitmap destBitmap = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888);
                        try {
                            PixelCopy.request(window, srcRect, destBitmap, copyResult -> {
                                if (revision != viewRevision || isFinishing()) { destBitmap.recycle(); return; }
                                if (copyResult == PixelCopy.SUCCESS) {
                                    saveSnapshot(destBitmap);
                                } else {
                                    destBitmap.recycle();
                                    captureWithCanvasFallback(finalCropLeft, finalCropTop, finalCropSize);
                                }
                            }, new Handler(Looper.getMainLooper()));
                            return;
                        } catch (Throwable ignored) {
                            destBitmap.recycle();
                        }
                    }
                }

                // Fallback for older API or off-window states
                captureWithCanvasFallback(finalCropLeft, finalCropTop, finalCropSize);
            }));
        } catch (Throwable t) {
            restoreEditor("Failed to capture preview");
        }
    }

    private void captureWithCanvasFallback(int cropLeft, int cropTop, int cropSize) {
        try {
            // Draw the chosen stage rectangle directly into the bounded cover buffer.
            Bitmap snapshot = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(snapshot);
            canvas.drawColor(Color.BLACK);
            float scale = COVER_SIZE / (float) cropSize;
            canvas.scale(scale, scale);
            canvas.translate(-cropLeft, -cropTop);
            canvas.translate(webViewWrapper.getTranslationX(), webViewWrapper.getTranslationY());
            canvas.scale(webViewWrapper.getScaleX(), webViewWrapper.getScaleY(), webViewWrapper.getPivotX(), webViewWrapper.getPivotY());
            webViewWrapper.draw(canvas);
            saveSnapshot(snapshot);
        } catch (Throwable t) {
            restoreEditor("Failed to capture preview");
        }
    }

    private void saveSnapshot(Bitmap snapshot) {
        BookmarkPreviewManager.getInstance(this).savePreviewBitmap(bookmarkId, snapshot, saved -> {
            snapshot.recycle();
            if (webView == null || isFinishing() || isDestroyed()) return;
            if (saved) {
                Toast.makeText(this, "Preview snapshot saved", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                restoreEditor("Could not save preview");
            }
        });
    }

    private void restoreEditor(String message) {
        isCapturing = false;
        if (cropOverlayView != null) cropOverlayView.setVisibility(View.VISIBLE);
        if (guideCaption != null) guideCaption.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void disposePreview() {
        WebView view = webView;
        webView = null;
        viewRevision++;
        if (view != null) com.privacy.browser.WebViewDisposal.detachAndDestroy(view);
    }

    @Override
    protected void onDestroy() {
        disposePreview();
        super.onDestroy();
    }
}

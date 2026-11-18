package com.privacy.browser.bookmarks;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import com.privacy.browser.images.LocalThumbnailStore;
import com.privacy.browser.images.ThumbnailBudget;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class BookmarkPreviewManager {

    private static volatile BookmarkPreviewManager instance;

    private final LocalThumbnailStore<Long> covers;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BookmarkPreviewManager(Context context) {
        Context app = context.getApplicationContext();
        covers = new LocalThumbnailStore<>(() -> new File(app.getFilesDir(), "bookmark_previews"),
                id -> "bm_" + id + ".webp", ThumbnailBudget.BOOKMARK_COVER, 8 * 1024 * 1024);
    }

    public static BookmarkPreviewManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BookmarkPreviewManager.class) {
                if (instance == null) {
                    instance = new BookmarkPreviewManager(context);
                }
            }
        }
        return instance;
    }

    public Bitmap peekPreview(long bookmarkId) {
        return covers.peek(bookmarkId);
    }

    public void loadPreview(long bookmarkId, Consumer<Bitmap> ready) {
        if (bookmarkId > 0) covers.load(bookmarkId, ready);
        else ready.accept(null);
    }

    public void captureAndSavePreview(long bookmarkId, View webView, BooleanSupplier current) {
        if (!current.getAsBoolean() || webView == null || webView.getWidth() <= 0 || webView.getHeight() <= 0) return;

        try {
            int width = webView.getWidth();
            int height = webView.getHeight();

            // The user requested a square snap (1:1 aspect ratio) starting from the top of the visible scroll position
            int squareDimension = Math.min(width, height);
            int targetSize = 480;

            // Attempt hardware-accelerated PixelCopy to include playing HTML5 video & WebGL layers
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Activity activity = findActivity(webView.getContext());
                if (activity != null && activity.getWindow() != null) {
                    Window window = activity.getWindow();
                    int[] location = new int[2];
                    webView.getLocationInWindow(location);
                    Rect srcRect = new Rect(location[0], location[1], location[0] + squareDimension, location[1] + squareDimension);

                    // PixelCopy scales the source rectangle to the destination bitmap.
                    Bitmap destBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
                    try {
                        PixelCopy.request(window, srcRect, destBitmap, copyResult -> {
                            if (!current.getAsBoolean()) { destBitmap.recycle(); return; }
                            if (copyResult == PixelCopy.SUCCESS) {
                                savePreviewBitmap(bookmarkId, destBitmap, null);
                            } else {
                                destBitmap.recycle();
                                captureWithCanvasFallback(bookmarkId, webView, squareDimension, targetSize);
                            }
                        }, mainHandler);
                        return;
                    } catch (Throwable ignored) {
                        destBitmap.recycle();
                    }
                }
            }

            // Fallback for older API or off-window contexts
            captureWithCanvasFallback(bookmarkId, webView, squareDimension, targetSize);
        } catch (Throwable ignored) {}
    }

    private void captureWithCanvasFallback(long bookmarkId, View webView, int squareDimension, int targetSize) {
        try {
            float scale = (float) targetSize / (float) squareDimension;
            Bitmap bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bitmap);

            canvas.scale(scale, scale);
            canvas.clipRect(0, 0, squareDimension, squareDimension);
            webView.draw(canvas);
            savePreviewBitmap(bookmarkId, bitmap, null);
        } catch (Throwable ignored) {}
    }

    private Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public void savePreviewBitmap(long bookmarkId, Bitmap bitmap, Consumer<Boolean> ready) {
        if (bookmarkId <= 0) {
            if (ready != null) ready.accept(false);
            return;
        }
        Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        covers.save(bookmarkId, bitmap, format, 80, ready);
    }

    public void deletePreview(long bookmarkId, Consumer<Boolean> ready) {
        covers.delete(Collections.singleton(bookmarkId), ready);
    }

    public void deletePreviews(Set<Long> bookmarkIds) {
        covers.delete(bookmarkIds, null);
    }
}

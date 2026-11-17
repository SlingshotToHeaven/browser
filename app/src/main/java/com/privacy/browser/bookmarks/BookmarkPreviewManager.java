package com.privacy.browser.bookmarks;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookmarkPreviewManager {

    private static volatile BookmarkPreviewManager instance;

    private final Context appContext;
    private final File previewDir;
    private final LruCache<Long, Bitmap> memoryCache;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface PreviewCallback {
        void onPreviewLoaded(long bookmarkId, Bitmap bitmap);
    }

    private BookmarkPreviewManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.previewDir = new File(appContext.getFilesDir(), "bookmark_previews");
        if (!previewDir.exists()) {
            previewDir.mkdirs();
        }

        // Cache up to 30 thumbnails in memory (each 480x300 bitmap is ~400 KB uncompressed)
        this.memoryCache = new LruCache<Long, Bitmap>(30) {
            @Override
            protected int sizeOf(Long key, Bitmap bitmap) {
                return 1;
            }
        };
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

    public Bitmap getCachedPreview(long bookmarkId) {
        synchronized (memoryCache) {
            Bitmap mem = memoryCache.get(bookmarkId);
            if (mem != null) return mem;
        }

        File file = getPreviewFile(bookmarkId);
        if (file.exists()) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bmp != null) {
                    synchronized (memoryCache) {
                        memoryCache.put(bookmarkId, bmp);
                    }
                    return bmp;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public void loadPreviewAsync(long bookmarkId, PreviewCallback callback) {
        Bitmap cached = getCachedPreview(bookmarkId);
        if (cached != null) {
            if (callback != null) {
                callback.onPreviewLoaded(bookmarkId, cached);
            }
            return;
        }

        ioExecutor.execute(() -> {
            File file = getPreviewFile(bookmarkId);
            if (file.exists()) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bmp != null) {
                        synchronized (memoryCache) {
                            memoryCache.put(bookmarkId, bmp);
                        }
                        if (callback != null) {
                            mainHandler.post(() -> callback.onPreviewLoaded(bookmarkId, bmp));
                        }
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    public void captureAndSavePreview(long bookmarkId, View webView) {
        if (webView == null || webView.getWidth() <= 0 || webView.getHeight() <= 0) return;

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

                    Bitmap destBitmap = Bitmap.createBitmap(squareDimension, squareDimension, Bitmap.Config.ARGB_8888);
                    try {
                        PixelCopy.request(window, srcRect, destBitmap, copyResult -> {
                            if (copyResult == PixelCopy.SUCCESS) {
                                Bitmap scaled = Bitmap.createScaledBitmap(destBitmap, targetSize, targetSize, true);
                                if (destBitmap != scaled) {
                                    destBitmap.recycle();
                                }
                                savePreviewBitmap(bookmarkId, scaled);
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

            try {
                webView.setDrawingCacheEnabled(true);
                Bitmap cache = webView.getDrawingCache();
                if (cache != null && !cache.isRecycled()) {
                    Bitmap cropped = Bitmap.createBitmap(cache, 0, 0, squareDimension, squareDimension);
                    Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true);
                    savePreviewBitmap(bookmarkId, scaled);
                    webView.setDrawingCacheEnabled(false);
                    return;
                }
                webView.setDrawingCacheEnabled(false);
            } catch (Throwable ignored) {}

            webView.draw(canvas);
            savePreviewBitmap(bookmarkId, bitmap);
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

    public void savePreviewBitmap(long bookmarkId, Bitmap bitmap) {
        if (bitmap == null) return;

        synchronized (memoryCache) {
            memoryCache.put(bookmarkId, bitmap);
        }

        ioExecutor.execute(() -> {
            File file = getPreviewFile(bookmarkId);
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out);
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out);
                }
            } catch (Exception ignored) {}
        });
    }

    public void deletePreview(long bookmarkId) {
        synchronized (memoryCache) {
            memoryCache.remove(bookmarkId);
        }
        ioExecutor.execute(() -> {
            File file = getPreviewFile(bookmarkId);
            if (file.exists()) {
                file.delete();
            }
        });
    }

    public File getPreviewFile(long bookmarkId) {
        return new File(previewDir, "bm_" + bookmarkId + ".webp");
    }
}

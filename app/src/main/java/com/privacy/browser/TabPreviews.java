package com.privacy.browser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.function.BooleanSupplier;

/** In-memory snapshots of settled, on-screen regular tabs. Opening the grid is never a capture trigger. */
final class TabPreviews {
    private static final int EDGE = 192;
    private static final int COPY_BYTES = EDGE * EDGE * 4;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long SETTLE_MS = 750;

    private static final class Request {
        final String id;
        final PrivacyWebView view;
        final TabNavigation.Page page;
        final Window window;
        final BooleanSupplier current;

        Request(String id, PrivacyWebView view, TabNavigation.Page page, Window window, BooleanSupplier current) {
            this.id = id;
            this.view = view;
            this.page = page;
            this.window = window;
            this.current = current;
        }

        boolean canCopy() {
            return current.getAsBoolean() && view.isShown() && view.isAttachedToWindow()
                    && (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0;
        }
    }

    // Reserve one destination at all times so a native copy cannot overshoot the cache budget.
    private final TabPreviewCache<String, Bitmap> cache = new TabPreviewCache<>(MAX_BYTES - COPY_BYTES);
    private final TabPreviewRequests<Request> work = new TabPreviewRequests<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable capture = this::capture;
    private int copyingBytes;

    void schedule(TabModel tab, PrivacyWebView view, TabNavigation.Page page, Window window, BooleanSupplier current) {
        if (tab.isIncognito() || view == null || page == null || cache.get(tab.getId()) != null) return;
        Request request = work.wanted();
        if (request != null && request.view == view && request.page == page) return;
        work.request(new Request(tab.getId(), view, page, window, current));
        deferCapture();
    }

    private void deferCapture() {
        main.removeCallbacks(capture);
        main.postDelayed(capture, SETTLE_MS);
    }

    void cancel() {
        main.removeCallbacks(capture);
        work.cancel();
    }

    void cancel(TabModel tab) {
        Request request = work.wanted();
        if (request != null && request.id.equals(tab.getId())) cancel();
    }

    boolean remove(TabModel tab) {
        cancel(tab);
        if (!cache.remove(tab.getId())) return false;
        traceBytes();
        return true;
    }

    void clear() {
        cancel();
        cache.clear();
        traceBytes();
    }

    private void capture() {
        Object ticket = work.begin();
        if (ticket == null) return;
        Request request = work.wanted();
        PrivacyWebView view = request.view;
        Window window = request.window;
        Rect crop = new Rect();
        if (!request.canCopy() || !view.isHardwareAccelerated() || !view.getGlobalVisibleRect(crop)) {
            finish(ticket, null, false);
            return;
        }
        int[] point = new int[2];
        View decor = window.getDecorView();
        decor.getLocationInWindow(point);
        crop.offset(point[0], point[1]);
        if (!crop.intersect(0, 0, decor.getWidth(), decor.getHeight()) || crop.width() < 2 || crop.height() < 2) {
            finish(ticket, null, false);
            return;
        }
        double scale = Math.min(1, (double) EDGE / Math.max(crop.width(), crop.height()));
        final Bitmap target;
        try {
            target = Bitmap.createBitmap(Math.max(1, (int) (crop.width() * scale)),
                    Math.max(1, (int) (crop.height() * scale)), Bitmap.Config.ARGB_8888);
        } catch (IllegalArgumentException | OutOfMemoryError unavailable) {
            finish(ticket, null, false);
            return;
        }
        int allocated = target.getAllocationByteCount();
        if (allocated < 1 || allocated > COPY_BYTES) {
            finish(ticket, target, false);
            return;
        }
        copyingBytes = allocated;
        traceBytes();
        Trace.beginSection("Browser.tabs.previewCopy");
        try {
            PixelCopy.request(window, crop, target, result -> finish(ticket, target, result == PixelCopy.SUCCESS), main);
        } catch (RuntimeException unavailable) {
            finish(ticket, target, false); // placeholder, never a synchronous WebView.draw fallback
        } finally { Trace.endSection(); }
    }

    private void finish(Object ticket, Bitmap target, boolean copied) {
        if (!work.isCopying(ticket)) return;
        Request request = work.complete(ticket);
        copyingBytes = 0;
        if (target != null && !(request != null && copied && request.canCopy()
                && cache.put(request.id, target, target.getAllocationByteCount()))) {
            target.recycle(); // never displayed
        }
        traceBytes();
        if (work.wanted() != null) deferCapture();
    }

    int retainedBytes() { return cache.bytes() + copyingBytes; }

    private void traceBytes() {
        if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) Trace.setCounter("Browser.tabs.previewBytes", retainedBytes());
    }

    /** A card keeps only a key, not a second strong reference to an evicted Bitmap. */
    Drawable drawable(TabModel tab) {
        String id = tab.getId();
        if (tab.isIncognito() || cache.get(id) == null) return null;
        return new Drawable() {
            private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            private final Rect source = new Rect();

            @Override public void draw(Canvas canvas) {
                Bitmap image = cache.get(id);
                Rect bounds = getBounds();
                if (image == null || bounds.isEmpty()) return;
                int width = image.getWidth(), height = image.getHeight();
                float aspect = (float) bounds.width() / bounds.height();
                int croppedW = Math.max(1, Math.min(width, (int) (height * aspect)));
                int croppedH = Math.max(1, Math.min(height, (int) (width / aspect)));
                int x = (width - croppedW) / 2, y = (height - croppedH) / 2;
                source.set(x, y, x + croppedW, y + croppedH);
                canvas.drawBitmap(image, source, bounds, paint);
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}

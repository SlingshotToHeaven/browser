package com.privacy.browser.favicon;

import android.content.Context;
import android.graphics.Bitmap;

import com.privacy.browser.InternalHome;
import com.privacy.browser.images.LocalThumbnailStore;
import com.privacy.browser.images.ThumbnailBudget;
import com.privacy.downloader.net.RequestContext;

import java.io.File;
import java.net.URI;
import java.util.Locale;
import java.util.function.Consumer;

/** Cache only icons delivered by the regular tab's WebView. A miss stays a local placeholder. */
public class FaviconManager {

    private static volatile FaviconManager instance;

    private static final int MAX_ICON_EDGE = 96;
    private final LocalThumbnailStore<String> icons;

    private FaviconManager(Context context) {
        Context app = context.getApplicationContext();
        icons = new LocalThumbnailStore<>(() -> new File(app.getCacheDir(), "favicons"),
                domain -> domain.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png",
                ThumbnailBudget.FAVICON, 1024 * 1024);
    }

    public static FaviconManager getInstance(Context context) {
        if (instance == null) {
            synchronized (FaviconManager.class) {
                if (instance == null) {
                    instance = new FaviconManager(context);
                }
            }
        }
        return instance;
    }

    public static String extractDomain(String url) {
        if (RequestContext.origin(url) == null || InternalHome.ownsHost(url)) return "";
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        return host;
    }

    public void loadCachedFavicon(String domain, Consumer<Bitmap> ready) {
        if (domain != null && !domain.isEmpty()) icons.load(domain, ready);
        else ready.accept(null);
    }

    public void saveFavicon(String domain, Bitmap bitmap) {
        if (domain == null || domain.isEmpty() || bitmap == null || bitmap.isRecycled()) return;
        int largest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        Bitmap icon = largest <= MAX_ICON_EDGE ? bitmap : Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * (float) MAX_ICON_EDGE / largest)),
                Math.max(1, Math.round(bitmap.getHeight() * (float) MAX_ICON_EDGE / largest)), true);
        icons.save(domain, icon, Bitmap.CompressFormat.PNG, 100, null);
    }
}

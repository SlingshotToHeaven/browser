package com.privacy.browser.favicon;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaviconManager {

    private static volatile FaviconManager instance;

    private final Context appContext;
    private final File cacheDir;
    private final LruCache<String, Bitmap> memoryCache;
    private final Set<String> failedDomains = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> activeFetches = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface FaviconCallback {
        void onFaviconLoaded(String domain, Bitmap bitmap);
    }

    private FaviconManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.cacheDir = new File(appContext.getCacheDir(), "favicons");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        // Cache up to 100 favicons in memory (each 16-48px bitmap is ~2-9 KB)
        this.memoryCache = new LruCache<String, Bitmap>(100) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return 1;
            }
        };
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
        if (url == null || url.trim().isEmpty()) return "";
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                return host.toLowerCase();
            }
        } catch (Exception ignored) {}

        String clean = url.replaceFirst("^https?://(www\\.)?", "");
        int slash = clean.indexOf('/');
        if (slash != -1) {
            clean = clean.substring(0, slash);
        }
        return clean.toLowerCase();
    }

    public Bitmap getCachedFavicon(String domain) {
        if (domain == null || domain.isEmpty()) return null;
        synchronized (memoryCache) {
            Bitmap mem = memoryCache.get(domain);
            if (mem != null) return mem;
        }

        File file = getCacheFile(domain);
        if (file.exists()) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bmp != null) {
                    synchronized (memoryCache) {
                        memoryCache.put(domain, bmp);
                    }
                    return bmp;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public void saveFavicon(String domain, Bitmap bitmap) {
        if (domain == null || domain.isEmpty() || bitmap == null) return;
        synchronized (memoryCache) {
            memoryCache.put(domain, bitmap);
        }
        failedDomains.remove(domain);

        fetchExecutor.execute(() -> {
            File file = getCacheFile(domain);
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {}
        });
    }

    public void loadFaviconAsync(String url, FaviconCallback callback) {
        final String domain = extractDomain(url);
        if (domain.isEmpty() || domain.startsWith("android_asset")) {
            return;
        }

        Bitmap cached = getCachedFavicon(domain);
        if (cached != null) {
            if (callback != null) {
                callback.onFaviconLoaded(domain, cached);
            }
            return;
        }

        if (failedDomains.contains(domain) || activeFetches.contains(domain)) {
            return;
        }

        activeFetches.add(domain);
        fetchExecutor.execute(() -> {
            Bitmap fetched = fetchFromNetwork(domain);
            activeFetches.remove(domain);
            if (fetched != null) {
                saveFavicon(domain, fetched);
                if (callback != null) {
                    mainHandler.post(() -> callback.onFaviconLoaded(domain, fetched));
                }
            } else {
                failedDomains.add(domain);
            }
        });
    }

    private Bitmap fetchFromNetwork(String domain) {
        // High-reliability Google favicon service (1 request per domain, permanently cached locally)
        String iconUrl = "https://www.google.com/s2/favicons?domain=" + domain + "&sz=64";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(iconUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(in);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private File getCacheFile(String domain) {
        // Sanitize file name
        String safeName = domain.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png";
        return new File(cacheDir, safeName);
    }
}

package com.privacy.browser.comic;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * High-performance sequential comic and gallery archiver. Downloads sequential images,
 * negotiates anti-bot/captcha pauses, and packages contents into sorted .cbr/.cbz archives.
 */
public class ComicArchiver {

    private static final String TAG = "ComicArchiver";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface ArchiveProgressListener {
        void onProgress(int current, int total);
        void onCompleted(@NonNull File archiveFile);
        void onError(@NonNull String errorMessage);
    }

    public static void buildArchive(@NonNull Context context,
                                    @NonNull String title,
                                    @NonNull List<String> imageUrls,
                                    @Nullable String referer,
                                    @Nullable String userAgent,
                                    @NonNull String formatExtension,
                                    @NonNull ArchiveProgressListener listener) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final String sanitizedTitle = sanitizeFilename(title);

        EXECUTOR.execute(() -> {
            File tempDir = new File(context.getCacheDir(), "comic_" + System.currentTimeMillis());
            if (!tempDir.mkdirs() && !tempDir.exists()) {
                mainHandler.post(() -> listener.onError("Failed to allocate temporary workspace directory."));
                return;
            }

            String activeCookies = CookieManager.getInstance().getCookie(referer != null ? referer : imageUrls.get(0));
            int total = imageUrls.size();
            List<File> downloadedFiles = new ArrayList<>();

            try {
                for (int i = 0; i < total; i++) {
                    String pageUrl = imageUrls.get(i);
                    File pageFile = new File(tempDir, String.format(Locale.US, "page_%04d.jpg", i + 1));

                    boolean success = downloadPageWithCaptchaHandling(
                            context, pageUrl, pageFile, referer, userAgent, activeCookies, mainHandler);

                    if (!success) {
                        Log.w(TAG, "Failed downloading page " + (i + 1) + ": " + pageUrl);
                    } else {
                        downloadedFiles.add(pageFile);
                    }

                    final int currentProgress = i + 1;
                    mainHandler.post(() -> listener.onProgress(currentProgress, total));
                }

                if (downloadedFiles.isEmpty()) {
                    cleanup(tempDir);
                    mainHandler.post(() -> listener.onError("No pages could be retrieved."));
                    return;
                }

                // Package into .cbr / .cbz archive
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) {
                    downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                }
                if (downloadsDir == null) {
                    downloadsDir = context.getFilesDir();
                }

                String ext = formatExtension.startsWith(".") ? formatExtension : "." + formatExtension;
                File targetArchive = getUniqueFile(downloadsDir, sanitizedTitle, ext);

                zipFiles(downloadedFiles, targetArchive);

                // Notify Android MediaScanner
                MediaScannerConnection.scanFile(
                        context,
                        new String[]{targetArchive.getAbsolutePath()},
                        new String[]{"application/x-cbr"},
                        null
                );

                cleanup(tempDir);
                mainHandler.post(() -> listener.onCompleted(targetArchive));

            } catch (Exception e) {
                Log.e(TAG, "Error packaging comic archive", e);
                cleanup(tempDir);
                mainHandler.post(() -> listener.onError(e.getMessage() != null ? e.getMessage() : "Archiving error"));
            }
        });
    }

    private static boolean downloadPageWithCaptchaHandling(Context context,
                                                           String pageUrl,
                                                           File outputFile,
                                                           String referer,
                                                           String userAgent,
                                                           String initialCookies,
                                                           Handler mainHandler) {
        String cookies = initialCookies;
        int maxAttempts = 3;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(pageUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", userAgent != null ? userAgent : "Mozilla/5.0 (Android; Mobile)");
                conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
                if (referer != null) {
                    conn.setRequestProperty("Referer", referer);
                }
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }

                int responseCode = conn.getResponseCode();

                // Check for anti-bot / challenge triggers (403, 429, 503)
                if (responseCode == 403 || responseCode == 429 || responseCode == 503) {
                    Log.w(TAG, "Anti-bot challenge HTTP " + responseCode + " encountered on: " + pageUrl);
                    String updated = promptCaptchaAndWait(context, pageUrl, userAgent);
                    if (updated != null) {
                        cookies = updated;
                    }
                    continue;
                }

                if (responseCode >= 200 && responseCode < 300) {
                    String contentType = conn.getContentType();
                    // If HTML page returned, handle verification challenge or extract full-res comic image
                    if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (InputStream is = conn.getInputStream()) {
                            byte[] buf = new byte[8192];
                            int read;
                            while ((read = is.read(buf)) != -1) {
                                baos.write(buf, 0, read);
                                if (baos.size() > 512 * 1024) break; // Read sufficient HTML for reader parsing
                            }
                        }
                        String htmlBody = baos.toString("UTF-8");
                        String bodySnippet = htmlBody.toLowerCase(Locale.US);
                        if (bodySnippet.contains("turnstile") || bodySnippet.contains("challenge")
                                || bodySnippet.contains("captcha") || bodySnippet.contains("just a moment")) {
                            Log.w(TAG, "Inline anti-bot challenge page detected for: " + pageUrl);
                            String updated = promptCaptchaAndWait(context, pageUrl, userAgent);
                            if (updated != null) {
                                cookies = updated;
                            }
                            continue;
                        }

                        // Parse the reader HTML to extract the actual full-resolution comic image URL
                        String resolvedImageUrl = extractComicImageFromHtml(htmlBody, pageUrl);
                        if (resolvedImageUrl != null) {
                            Log.d(TAG, "Resolved full-res comic image from reader page: " + resolvedImageUrl);
                            boolean downloaded = downloadDirectImage(resolvedImageUrl, outputFile, pageUrl, userAgent, cookies);
                            if (downloaded) {
                                return true;
                            }
                        }
                        Log.w(TAG, "Could not resolve comic image from reader HTML for: " + pageUrl);
                        return false;
                    }

                    // Direct image binary stream: write image to disk
                    try (InputStream is = new BufferedInputStream(conn.getInputStream());
                         FileOutputStream fos = new FileOutputStream(outputFile);
                         BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            bos.write(buffer, 0, bytesRead);
                        }
                        bos.flush();
                    }
                    return true;
                }

            } catch (Exception e) {
                Log.w(TAG, "Attempt " + attempt + " failed for: " + pageUrl + " (" + e.getMessage() + ")");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }

    private static boolean downloadDirectImage(String imageUrl,
                                               File outputFile,
                                               String referer,
                                               String userAgent,
                                               String cookies) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(imageUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", userAgent != null ? userAgent : "Mozilla/5.0 (Android; Mobile)");
            conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
            }
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream is = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(outputFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        bos.write(buffer, 0, bytesRead);
                    }
                    bos.flush();
                }
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed downloading direct comic image: " + imageUrl + " (" + e.getMessage() + ")");
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private static String extractComicImageFromHtml(String html, String pageUrl) {
        if (html == null || html.isEmpty()) return null;

        // 1. Try JavaScript variables holding image URLs
        Pattern jsPattern = Pattern.compile(
                "(?:currImage|imgUrl|page_image|chapter_image|comic_image|image_url|img_src|src)\\s*=\\s*[\"'](https?:[^\"']+\\.(?:jpe?g|png|webp|avif|gif)[^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE
        );
        Matcher jsMatcher = jsPattern.matcher(html);
        if (jsMatcher.find()) {
            return jsMatcher.group(1);
        }

        // 2. Try reader container or specific comic img tags
        Pattern imgTagPattern = Pattern.compile(
                "<img[^>]+(?:id|class)=[\"'][^\"']*(?:comic-image|reader-image|comic-page|main-image|manga-image|chapter-img|wp-manga)[^\"']*[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE
        );
        Matcher imgMatcher = imgTagPattern.matcher(html);
        if (imgMatcher.find()) {
            String imgTag = imgMatcher.group(0);
            String url = extractBestImageSrcFromTag(imgTag, pageUrl);
            if (url != null) return url;
        }

        // 3. Try container: readerarea, viewer, reading-content, etc.
        Pattern containerPattern = Pattern.compile(
                "<div[^>]+(?:id|class)=[\"'][^\"']*(?:readerarea|viewer|reading-content|comic-wrap|manga-read)[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>",
                Pattern.CASE_INSENSITIVE
        );
        Matcher cMatcher = containerPattern.matcher(html);
        if (cMatcher.find()) {
            String containerHtml = cMatcher.group(1);
            Matcher cImgMatcher = Pattern.compile("<img[^>]+>", Pattern.CASE_INSENSITIVE).matcher(containerHtml);
            if (cImgMatcher.find()) {
                String url = extractBestImageSrcFromTag(cImgMatcher.group(0), pageUrl);
                if (url != null) return url;
            }
        }

        // 4. Fallback: Find all <img> tags and pick the one that looks like a comic page (not icon/logo/banner)
        Matcher anyImgMatcher = Pattern.compile("<img[^>]+>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (anyImgMatcher.find()) {
            String tag = anyImgMatcher.group(0);
            String url = extractBestImageSrcFromTag(tag, pageUrl);
            if (url != null) {
                String lower = url.toLowerCase(Locale.US);
                if (!lower.contains("logo") && !lower.contains("icon") && !lower.contains("avatar")
                        && !lower.contains("banner") && !lower.contains("advert")) {
                    return url;
                }
            }
        }

        return null;
    }

    private static String extractBestImageSrcFromTag(String imgTag, String baseUrl) {
        String[] attrs = new String[]{
                "data-src", "data-original", "data-full-res", "data-highres",
                "data-zoom-src", "data-url", "data-lazy-src", "src"
        };
        for (String attr : attrs) {
            Pattern p = Pattern.compile(attr + "=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(imgTag);
            if (m.find()) {
                String candidate = m.group(1).trim();
                if (candidate.length() > 5 && !candidate.startsWith("data:") && !candidate.startsWith("javascript:")) {
                    try {
                        return new URL(new URL(baseUrl), candidate).toString();
                    } catch (Exception e) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static String promptCaptchaAndWait(Context context, String challengeUrl, String userAgent) {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] resolvedCookies = new String[1];

        CaptchaVerificationDialog.show(context, challengeUrl, userAgent, cookies -> {
            resolvedCookies[0] = cookies;
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return resolvedCookies[0];
    }

    private static void zipFiles(List<File> files, File zipFile) throws Exception {
        files.sort(Comparator.comparing(File::getName));
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            byte[] buffer = new byte[8192];
            for (int i = 0; i < files.size(); i++) {
                File file = files.get(i);
                String entryName = String.format(Locale.US, "page_%04d.jpg", i + 1);
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);

                try (FileInputStream fis = new FileInputStream(file);
                     BufferedInputStream bis = new BufferedInputStream(fis)) {
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                }
                zos.closeEntry();
            }
            zos.finish();
        }
    }

    private static File getUniqueFile(File dir, String baseName, String extension) {
        File file = new File(dir, baseName + extension);
        int counter = 1;
        while (file.exists()) {
            file = new File(dir, baseName + " (" + counter + ")" + extension);
            counter++;
        }
        return file;
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) return "Comic";
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        clean = clean.replaceAll("\\s+", " ");
        if (clean.length() > 64) {
            clean = clean.substring(0, 64).trim();
        }
        return clean.isEmpty() ? "Comic" : clean;
    }

    private static void cleanup(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }
}

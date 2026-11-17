package com.privacy.browser.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public class AgentAttachmentManager {

    private static final String TAG = "AgentAttachmentManager";
    private static final String ATTACHMENTS_DIR = "agent_images";

    private static final int MAX_MEMORY = (int) (Runtime.getRuntime().maxMemory() / 1024);
    private static final int CACHE_SIZE_KB = Math.max(4096, Math.min(32768, MAX_MEMORY / 16));
    private static final LruCache<String, Bitmap> BITMAP_CACHE = new LruCache<String, Bitmap>(CACHE_SIZE_KB) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap != null ? (bitmap.getByteCount() / 1024) : 0;
        }
    };

    public static Bitmap getCachedBitmap(String path) {
        if (path == null) return null;
        Bitmap cached = BITMAP_CACHE.get(path);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        return null;
    }

    public static void putCachedBitmap(String path, Bitmap bitmap) {
        if (path != null && bitmap != null && !bitmap.isRecycled()) {
            BITMAP_CACHE.put(path, bitmap);
        }
    }

    public static File getAttachmentsDir(Context context) {
        File dir = new File(context.getFilesDir(), ATTACHMENTS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String saveImageBytes(Context context, byte[] imageBytes, String extension) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        File dir = getAttachmentsDir(context);
        String ext = (extension != null && !extension.isEmpty()) ? extension : "webp";
        String fileName = "img_" + UUID.randomUUID().toString() + "." + ext;
        File dest = new File(dir, fileName);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(dest);
            fos.write(imageBytes);
            fos.flush();
            return dest.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save attachment file", e);
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String saveBase64Webp(Context context, String base64Webp) {
        return saveBase64Image(context, base64Webp, "webp");
    }

    public static String saveBase64Jpeg(Context context, String base64Jpeg) {
        return saveBase64Image(context, base64Jpeg, "jpg");
    }

    public static String saveBase64Image(Context context, String base64Image, String extension) {
        if (base64Image == null || base64Image.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(base64Image, Base64.DEFAULT);
            return saveImageBytes(context, bytes, extension);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode base64 to save file", e);
            return null;
        }
    }

    public static String getMimeTypeFromPath(String path) {
        if (path != null && path.toLowerCase().endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    public static String getBase64FromPath(String path) {
        if (path == null) return null;
        File file = new File(path);
        if (!file.exists()) return null;

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read attachment for base64", e);
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static Bitmap loadSampledBitmap(String path, int reqWidth, int reqHeight) {
        if (path == null) return null;
        Bitmap cached = getCachedBitmap(path);
        if (cached != null) {
            return cached;
        }

        File file = new File(path);
        if (!file.exists()) return null;

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            Bitmap decoded = BitmapFactory.decodeFile(path, options);
            if (decoded != null) {
                putCachedBitmap(path, decoded);
            }
            return decoded;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to decode sampled bitmap", t);
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static void deleteAttachment(String path) {
        if (path == null) return;
        BITMAP_CACHE.remove(path);
        try {
            File file = new File(path);
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete attachment: " + path, e);
        }
    }

    public static void cleanOrphanedAttachments(Context context, Set<String> validPaths) {
        try {
            File dir = getAttachmentsDir(context);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && (validPaths == null || !validPaths.contains(f.getAbsolutePath()))) {
                        BITMAP_CACHE.remove(f.getAbsolutePath());
                        f.delete();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to clean orphaned attachments", e);
        }
    }
}

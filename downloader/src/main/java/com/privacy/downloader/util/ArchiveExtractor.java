package com.privacy.downloader.util;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ArchiveExtractor {

    private static final String TAG = "ArchiveExtractor";

    public interface ExtractionCallback {
        void onProgress(String currentFile, int extractedCount);
        void onCompleted(File destDir, int totalFiles);
        void onError(String message);
    }

    public static void extractZipAsync(File zipFile, File outputDir, ExtractionCallback callback) {
        new Thread(() -> {
            try {
                if (!zipFile.exists()) {
                    if (callback != null) callback.onError("Archive file not found");
                    return;
                }

                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }

                String canonicalDestDirPath = outputDir.getCanonicalPath();
                int count = 0;

                try (InputStream is = new FileInputStream(zipFile);
                     ZipInputStream zis = new ZipInputStream(is)) {

                    ZipEntry entry;
                    byte[] buffer = new byte[16384];

                    while ((entry = zis.getNextEntry()) != null) {
                        File newFile = new File(outputDir, entry.getName());
                        String canonicalFilePath = newFile.getCanonicalPath();

                        // Guard against Zip Slip vulnerability (path traversal attack)
                        if (!canonicalFilePath.startsWith(canonicalDestDirPath + File.separator) &&
                            !canonicalFilePath.equals(canonicalDestDirPath)) {
                            logW("Zip Slip attempt detected: " + entry.getName());
                            continue;
                        }

                        if (entry.isDirectory()) {
                            newFile.mkdirs();
                        } else {
                            File parent = newFile.getParentFile();
                            if (parent != null && !parent.exists()) {
                                parent.mkdirs();
                            }
                            try (FileOutputStream fos = new FileOutputStream(newFile)) {
                                int len;
                                while ((len = zis.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                }
                            }
                            count++;
                            if (callback != null) {
                                callback.onProgress(entry.getName(), count);
                            }
                        }
                        zis.closeEntry();
                    }
                }

                if (callback != null) {
                    callback.onCompleted(outputDir, count);
                }

            } catch (Exception e) {
                logE("Extraction failed: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    private static void logW(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (Throwable t) {
            System.err.println("[" + TAG + "] " + msg);
        }
    }

    private static void logE(String msg, Throwable e) {
        try {
            Log.e(TAG, msg, e);
        } catch (Throwable t) {
            System.err.println("[" + TAG + "] " + msg);
        }
    }
}

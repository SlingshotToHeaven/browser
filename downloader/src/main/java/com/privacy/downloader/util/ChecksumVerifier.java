package com.privacy.downloader.util;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public class ChecksumVerifier {

    public interface ChecksumCallback {
        void onCalculated(String sha256Hex);
        void onError(String message);
    }

    public static void computeSha256Async(File file, ChecksumCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                if (!file.exists()) {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError("File does not exist");
                    });
                    return;
                }

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536];

                try (FileInputStream fis = new FileInputStream(file)) {
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        digest.update(buffer, 0, bytesRead);
                    }
                }

                byte[] hashBytes = digest.digest();
                StringBuilder sb = new StringBuilder();
                for (byte b : hashBytes) {
                    sb.append(String.format("%02x", b));
                }

                String hexResult = sb.toString();
                mainHandler.post(() -> {
                    if (callback != null) callback.onCalculated(hexResult);
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            }
        }).start();
    }
}

package com.privacy.browser.images;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** Encoded-byte, header and display-object limits for app-owned thumbnails. */
public enum ThumbnailBudget {
    BOOKMARK_COVER(2 * 1024 * 1024, 4096, 4_194_304, 480),
    FAVICON(512 * 1024, 512, 262_144, 96);

    final int maxEncodedBytes;
    private final int maxSourceEdge;
    private final long maxSourcePixels;
    final int displayEdge;

    ThumbnailBudget(int maxEncodedBytes, int maxSourceEdge, long maxSourcePixels, int displayEdge) {
        this.maxEncodedBytes = maxEncodedBytes;
        this.maxSourceEdge = maxSourceEdge;
        this.maxSourcePixels = maxSourcePixels;
        this.displayEdge = displayEdge;
    }

    /** Read a single bounded snapshot: inspect and decode the same bytes, even during a replacement. */
    byte[] read(File file) throws IOException {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        long length = Files.size(file.toPath());
        if (length <= 0 || length > maxEncodedBytes) return null;
        byte[] bytes = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read <= 0) return null;
                offset += read;
            }
            return in.read() == -1 ? bytes : null; // Refuse a file that grew rather than allocating again.
        }
    }

    /** Zero means refuse the header; otherwise return an Android power-of-two sample size. */
    int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > maxSourceEdge || height > maxSourceEdge
                || (long) width * height > maxSourcePixels) return 0;
        int sample = 1;
        while (Math.max(width, height) > displayEdge * sample) sample *= 2;
        return sample;
    }

    boolean acceptsBitmap(int width, int height, int allocatedBytes) {
        return width > 0 && height > 0 && width <= displayEdge && height <= displayEdge
                && allocatedBytes > 0 && allocatedBytes <= displayEdge * displayEdge * 4;
    }
}

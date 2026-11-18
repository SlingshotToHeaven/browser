package com.privacy.browser.comic;

import java.util.Arrays;

/** Container checks only. Do not decode arbitrarily large untrusted images while archiving. */
enum ComicImageFormat {
    JPEG("jpg"), PNG("png"), GIF("gif"), WEBP("webp"), AVIF("avif");

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] PNG_END = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xae, 0x42, 0x60, (byte) 0x82};
    final String extension;

    ComicImageFormat(String extension) { this.extension = extension; }

    static ComicImageFormat detect(byte[] prefix, int count) {
        if (count >= 16 && u(prefix[0]) == 0xff && u(prefix[1]) == 0xd8 && u(prefix[2]) == 0xff) return JPEG;
        if (count >= 24 && Arrays.equals(PNG_SIGNATURE, Arrays.copyOf(prefix, PNG_SIGNATURE.length))
                && big32(prefix, 8) == 13 && text(prefix, 12, "IHDR") && big32(prefix, 16) > 0 && big32(prefix, 20) > 0) return PNG;
        if (count >= 14 && (text(prefix, 0, "GIF87a") || text(prefix, 0, "GIF89a"))
                && (prefix[6] != 0 || prefix[7] != 0) && (prefix[8] != 0 || prefix[9] != 0)) return GIF;
        if (count >= 20 && text(prefix, 0, "RIFF") && text(prefix, 8, "WEBP")
                && (text(prefix, 12, "VP8 ") || text(prefix, 12, "VP8L") || text(prefix, 12, "VP8X"))) return WEBP;
        if (count >= 16 && text(prefix, 4, "ftyp")) {
            long box = big32(prefix, 0);
            if (box >= 16 && box % 4 == 0) {
                if (avifBrand(prefix, 8)) return AVIF;
                for (int i = 16; i + 4 <= Math.min(box, count); i += 4) {
                    if (avifBrand(prefix, i)) return AVIF;
                }
            }
        }
        return null;
    }

    boolean complete(byte[] prefix, byte[] tail, long size) {
        switch (this) {
            case JPEG: return u(tail[10]) == 0xff && u(tail[11]) == 0xd9;
            case PNG: return size >= 45 && Arrays.equals(PNG_END, tail);
            case GIF: return tail[11] == 0x3b;
            case WEBP: return size == 8 + little32(prefix, 4) && size >= 20 + little32(prefix, 16);
            case AVIF: return size >= big32(prefix, 0) + 8;
            default: throw new AssertionError(this);
        }
    }

    private static boolean avifBrand(byte[] bytes, int start) {
        return text(bytes, start, "avif") || text(bytes, start, "avis");
    }

    private static boolean text(byte[] bytes, int start, String expected) {
        for (int i = 0; i < expected.length(); i++) if (bytes[start + i] != expected.charAt(i)) return false;
        return true;
    }

    private static long big32(byte[] b, int p) {
        return ((long) u(b[p]) << 24) | (u(b[p + 1]) << 16) | (u(b[p + 2]) << 8) | u(b[p + 3]);
    }

    private static long little32(byte[] b, int p) {
        return ((long) u(b[p + 3]) << 24) | (u(b[p + 2]) << 16) | (u(b[p + 1]) << 8) | u(b[p]);
    }

    private static int u(byte value) { return value & 0xff; }
}

package com.privacy.downloader.util;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

/** Identity of the directory entry attested at publication, not a content hash. */
public final class PublishedFileIdentity {
    private PublishedFileIdentity() {}

    public static String capture(String path, long size) {
        try {
            return of(Os.lstat(path), size);
        } catch (ErrnoException | SecurityException e) {
            return null;
        }
    }

    public static String of(StructStat stat, long size) {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino == 0 || size != stat.st_size) return null;
        return stat.st_dev + ":" + stat.st_ino + ":" + stat.st_mtime + ":" + size;
    }
}

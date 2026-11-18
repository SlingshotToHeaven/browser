package com.privacy.downloader.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Name selection shared by every enqueue path. The caller must persist the claim atomically. */
public final class DownloadDestination {
    private DownloadDestination() {}

    public static File choose(File requested, Set<String> takenKeys) throws IOException {
        return choose(requested, takenKeys::contains);
    }

    public static File choose(File requested, Predicate<String> claimed) throws IOException {
        File directory = canonicalParent(requested);
        String name = requested.getName();
        requireFileName(name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int suffix = 0; suffix < 10000; suffix++) {
            File candidate = new File(directory, suffix == 0 ? name : stem + "_" + suffix + ext);
            if (!claimed.test(pathKey(candidate)) && !entryExists(candidate)) return candidate;
        }
        throw new IOException("Too many downloads use this name. Choose a different file name.");
    }

    public static void requireFileName(String name) throws IOException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("Enter a file name without a folder or special characters.");
        }
    }

    /** Canonicalize the folder (including aliases) but do not follow a symlink at the final name. */
    public static String key(File file) throws IOException {
        return pathKey(new File(canonicalParent(file), file.getName()));
    }

    public static String normalizeSha256(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) return null;
        String hash = value.trim();
        if (!hash.matches("[0-9a-fA-F]{64}")) throw new IOException("Expected SHA-256 must contain 64 hexadecimal characters.");
        return hash.toLowerCase(Locale.ROOT);
    }

    private static File canonicalParent(File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("Choose a download folder and file name.");
        return parent.getCanonicalFile();
    }

    private static String pathKey(File file) {
        // Conservatively share a name across case/Unicode aliases on removable volumes.
        return Normalizer.normalize(file.getPath(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** A claimed name may hold an interrupted publication or someone else's file. Never adopt it. */
    public static void requireVacant(File file) throws IOException {
        if (entryExists(file)) {
            throw new IOException("The reserved destination is occupied. The file was left untouched. Move it or queue a new download with a different name.");
        }
    }

    private static boolean entryExists(File file) throws IOException {
        try {
            Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true; // Includes directories and dangling symlinks.
        } catch (NoSuchFileException e) {
            return false;
        }
    }
}

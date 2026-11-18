package com.privacy.browser.ai;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.regex.Pattern;

/** Only the files named by the retired attachment writer belong to this cleanup. */
final class AgentLegacyFiles {
    private static final String DIRECTORY = "agent_images";
    static final int MAX_NAME_LENGTH = 45; // img_ + UUID + .webp/.jpeg
    private static final Pattern NAME = Pattern.compile(
            "img_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:webp|jpg|jpeg|png)");

    static final class Sweep {
        final int removed;
        final boolean complete;
        Sweep(int removed, boolean complete) { this.removed = removed; this.complete = complete; }
    }

    private AgentLegacyFiles() { }

    /** Preserve matching basenames even when older rows spell the app's private path differently. */
    static String referenceName(String pathTail) {
        if (pathTail == null) return null;
        int end = pathTail.length();
        while (end > 0 && pathTail.charAt(end - 1) == '/') end--;
        String name = pathTail.substring(pathTail.lastIndexOf('/', end - 1) + 1, end);
        return NAME.matcher(name).matches() ? name : null;
    }

    static Path previewFile(File filesDir, String savedPath) throws IOException {
        if (savedPath == null || savedPath.length() > 1024) return null;
        String name = referenceName(savedPath);
        File file = new File(savedPath);
        if (name == null || !file.isAbsolute()) return null;
        Path directory = directory(filesDir);
        if (directory == null) return null;
        Path expected = directory.resolve(name);
        return regular(expected) && expected.toFile().equals(file.getCanonicalFile()) ? expected : null;
    }

    /** A null reference set means the caller could not safely enumerate every reference. */
    static Sweep removeUnused(File filesDir, Set<String> referenced, Set<String> candidates) {
        if (referenced == null) return new Sweep(0, false);
        int removed = 0;
        boolean complete = true;
        try {
            Path directory = directory(filesDir);
            if (directory == null) return new Sweep(0, true);
            if (candidates != null) {
                // Deleting a chat does not scan or clean up unrelated legacy images.
                for (String name : candidates) {
                    try { if (deleteIfUnused(directory, name, referenced)) removed++; }
                    catch (IOException e) { complete = false; }
                }
            } else {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                    for (Path file : files) {
                        try { if (deleteIfUnused(directory, file.getFileName().toString(), referenced)) removed++; }
                        catch (IOException e) { complete = false; }
                    }
                }
            }
        } catch (IOException | RuntimeException e) { complete = false; }
        return new Sweep(removed, complete);
    }

    private static Path directory(File filesDir) throws IOException {
        Path directory = filesDir.getCanonicalFile().toPath().resolve(DIRECTORY);
        try {
            if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw new IOException("Legacy attachment directory is not an app-owned directory.");
            }
            return directory;
        } catch (NoSuchFileException absent) { return null; }
    }

    private static boolean deleteIfUnused(Path directory, String name, Set<String> referenced) throws IOException {
        if (!NAME.matcher(name).matches() || referenced.contains(name)) return false;
        Path file = directory.resolve(name);
        return regular(file) && Files.deleteIfExists(file);
    }

    private static boolean regular(Path file) throws IOException {
        try { return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile(); }
        catch (NoSuchFileException absent) { return false; }
    }
}

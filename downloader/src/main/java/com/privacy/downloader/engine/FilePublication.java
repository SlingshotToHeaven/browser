package com.privacy.downloader.engine;

import com.privacy.downloader.util.DownloadDestination;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

/**
 * Publishes verified staging bytes without replacing a destination entry. The caller owns
 * the staging file and must commit the publication only while its attempt is still active.
 */
public final class FilePublication implements AutoCloseable {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final Path source;
    private final Path target;
    private final Object sourceKey;
    private Object targetKey;
    private boolean linked;
    private boolean committed;

    private FilePublication(Path source, Path target, Object sourceKey) {
        this.source = source;
        this.target = target;
        this.sourceKey = sourceKey;
    }

    public static FilePublication open(Path source, Path target, long size, String expectedSha256, BooleanSupplier stopped) throws IOException {
        checkActive(stopped);
        BasicFileAttributes input = requireRegularFile(source, size);
        // Sync staged bytes even for mux output, not just HTTP checkpoints.
        try (FileChannel staged = FileChannel.open(source, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            staged.force(true);
        }
        String expected = DownloadDestination.normalizeSha256(expectedSha256);
        if (expected != null) verifyExpected(hash(source, size, stopped), expected);
        checkActive(stopped);

        FilePublication publication = new FilePublication(source, target, input.fileKey());
        try {
            // A hard link publishes the same inode atomically without Linux rename's
            // check-then-replace race. Some Android shared volumes disallow links.
            publication.linked = source.getParent().equals(target.getParent()) && tryLink(source, target);
            if (publication.linked) {
                publication.targetKey = input.fileKey();
                if (!Files.isSameFile(source, target)) throw new IOException("The published file changed during publication.");
            } else {
                publication.copyAndVerify(size, expected, stopped);
            }
            publication.verifyEntry(size);
            syncDirectory(target.getParent());
            checkActive(stopped);
            return publication;
        } catch (IOException | RuntimeException e) {
            try {
                publication.close();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            if (e instanceof FileAlreadyExistsException) {
                throw new IOException("The destination already exists. Choose a different file name for a new download.", e);
            }
            throw e;
        }
    }

    private static void syncDirectory(Path directory) {
        // Shared/external storage may not permit fsync on directories.
        try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
            parent.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {}
    }

    private static boolean tryLink(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source);
            return true;
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (IOException | UnsupportedOperationException e) {
            // The fallback also uses exclusive creation; it cannot overwrite anything.
            return false;
        }
    }

    private void copyAndVerify(long size, String expected, BooleanSupplier stopped) throws IOException {
        if (attributes(target.getParent()).fileKey() == null) {
            throw new IOException("This download folder cannot verify ownership of copied files.");
        }
        MessageDigest copied = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;
        checkActive(stopped);
        try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS)) {
            targetKey = requireRegularFile(target, 0).fileKey();
            if (targetKey == null) throw new IOException("Could not identify the copy in the download folder.");
            for (int count; (count = in.read(buffer)) != -1; ) {
                checkActive(stopped);
                if (count > size - written) throw changedStaging();
                ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, count);
                while (chunk.hasRemaining()) out.write(chunk);
                copied.update(buffer, 0, count);
                written += count;
            }
            if (written != size) throw changedStaging();
            out.force(true);
        }
        byte[] expectedBytes = copied.digest();
        if (expected != null) verifyExpected(expectedBytes, expected);
        verifyEntry(size);
        if (!MessageDigest.isEqual(expectedBytes, hash(target, size, stopped))) {
            throw new IOException("The copied file did not match the staged download.");
        }
    }

    void verifyEntry(long size) throws IOException {
        BasicFileAttributes candidate = requireRegularFile(target, size);
        if (targetKey != null && !targetKey.equals(candidate.fileKey())) {
            throw new IOException("The published file changed during publication.");
        }
    }

    public void commit() {
        committed = true;
    }

    @Override
    public void close() throws IOException {
        if (committed) {
            // The caller has committed the result. A cleanup failure cannot undo the commit.
            try { deleteOwned(source, sourceKey, false); } catch (IOException | SecurityException ignored) {}
        } else {
            deleteOwned(target, targetKey, linked);
        }
    }

    private void deleteOwned(Path path, Object key, boolean checkLink) throws IOException {
        try {
            BasicFileAttributes entry = attributes(path);
            if (entry.isRegularFile() && (key != null ? key.equals(entry.fileKey()) : checkLink && Files.isSameFile(source, path))) {
                Files.deleteIfExists(path);
            }
        } catch (NoSuchFileException ignored) {}
    }

    private static BasicFileAttributes requireRegularFile(Path path, long size) throws IOException {
        BasicFileAttributes file = attributes(path);
        if (!file.isRegularFile() || size < 0 || file.size() != size) throw changedStaging();
        return file;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static IOException changedStaging() {
        return new IOException("The staged download is missing, incomplete, or has changed.");
    }

    private static byte[] hash(Path path, long size, BooleanSupplier stopped) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        long hashed = 0;
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            for (int count; (count = in.read(buffer)) != -1; ) {
                checkActive(stopped);
                if (count > size - hashed) throw changedStaging();
                digest.update(buffer, 0, count);
                hashed += count;
            }
        }
        if (hashed != size) throw changedStaging();
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // SHA-256 is required on Android and the JVM.
        }
    }

    private static void verifyExpected(byte[] hash, String expected) throws IOException {
        byte[] expectedBytes = new byte[32];
        for (int i = 0; i < expectedBytes.length; i++) {
            expectedBytes[i] = (byte) Integer.parseInt(expected.substring(i * 2, i * 2 + 2), 16);
        }
        if (!MessageDigest.isEqual(hash, expectedBytes)) {
            throw new IOException("SHA-256 mismatch. The download was not published.");
        }
    }

    private static void checkActive(BooleanSupplier stopped) throws InterruptedIOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download stopped before publication.");
        }
    }
}

package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.util.DownloadDestination;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class FilePublicationTest {
    private static final byte[] DATA = {1, 3, 5, 7};
    private static final byte[] EXISTING = {9, 9, 9, 9};

    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void queuedNamesAndExistingEntriesBothBlockAReservation() throws Exception {
        Path directory = files.newFolder().toPath();
        Path requested = directory.resolve("movie.mp4");
        Files.write(directory.resolve("movie_1.mp4"), EXISTING);
        Files.createSymbolicLink(directory.resolve("movie_2.mp4"), directory.resolve("missing"));
        Path alias = files.getRoot().toPath().resolve("alias");
        Files.createSymbolicLink(alias, directory);
        String reserved = DownloadDestination.key(alias.resolve("MOVIE.MP4").toFile());

        Path name = DownloadDestination.choose(requested.toFile(), Set.of(reserved)).toPath();
        assertEquals(directory.toRealPath().resolve("movie_3.mp4"), name);
        assertFalse(Files.exists(name)); // A reservation is a DB row, not a placeholder file.
        assertArrayEquals(EXISTING, Files.readAllBytes(directory.resolve("movie_1.mp4")));
    }

    @Test
    public void muxJobsReserveTheMp4NameRatherThanAnInputName() throws Exception {
        Path directory = files.newFolder().toPath();
        Path reserved = directory.resolve("clip.mp4");
        for (String name : new String[]{"clip.M3U8", "clip.webm"}) {
            DownloadItem item = new DownloadItem();
            item.setUrl("https://example.test/" + name);
            if (name.endsWith(".webm")) item.setAudioUrl("https://example.test/audio");
            item.setFileName(name);
            item.setDestinationPath(directory.resolve(name).toString());
            Path selected = DownloadDestination.choose(DownloadQueue.requestedDestination(item, true),
                    Set.of(DownloadDestination.key(reserved.toFile()))).toPath();
            assertEquals(directory.toRealPath().resolve("clip_1.mp4"), selected);
        }
    }

    @Test
    public void namesCannotAddAFolderAndDigestsMustBeSha256Hex() throws Exception {
        for (String name : new String[]{"../file", "a/b", "a\\b", "..", ".", "", "x\n"}) {
            assertThrows(IOException.class, () -> DownloadDestination.requireFileName(name));
        }
        assertNull(DownloadDestination.normalizeSha256("   "));
        assertThrows(IOException.class, () -> DownloadDestination.normalizeSha256("1234"));
        assertEquals("ab".repeat(32), DownloadDestination.normalizeSha256(" AB" + "AB".repeat(31) + " "));
    }

    @Test
    public void successfulCopyIsVerifiedAndOnlyCleansUpStagingAfterCommit() throws Exception {
        Path stage = Files.write(files.newFolder().toPath().resolve("incoming.part"), DATA);
        Path target = files.newFolder().toPath().resolve("result.bin");
        try (FilePublication publication = FilePublication.open(stage, target, DATA.length, sha256(DATA), () -> false)) {
            assertFalse(Files.isSameFile(stage, target));
            assertArrayEquals(DATA, Files.readAllBytes(target));
            assertTrue(Files.exists(stage));
            publication.commit();
        }
        assertArrayEquals(DATA, Files.readAllBytes(target));
        assertFalse(Files.exists(stage));
    }

    @Test
    public void neitherPublishRouteOverwritesAnOccupiedName() throws Exception {
        for (boolean copy : new boolean[]{false, true}) {
            Path directory = files.newFolder().toPath();
            Path stage = Files.write((copy ? files.newFolder().toPath() : directory).resolve("source.part"), DATA);
            Path dest = Files.write(directory.resolve("name.bin"), EXISTING);
            IOException error = assertThrows(IOException.class,
                    () -> FilePublication.open(stage, dest, DATA.length, null, () -> false));
            assertTrue(error.getMessage().contains("destination already exists"));
            assertArrayEquals(DATA, Files.readAllBytes(stage));
            assertArrayEquals(EXISTING, Files.readAllBytes(dest));
        }
    }

    @Test(timeout = 10000)
    public void racingPublishersCannotBothAcquireTheSameFilesystemName() throws Exception {
        for (boolean copy : new boolean[]{false, true}) {
            Path directory = files.newFolder().toPath();
            Path dest = directory.resolve("name.bin");
            Path a = Files.write((copy ? files.newFolder().toPath() : directory).resolve("a.part"), DATA);
            Path b = Files.write((copy ? files.newFolder().toPath() : directory).resolve("b.part"), EXISTING);
            CountDownLatch go = new CountDownLatch(1);
            FutureTask<Boolean> first = publishAtOnce(a, dest, go);
            FutureTask<Boolean> second = publishAtOnce(b, dest, go);
            go.countDown();
            boolean aWon = first.get(4, TimeUnit.SECONDS);
            assertNotEquals(aWon, second.get(4, TimeUnit.SECONDS));
            assertArrayEquals(aWon ? DATA : EXISTING, Files.readAllBytes(dest));
            assertEquals(!aWon, Files.exists(a));
            assertEquals(aWon, Files.exists(b));
        }
    }

    private static FutureTask<Boolean> publishAtOnce(Path stage, Path dest, CountDownLatch go) {
        FutureTask<Boolean> attempt = new FutureTask<>(() -> {
            go.await();
            try (FilePublication publication = FilePublication.open(stage, dest, DATA.length, null, () -> false)) {
                publication.commit();
                return true;
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("destination already exists"));
                return false;
            }
        });
        Thread thread = new Thread(attempt, "publish-fixture");
        thread.setDaemon(true);
        thread.start();
        return attempt;
    }

    @Test
    public void stoppingDuringCopyRemovesOnlyTheUncommittedOutput() throws Exception {
        byte[] bytes = new byte[192 * 1024];
        Arrays.fill(bytes, (byte) 8);
        Path stage = Files.write(files.newFolder().toPath().resolve("source.part"), bytes);
        Path dest = files.newFolder().toPath().resolve("name.bin");
        assertThrows(InterruptedIOException.class, () -> FilePublication.open(stage, dest, bytes.length, null,
                () -> dest.toFile().length() > 0 && dest.toFile().length() < bytes.length));
        assertFalse(Files.exists(dest));
        assertArrayEquals(bytes, Files.readAllBytes(stage));
    }

    @Test
    public void sameSizeCopyCorruptionIsRejectedBeforeCommit() throws Exception {
        byte[] bytes = new byte[192 * 1024];
        Path stage = Files.write(files.newFolder().toPath().resolve("source.part"), bytes);
        Path dest = files.newFolder().toPath().resolve("name.bin");
        AtomicBoolean changed = new AtomicBoolean();
        IOException error = assertThrows(IOException.class, () -> FilePublication.open(stage, dest, bytes.length, null, () -> {
            if (dest.toFile().length() == bytes.length && changed.compareAndSet(false, true)) {
                try (RandomAccessFile copy = new RandomAccessFile(dest.toFile(), "rw")) {
                    copy.seek(bytes.length - 1);
                    copy.writeByte(8);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            return false;
        }));
        assertTrue(error.getMessage(), error.getMessage().contains("did not match"));
        assertFalse(Files.exists(dest));
        assertArrayEquals(bytes, Files.readAllBytes(stage));
    }

    @Test
    public void rollbackDoesNotDeleteAReplacementFile() throws Exception {
        Path stage = Files.write(files.newFolder().toPath().resolve("source.part"), DATA);
        Path directory = files.newFolder().toPath();
        Path dest = directory.resolve("name.bin");
        Path replacement = Files.write(directory.resolve("external"), EXISTING);
        try (FilePublication publication = FilePublication.open(stage, dest, DATA.length, null, () -> false)) {
            Files.move(replacement, dest, StandardCopyOption.REPLACE_EXISTING);
            assertThrows(IOException.class, () -> publication.verifyEntry(DATA.length));
        }
        assertArrayEquals(EXISTING, Files.readAllBytes(dest));
        assertArrayEquals(DATA, Files.readAllBytes(stage));
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(Locale.ROOT, "%02x", b));
        return result.toString();
    }
}

package com.privacy.downloader.engine;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

/** Writes only the frames delivered to this worker's one-shot, confirmed source. */
public class BlobDownloadTask extends DownloadTask {
    private final BlobIntake intake;

    BlobDownloadTask(DownloadItem item, DownloadDbHelper dbHelper, TaskListener listener, SpeedLimiter globalLimiter,
                     BlobIntake intake) {
        super(item, dbHelper, listener, globalLimiter);
        this.intake = intake;
    }

    @Override
    protected void onStopRequested() {
        if (intake != null) intake.close();
    }

    @Override
    public void run() {
        String failure = null;
        File staged = null;
        boolean published = false;
        try {
            if (isStopRequested()) return;
            if (intake == null) throw new IOException(BlobIntake.SOURCE_GONE);
            markRunning(null);
            intake.start();
            String first = nextFrame();
            if (!first.startsWith("begin:")) throw invalidStream();
            String length = first.substring(6);
            long reported = "-1".equals(length) ? -1 : parsePosition(length);
            if (reported > BlobIntake.MAX_BYTES) throw tooLarge();
            long expected = intake.knownLength >= 0 ? intake.knownLength : reported;
            if (reported >= 0 && expected != reported) throw new IOException("The blob length does not match the confirmed download.");
            item.setTotalBytes(expected);
            item.setDownloadedBytes(0);

            staged = File.createTempFile("download-", ".blob.part", outputDirectory());
            bindStagingFile(staged);
            long received;
            try (OutputStream out = Files.newOutputStream(staged.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                received = writeFrames(out, expected);
            }
            intake.seal();
            published = publishOutput(staged, received, item.getMimeType());
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = e.getMessage() == null ? "Blob transfer failed." : e.getMessage();
        } finally {
            if (intake != null) intake.close();
            if (!published && staged != null) staged.delete();
        }
        if (published) {
            if (listener != null) listener.onTaskCompleted(item);
        } else {
            fail(failure);
        }
    }

    private long writeFrames(OutputStream out, long expected) throws IOException, InterruptedException {
        long received = 0;
        long previousBytes = 0;
        long previousTime = System.nanoTime();
        for (;;) {
            // The previous frame was checked (and any bytes written) before granting another.
            intake.acknowledge();
            String frame = nextFrame();
            if (frame.startsWith("end:")) {
                if (parsePosition(frame.substring(4)) != received) throw invalidStream();
                if (expected >= 0 && received != expected) {
                    throw new IOException("The blob transfer ended before all expected bytes arrived.");
                }
                if (expected < 0 && received == 0) throw new IOException("The blob transfer returned no bytes.");
                return received;
            }
            int separator = frame.indexOf(':', 5);
            if (!frame.startsWith("data:") || separator < 0 || parsePosition(frame.substring(5, separator)) != received) {
                throw invalidStream();
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(frame.substring(separator + 1));
            } catch (IllegalArgumentException e) {
                throw invalidStream();
            }
            if (bytes.length == 0 || bytes.length > BlobIntake.MAX_CHUNK_BYTES) throw invalidStream();
            if (bytes.length > BlobIntake.MAX_BYTES - received) throw tooLarge();
            if (expected >= 0 && bytes.length > expected - received) throw invalidStream();
            out.write(bytes);
            received += bytes.length;
            item.setDownloadedBytes(received);
            long now = System.nanoTime();
            if (now - previousTime >= 500_000_000L) {
                item.setSpeedBytesPerSec((received - previousBytes) * 1_000_000_000L / (now - previousTime));
                previousBytes = received;
                previousTime = now;
                if (listener != null) listener.onTaskUpdated(item);
            }
        }
    }

    private String nextFrame() throws IOException, InterruptedException {
        String frame = intake.next();
        if (isStopRequested()) throw new InterruptedIOException("Blob download stopped.");
        if ("error".equals(frame)) throw new IOException("The page could not read the blob. It may have expired.");
        return frame;
    }

    private static long parsePosition(String value) throws IOException {
        if (value.isEmpty() || value.length() > 10) throw invalidStream();
        long position = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = value.charAt(i) - '0';
            if (digit < 0 || digit > 9) throw invalidStream();
            position = position * 10 + digit;
        }
        return position;
    }

    private static IOException invalidStream() {
        return new IOException("The blob stream did not contain the expected bytes.");
    }

    private static IOException tooLarge() {
        return new IOException("This blob exceeds the 512 MiB download limit.");
    }
}

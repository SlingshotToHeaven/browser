package com.privacy.browser.adblock.rules;

import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** A disposable parsed index, never a replacement for the exact, authoritative local rule text. */
public final class FilterIndexCache {
    // Bump when either the grammar or the compiled representation changes.
    private static final int MAGIC = 0x52495831; // RIX1
    private static final int DIGEST = 32;
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final Path file;
    private final Executor writer;
    private FilterSnapshot pending;
    private boolean scheduled;

    public FilterIndexCache(Path file, Executor writer) {
        this.file = file;
        this.writer = writer;
    }

    /** Run on the rule loader, not the UI. A miss never substitutes a different ruleset. */
    public FilterSnapshot loadOrBuild(Map<Part, String> text) {
        Inputs inputs = new Inputs(text);
        try {
            return load(text, inputs);
        } catch (IOException | RuntimeException badCache) {
            FilterSnapshot ready = FilterSnapshot.parse(text, false);
            remember(ready);
            return ready;
        }
    }

    private FilterSnapshot load(Map<Part, String> text, Inputs expected) throws IOException {
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(file);
             DataInputStream in = new DataInputStream(Channels.newInputStream(channel))) {
            long size = channel.size();
            if (size < Integer.BYTES + 2 * DIGEST || size > MAX_BYTES) throw new IOException("Bad rule index length");
            bytes = new byte[(int) size];
            in.readFully(bytes);
            if (in.read() != -1) throw new IOException("Trailing rule index bytes");
        }
        int contentLength = bytes.length - DIGEST;
        MessageDigest checksum = sha();
        checksum.update(bytes, 0, contentLength);
        if (!MessageDigest.isEqual(checksum.digest(), Arrays.copyOfRange(bytes, contentLength, bytes.length))) {
            throw new IOException("Damaged rule index");
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes, 0, contentLength))) {
            if (data.readInt() != MAGIC) throw new IOException("Unknown rule index version");
            byte[] source = new byte[DIGEST];
            data.readFully(source);
            if (!MessageDigest.isEqual(expected.digest, source)) throw new IOException("Stale rule index");
            FilterSnapshot rules = FilterSnapshot.readReady(text, expected.lengths, data);
            if (data.read() != -1) throw new IOException("Unexpected rule index data");
            return rules;
        }
    }

    public void remember(FilterSnapshot snapshot) {
        synchronized (this) {
            pending = snapshot;
            if (scheduled) return;
            scheduled = true;
        }
        try { writer.execute(this::drain); }
        catch (RejectedExecutionException offline) {
            synchronized (this) { scheduled = false; pending = null; }
        }
    }

    private void drain() {
        while (true) {
            FilterSnapshot next;
            synchronized (this) {
                next = pending;
                pending = null;
                if (next == null) { scheduled = false; return; }
            }
            try { write(next); }
            catch (IOException | RuntimeException ignored) { /* source text remains authoritative */ }
        }
    }

    private void write(FilterSnapshot snapshot) throws IOException {
        EnumMap<Part, String> text = new EnumMap<>(Part.class);
        for (Part part : Part.values()) text.put(part, snapshot.text(part));
        Inputs inputs = new Inputs(text);
        Files.createDirectories(file.getParent());
        Path stage = Files.createTempFile(file.getParent(), ".rule-index-", ".tmp");
        try {
            MessageDigest checksum = sha();
            try (FileOutputStream raw = new FileOutputStream(stage.toFile());
                 DigestOutputStream hash = new DigestOutputStream(new BoundedOutput(new BufferedOutputStream(raw)), checksum);
                 DataOutputStream out = new DataOutputStream(hash)) {
                out.writeInt(MAGIC);
                out.write(inputs.digest);
                snapshot.writeReady(out);
                hash.on(false);
                out.write(checksum.digest());
                out.flush();
                raw.getFD().sync();
            }
            Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(stage); }
    }

    private static final class Inputs {
        final EnumMap<Part, Integer> lengths = new EnumMap<>(Part.class);
        final byte[] digest;

        Inputs(Map<Part, String> texts) {
            MessageDigest hash = sha();
            for (Part part : Part.values()) {
                byte[] bytes = FilterSnapshot.utf8(texts.get(part), part.byteLimit);
                int n = bytes.length;
                lengths.put(part, n);
                for (int shift = 24; shift >= 0; shift -= 8) hash.update((byte) (n >>> shift));
                hash.update(bytes);
            }
            digest = hash.digest();
        }
    }

    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unsupported) { throw new AssertionError(unsupported); }
    }

    private static final class BoundedOutput extends OutputStream {
        final OutputStream out;
        int remaining = MAX_BYTES;
        BoundedOutput(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { take(1); out.write(b); }
        @Override public void write(byte[] b, int offset, int count) throws IOException { take(count); out.write(b, offset, count); }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
        private void take(int bytes) throws IOException {
            if (bytes > remaining) throw new IOException("Rule index exceeds its limit");
            remaining -= bytes;
        }
    }
}

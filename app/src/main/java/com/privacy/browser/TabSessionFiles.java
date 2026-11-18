package com.privacy.browser;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Immutable state generations become reachable only when index.json is atomically replaced. */
final class TabSessionFiles {
    interface Legacy {
        TabSession.Index read() throws JSONException;
        boolean clear();
    }

    @FunctionalInterface interface Rename { void replace(Path stage, Path target) throws IOException; }
    @FunctionalInterface private interface Output { void write(DataOutputStream out) throws IOException; }

    private static final int STATE_MAGIC = 0x54534231; // TSB1
    private static final int CHECKSUM_BYTES = 32;
    private static final Pattern OWNED = Pattern.compile(
            "(?:state-[0-9a-f-]{36}-[0-9a-f-]{36}\\.bin|tab_[0-9]+\\.bin|session-stage-[0-9]+\\.tmp)");

    private final Path directory;
    private final Path indexFile;
    private final Legacy legacy;
    private final Rename rename;
    private final Consumer<String> warning;
    private TabSession.Index published; // bootstrap, then writer only
    private boolean needsSweep = true;
    private boolean legacyPending = true;

    TabSessionFiles(Path directory, Legacy legacy, Consumer<String> warning) {
        this(directory, legacy, (from, to) -> Files.move(from, to,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), warning);
    }

    TabSessionFiles(Path directory, Legacy legacy, Rename rename, Consumer<String> warning) {
        this.directory = directory;
        indexFile = directory.resolve("index.json");
        this.legacy = legacy;
        this.rename = rename;
        this.warning = warning;
    }

    /** Cold process load. Never use stale positional preferences in place of a newer unreadable index. */
    TabSession.Index load() {
        try (SeekableByteChannel file = Files.newByteChannel(indexFile);
             DataInputStream in = new DataInputStream(Channels.newInputStream(file))) {
            // The index is immutable once opened, including across an atomic replacement.
            long length = file.size();
            if (length > TabSession.MAX_INDEX) throw new IOException("Oversized session index");
            byte[] bytes = new byte[(int) length];
            in.readFully(bytes);
            if (in.read() != -1) throw new IOException("Unexpected trailing index bytes");
            published = TabSession.decode(new String(bytes, StandardCharsets.UTF_8));
            return published;
        } catch (NoSuchFileException missing) {
            try { return legacy.read(); }
            catch (JSONException | RuntimeException badLegacy) { warning.accept("Legacy tab session could not be read"); }
        } catch (IOException | JSONException | RuntimeException damaged) {
            warning.accept("Tab session index could not be read");
        }
        return TabSession.EMPTY;
    }

    /** A missing/damaged parcel affects only this tab. Index metadata is still usable. */
    TabSession.StateRead readState(TabSession.Row row) {
        if (row.state == null) return TabSession.StateRead.URL_ONLY;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve(row.filename()))))) {
            if (in.readInt() != STATE_MAGIC || !row.id.equals(in.readUTF()) || !row.state.equals(in.readUTF())
                    || !row.url.equals(in.readUTF()) || row.desktop != in.readBoolean()) return TabSession.StateRead.CORRUPT;
            int length = in.readInt();
            if (length <= 0 || length > TabSession.MAX_STATE) return TabSession.StateRead.CORRUPT;
            byte[] checksum = new byte[CHECKSUM_BYTES];
            in.readFully(checksum);
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            return in.read() == -1 && MessageDigest.isEqual(checksum, digest(bytes))
                    ? TabSession.StateRead.loaded(bytes) : TabSession.StateRead.CORRUPT;
        } catch (NoSuchFileException missing) {
            return TabSession.StateRead.MISSING;
        } catch (IOException | RuntimeException unavailable) {
            return TabSession.StateRead.CORRUPT;
        }
    }

    /** Only the ordered worker may publish. A failed transaction leaves the prior index and its blobs intact. */
    TabSession.Index publish(TabSession.Frame frame) throws IOException {
        boolean firstIndex = published == null;
        Map<String, TabSession.Row> previous = references(published);
        List<TabSession.Row> rows = new ArrayList<>();
        for (TabSession.Row row : frame.index.tabs) {
            if (row.state != null) {
                TabSession.Row old = previous.get(row.filename());
                if (old != null && !old.compatible(row)) {
                    row = row.withState(null); // A reachable filename is immutable, even on a mismatched handoff.
                } else if (old == null) {
                    byte[] bytes = frame.state(row);
                    if (bytes != null && bytes.length > 0 && bytes.length <= TabSession.MAX_STATE) {
                        TabSession.Row captured = row;
                        replace(directory.resolve(row.filename()), out -> writeState(out, captured, bytes));
                    } else {
                        // A bounded handoff may omit unsettled bytes. Never publish a dangling reference.
                        row = row.withState(null);
                    }
                }
            }
            rows.add(row);
        }
        TabSession.Index next = new TabSession.Index(rows, frame.index.selected);
        Set<String> live = references(next).keySet();
        if (!next.equals(published)) writeIndex(next);
        // Retire named predecessors, but leave full directory scans to deferred maintenance.
        for (String old : previous.keySet()) {
            if (!live.contains(old) && !remove(directory.resolve(old))) needsSweep = true;
        }
        if (firstIndex && legacyPending) {
            legacyPending = !clearLegacy(); // the v1 index is now durable
            if (legacyPending) warning.accept("Legacy tab session preferences could not be removed");
        }
        return next;
    }

    void requestSweep() { needsSweep = true; } // a failed publication may have left an unreferenced generation

    void maintain() {
        if (published == null) return;
        if (legacyPending) legacyPending = !clearLegacy();
        if (needsSweep) needsSweep = !sweep(references(published).keySet());
        if (legacyPending || needsSweep) warning.accept("Some obsolete tab session files could not be removed");
    }

    private static boolean remove(Path file) {
        try { Files.deleteIfExists(file); return true; }
        catch (IOException | RuntimeException failed) { return false; }
    }

    /** Publish a tombstone before removing blobs and the old preferences. Completion reflects all operations. */
    boolean wipe() {
        boolean indexRemoved = false;
        try {
            writeIndex(TabSession.EMPTY);
            indexRemoved = true;
        } catch (IOException | RuntimeException failed) {
            warning.accept("Could not publish an empty tab session");
        }
        boolean removedLegacy = clearLegacy();
        boolean swept = sweep(new HashSet<>());
        // Even if index replacement failed, deleted references must not be reused by a later save.
        if (!indexRemoved) published = null;
        legacyPending = !removedLegacy;
        needsSweep = !swept;
        return indexRemoved && removedLegacy && swept;
    }

    private boolean clearLegacy() {
        try { return legacy.clear(); }
        catch (RuntimeException failed) { return false; }
    }

    private void writeIndex(TabSession.Index next) throws IOException {
        byte[] json;
        try { json = TabSession.encode(next).getBytes(StandardCharsets.UTF_8); }
        catch (JSONException invalid) { throw new IOException("Invalid tab session metadata", invalid); }
        if (json.length > TabSession.MAX_INDEX) throw new IOException("Oversized session index");
        replace(indexFile, out -> out.write(json));
        published = next;
    }

    private void replace(Path target, Output output) throws IOException {
        Files.createDirectories(directory);
        Path stage = Files.createTempFile(directory, "session-stage-", ".tmp");
        try {
            try (FileOutputStream file = new FileOutputStream(stage.toFile());
                 DataOutputStream data = new DataOutputStream(new BufferedOutputStream(file))) {
                output.write(data);
                data.flush();
                file.getFD().sync();
            }
            rename.replace(stage, target);
        } finally {
            try { Files.deleteIfExists(stage); }
            catch (IOException | RuntimeException ignored) { /* an unreferenced stage is never restored */ }
        }
    }

    private boolean sweep(Set<String> live) {
        boolean success = true;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!live.contains(name) && OWNED.matcher(name).matches()) {
                    success &= remove(file);
                }
            }
        } catch (NoSuchFileException absent) {
            // No snapshots to remove.
        } catch (IOException | RuntimeException failed) {
            success = false;
        }
        return success;
    }

    private static Map<String, TabSession.Row> references(TabSession.Index index) {
        Map<String, TabSession.Row> references = new HashMap<>();
        if (index != null) for (TabSession.Row row : index.tabs) {
            if (row.state != null) references.put(row.filename(), row);
        }
        return references;
    }

    private static void writeState(DataOutputStream out, TabSession.Row row, byte[] bytes) throws IOException {
        out.writeInt(STATE_MAGIC);
        out.writeUTF(row.id);
        out.writeUTF(row.state);
        out.writeUTF(row.url);
        out.writeBoolean(row.desktop);
        out.writeInt(bytes.length);
        out.write(digest(bytes));
        out.write(bytes);
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
}

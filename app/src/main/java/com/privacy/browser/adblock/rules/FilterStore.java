package com.privacy.browser.adblock.rules;

import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;

/** Stages all rule files together and changes the live snapshot only after an atomic rename. */
public final class FilterStore {
    private static final int MAGIC = 0x52554c32; // RUL2

    @FunctionalInterface public interface LegacyRules { FilterSnapshot read() throws IOException; }
    @FunctionalInterface interface Rename { void replace(Path from, Path to) throws IOException; }

    private final Path file;
    private final Rename rename;
    private final String recoveryWarning;
    private final FilterIndexCache readyCache;
    private volatile FilterSnapshot live;

    public FilterStore(Path file, LegacyRules legacy) throws IOException {
        this(file, legacy, (FilterIndexCache) null);
    }

    public FilterStore(Path file, LegacyRules legacy, FilterIndexCache readyCache) throws IOException {
        this(file, legacy, (src, dst) -> Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), readyCache);
    }

    FilterStore(Path file, LegacyRules legacy, Rename rename) throws IOException {
        this(file, legacy, rename, null);
    }

    private FilterStore(Path file, LegacyRules legacy, Rename rename, FilterIndexCache readyCache) throws IOException {
        this.file = file;
        this.rename = rename;
        this.readyCache = readyCache;
        String warning = null;
        try {
            live = read(file);
        } catch (NoSuchFileException missing) {
            live = legacy.read();
        } catch (IOException | IllegalArgumentException damaged) {
            // Never overwrite an unreadable snapshot with defaults on restart.
            live = legacy.read();
            warning = "Saved filter snapshot is unreadable. Using the legacy rules in read-only mode.";
        }
        recoveryWarning = warning;
    }

    public FilterSnapshot current() { return live; }
    public String recoveryWarning() { return recoveryWarning; }

    public synchronized void edit(Part part, String text) throws IOException {
        FilterSnapshot before = live;
        commit(before, before.replace(part, text));
    }

    public synchronized void commit(FilterSnapshot expected, FilterSnapshot next) throws IOException {
        if (recoveryWarning != null) throw new IOException(recoveryWarning);
        if (live != expected) throw new IOException("Local rules changed after preview. Reopen the preview before applying.");
        if (expected == next) return;
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path stage = Files.createTempFile(parent, ".rules-stage-", ".tmp");
        boolean published = false;
        try {
            try (FileOutputStream out = new FileOutputStream(stage.toFile());
                 DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out))) {
                data.writeInt(MAGIC);
                for (Part part : Part.values()) {
                    byte[] bytes = FilterSnapshot.utf8(next.text(part), part.byteLimit);
                    data.writeInt(bytes.length);
                    data.write(bytes);
                }
                data.flush();
                out.getFD().sync();
            }
            rename.replace(stage, file);
            published = true;
            live = next;
            if (readyCache != null) readyCache.remember(next);
        } finally {
            if (!published) {
                try { Files.deleteIfExists(stage); } catch (IOException ignored) { /* never read a stage file */ }
            }
        }
    }

    private FilterSnapshot read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC) throw new IOException("Unrecognized rule snapshot.");
            EnumMap<Part, String> text = new EnumMap<>(Part.class);
            for (Part part : Part.values()) {
                int bytes = in.readInt();
                if (bytes < 0 || bytes > part.byteLimit) throw new IOException("Invalid rule section size.");
                byte[] raw = new byte[bytes];
                in.readFully(raw);
                text.put(part, FilterSnapshot.decode(raw));
            }
            if (in.read() != -1) throw new IOException("Trailing rule snapshot bytes.");
            return readyCache == null ? FilterSnapshot.parse(text, false) : readyCache.loadOrBuild(text);
        }
    }
}

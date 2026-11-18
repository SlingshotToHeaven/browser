package com.privacy.browser;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class TabSessionFilesTest {
    static final String A = "https://example.test/a";
    static final String B = "https://example.test/b";
    static final byte[] ONE = {1, 2, 3};
    static final byte[] TWO = {7, 8, 9};

    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    static final class Legacy implements TabSessionFiles.Legacy {
        String json;
        int selection, reads, clears;
        boolean canClear = true;

        @Override public TabSession.Index read() throws JSONException {
            reads++;
            return TabSession.fromLegacy(json, selection);
        }
        @Override public boolean clear() {
            clears++;
            if (canClear) json = null;
            return canClear;
        }
    }

    @Test public void filesFollowStableIdsNotOrderAndNeverConfuseIdenticalCurrentUrls() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        TabSessionFiles files = read(dir, legacy);
        TabSession.Row first = capture(A), second = capture(A);
        files.publish(frame(second.id, Map.of(first.filename(), ONE, second.filename(), TWO), first, second));
        files.publish(frame(first.id, Collections.emptyMap(), second, first));

        TabSessionFiles restarted = read(dir, legacy);
        TabSession.Index afterReorder = restarted.load();
        assertEquals(Arrays.asList(second, first), afterReorder.tabs);
        assertEquals(first.id, afterReorder.selected);
        assertArrayEquals(TWO, restarted.readState(afterReorder.tabs.get(0)).bytes);
        assertArrayEquals(ONE, restarted.readState(afterReorder.tabs.get(1)).bytes);

        files.publish(frame(second.id, Collections.emptyMap(), second));
        assertFalse(Files.exists(dir.resolve(first.filename())));
        assertArrayEquals(TWO, restarted.readState(second).bytes);
        assertEquals(second.id, restarted.load().selected);
    }

    @Test public void aSelectionOnlyCommitRenamesOnlyTheIndexAndLeavesSnapshotsUntouched() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        List<String> renames = new ArrayList<>();
        TabSessionFiles files = new TabSessionFiles(dir, legacy, (stage, target) -> {
            renames.add(target.getFileName().toString());
            rename(stage, target);
        }, message -> fail(message));
        files.load();
        TabSession.Row first = capture(A), second = capture(B);
        files.publish(frame(first.id, Map.of(first.filename(), ONE, second.filename(), TWO), first, second));
        renames.clear();
        Path unrelatedOrphan = Files.write(dir.resolve("tab_98.bin"), ONE);
        files.publish(frame(second.id, Collections.emptyMap(), first, second));
        assertEquals(Collections.singletonList("index.json"), renames);
        assertEquals(1, legacy.clears);
        assertTrue(Files.exists(unrelatedOrphan)); // no directory sweep on a clean switch
        assertArrayEquals(ONE, files.readState(first).bytes);
        assertArrayEquals(TWO, files.readState(second).bytes);
    }

    @Test public void failureBeforeIndexRenameKeepsOldBackStackAndSelection() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        boolean[] failIndex = {false};
        TabSessionFiles files = new TabSessionFiles(dir, legacy, (stage, dst) -> {
            if (failIndex[0] && "index.json".equals(dst.getFileName().toString())) throw new IOException("injected before rename");
            rename(stage, dst);
        }, message -> fail(message));
        files.load();
        TabSession.Row first = capture(A), second = capture(B);
        files.publish(frame(first.id, Map.of(first.filename(), ONE, second.filename(), TWO), first, second));
        TabSession.Row next = new TabSession.Row(first.id, B, "After load", false, false, UUID.randomUUID().toString());
        TabSession.Frame attempted = frame(second.id, Map.of(next.filename(), TWO), next, second);
        failIndex[0] = true;
        assertThrows(IOException.class, () -> files.publish(attempted));
        assertTrue(Files.exists(dir.resolve(next.filename()))); // staged blob isn't a commit
        TabSessionFiles restart = read(dir, legacy);
        TabSession.Index old = restart.load();
        assertEquals(first.id, old.selected);
        assertEquals(Arrays.asList(first, second), old.tabs);
        assertArrayEquals(ONE, restart.readState(first).bytes);

        failIndex[0] = false;
        files.publish(attempted);
        assertEquals(attempted.index, restart.load());
        assertFalse(Files.exists(dir.resolve(first.filename())));
        assertArrayEquals(TWO, restart.readState(next).bytes);
    }

    @Test public void missingCorruptOrSwappedBlobsLoseOnlyTheirOwnHistory() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        TabSessionFiles files = read(dir, legacy);
        TabSession.Row first = capture(A), second = capture(A);
        TabSession.Index expected = files.publish(frame(second.id, Map.of(first.filename(), ONE, second.filename(), TWO), first, second));
        Path firstFile = dir.resolve(first.filename()), secondFile = dir.resolve(second.filename());
        byte[] raw = Files.readAllBytes(firstFile);
        raw[raw.length - 1] ^= 1; // same size, invalid checksum
        Files.write(firstFile, raw);
        assertSame(TabSession.StateRead.CORRUPT, files.readState(first));
        assertArrayEquals(TWO, files.readState(second).bytes);
        Files.copy(secondFile, firstFile, StandardCopyOption.REPLACE_EXISTING);
        assertSame(TabSession.StateRead.CORRUPT, files.readState(first)); // even equal URLs cannot validate another tab's state
        Files.delete(firstFile);
        TabSessionFiles restart = read(dir, legacy);
        assertEquals(expected, restart.load());
        assertSame(TabSession.StateRead.MISSING, restart.readState(first));
        assertSame(TabSession.StateRead.URL_ONLY, restart.readState(first.withState(null)));
        assertArrayEquals(TWO, restart.readState(second).bytes);
        assertEquals(A, restart.load().tabs.get(0).url);
    }

    @Test public void legacyPreferencesMigrateUrlsSelectionAndFlagsButNeverAdoptPositionalBundles() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        legacy.json = "[{\"url\":\"file:///android_asset/home.html#old\",\"state_version\":2,\"state_file\":\"tab_0.bin\"},"
                + "{\"url\":\"" + B + "\",\"desktop\":true,\"title\":\"Recovered\",\"renderer_lost\":true},"
                + "{\"url\":\"https://private.example/\",\"incognito\":true}]";
        legacy.selection = 1;
        Files.write(dir.resolve("tab_0.bin"), ONE);
        TabSessionFiles files = new TabSessionFiles(dir, legacy, message -> fail(message));
        TabSession.Index imported = files.load();
        assertEquals(2, imported.tabs.size());
        assertEquals(InternalHome.URL, imported.tabs.get(0).url);
        assertEquals(B, imported.tabs.get(1).url);
        assertEquals("Recovered", imported.tabs.get(1).title);
        assertEquals(imported.tabs.get(1).id, imported.selected);
        assertTrue(imported.tabs.get(1).desktop);
        assertTrue(imported.tabs.get(1).rendererLost);
        for (TabSession.Row row : imported.tabs) assertNull(row.state);
        assertTrue(Files.exists(dir.resolve("tab_0.bin")));
        files.publish(new TabSession.Frame(imported, Collections.emptyMap()));
        assertEquals(1, legacy.clears);
        assertTrue(Files.exists(dir.resolve("tab_0.bin"))); // no full sweep on the startup transaction
        files.maintain();
        assertEquals(1, legacy.clears); // already retired at the first durable index
        assertFalse(Files.exists(dir.resolve("tab_0.bin")));
        assertEquals(imported, read(dir, legacy).load());
        assertEquals(1, legacy.reads);
    }

    @Test public void existingUnrecognizedIndexDoesNotResurrectLegacyTabs() throws Exception {
        Path dir = temp.getRoot().toPath();
        Files.write(dir.resolve("index.json"), "{\"version\":99,\"tabs\":[]}".getBytes(StandardCharsets.UTF_8));
        Legacy stale = new Legacy();
        stale.json = "[{\"url\":\"" + A + "\"}]";
        List<String> warnings = new ArrayList<>();
        assertEquals(TabSession.EMPTY, new TabSessionFiles(dir, stale, warnings::add).load());
        assertEquals(0, stale.reads);
        assertEquals(1, warnings.size());
    }

    @Test public void invalidStateFilenameAndUnsafeUrlAreSanitizedIndependently() throws Exception {
        TabSession.Row first = capture(A), second = capture(B);
        JSONObject json = new JSONObject(TabSession.encode(new TabSession.Index(Arrays.asList(first, second), first.id)));
        json.getJSONArray("tabs").getJSONObject(0).put("state", "../../elsewhere");
        json.getJSONArray("tabs").getJSONObject(1).put("url", "javascript:alert(0)");
        TabSession.Index sanitized = TabSession.decode(json.toString());
        assertEquals(first.id, sanitized.selected);
        assertEquals(A, sanitized.tabs.get(0).url);
        assertEquals(InternalHome.URL, sanitized.tabs.get(1).url);
        assertNull(sanitized.tabs.get(0).state);
        assertNull(sanitized.tabs.get(1).state);
    }

    @Test public void aReachableGenerationCannotBeOverwrittenToChangeItsUrl() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        TabSessionFiles files = read(dir, legacy);
        TabSession.Row first = capture(A);
        files.publish(frame(first.id, Map.of(first.filename(), ONE), first));
        TabSession.Row incompatible = new TabSession.Row(first.id, B, "New", false, false, first.state);
        assertEquals(first.filename(), incompatible.filename());
        TabSession.Index result = files.publish(frame(first.id, Map.of(first.filename(), TWO), incompatible));
        assertNull(result.tabs.get(0).state);
        assertEquals(B, result.tabs.get(0).url);
        assertFalse(Files.exists(dir.resolve(first.filename())));
    }

    @Test public void wipeReportsOnlyWhatCouldBeRemovedAndPreservesUnrelatedFiles() throws Exception {
        Path dir = temp.getRoot().toPath();
        Legacy legacy = new Legacy();
        TabSessionFiles files = read(dir, legacy);
        TabSession.Row first = capture(A);
        files.publish(frame(first.id, Map.of(first.filename(), ONE), first));
        legacy.json = "[{\"url\":\"" + B + "\"}]";
        Path unrelated = Files.write(dir.resolve("not-session-data.txt"), TWO);
        Path undeletable = Files.createDirectory(dir.resolve("tab_9.bin"));
        Files.write(undeletable.resolve("child"), ONE);
        assertFalse(files.wipe());
        assertNull(legacy.json);
        assertEquals(TabSession.EMPTY, read(dir, legacy).load());
        assertFalse(Files.exists(dir.resolve(first.filename())));
        assertArrayEquals(TWO, Files.readAllBytes(unrelated));
        Files.delete(undeletable.resolve("child"));
        assertTrue(files.wipe());
        assertFalse(Files.exists(undeletable));
    }

    static TabSessionFiles read(Path dir, Legacy legacy) {
        TabSessionFiles files = new TabSessionFiles(dir, legacy, message -> fail(message));
        files.load();
        return files;
    }

    static TabSession.Row capture(String url) {
        return new TabSession.Row(UUID.randomUUID().toString(), url, "Fixture", false, false, UUID.randomUUID().toString());
    }

    static TabSession.Frame frame(String selected, Map<String, byte[]> bytes, TabSession.Row... rows) {
        return new TabSession.Frame(new TabSession.Index(Arrays.asList(rows), selected), bytes);
    }

    static void rename(Path stage, Path destination) throws IOException {
        Files.move(stage, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}

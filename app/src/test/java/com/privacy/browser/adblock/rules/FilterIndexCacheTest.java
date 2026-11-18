package com.privacy.browser.adblock.rules;

import com.privacy.browser.adblock.rules.FilterSnapshot.Network;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Queue;

import static org.junit.Assert.*;

public class FilterIndexCacheTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void readyIndexPreservesExactTextAndDecisionsIncludingLegacyAndDuplicateScopes() throws Exception {
        Fixture f = new Fixture();
        EnumMap<Part, String> text = rules();
        // Repeated scopes in one line are legal and don't prevent a cache hit.
        text.put(Part.COSMETIC, "! Cosmetic comment\n##div:is(.ad,[title=\"é,a\"])\n"
                + "a.test,a.test,a.test,a.test,b.test##.sponsored\n##+js(inert)\n");
        FilterSnapshot parsed = f.cache().loadOrBuild(text);
        f.finishWrite();
        FilterSnapshot ready = f.cache().loadOrBuild(text);
        assertTrue("the second opener must use the ready index", f.writer.isEmpty());
        assertTextsAndCounts(parsed, ready);
        assertThrows(IllegalArgumentException.class, ready::requireSupported);
        assertEquals(2, ready.hostCount());
        assertEquals(Network.BLOCK, ready.network("child.BLOCKED.test.", "/anything", null));
        assertEquals(Network.BLOCK, ready.network("path.test", "/Track%2FPage", "ID=12"));
        assertEquals(Network.NONE, ready.network("path.test", "/Track%2FPage", "ID=123"));
        assertEquals(Network.EXCEPTION, ready.network("path.test", "/Track%2FPage", "ID=12&allowed=1"));
        assertEquals(Network.BLOCK, ready.network("sub.custom.test", "/resource.js", null));
        assertEquals(Network.EXCEPTION, ready.network("blocked.test", "/ok", null));
        for (String host : Arrays.asList(null, "a.test", "b.test", "sub.a.test", "not-a.test", "custom.test")) {
            assertEquals(parsed.cosmeticCss(host), ready.cosmeticCss(host));
        }
        assertTrue(ready.cosmeticCss("custom.test").contains("#my-filter"));
        assertFalse(ready.cosmeticCss("a.test").contains("+js"));
    }

    @Test public void exactSourceEditsInAnySectionInvalidateAnOtherwiseMatchingCache() throws Exception {
        Fixture f = new Fixture();
        EnumMap<Part, String> text = rules();
        f.cache().loadOrBuild(text);
        f.finishWrite();
        for (Part part : Part.values()) {
            // Comments and newline changes must also be bound, not merely the resulting rule counts.
            EnumMap<Part, String> changed = new EnumMap<>(text);
            changed.put(part, "! Different bytes\r\n" + text.get(part));
            FilterSnapshot ready = f.cache().loadOrBuild(changed);
            assertEquals(changed.get(part), ready.text(part));
            assertEquals("must rebuild for change in " + part, 1, f.writer.size());
            f.writer.clear(); // leave the original on-disk index for the next iteration
        }
    }

    @Test public void corruptTruncatedOversizedAndUnknownVersionCachesRebuildFromExactText() throws Exception {
        Fixture f = new Fixture();
        EnumMap<Part, String> text = rules();
        FilterSnapshot expected = f.cache().loadOrBuild(text);
        f.finishWrite();
        byte[] good = Files.readAllBytes(f.index);
        byte[] corrupt = good.clone();
        corrupt[41] ^= 1;
        byte[] unknown = good.clone();
        unknown[3] = '2';
        checksum(unknown); // a valid checksum alone cannot make an incompatible version usable
        byte[] invalidCount = good.clone();
        ByteBuffer.wrap(invalidCount).putInt(36, Integer.MAX_VALUE);
        checksum(invalidCount);
        for (byte[] bad : new byte[][] {corrupt, Arrays.copyOf(good, good.length - 3), unknown, invalidCount}) {
            Files.write(f.index, bad);
            assertTextsAndCounts(expected, f.cache().loadOrBuild(text));
            f.finishWrite();
            f.cache().loadOrBuild(text);
            assertTrue(f.writer.isEmpty());
        }
        try (RandomAccessFile file = new RandomAccessFile(f.index.toFile(), "rw")) { file.setLength(17L * 1024 * 1024); }
        assertTextsAndCounts(expected, f.cache().loadOrBuild(text));
        f.finishWrite();
        assertTrue(Files.size(f.index) < 1024 * 1024);
    }

    @Test public void authoritativeStoreCommitKeepsOldOrMissingCacheFromOverridingCustomRules() throws Exception {
        Fixture f = new Fixture();
        Path source = f.index.resolveSibling("rules-v2.bin");
        FilterSnapshot before = f.cache().loadOrBuild(rules());
        f.finishWrite();
        byte[] oldIndex = Files.readAllBytes(f.index);
        FilterIndexCache edits = f.cache();
        FilterStore store = new FilterStore(source, () -> before, edits);
        store.edit(Part.USER_FILTERS, "||changed.test^\r\nchanged.test##.saved\r\n");
        store.edit(Part.NETWORK, "@@||changed.test/ok^\n");
        assertEquals(1, f.writer.size()); // only the newest committed snapshot remains queued
        assertArrayEquals(oldIndex, Files.readAllBytes(f.index));
        FilterSnapshot latest = store.current();
        assertEquals(Network.BLOCK, latest.network("changed.test", "/first.js", null));
        assertEquals(Network.EXCEPTION, latest.network("changed.test", "/ok", null));
        FilterStore staleIndex = new FilterStore(source, () -> { throw new AssertionError("expected authored rules"); }, f.cache());
        assertTextsAndCounts(latest, staleIndex.current());
        assertEquals(Network.NONE, staleIndex.current().network("custom.test", "/resource.js", null));
        assertEquals(Network.BLOCK, staleIndex.current().network("changed.test", "/first.js", null));
        f.writer.remove().run(); // publish the editor's latest derived state
        f.writer.clear();

        FilterStore reopened = new FilterStore(source, () -> { throw new AssertionError("expected authored rules"); }, f.cache());
        assertTextsAndCounts(latest, reopened.current());
        assertTrue(f.writer.isEmpty());
        Files.delete(f.index);
        assertTextsAndCounts(latest, new FilterStore(source, () -> before, f.cache()).current());
        f.finishWrite();
    }

    @Test public void readableCacheIsNotAuthorityForAnUnreadableSource() throws Exception {
        Fixture f = new Fixture();
        FilterSnapshot cached = f.cache().loadOrBuild(rules());
        f.finishWrite();
        Path storeFile = Files.write(f.index.resolveSibling("rules-v2.bin"), new byte[] {1, 2, 3});
        FilterSnapshot legacy = cached.replace(Part.USER_FILTERS, "");
        FilterStore damaged = new FilterStore(storeFile, () -> legacy, f.cache());
        assertNotNull(damaged.recoveryWarning());
        assertSame(legacy, damaged.current());
        assertThrows(IOException.class, () -> damaged.commit(legacy, cached));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(storeFile));
    }

    @Test public void bundledRulesWarmOpenWithoutRebuilding() throws Exception {
        Fixture f = new Fixture();
        Path assets = Path.of("src/main/assets/filters");
        if (!Files.isDirectory(assets)) assets = Path.of("app/src/main/assets/filters");
        EnumMap<Part, String> text = rules();
        text.put(Part.HOSTS, new String(Files.readAllBytes(assets.resolve("hosts.txt")), StandardCharsets.UTF_8));
        text.put(Part.COSMETIC, new String(Files.readAllBytes(assets.resolve("cosmetic_rules.txt")), StandardCharsets.UTF_8));
        FilterSnapshot parsed = f.cache().loadOrBuild(text);
        assertTrue(parsed.hostCount() > 100_000);
        f.finishWrite();
        FilterSnapshot ready = f.cache().loadOrBuild(text);
        assertTrue(f.writer.isEmpty());
        assertTextsAndCounts(parsed, ready);
        assertEquals(parsed.cosmeticCss("www.youtube.com"), ready.cosmeticCss("www.youtube.com"));
    }

    private final class Fixture {
        final Path index = temp.getRoot().toPath().resolve("rules-index-v1.bin");
        final Queue<Runnable> writer = new ArrayDeque<>();
        FilterIndexCache cache() { return new FilterIndexCache(index, writer::add); }
        void finishWrite() {
            assertEquals(1, writer.size());
            writer.remove().run();
            assertTrue(Files.isRegularFile(index));
        }
    }

    private static void checksum(byte[] encoded) throws Exception {
        int end = encoded.length - 32;
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(encoded, end));
        System.arraycopy(hash, 0, encoded, end, hash.length);
    }

    private static EnumMap<Part, String> rules() {
        EnumMap<Part, String> text = new EnumMap<>(Part.class);
        text.put(Part.HOSTS, "! Hosts\r\n0.0.0.0 blocked.test\r\nblocked.test # Duplicate\r\nother.test\r\n");
        text.put(Part.NETWORK, "||path.test/Track%2FPage?ID=12^\n@@||path.test/Track%2FPage?ID=12&allowed=1^\n");
        text.put(Part.COSMETIC, "##.global\n");
        text.put(Part.USER_FILTERS, "||custom.test/resource.js^\r\n@@||blocked.test/ok^\r\ncustom.test###my-filter\r\n");
        return text;
    }

    private static void assertTextsAndCounts(FilterSnapshot expected, FilterSnapshot actual) {
        for (Part part : Part.values()) {
            assertEquals(expected.text(part), actual.text(part));
            assertEquals(expected.bytes(part), actual.bytes(part));
            assertEquals(expected.ruleCount(part), actual.ruleCount(part));
            assertEquals(expected.unsupported(part), actual.unsupported(part));
        }
        assertEquals(expected.hostCount(), actual.hostCount());
    }
}

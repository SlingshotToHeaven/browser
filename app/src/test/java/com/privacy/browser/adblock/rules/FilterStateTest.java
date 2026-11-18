package com.privacy.browser.adblock.rules;

import com.privacy.browser.adblock.cloud.AdBlockDiffEngine;
import com.privacy.browser.adblock.cloud.AdBlockDiffEngine.ApplyMode;
import com.privacy.browser.adblock.rules.FilterSnapshot.Network;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class FilterStateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void networkExceptionsKeepTheEncodedCaseSensitivePathAndQuery() {
        FilterSnapshot snapshot = snapshot("tracker.example.invalid\n", "||example.invalid/Track%2FFile?ID=12^\n"
                + "@@||tracker.example.invalid/Allow?token=YES^\n", "", "||example.invalid/other\n@@||example.invalid/other/allowed^\n");
        assertEquals(Network.BLOCK, snapshot.network("Sub.Example.Invalid", "/Track%2FFile", "ID=12&x=y"));
        assertEquals(Network.NONE, snapshot.network("sub.example.invalid", "/track%2FFile", "ID=12"));
        assertEquals(Network.NONE, snapshot.network("sub.example.invalid", "/Track/File", "ID=12"));
        assertEquals(Network.NONE, snapshot.network("sub.example.invalid", "/Track%2FFile", "ID=123"));
        assertEquals(Network.NONE, snapshot.network("otherexample.invalid", "/Track%2FFile", "ID=12"));
        assertEquals(Network.BLOCK, snapshot.network("tracker.example.invalid", "/Allow", "token=yes"));
        assertEquals(Network.EXCEPTION, snapshot.network("tracker.example.invalid", "/Allow", "token=YES&other=1"));
        assertEquals(Network.BLOCK, snapshot.network("tracker.example.invalid", "/AllowMore", "token=YES"));
        assertEquals(Network.EXCEPTION, snapshot.network("example.invalid", "/other/allowed", null));
    }

    @Test public void globalsAndScopedSelectorListsRemainIntact() {
        String global = "div:is(.ad, [data-slot=\"x,y\"])";
        String scoped = "#promo\\:banner, [title=\"a,b\"]";
        FilterSnapshot rules = snapshot("", "", "##" + global, "www.example.invalid, m.example.invalid##" + scoped);
        assertTrue(rules.cosmeticCss("cdn.example.invalid").startsWith(global + " {"));
        assertFalse(rules.cosmeticCss("example.invalid").contains(scoped));
        assertTrue(rules.cosmeticCss("www.example.invalid").contains(scoped + " {"));
        assertTrue(rules.cosmeticCss("sub.m.example.invalid").contains(scoped + " {"));
        assertEquals(scoped, FilterSnapshot.pickerSelector("www.example.invalid##" + scoped, "www.example.invalid"));
        assertThrows(IllegalArgumentException.class, () -> FilterSnapshot.pickerSelector("##" + global, "www.example.invalid"));
    }

    @Test public void makeSameAndMergeAreDifferentAndSurviveRestart() throws Exception {
        FilterSnapshot local = snapshot("# local\r\nold.example.invalid\r\n", "||old.example.invalid/ad\n", "##.old\n", "##.keep\n");
        FilterSnapshot remote = snapshot("# published\r\nnew.example.invalid\r\n", "", "##.new, :is(.a,.b)\n", "##.keep");
        Path file = temp.newFolder().toPath().resolve("rules-v2.bin");
        FilterStore store = new FilterStore(file, () -> local);
        AdBlockDiffEngine.Plan exact = AdBlockDiffEngine.preview(store.current(), remote, ApplyMode.MAKE_SAME);
        AdBlockDiffEngine.Plan merge = AdBlockDiffEngine.preview(store.current(), remote, ApplyMode.MERGE);
        assertEquals(4, exact.changes.size());
        assertTrue(exact.changes.stream().anyMatch(c -> c.part == Part.NETWORK && c.removed == 1));
        assertEquals("# local\r\nold.example.invalid\r\nnew.example.invalid\n", merge.result.text(Part.HOSTS));
        assertEquals(local.text(Part.NETWORK), merge.result.text(Part.NETWORK));
        assertEquals("##.old\n##.new, :is(.a,.b)\n", merge.result.text(Part.COSMETIC));
        assertEquals(local.text(Part.USER_FILTERS), merge.result.text(Part.USER_FILTERS));
        store.commit(exact.expected, exact.result);
        assertThrows(IOException.class, () -> store.commit(merge.expected, merge.result));
        FilterStore reopened = new FilterStore(file, () -> { throw new AssertionError("valid snapshot must not use legacy files"); });
        for (Part part : Part.values()) assertEquals(remote.text(part), reopened.current().text(part));
        assertEquals(Network.NONE, reopened.current().network("old.example.invalid", "/ad", null));
        assertSame(reopened.current(), AdBlockDiffEngine.preview(reopened.current(), remote, ApplyMode.MAKE_SAME).result);
    }

    @Test public void failedCommitKeepsDiskAndLiveSnapshotAndDeletesStage() throws Exception {
        Path root = temp.newFolder().toPath();
        Path file = root.resolve("rules-v2.bin");
        FilterSnapshot legacy = snapshot("", "", "", "");
        FilterStore seed = new FilterStore(file, () -> legacy);
        seed.edit(Part.COSMETIC, "##.previous\n");
        byte[] beforeBytes = Files.readAllBytes(file);
        FilterStore failing = new FilterStore(file, () -> legacy, (stage, dst) -> {
            assertTrue(Files.size(stage) > 20);
            throw new IOException("injected rename failure");
        });
        FilterSnapshot before = failing.current();
        FilterSnapshot replacement = snapshot("new.example.invalid", "||example.invalid/ad\n", "##.different", "##.new");
        assertThrows(IOException.class, () -> failing.commit(before, replacement));
        assertSame(before, failing.current());
        assertArrayEquals(beforeBytes, Files.readAllBytes(file));
        try (java.util.stream.Stream<Path> files = Files.list(root)) { assertEquals(1L, files.count()); }
    }

    @Test public void concurrentPreviewsCannotOverwriteEachOther() throws Exception {
        Path file = temp.newFolder().toPath().resolve("rules-v2.bin");
        FilterSnapshot before = snapshot("", "", "", "");
        FilterStore store = new FilterStore(file, () -> before);
        FilterSnapshot first = before.replace(Part.NETWORK, "||one.example.invalid^\n");
        FilterSnapshot second = before.replace(Part.COSMETIC, "##.second\n");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = workers.submit(() -> commit(store, before, first, start));
            Future<Boolean> b = workers.submit(() -> commit(store, before, second, start));
            start.countDown();
            boolean wonA = a.get(5, TimeUnit.SECONDS);
            assertNotEquals(wonA, b.get(5, TimeUnit.SECONDS));
            assertSame(wonA ? first : second, store.current());
            assertEquals(store.current().text(Part.NETWORK), new FilterStore(file, () -> before).current().text(Part.NETWORK));
        } finally { workers.shutdownNow(); }
    }

    @Test public void unsupportedLegacyTextIsInertAndDamagedSnapshotsAreReadOnly() throws Exception {
        FilterSnapshot legacy = FilterSnapshot.parse(parts("", "||bad.example.invalid/ad$script\n", "##.ok\n##+js(inert)", ""), false);
        assertEquals(1, legacy.unsupported(Part.NETWORK));
        assertEquals(1, legacy.unsupported(Part.COSMETIC));
        assertEquals(Network.NONE, legacy.network("bad.example.invalid", "/ad", null));
        assertFalse(legacy.cosmeticCss("example.invalid").contains("+js"));
        assertThrows(IllegalArgumentException.class, legacy::requireSupported);
        Path file = temp.newFolder().toPath().resolve("rules-v2.bin");
        byte[] original = {1, 2, 3};
        Files.write(file, original);
        FilterStore store = new FilterStore(file, () -> legacy);
        assertNotNull(store.recoveryWarning());
        assertSame(legacy, store.current());
        assertThrows(IOException.class, () -> store.edit(Part.NETWORK, ""));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test public void rejectsMalformedOrExecutableRulesWithNoWidening() {
        for (String candidate : List.of("##+js(test)", "##.foo { color: red; }", "##.a,", "#@#.a", "example.invalid#@#.a",
                "##[title=\"incomplete]", "example.invalid,##.bad", "##.ad:has-text(a)", "##a\\", "##.bad\u0000")) {
            assertThrows(candidate, IllegalArgumentException.class, () -> snapshot("", "", candidate, ""));
        }
        assertThrows(IllegalArgumentException.class, () -> snapshot("example.invalid##.bad", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> snapshot("", "@@||example.invalid/skip*", "", ""));
        assertThrows(IllegalArgumentException.class, () -> snapshot("", "!".repeat(Part.NETWORK.byteLimit + 1), "", ""));
        assertThrows(IllegalArgumentException.class, () -> FilterSnapshot.parse(new EnumMap<>(Part.class), true));
    }

    @Test public void bundledHostsAndCosmeticsParseAndAFullReplacementCanBeReopened() throws Exception {
        Path assets = Paths.get("src/main/assets/filters");
        if (!Files.isDirectory(assets)) assets = Paths.get("app/src/main/assets/filters");
        String hosts = new String(Files.readAllBytes(assets.resolve("hosts.txt")), StandardCharsets.UTF_8);
        String cosmetic = new String(Files.readAllBytes(assets.resolve("cosmetic_rules.txt")), StandardCharsets.UTF_8);
        FilterSnapshot incoming = snapshot(hosts, "", cosmetic, "");
        assertTrue(incoming.hostCount() > 100_000);
        FilterSnapshot before = snapshot("old.example.invalid", "", "", "");
        Path file = temp.newFolder().toPath().resolve("rules-v2.bin");
        FilterStore store = new FilterStore(file, () -> before);
        AdBlockDiffEngine.Plan plan = AdBlockDiffEngine.preview(before, incoming, ApplyMode.MAKE_SAME);
        store.commit(plan.expected, plan.result);
        FilterSnapshot restarted = new FilterStore(file, () -> before).current();
        assertEquals(hosts, restarted.text(Part.HOSTS));
        assertEquals(cosmetic, restarted.text(Part.COSMETIC));
        assertEquals(Network.NONE, restarted.network("old.example.invalid", "/", null));
    }

    private static boolean commit(FilterStore store, FilterSnapshot before, FilterSnapshot after, CountDownLatch start) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { store.commit(before, after); return true; }
        catch (IOException e) { assertTrue(e.getMessage().contains("changed after preview")); return false; }
    }

    private static FilterSnapshot snapshot(String hosts, String network, String cosmetic, String user) {
        return FilterSnapshot.parse(parts(hosts, network, cosmetic, user), true);
    }

    private static EnumMap<Part, String> parts(String hosts, String network, String cosmetic, String user) {
        EnumMap<Part, String> text = new EnumMap<>(Part.class);
        text.put(Part.HOSTS, hosts);
        text.put(Part.NETWORK, network);
        text.put(Part.COSMETIC, cosmetic);
        text.put(Part.USER_FILTERS, user);
        return text;
    }
}

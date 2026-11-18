package com.privacy.browser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.privacy.browser.TabSession.StateRead;
import static com.privacy.browser.TabSessionFilesTest.ONE;
import static com.privacy.browser.TabSessionFilesTest.capture;
import static org.junit.Assert.*;

public class LazyTabRestoreTest {
    private static final class Read {
        final TabSession.Row row;
        final Consumer<StateRead> done;
        Read(TabSession.Row row, Consumer<StateRead> done) { this.row = row; this.done = done; }
    }

    private static final class Fixture {
        final TabsManager tabs = new TabsManager();
        final List<Read> reads = new ArrayList<>();
        final List<TabModel> permitted = new ArrayList<>();
        final List<StateRead> results = new ArrayList<>();
        final LazyTabRestore<TabModel> restore = new LazyTabRestore<>((row, done) -> reads.add(new Read(row, done)),
                (tab, row, result) -> {
                    assertSame(tabs.getActiveTab(), tab);
                    assertSame(tab.recovery(), row);
                    tab.discardRecovery();
                    permitted.add(tab);
                    results.add(result);
                });

        Fixture(List<TabSession.Row> rows, int selected) {
            tabs.setListener(new TabsManager.TabChangeListener() {
                @Override public void onTabSwitched(TabModel tab) { restore.select(tab, tab.recovery()); }
                @Override public void onTabCountChanged(int count) {}
            });
            tabs.restoreIndex(new TabSession.Index(rows, rows.get(selected).id));
        }
    }

    @Test public void thirtySavedTabsInstallMetadataButOnlySelectedRowCanReadOrMaterialize() {
        List<TabSession.Row> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(new TabSession.Row(UUID.randomUUID().toString(), "https://fixture.test/" + i, "Title " + i,
                    i == 23, false, UUID.randomUUID().toString()));
        }
        Fixture f = new Fixture(rows, 23);
        assertEquals(23, f.tabs.getActiveIndex());
        assertTrue(f.reads.isEmpty());
        for (int i = 0; i < rows.size(); i++) {
            TabModel tab = f.tabs.getTabs().get(i);
            assertEquals(rows.get(i).title, tab.getTitle());
            assertEquals(rows.get(i).url, tab.getUrl());
            assertEquals(rows.get(i).desktop, tab.isDesktopMode());
            assertSame(rows.get(i), tab.recovery());
            assertNull(tab.getWebView());
            assertNull(tab.navigation.page());
        }
        f.tabs.switchTab(23);
        f.tabs.switchTab(23); // reattaching the placeholder does not enqueue another read
        assertEquals(1, f.reads.size());
        assertSame(rows.get(23), f.reads.get(0).row);
        assertTrue(f.permitted.isEmpty());
        f.reads.get(0).done.accept(StateRead.loaded(ONE));
        assertEquals(List.of(f.tabs.getTabs().get(23)), f.permitted);
        assertEquals(1, f.reads.size());

        f.tabs.switchTab(7);
        assertEquals(2, f.reads.size());
        assertSame(rows.get(7), f.reads.get(1).row);
        f.reads.get(1).done.accept(StateRead.loaded(ONE));
        assertEquals(List.of(f.tabs.getTabs().get(23), f.tabs.getTabs().get(7)), f.permitted);
    }

    @Test public void switchAwayAndBackOnlyAcceptsTheNewestTicketEvenAtTheSameAddress() {
        TabSession.Row a = capture("https://fixture.test/"), b = capture(a.url);
        Fixture f = new Fixture(List.of(a, b), 0);
        f.tabs.switchTab(0);
        f.tabs.switchTab(1);
        f.tabs.switchTab(0);
        f.reads.get(0).done.accept(StateRead.loaded(ONE));
        f.reads.get(1).done.accept(StateRead.MISSING);
        assertTrue(f.permitted.isEmpty());
        f.reads.get(2).done.accept(StateRead.loaded(ONE));
        f.reads.get(2).done.accept(StateRead.loaded(ONE));
        assertEquals(List.of(f.tabs.getActiveTab()), f.permitted);
    }

    @Test public void closingSelectedPendingTabCanOnlyMaterializeItsReplacement() {
        TabSession.Row a = capture("https://fixture.test/"), b = capture(a.url).withState(null);
        Fixture f = new Fixture(List.of(a, b), 0);
        f.tabs.switchTab(0);
        TabModel closing = f.tabs.getActiveTab();
        f.tabs.closeTab(0, null); // another row remains, so no Android home-view factory is used
        f.reads.get(0).done.accept(StateRead.loaded(ONE));
        assertFalse(f.tabs.getTabs().contains(closing));
        assertEquals(List.of(f.tabs.getActiveTab()), f.permitted);
        assertEquals(List.of(StateRead.URL_ONLY), f.results);
        assertEquals(1, f.reads.size());
    }

    @Test public void directNavigationAndOwnerTeardownCancelPendingHistory() {
        TabSession.Row saved = capture("https://fixture.test/old");
        Fixture old = new Fixture(List.of(saved), 0);
        old.tabs.switchTab(0);
        old.restore.cancel(); // direct navigation replaces, not restores, this document
        old.tabs.getActiveTab().discardRecovery();
        old.reads.get(0).done.accept(StateRead.loaded(ONE));
        assertTrue(old.permitted.isEmpty());

        Fixture recreated = new Fixture(List.of(saved), 0);
        recreated.tabs.switchTab(0);
        recreated.restore.cancel(); // wipe/destroy invalidates this owner's callback
        recreated.tabs.dispose();
        recreated.reads.get(0).done.accept(StateRead.loaded(ONE));
        assertTrue(recreated.permitted.isEmpty());
        assertEquals(0, recreated.tabs.getTabCount());
    }

    @Test public void urlOnlyMissingAndCorruptAreDistinctAndRendererLossDoesNotRead() {
        TabSession.Row a = capture("https://fixture.test/");
        TabSession.Row lost = new TabSession.Row(UUID.randomUUID().toString(), a.url, "Lost", false, true, a.state);
        Fixture f = new Fixture(List.of(lost, a.withState(null), capture(a.url), capture(a.url), capture(a.url)), 0);
        f.tabs.switchTab(0);
        assertTrue(f.tabs.getActiveTab().navigation.rendererLost());
        assertTrue(f.permitted.isEmpty());
        assertTrue(f.reads.isEmpty());
        f.tabs.switchTab(1);
        assertTrue(f.reads.isEmpty());
        f.tabs.switchTab(2);
        f.reads.get(0).done.accept(StateRead.MISSING);
        f.tabs.switchTab(3);
        f.reads.get(1).done.accept(StateRead.CORRUPT);
        f.tabs.switchTab(4);
        f.reads.get(2).done.accept(StateRead.STALE);
        assertEquals(List.of(StateRead.URL_ONLY, StateRead.MISSING, StateRead.CORRUPT), f.results);
    }

    @Test public void openingInBackgroundIsMetadataOnlyUntilSelected() {
        TabsManager tabs = new TabsManager(context -> { throw new AssertionError("background view allocated"); });
        TabSession.Row saved = capture("https://fixture.test/active");
        tabs.restoreIndex(new TabSession.Index(List.of(saved), saved.id));
        TabModel active = tabs.getActiveTab();
        List<TabModel> requested = new ArrayList<>();
        LazyTabRestore<TabModel> restore = new LazyTabRestore<>((row, done) -> fail("URL-only row read a blob"),
                (tab, row, result) -> {
                    assertSame(StateRead.URL_ONLY, result);
                    assertEquals("https://fixture.test/background", row.url);
                    requested.add(tab);
                });
        tabs.setListener(new TabsManager.TabChangeListener() {
            @Override public void onTabSwitched(TabModel tab) { restore.select(tab, tab.recovery()); }
            @Override public void onTabCountChanged(int count) {}
        });
        TabModel background = tabs.queueBackgroundTab("https://fixture.test/background", false);
        assertNotNull(background);
        assertSame(active, tabs.getActiveTab());
        assertNull(background.getWebView());
        assertNull(background.navigation.page());
        assertTrue(requested.isEmpty());
        assertNull(tabs.queueBackgroundTab("file:///not-allowed", false));
        assertThrows(IllegalStateException.class, () -> tabs.queueBackgroundTab("https://fixture.test/private", true));
        assertEquals(2, tabs.getTabCount());
        tabs.switchTab(1);
        assertEquals(List.of(background), requested);
    }

    @Test public void onlyTheFreshSelectedReadCanCompleteAfterPauseOrTrim() {
        TabSession.Row a = capture("https://fixture.test/parked");
        Fixture f = new Fixture(List.of(a), 0);
        f.tabs.switchTab(0);
        f.restore.cancel(); // pause drops ownership without changing the selected row
        f.tabs.switchTab(0); // resume requests that row again
        assertEquals(2, f.reads.size());
        f.reads.get(0).done.accept(StateRead.loaded(ONE));
        assertTrue(f.permitted.isEmpty());
        f.reads.get(1).done.accept(StateRead.loaded(ONE));
        assertEquals(List.of(f.tabs.getActiveTab()), f.permitted);
    }

    @Test public void launchTargetIsValidatedBeforeChoosingWhichTabToMaterialize() {
        for (String bad : List.of("javascript:alert(1)", "file:///sdcard/page.html", "https://", "https://bad host/",
                "https://user@fixture.test/", "content://fixture/test", InternalHome.URL + "other")) {
            assertNull(bad, MainActivity.navigationTarget(bad));
        }
        assertEquals("https://fixture.test/new#a", MainActivity.navigationTarget(" https://fixture.test/new#a "));
        assertEquals(InternalHome.URL, MainActivity.navigationTarget("file:///android_asset/home.html"));
    }
}

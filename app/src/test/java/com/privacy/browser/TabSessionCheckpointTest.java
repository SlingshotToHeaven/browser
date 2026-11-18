package com.privacy.browser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class TabSessionCheckpointTest {
    private static final String A = "https://a.example/";
    private static final String B = "https://b.example/";

    private static final class Clock implements TabSessionCheckpoint.Scheduler {
        final Map<Runnable, Long> waiting = new LinkedHashMap<>();
        long time;

        @Override public long now() { return time; }
        @Override public void postAt(Runnable task, long uptime) { assertNull(waiting.put(task, uptime)); }
        @Override public void cancel(Runnable task) { waiting.remove(task); }

        void go(long target) {
            while (true) {
                Map.Entry<Runnable, Long> earliest = null;
                for (Map.Entry<Runnable, Long> candidate : waiting.entrySet()) {
                    if (candidate.getValue() <= target && (earliest == null || candidate.getValue() < earliest.getValue())) {
                        earliest = candidate;
                    }
                }
                if (earliest == null) break;
                time = earliest.getValue();
                Runnable next = earliest.getKey();
                waiting.remove(next);
                next.run();
            }
            time = target;
        }
    }

    private static final class Tab {
        final String id = UUID.randomUUID().toString();
        String url, title = "Fixture";
        boolean privateTab, lost;
        TabSessionCheckpoint.History history;
        byte[] data = {42};

        Tab(String url) {
            this.url = url;
            this.history = list(0, url);
        }

        TabSession.Row metadata() { return TabSession.regular(id, url, title, false, lost, privateTab); }
    }

    private static final class Fixture implements TabSessionCheckpoint.Source<Tab> {
        final Thread owner = Thread.currentThread();
        final Clock clock = new Clock();
        final List<Tab> probes = new ArrayList<>(), saves = new ArrayList<>();
        final List<TabSession.Frame> frames = new ArrayList<>();
        final TabSessionCheckpoint<Tab> checkpoint = new TabSessionCheckpoint<>(clock, this, frames::add);

        @Override public TabSession.Row metadata(Tab tab) { return tab.metadata(); }
        @Override public TabSessionCheckpoint.History history(Tab tab, TabSession.Row row) {
            assertSame(owner, Thread.currentThread());
            assertEquals(tab.url, row.url);
            probes.add(tab);
            return tab.history;
        }
        @Override public byte[] save(Tab tab, TabSession.Row row, TabSessionCheckpoint.History expected) {
            assertSame(owner, Thread.currentThread());
            assertSame(tab.history, expected);
            saves.add(tab);
            return tab.data;
        }

        TabSession.Row seed(Tab tab) {
            TabSession.Row saved = tab.metadata().withState(UUID.randomUUID().toString());
            checkpoint.restore(tab, saved, tab.history);
            return saved;
        }

        TabSession.Frame last() { return frames.get(frames.size() - 1); }
    }

    @Test public void selectionDeadlineDoesNotTouchEvenAlreadyDirtyWebViews() {
        Fixture f = new Fixture();
        Tab first = new Tab(A), second = new Tab(B);
        TabSession.Row original = f.seed(first), unchanged = f.seed(second);
        f.checkpoint.tabsChanged(Arrays.asList(first, second), first.id);
        f.checkpoint.select(second.id);
        f.clock.go(100);
        first.history = list(1, A, A);
        f.checkpoint.historyChanged(first);
        f.clock.go(350);
        assertEquals(second.id, f.last().index.selected);
        assertEquals(Arrays.asList(original, unchanged), f.last().index.tabs);
        assertTrue(f.last().states.isEmpty());
        assertTrue(f.probes.isEmpty());
        assertTrue(f.saves.isEmpty());
        f.clock.go(450);
        assertEquals(Arrays.asList(first), f.probes);
        assertEquals(Arrays.asList(first), f.saves);
        assertEquals(unchanged, f.last().index.tabs.get(1));
        assertEquals(1, f.last().states.size());
        f.checkpoint.select(first.id);
        f.clock.go(2000);
        assertEquals(first.id, f.last().index.selected);
        assertTrue(f.last().states.isEmpty());
        assertEquals(1, f.saves.size());
    }

    @Test public void duplicateNativeSpaNotificationsDoNotSaveButSameUrlEntriesAndBackDo() {
        Fixture f = new Fixture();
        Tab tab = new Tab(A);
        f.seed(tab);
        f.checkpoint.tabsChanged(Arrays.asList(tab), tab.id);
        for (int i = 0; i < 6; i++) {
            f.clock.go(i * 70);
            f.checkpoint.historyChanged(tab);
        }
        f.clock.go(1000);
        assertEquals(1, f.probes.size());
        assertTrue(f.saves.isEmpty());
        assertEquals(1, f.frames.size());

        tab.history = list(1, A, A); // pushState to the same URL, size and position change
        f.checkpoint.historyChanged(tab);
        f.clock.go(1500);
        String pushed = f.last().index.tabs.get(0).state;
        tab.history = list(0, A, A); // Back, URL and size stay the same
        f.checkpoint.historyChanged(tab);
        f.clock.go(2000);
        String backed = f.last().index.tabs.get(0).state;
        assertNotEquals(pushed, backed);
        assertEquals(A, f.last().index.tabs.get(0).url);
        assertEquals(2, f.saves.size());

        f.checkpoint.historyChanged(tab);
        f.clock.go(2500);
        assertEquals(backed, f.last().index.tabs.get(0).state);
        assertEquals(2, f.saves.size());
        assertEquals(3, f.frames.size());
    }

    @Test public void sameCurrentEntryButDifferentForwardStackChangesHistory() {
        Fixture f = new Fixture();
        Tab tab = new Tab(A);
        tab.history = list(0, A, B);
        f.seed(tab);
        f.checkpoint.tabsChanged(Arrays.asList(tab), tab.id);
        tab.history = list(0, A, A);
        f.checkpoint.historyChanged(tab);
        f.clock.go(500);
        assertEquals(1, f.saves.size());
    }

    @Test public void continuousNativeEventsCannotPushCapturePastTheLimit() {
        Fixture f = new Fixture();
        Tab first = new Tab(A), second = new Tab(B);
        f.checkpoint.tabsChanged(Arrays.asList(first, second), first.id);
        for (int i = 0; i < TabSessionCheckpoint.MAX_DELAY_MS; i += 100) {
            f.clock.go(i);
            f.checkpoint.historyChanged(first);
            f.checkpoint.select(i % 200 == 0 ? second.id : first.id);
        }
        f.clock.go(TabSessionCheckpoint.MAX_DELAY_MS - 1);
        assertTrue(f.saves.isEmpty());
        f.clock.go(TabSessionCheckpoint.MAX_DELAY_MS);
        assertEquals(Arrays.asList(first), f.saves);
        assertEquals(second.id, f.last().index.selected);
    }

    @Test public void metadataPublicationKeepsLastUrlAndStateUntilNewHistoryCanBeCaptured() {
        Fixture f = new Fixture();
        Tab first = new Tab(A), second = new Tab(B);
        TabSession.Row previous = f.seed(first);
        f.checkpoint.tabsChanged(Arrays.asList(first, second), first.id);
        first.url = B;
        first.history = null; // no native list agreeing with the new URL yet
        f.checkpoint.metadataChanged(first);
        f.checkpoint.historyChanged(first);
        f.checkpoint.select(second.id);
        f.clock.go(500);
        assertEquals(previous, f.last().index.tabs.get(0));
        assertEquals(second.id, f.last().index.selected);
        assertTrue(f.saves.isEmpty());

        first.history = list(1, A, B);
        f.checkpoint.historyChanged(first);
        f.clock.go(1000);
        assertEquals(B, f.last().index.tabs.get(0).url);
        assertNotEquals(previous.state, f.last().index.tabs.get(0).state);
        assertEquals(Arrays.asList(first), f.saves);
    }

    @Test public void checkpointDuringPauseCapturesOnlyDirtyRegularTabsAndCancelsTimers() {
        Fixture f = new Fixture();
        Tab first = new Tab(A), dirty = new Tab(B), privateTab = new Tab(A);
        privateTab.privateTab = true;
        f.checkpoint.tabsChanged(Arrays.asList(first, dirty, privateTab), first.id);
        f.checkpoint.select(null);
        f.checkpoint.historyChanged(privateTab);
        f.checkpoint.historyChanged(dirty);
        f.checkpoint.checkpointNow();
        assertEquals(Arrays.asList(dirty), f.saves);
        assertEquals(first.id, f.last().index.selected);
        assertEquals(2, f.last().index.tabs.size());
        assertTrue(f.clock.waiting.isEmpty());
    }

    @Test public void closingAndResettingCancelStaleCaptureAndSelection() {
        Fixture f = new Fixture();
        Tab first = new Tab(A), second = new Tab(B);
        f.checkpoint.tabsChanged(Arrays.asList(first, second), first.id);
        f.checkpoint.historyChanged(first);
        f.checkpoint.tabsChanged(Arrays.asList(second), second.id);
        assertEquals(1, f.last().index.tabs.size());
        f.checkpoint.historyChanged(first); // late notification from a closed view
        f.clock.go(500);
        assertTrue(f.probes.isEmpty());
        f.checkpoint.historyChanged(second);
        f.checkpoint.reset();
        f.clock.go(2000);
        assertTrue(f.saves.isEmpty());
        assertEquals(2, f.frames.size());
    }

    @Test public void dormantGenerationsSurviveStartupAndSelectionWithoutNativeHistory() {
        Fixture f = new Fixture();
        Tab selected = new Tab(A), dormant = new Tab(B);
        TabSession.Row savedSelected = f.seed(selected), savedDormant = dormant.metadata().withState(UUID.randomUUID().toString());
        f.checkpoint.restore(dormant, savedDormant, null);
        f.checkpoint.tabsChanged(Arrays.asList(selected, dormant), selected.id);
        f.checkpoint.select(dormant.id);
        f.clock.go(500);
        assertEquals(dormant.id, f.last().index.selected);
        assertEquals(Arrays.asList(savedSelected, savedDormant), f.last().index.tabs);
        assertTrue(f.probes.isEmpty());
        assertTrue(f.saves.isEmpty());
        assertTrue(f.last().states.isEmpty());

        f.checkpoint.forgetHistory(dormant); // only after direct navigation or a rejected restore
        f.clock.go(1000);
        assertEquals(Arrays.asList(savedSelected, savedDormant.withState(null)), f.last().index.tabs);
        assertTrue(f.probes.isEmpty());
    }

    @Test public void rendererLossCannotCaptureAndDoesNotRetainTheOldState() {
        Fixture f = new Fixture();
        Tab tab = new Tab(A);
        f.seed(tab);
        f.checkpoint.tabsChanged(Arrays.asList(tab), tab.id);
        f.checkpoint.historyChanged(tab);
        tab.lost = true;
        f.checkpoint.metadataChanged(tab);
        f.clock.go(500);
        assertTrue(f.last().index.tabs.get(0).rendererLost);
        assertNull(f.last().index.tabs.get(0).state);
        assertTrue(f.probes.isEmpty());
    }

    @Test public void transientCaptureCanRetryButKnownSizeLimitDoesNotRepeatOnDuplicates() {
        Fixture f = new Fixture();
        Tab tab = new Tab(A);
        f.seed(tab);
        f.checkpoint.tabsChanged(Arrays.asList(tab), tab.id);
        tab.history = list(1, A, A);
        tab.data = null;
        f.checkpoint.historyChanged(tab);
        f.clock.go(500);
        assertNotNull(f.last().index.tabs.get(0).state);
        tab.data = new byte[0]; // native parcel exceeded the cap
        f.checkpoint.historyChanged(tab);
        f.clock.go(1000);
        assertNull(f.last().index.tabs.get(0).state);
        f.checkpoint.historyChanged(tab);
        f.clock.go(1500);
        assertEquals(2, f.saves.size());
    }

    @Test public void evictionCapturesOnlyItsDirtyRowAndHandsItsBytesToTheWriter() {
        Fixture f = new Fixture();
        Tab selected = new Tab(A), victim = new Tab(B);
        TabSession.Row untouched = f.seed(selected), old = f.seed(victim);
        f.checkpoint.tabsChanged(List.of(selected, victim), selected.id);
        selected.history = list(1, A, A);
        victim.history = list(1, B, B);
        f.checkpoint.historyChanged(selected);
        f.checkpoint.historyChanged(victim);

        TabSession.Row parked = f.checkpoint.checkpointForEviction(victim);
        assertNotNull(parked.state);
        assertNotEquals(old.state, parked.state);
        assertEquals(List.of(untouched, parked), f.last().index.tabs);
        assertSame(victim.data, f.last().state(parked));
        assertEquals(List.of(victim), f.probes);
        assertEquals(List.of(victim), f.saves);

        f.clock.go(500); // the normal history deadline still handles the other edited stack
        assertEquals(List.of(victim, selected), f.probes);
        assertEquals(List.of(victim, selected), f.saves);
        assertEquals(parked, f.last().index.tabs.get(1));
    }

    @Test public void cleanEvictionKeepsItsRecoverableGenerationWithoutProviderCalls() {
        Fixture f = new Fixture();
        Tab tab = new Tab(A);
        TabSession.Row saved = f.seed(tab);
        f.checkpoint.tabsChanged(List.of(tab), tab.id);
        assertEquals(saved, f.checkpoint.checkpointForEviction(tab));
        assertTrue(f.probes.isEmpty());
        assertTrue(f.saves.isEmpty());
        assertEquals(1, f.frames.size());
    }

    @Test public void evictionUsesCommittedUrlWithoutIncompatibleHistoryWhenProviderCannotSave() {
        Fixture f = new Fixture();
        Tab tab = new Tab(A);
        f.seed(tab);
        f.checkpoint.tabsChanged(List.of(tab), tab.id);
        tab.url = B;
        tab.history = list(1, A, B);
        tab.data = null;
        f.checkpoint.historyChanged(tab);

        TabSession.Row parked = f.checkpoint.checkpointForEviction(tab);
        assertEquals(B, parked.url);
        assertNull(parked.state);
        assertFalse(parked.rendererLost);
        assertEquals(List.of(parked), f.last().index.tabs);
        assertTrue(f.last().states.isEmpty());
        assertEquals(List.of(tab), f.saves);
        assertTrue(f.clock.waiting.isEmpty());
    }

    @Test public void closedAndRendererLostRowsCannotBeParkedOrResurrectedByTrim() {
        Fixture f = new Fixture();
        Tab closed = new Tab(A), lost = new Tab(B);
        f.checkpoint.tabsChanged(List.of(closed, lost), closed.id);
        f.checkpoint.historyChanged(closed);
        f.checkpoint.historyChanged(lost);
        lost.lost = true;
        f.checkpoint.metadataChanged(lost);
        f.checkpoint.tabsChanged(List.of(lost), lost.id);
        assertNull(f.checkpoint.checkpointForEviction(closed));
        assertNull(f.checkpoint.checkpointForEviction(lost));
        f.clock.go(500);
        assertEquals(List.of(lost.metadata()), f.last().index.tabs);
        assertTrue(f.probes.isEmpty());
        assertTrue(f.saves.isEmpty());
    }

    private static TabSessionCheckpoint.History list(int current, String... urls) {
        List<String> entries = new ArrayList<>();
        for (String url : urls) {
            entries.add(url);
            entries.add(url);
        }
        return new TabSessionCheckpoint.History(current, entries);
    }
}

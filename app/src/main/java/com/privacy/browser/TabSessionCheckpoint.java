package com.privacy.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Main/owner-thread bookkeeping. Selection can only schedule metadata, never a history probe. */
final class TabSessionCheckpoint<T> {
    static final long QUIET_MS = 350;
    static final long MAX_DELAY_MS = 1500;

    interface Scheduler {
        long now();
        void postAt(Runnable task, long uptime);
        void cancel(Runnable task);
    }

    interface Source<T> {
        TabSession.Row metadata(T tab); // null for private; no WebView calls
        History history(T tab, TabSession.Row row);
        byte[] save(T tab, TabSession.Row row, History expected);
    }

    /** Android exposes URLs and position, not a stable provider entry ID. Do not key by current URL. */
    static final class History {
        final int current;
        final List<String> entries; // ordered (URL, original URL) pairs, including repeated URLs

        History(int current, List<String> entries) {
            this.current = current;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        @Override public boolean equals(Object other) {
            return other instanceof History && current == ((History) other).current && entries.equals(((History) other).entries);
        }

        @Override public int hashCode() { return Objects.hash(current, entries); }
    }

    private static final class Slot {
        TabSession.Row metadata;
        TabSession.Row row; // last recoverable URL/state pair, not the next pending transaction
        History checkpoint;

        Slot(TabSession.Row row, History checkpoint) {
            this.metadata = row.withState(null);
            this.row = row;
            this.checkpoint = checkpoint;
        }

        void update(TabSession.Row live) {
            metadata = live;
            if (row.compatible(live)) {
                row = live.withState(row.state);
            } else if (row.state == null || live.rendererLost) {
                row = live;
                checkpoint = null;
            }
            // Otherwise keep the last consistent pair until a matching capture (or a known
            // limit) replaces it. A metadata-only selection cannot discard good history.
        }
    }

    private final Scheduler scheduler;
    private final Source<T> source;
    private final Consumer<TabSession.Frame> sink;
    private final Map<T, Slot> tabs = new LinkedHashMap<>();
    private final Set<T> dirty = new LinkedHashSet<>();
    private final Deadline metadata;
    private final Deadline history;
    private String selected;

    TabSessionCheckpoint(Scheduler scheduler, Source<T> source, Consumer<TabSession.Frame> sink) {
        this.scheduler = scheduler;
        this.source = source;
        this.sink = sink;
        metadata = new Deadline(() -> publish(Collections.emptyMap()));
        history = new Deadline(() -> {
            Map<String, byte[]> states = captureChanged();
            if (states != null) publish(states);
        });
    }

    void restore(T tab, TabSession.Row row, History restoredHistory) {
        // Dormant tabs keep their generation without reading the blob. A rejected restore
        // explicitly forgets that generation; absence of a live view is not corruption.
        tabs.put(tab, new Slot(row, restoredHistory));
    }

    void tabsChanged(List<T> live, String selectedId) {
        Map<T, Slot> next = new LinkedHashMap<>();
        for (T tab : live) {
            TabSession.Row row = source.metadata(tab);
            if (row == null) continue;
            Slot slot = tabs.get(tab);
            if (slot == null) slot = new Slot(row, null);
            else slot.update(row);
            next.put(tab, slot);
        }
        tabs.clear();
        tabs.putAll(next);
        dirty.retainAll(tabs.keySet());
        if (dirty.isEmpty()) history.cancel();
        if (selectedId != null) selected = selectedId;
        // Enqueue the new set immediately (still no capture or disk on the UI thread), so
        // an in-flight capture cannot permanently reintroduce a tab that was closed.
        publish(Collections.emptyMap());
    }

    void select(String regularId) {
        // Private selection (null) does not overwrite the last regular selection.
        if (regularId == null || regularId.equals(selected)) return;
        selected = regularId;
        metadata.signal();
    }

    void metadataChanged(T tab) {
        Slot slot = tabs.get(tab);
        if (slot == null) return;
        TabSession.Row before = slot.row;
        slot.update(source.metadata(tab));
        if (!before.equals(slot.row)) metadata.signal();
    }

    void historyChanged(T tab) {
        if (!tabs.containsKey(tab)) return;
        dirty.add(tab);
        history.signal();
    }

    void forgetHistory(T tab) {
        Slot slot = tabs.get(tab);
        if (slot == null) return;
        dirty.remove(tab);
        if (dirty.isEmpty()) history.cancel();
        slot.checkpoint = null;
        if (slot.row.state != null) {
            slot.row = slot.metadata.withState(null);
            metadata.signal();
        }
    }

    /** Park only the chosen inactive tab. Clean rows keep their generation with no provider calls. */
    TabSession.Row checkpointForEviction(T tab) {
        Slot slot = tabs.get(tab);
        if (slot == null) return null;
        TabSession.Row live = source.metadata(tab);
        if (live == null || live.rendererLost) return null;
        TabSession.Row before = slot.row;
        slot.update(live);
        Map<String, byte[]> states = new LinkedHashMap<>();
        if (dirty.remove(tab)) capture(tab, slot, TabSession.MAX_STATE, states);
        if (dirty.isEmpty()) history.cancel();
        if (tabs.get(tab) != slot) return null;
        // If the currently committed address no longer matches old history, keep the current
        // address rather than carrying an incompatible bundle to another WebView.
        if (!slot.row.compatible(slot.metadata)) {
            slot.row = slot.metadata;
            slot.checkpoint = null;
        }
        if (!before.equals(slot.row)) publish(states);
        return slot.row;
    }

    void checkpointNow() {
        history.cancel();
        Map<String, byte[]> states = captureChanged();
        publish(states == null ? Collections.emptyMap() : states);
    }

    void reset() {
        metadata.cancel();
        history.cancel();
        tabs.clear();
        dirty.clear();
        selected = null;
    }

    /** At most one save per changed tab; no history APIs on clean, lost, closed or private tabs. */
    private Map<String, byte[]> captureChanged() {
        if (dirty.isEmpty()) return null;
        List<T> candidates = new ArrayList<>(dirty);
        dirty.clear();
        // Give the visible tab first use of the finite handoff budget.
        for (int i = 0; i < candidates.size(); i++) {
            if (tabs.get(candidates.get(i)).row.id.equals(selected)) {
                if (i > 0) Collections.swap(candidates, 0, i);
                break;
            }
        }
        int remaining = TabSession.MAX_BATCH;
        Map<String, byte[]> states = new LinkedHashMap<>();
        boolean changed = false;
        for (T tab : candidates) {
            Slot slot = tabs.get(tab);
            if (slot == null || slot.metadata.rendererLost) continue;
            TabSession.Row before = slot.row;
            remaining -= capture(tab, slot, remaining, states);
            changed |= !before.equals(slot.row);
        }
        return changed ? states : null;
    }

    private int capture(T tab, Slot slot, int remaining, Map<String, byte[]> states) {
        TabSession.Row live = slot.metadata;
        History observed = source.history(tab, live);
        if (observed == null || (slot.row.compatible(live) && observed.equals(slot.checkpoint))) return 0;
        byte[] bytes = remaining > 0 ? source.save(tab, live, observed) : new byte[0];
        if (bytes == null || tabs.get(tab) != slot || !live.equals(slot.metadata)) return 0;
        slot.checkpoint = observed;
        boolean usable = bytes.length > 0 && bytes.length <= TabSession.MAX_STATE && bytes.length <= remaining;
        slot.row = live.withState(usable ? UUID.randomUUID().toString() : null);
        if (!usable) return 0;
        states.put(slot.row.filename(), bytes);
        return bytes.length;
    }

    private void publish(Map<String, byte[]> states) {
        metadata.cancel();
        List<TabSession.Row> rows = new ArrayList<>();
        for (Slot slot : tabs.values()) rows.add(slot.row);
        sink.accept(new TabSession.Frame(new TabSession.Index(rows, selected), states));
    }

    private final class Deadline implements Runnable {
        private final Runnable task;
        private long earliest, due;
        private boolean scheduled;

        Deadline(Runnable task) { this.task = task; }

        void signal() {
            long now = scheduler.now();
            if (!scheduled) {
                earliest = now;
                due = now + QUIET_MS;
                scheduled = true;
                scheduler.postAt(this, due);
            } else {
                due = Math.min(earliest + MAX_DELAY_MS, now + QUIET_MS);
            }
        }

        void cancel() {
            if (!scheduled) return;
            scheduled = false;
            scheduler.cancel(this);
        }

        @Override public void run() {
            if (!scheduled) return;
            if (scheduler.now() < due) {
                scheduler.postAt(this, due);
                return;
            }
            scheduled = false;
            task.run();
        }
    }
}

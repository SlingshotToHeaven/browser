package com.privacy.browser.bookmarks;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class BookmarkTreeTest {
    @Test public void repairCutsOnlyInvalidParentsAndOneEdgePerCycle() {
        Map<Long, BookmarkTree.Link> rows = new LinkedHashMap<>();
        rows.put(1L, folder(0));
        rows.put(2L, folder(1));
        rows.put(3L, bookmark(2));
        rows.put(4L, bookmark(3)); // A bookmark cannot own children.
        rows.put(5L, folder(22));  // Smaller than a cycle member, but not in the cycle.
        rows.put(6L, folder(99));  // Missing parent; keep its children in place.
        rows.put(7L, bookmark(6));
        rows.put(8L, bookmark(BookmarkTree.INVALID_PARENT));
        rows.put(9L, folder(9));
        rows.put(22L, folder(21));
        rows.put(21L, folder(20));
        rows.put(20L, folder(22));
        rows.put(32L, folder(31));
        rows.put(31L, folder(32));

        Set<Long> repair = BookmarkTree.linksToReset(rows);
        assertEquals(Set.of(4L, 6L, 8L, 9L, 20L, 31L), repair);
        for (long id : repair) rows.put(id, new BookmarkTree.Link(0, rows.get(id).folder));
        assertTrue(BookmarkTree.linksToReset(rows).isEmpty());
        assertEquals(2, rows.get(3L).parent);
        assertEquals(22, rows.get(5L).parent);
        assertEquals(6, rows.get(7L).parent);

        BookmarkTree.Destinations rooted = new BookmarkTree.Destinations(Set.of(), rows::get);
        for (Map.Entry<Long, BookmarkTree.Link> entry : rows.entrySet()) {
            assertTrue(rooted.allows(entry.getValue().parent));
        }
    }

    @Test public void migrationWalkPreservesDeepHealthyChainsAndCutsDeepCycles() {
        int depth = 25_000;
        Map<Long, BookmarkTree.Link> rows = new LinkedHashMap<>();
        for (long id = depth; id > 0; id--) rows.put(id, folder(id - 1));
        assertTrue(BookmarkTree.linksToReset(rows).isEmpty());

        // Most of the path is outside a cycle at the end of a deep walk.
        rows.put(3L, folder(5));
        assertEquals(Set.of(3L), BookmarkTree.linksToReset(rows));
    }

    @Test public void mixedSelectionExcludesEverySelectedFolderSubtreeButNotTheirAncestors() {
        Map<Long, BookmarkTree.Link> rows = Map.of(
                1L, folder(0), 2L, folder(1), 3L, folder(2), 4L, folder(3),
                5L, folder(0), 6L, folder(5), 7L, bookmark(0), 8L, folder(0));
        BookmarkTree.Destinations destinations = new BookmarkTree.Destinations(Set.of(7L, 2L, 5L), rows::get);

        for (long id : List.of(2L, 4L, 3L, 5L, 6L, 7L)) assertFalse("destination " + id, destinations.allows(id));
        for (long id : List.of(0L, 1L, 8L)) assertTrue("destination " + id, destinations.allows(id));
    }

    @Test public void onlyRootOrAnAnchoredFolderCanReceiveNewChildren() {
        Map<Long, BookmarkTree.Link> rows = Map.of(
                1L, folder(0), 2L, bookmark(0), 3L, folder(2), 4L, folder(3),
                5L, folder(6), 6L, folder(5), 7L, folder(50), 8L, folder(8), 9L, folder(-1));
        BookmarkTree.Destinations destinations = new BookmarkTree.Destinations(Set.of(), rows::get);
        assertTrue(destinations.allows(0));
        assertTrue(destinations.allows(1));
        for (long id : List.of(-1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 50L)) {
            assertFalse("destination " + id, destinations.allows(id));
        }
    }

    @Test public void validatingEveryDestinationInADeepChainVisitsEachLinkOnlyOnce() {
        int depth = 25_000;
        AtomicInteger lookups = new AtomicInteger();
        BookmarkTree.Destinations destinations = new BookmarkTree.Destinations(Set.of(), id -> {
            lookups.incrementAndGet();
            return folder(id - 1);
        });
        for (long id = depth; id >= 0; id--) assertTrue(destinations.allows(id));
        assertEquals(depth, lookups.get());
    }

    @Test public void deletionExpandsOverlappingAndCyclicChildrenOnce() {
        Map<Long, List<Long>> children = Map.of(1L, List.of(2L, 3L, 0L), 2L, List.of(1L, 4L),
                3L, List.of(3L), 7L, List.of(8L));
        Map<Long, Integer> visits = new HashMap<>();
        assertEquals(Set.of(1L, 2L, 3L, 4L), BookmarkTree.subtree(Set.of(1L, 2L), id -> {
            visits.merge(id, 1, Integer::sum);
            return children.getOrDefault(id, List.of());
        }));
        assertEquals(Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1), visits);
    }

    @Test public void deletingDeepSubtreeHasNoCallStackLimit() {
        long depth = 25_000;
        Set<Long> deletion = BookmarkTree.subtree(Set.of(1L), id -> id == depth ? List.of() : List.of(id + 1));
        assertEquals(depth, deletion.size());
        assertTrue(deletion.contains(depth));
    }

    private static BookmarkTree.Link folder(long parent) {
        return new BookmarkTree.Link(parent, true);
    }

    private static BookmarkTree.Link bookmark(long parent) {
        return new BookmarkTree.Link(parent, false);
    }
}

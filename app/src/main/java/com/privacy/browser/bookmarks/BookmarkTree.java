package com.privacy.browser.bookmarks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

/** Parent-link rules shared by schema repair and the live folder operations. */
final class BookmarkTree {
    static final long ROOT = 0;
    static final long INVALID_PARENT = -1;

    static final class Link {
        final long parent;
        final boolean folder;

        Link(long parent, boolean folder) {
            this.parent = parent;
            this.folder = folder;
        }
    }

    private BookmarkTree() {}

    /** Reset only broken edges and one edge (at the lowest id) per cycle. */
    static Set<Long> linksToReset(Map<Long, Link> rows) {
        Set<Long> reset = new LinkedHashSet<>();
        for (Map.Entry<Long, Link> row : rows.entrySet()) {
            long parent = row.getValue().parent;
            Link parentLink = rows.get(parent);
            if (parent != ROOT && (parent < ROOT || parentLink == null || !parentLink.folder)) {
                reset.add(row.getKey());
            }
        }

        Set<Long> visited = new HashSet<>();
        for (long id : rows.keySet()) {
            if (id == ROOT || visited.contains(id)) continue;
            Set<Long> path = new HashSet<>();
            long current = id;
            while (current != ROOT && !visited.contains(current) && path.add(current)) {
                current = reset.contains(current) ? ROOT : rows.get(current).parent;
            }
            if (path.contains(current)) {
                long lowest = current;
                // Only consider the cycle, not the path that led into it.
                for (long member = rows.get(current).parent; member != current; member = rows.get(member).parent) {
                    lowest = Math.min(lowest, member);
                }
                reset.add(lowest);
            }
            visited.addAll(path);
        }
        return reset;
    }

    /** Use only within one snapshot. Cache path results so listing a deep tree takes linear work. */
    static final class Destinations {
        private final LongFunction<Link> lookup;
        private final Map<Long, Boolean> allowed = new HashMap<>();

        Destinations(Set<Long> excluded, LongFunction<Link> lookup) {
            this.lookup = lookup;
            for (long id : excluded) allowed.put(id, false);
            allowed.put(ROOT, true);
        }

        boolean allows(long id) {
            Set<Long> path = new HashSet<>();
            long current = id;
            while (current > ROOT && !allowed.containsKey(current) && path.add(current)) {
                Link link = lookup.apply(current);
                if (link == null || !link.folder) break;
                current = link.parent;
            }
            boolean result = Boolean.TRUE.equals(allowed.get(current));
            for (long member : path) allowed.put(member, result);
            return result;
        }
    }

    /** The caller supplies existing roots and follows child links only from actual folders. */
    static Set<Long> subtree(Set<Long> roots, LongFunction<Iterable<Long>> children) {
        Set<Long> visited = new LinkedHashSet<>(roots);
        Deque<Long> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            for (long child : children.apply(pending.removeFirst())) {
                if (child > ROOT && visited.add(child)) pending.addLast(child);
            }
        }
        return visited;
    }
}

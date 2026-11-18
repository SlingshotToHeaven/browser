package com.privacy.downloader.ui;

import com.privacy.downloader.db.DownloadHistory;
import com.privacy.downloader.db.DownloadHistory.Row;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loaded prefix of a stable SQL order. The paging boundary is a key, not a position or a live row. */
final class DownloadPages {
    interface Edits {
        void inserted(int at, int count);
        void removed(int at);
        void moved(int from, int to);
        void changed(int at);
    }

    private final DownloadHistory.Sort sort;
    private final Edits edits;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Row> byId = new HashMap<>();
    private Row boundary;
    private boolean more;

    DownloadPages(DownloadHistory.Sort sort, Edits edits) {
        this.sort = sort;
        this.edits = edits;
    }

    int size() { return rows.size(); }
    Row get(int position) { return rows.get(position); }
    Row boundary() { return boundary; }
    boolean more() { return more; }

    int position(String id) {
        Row row = byId.get(id);
        return row == null ? -1 : Collections.binarySearch(rows, row, sort::compare);
    }

    void append(List<Row> page, boolean hasMore) {
        if (!page.isEmpty()) boundary = page.get(page.size() - 1);
        more = hasMore;
        int appendAt = rows.size();
        for (Row row : page) {
            if (!byId.containsKey(row.id) && (rows.isEmpty() || sort.compare(rows.get(rows.size() - 1), row) < 0)) {
                rows.add(row);
                byId.put(row.id, row);
            } else {
                if (rows.size() > appendAt) edits.inserted(appendAt, rows.size() - appendAt);
                replace(row.id, row); // A changing sort key may bring back an already loaded ID.
                appendAt = rows.size();
            }
        }
        if (rows.size() > appendAt) edits.inserted(appendAt, rows.size() - appendAt);
    }

    void replace(String id, Row row) {
        Row previous = byId.get(id);
        if (row != null && more && sort.compare(row, boundary) > 0) row = null;
        if (row == null ? previous == null : row.equals(previous)) return;
        int before = position(id);
        if (before >= 0) rows.remove(before);
        if (row == null) {
            byId.remove(id);
            edits.removed(before);
            return;
        }
        int after = -1 - Collections.binarySearch(rows, row, sort::compare);
        rows.add(after, row);
        byId.put(id, row);
        if (before < 0) edits.inserted(after, 1);
        else {
            if (before != after) edits.moved(before, after);
            edits.changed(after);
        }
    }
}

package com.privacy.downloader.ui;

import com.privacy.downloader.db.DownloadHistory.Row;
import com.privacy.downloader.db.DownloadHistory.Sort;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class DownloadPagesTest {
    @Test public void changedRowsCanMoveOrLeaveTheLoadedPrefixWithoutChangingTheSeekKey() {
        Edits edits = new Edits();
        DownloadPages pages = new DownloadPages(Sort.LARGEST, edits);
        Row middle = row("b", 20), boundary = row("c", 10);
        pages.append(List.of(row("a", 30), middle, boundary), true);
        assertEquals(List.of("insert:0:3"), edits.calls);
        edits.calls.clear();
        pages.replace("b", middle);
        pages.replace("c", null);
        pages.replace("a", row("a", 15));
        pages.replace("e", row("e", 8)); // Still beyond the loaded prefix.
        assertEquals(List.of("remove:2", "move:0:1", "change:1"), edits.calls);
        assertSame(boundary, pages.boundary());
        assertEquals(-1, pages.position("c"));
        assertEquals(0, pages.position("b"));
        Row aLater = row("a", 9);
        pages.append(List.of(aLater, row("e", 8), row("f", 1)), false);
        assertEquals(4, pages.size()); // Duplicate task from a changed size is merged by ID.
        assertSame(aLater, pages.get(1));
        assertEquals(2, pages.position("e"));
        assertFalse(pages.more());
    }

    @Test public void emptyMatchingPageCanAcceptANewRowAndRemoveItAgain() {
        Edits edits = new Edits();
        DownloadPages pages = new DownloadPages(Sort.NEWEST, edits);
        pages.append(List.of(), false);
        pages.replace("new", row("new", 20));
        assertEquals(0, pages.position("new"));
        pages.replace("new", null);
        assertEquals(0, pages.size());
        assertEquals(List.of("insert:0:1", "remove:0"), edits.calls);
    }

    private static Row row(String id, long total) {
        return new Row(id, null, id, DownloadStatus.COMPLETED, total, total, 1, null, 1, id);
    }

    private static final class Edits implements DownloadPages.Edits {
        final List<String> calls = new ArrayList<>();
        @Override public void inserted(int pos, int count) { calls.add("insert:" + pos + ":" + count); }
        @Override public void removed(int pos) { calls.add("remove:" + pos); }
        @Override public void moved(int from, int to) { calls.add("move:" + from + ":" + to); }
        @Override public void changed(int pos) { calls.add("change:" + pos); }
    }
}

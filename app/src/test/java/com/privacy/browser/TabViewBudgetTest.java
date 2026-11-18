package com.privacy.browser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TabViewBudgetTest {
    private static final class Row {
        boolean live, editing, intake;
        long used;

        Row(boolean live, long used) {
            this.live = live;
            this.used = used;
        }
    }

    private static Row victim(List<Row> rows, Row selected, int keep) {
        return TabViewBudget.victim(rows, selected, r -> r.live, r -> r.editing || r.intake, r -> r.used, keep);
    }

    @Test public void thirtyVisitsKeepOnlySelectedAndThreeMostRecentUnpinnedViews() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(new Row(false, 0));
        for (int i = 0; i < rows.size(); i++) {
            Row selected = rows.get(i);
            // Restore on selection uses the one-slot allowance before deferred eviction.
            assertNull(victim(rows, selected, TabViewBudget.inactiveBeforeAllocation(false)));
            selected.live = true;
            selected.used = i + 1;
            assertTrue(TabViewBudget.count(rows, r -> r.live) <= TabViewBudget.LIVE_UNPINNED);
            Row retire = victim(rows, selected, TabViewBudget.INACTIVE);
            if (i < 4) assertNull(retire);
            else {
                assertSame(rows.get(i - 4), retire);
                retire.live = false;
            }
            assertTrue(selected.live);
            assertEquals(Math.min(i + 1, 4), TabViewBudget.count(rows, r -> r.live));
        }
        assertEquals(30, rows.size());
    }

    @Test public void pressureSkipsSelectionDormantRowsAndBothKindsOfPin() {
        Row dormant = new Row(false, 0), selected = new Row(true, 1), draft = new Row(true, 2), download = new Row(true, 3);
        Row newer = new Row(true, 5), leastRecent = new Row(true, 4);
        draft.editing = true;
        download.intake = true;
        List<Row> rows = List.of(dormant, selected, draft, download, newer, leastRecent);
        assertSame(leastRecent, victim(rows, selected, 0));
        leastRecent.live = false;
        assertSame(newer, victim(rows, selected, 0));
        newer.live = false;
        assertNull(victim(rows, selected, 0));
        // Finishing intake makes the view eligible at the next trim, without changing recency.
        download.intake = false;
        assertSame(download, victim(rows, selected, 0));
    }

    @Test public void allocationReserveOnlyCountsASelectedViewThatIsActuallyLiveAndUnpinned() {
        Row first = new Row(true, 1), second = new Row(true, 2), third = new Row(true, 3), fourth = new Row(true, 4);
        Row selected = new Row(false, 5);
        List<Row> rows = List.of(first, second, third, fourth, selected);
        assertNull(victim(rows, selected, TabViewBudget.inactiveBeforeAllocation(false)));
        selected.live = true;
        // A rapid next allocation (before deferred eviction) must make room first.
        assertSame(first, victim(rows, selected, TabViewBudget.inactiveBeforeAllocation(true)));
    }
}

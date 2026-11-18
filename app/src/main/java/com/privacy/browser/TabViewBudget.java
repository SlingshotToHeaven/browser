package com.privacy.browser;

import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Limits for an Activity's tab WebViews, not a limit on the number of saved rows or renderer processes. */
final class TabViewBudget {
    static final int INACTIVE = 3;
    static final int LIVE_UNPINNED = 1 + INACTIVE + 1; // reserve one transient view for selection
    static final int MANUAL_PINS = 2;
    static final int PAGE_TRANSFERS = 2;

    private TabViewBudget() {}

    static int inactiveBeforeAllocation(boolean selectedCounts) {
        return LIVE_UNPINNED - 1 - (selectedCounts ? 1 : 0);
    }

    /** The selected row and effective pins don't count toward the inactive target. */
    static <T> T victim(Iterable<T> tabs, T selected, Predicate<T> live, Predicate<T> pinned,
                        ToLongFunction<T> lastUse, int keepInactive) {
        T oldest = null;
        long use = Long.MAX_VALUE;
        int inactive = 0;
        for (T tab : tabs) {
            if (tab == selected || !live.test(tab) || pinned.test(tab)) continue;
            inactive++;
            long used = lastUse.applyAsLong(tab);
            if (oldest == null || used < use) {
                use = used;
                oldest = tab;
            }
        }
        return inactive > keepInactive ? oldest : null;
    }

    static <T> int count(Iterable<T> tabs, Predicate<T> matches) {
        int count = 0;
        for (T tab : tabs) if (matches.test(tab)) count++;
        return count;
    }
}

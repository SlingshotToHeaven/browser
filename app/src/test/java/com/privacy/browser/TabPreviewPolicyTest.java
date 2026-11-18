package com.privacy.browser;

import org.junit.Test;

import static org.junit.Assert.*;

public class TabPreviewPolicyTest {
    @Test public void weightedLruEvictsToFitAndRemembersTouches() {
        TabPreviewCache<String, Object> cache = new TabPreviewCache<>(100);
        Object visited = new Object();
        assertTrue(cache.put("visited", visited, 40));
        assertTrue(cache.put("old", new Object(), 40));
        assertSame(visited, cache.get("visited"));
        assertTrue(cache.put("new", new Object(), 60));
        assertEquals(100, cache.bytes());
        assertNull(cache.get("old"));
        assertSame(visited, cache.get("visited"));
        // One larger entry can evict multiple small entries.
        Object large = new Object();
        assertTrue(cache.put("large", large, 95));
        assertNull(cache.get("visited"));
        assertNull(cache.get("new"));
        assertSame(large, cache.get("large"));
        assertEquals(95, cache.bytes());
    }

    @Test public void replacementRemovalAndTrimDoNotLeaveBytesOrAStaleCardKey() {
        TabPreviewCache<String, String> cache = new TabPreviewCache<>(32);
        assertTrue(cache.put("card", "first", 24));
        assertFalse(cache.put("card", "oversized", 33));
        assertFalse(cache.put("card", "empty", 0));
        assertEquals("first", cache.get("card"));
        assertEquals(24, cache.bytes());
        assertTrue(cache.put("card", "replacement", 12));
        assertEquals(12, cache.bytes());
        assertEquals("replacement", cache.get("card"));
        assertTrue(cache.remove("card"));
        assertFalse(cache.remove("card"));
        assertNull(cache.get("card"));
        assertEquals(0, cache.bytes());
        cache.put("another", "image", 10);
        cache.clear();
        assertNull(cache.get("another"));
        assertEquals(0, cache.bytes());
    }

    @Test public void replacementDuringCopyOnlyPublishesTheNewestRequest() {
        TabPreviewRequests<String> work = new TabPreviewRequests<>();
        work.request("page A");
        Object a = work.begin();
        assertNotNull(a);
        work.request("page B");
        work.request("page C");
        assertNull(work.begin());
        assertNull(work.complete(a));
        Object c = work.begin();
        assertNotNull(c);
        assertNotSame(a, c);
        assertNull(work.complete(a)); // duplicate late callback cannot drain the new job
        assertTrue(work.isCopying(c));
        assertEquals("page C", work.complete(c));
        assertNull(work.wanted());
        assertNull(work.begin());
    }

    @Test public void openingGridOrReleasingOwnerInvalidatesQueuedAndRunningCopies() {
        TabPreviewRequests<Object> work = new TabPreviewRequests<>();
        work.request(new Object());
        work.cancel();
        assertNull(work.begin());
        work.request(new Object());
        Object copy = work.begin();
        work.cancel();
        assertNull(work.wanted()); // no page or view retained by the canceled ticket
        assertTrue(work.isCopying(copy)); // destination stays accounted for until native replies
        assertNull(work.complete(copy));
        assertFalse(work.isCopying(copy));
        assertNull(work.begin()); // reopening/resuming cannot restart the canceled work
    }
}

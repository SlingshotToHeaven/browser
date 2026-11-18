package com.privacy.browser;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class OmniboxClipboardTest {
    @Test public void onlyFirstEligibleReadInAnEditUsesTheProvider() {
        OmniboxClipboard clipboard = new OmniboxClipboard();
        AtomicInteger reads = new AtomicInteger();
        Supplier<CharSequence> read = () -> "  text " + reads.incrementAndGet() + "  ";
        assertNull(clipboard.offer(false, "t")); // fallback can ask for the cached value without reading
        for (int i = 0; i < 25; i++) clipboard.inspect(false, read);
        assertEquals(1, reads.get());
        assertEquals("text 1", clipboard.offer(false, "te"));
        assertNull(clipboard.offer(false, "TEXT 1"));
        clipboard.reset();
        assertNull(clipboard.offer(false, "other"));
        clipboard.inspect(false, read);
        assertEquals("text 2", clipboard.offer(false, "other"));
        assertEquals(2, reads.get());
    }

    @Test public void privateRequestNeitherReadsNorOffersTheClipboard() {
        OmniboxClipboard clipboard = new OmniboxClipboard();
        clipboard.inspect(true, () -> { throw new AssertionError("Private clipboard read"); });
        clipboard.inspect(false, () -> "from regular focus");
        assertNull(clipboard.offer(true, "prefix"));
    }

    @Test public void noTextOrOverlongTextDoesNotCauseRetriesDuringTheSameEdit() {
        OmniboxClipboard clipboard = new OmniboxClipboard();
        clipboard.inspect(false, () -> null);
        clipboard.inspect(false, () -> "must not retry");
        assertNull(clipboard.offer(false, "prefix"));
        clipboard.reset();
        clipboard.inspect(false, () -> "a".repeat(OmniboxRequests.MAX_TEXT_CHARS + 1));
        assertNull(clipboard.offer(false, "prefix"));
    }
}

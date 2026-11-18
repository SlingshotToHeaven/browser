package com.privacy.browser.images;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ThumbnailBudgetTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void legacyHeadersAreSampledToTheDisplayBudget() {
        assertEquals(1, ThumbnailBudget.BOOKMARK_COVER.sampleSize(480, 480));
        assertEquals(2, ThumbnailBudget.BOOKMARK_COVER.sampleSize(481, 80));
        assertEquals(8, ThumbnailBudget.BOOKMARK_COVER.sampleSize(2048, 2048));
        assertEquals(1, ThumbnailBudget.FAVICON.sampleSize(96, 92));
        assertEquals(4, ThumbnailBudget.FAVICON.sampleSize(384, 240));
    }

    @Test public void rejectsInvalidEdgesAndPixelProductsBeforeDecoding() {
        assertEquals(0, ThumbnailBudget.BOOKMARK_COVER.sampleSize(-1, 100));
        assertEquals(0, ThumbnailBudget.BOOKMARK_COVER.sampleSize(640, 0));
        assertEquals(0, ThumbnailBudget.BOOKMARK_COVER.sampleSize(4097, 1));
        assertEquals(0, ThumbnailBudget.BOOKMARK_COVER.sampleSize(4096, 1025));
        assertEquals(0, ThumbnailBudget.BOOKMARK_COVER.sampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, ThumbnailBudget.FAVICON.sampleSize(513, 1));
    }

    @Test public void outputChecksActualDimensionsAndAllocationNotOnlyTheHeader() {
        assertTrue(ThumbnailBudget.BOOKMARK_COVER.acceptsBitmap(480, 480, 480 * 480 * 4));
        assertFalse(ThumbnailBudget.BOOKMARK_COVER.acceptsBitmap(481, 480, 1));
        assertFalse(ThumbnailBudget.BOOKMARK_COVER.acceptsBitmap(480, 480, 480 * 480 * 4 + 1));
        assertFalse(ThumbnailBudget.FAVICON.acceptsBitmap(96, 96, -1));
        assertFalse(ThumbnailBudget.FAVICON.acceptsBitmap(48, 0, 192));
    }

    @Test public void readsAtMostEachStoreByteLimit() throws Exception {
        for (ThumbnailBudget budget : ThumbnailBudget.values()) {
            File file = temp.newFile();
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                out.seek(budget.maxEncodedBytes - 1);
                out.write(7);
            }
            byte[] bytes = budget.read(file);
            assertEquals(budget.maxEncodedBytes, bytes.length);
            assertEquals(7, bytes[bytes.length - 1]);
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                out.setLength((long) budget.maxEncodedBytes + 1);
            }
            assertNull(budget.read(file));
        }
    }

    @Test public void missingEmptyAndNonFileInputsAreMisses() throws Exception {
        ThumbnailBudget budget = ThumbnailBudget.BOOKMARK_COVER;
        assertNull(budget.read(new File(temp.getRoot(), "absent")));
        assertNull(budget.read(temp.newFolder()));
        assertNull(budget.read(temp.newFile()));
        File legitimate = temp.newFile();
        Files.write(legitimate.toPath(), new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, budget.read(legitimate));
    }
}

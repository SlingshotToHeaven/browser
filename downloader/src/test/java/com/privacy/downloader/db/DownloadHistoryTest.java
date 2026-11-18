package com.privacy.downloader.db;

import com.privacy.downloader.db.DownloadHistory.Filter;
import com.privacy.downloader.db.DownloadHistory.Query;
import com.privacy.downloader.db.DownloadHistory.Row;
import com.privacy.downloader.db.DownloadHistory.Sort;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class DownloadHistoryTest {
    @Test public void literalSearchAndExtensionsAreBoundInBothSeekBranches() {
        Query query = new Query(Filter.DOCS, Sort.LARGEST, "  RÉ%_?  ");
        Row key = new Row("anchor", null, "résumé.doc", DownloadStatus.PAUSED, -1, 17L, 1, null, 10, "résumé.doc");
        DownloadHistory.Sql page = query.page(key);
        assertTrue(page.text.contains("UNION ALL"));
        assertTrue(page.text.contains("downloads_size"));
        assertTrue(page.text.contains("ORDER BY id DESC LIMIT 49"));
        assertTrue(page.text.endsWith("ORDER BY seek_key DESC, id DESC LIMIT 49"));
        assertFalse(page.text.contains("ré%_?"));
        assertFalse(page.text.contains("anchor"));
        assertFalse(page.text.contains("download_segments"));
        assertFalse(page.text.contains("original_url"));
        assertFalse(page.text.contains("temp_path"));
        assertFalse(page.text.contains(" url"));
        assertArrayEquals(new String[]{"ré%_?", ".pdf", ".doc", ".docx", ".txt", ".epub", "17", "anchor",
                "ré%_?", ".pdf", ".doc", ".docx", ".txt", ".epub", "17"}, page.args);
        assertTrue(Filter.DOCS.hasExtension("RÉSUMÉ.DOC"));
        assertFalse(Filter.DOCS.hasExtension("report.pdf.exe"));
    }

    @Test public void changedRowsAndSelectionIntersectionUsePrimaryKeyNotAStatusScan() {
        Query query = new Query(Filter.COMPLETED, Sort.NAME_ASC, "");
        DownloadHistory.Sql rows = query.ids(Arrays.asList("a", "c"), true);
        assertTrue(rows.text.contains("INDEXED BY sqlite_autoindex_downloads_1"));
        assertTrue(rows.text.contains("status='COMPLETED' AND id IN (?,?)"));
        assertArrayEquals(new String[]{"a", "c"}, rows.args);
        assertTrue(query.ids(null, false).text.startsWith("SELECT id FROM downloads "));
        assertTrue(query.page(null).text.contains("INDEXED BY downloads_completed_name"));
    }

    @Test public void nameFoldingAndSortTiesMatchTheStableKey() {
        assertEquals(DownloadHistory.nameKey("RÉSUMÉ.İÇ"), DownloadHistory.nameKey("résumé.ıç"));
        assertEquals(DownloadHistory.nameKey("Σ"), DownloadHistory.nameKey("ς"));
        assertEquals("", DownloadHistory.nameKey(null));
        Row a = new Row("a", null, "Z", DownloadStatus.COMPLETED, 100, 100, 1, null, 10, "z");
        Row b = new Row("b", null, "z", DownloadStatus.PAUSED, -1, 100, 1, null, 10, "z");
        for (Sort sort : Sort.values()) assertEquals(sort.desc ? 1 : -1, Integer.signum(sort.compare(a, b)));
    }
}

package com.privacy.browser.bookmarks;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

/** Actual SQLiteOpenHelper upgrades and writes against a uniquely named, disposable database. */
public class BookmarkDbHelperDeviceTest {
    private static final long ROOT = 0L;
    private static final long MISSING = 99_999L;
    private static final String LEGACY_TABLE = "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "title TEXT NOT NULL, url TEXT, created_at INTEGER NOT NULL";
    private static final String LEGACY_PARENT = ", parent_folder_id INTEGER NOT NULL DEFAULT 0";
    private static final String LEGACY_FOLDER = ", is_folder INTEGER NOT NULL DEFAULT 0";

    private Context context;
    private String name;
    private BookmarkDbHelper helper;

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "test-bookmark-tree-" + UUID.randomUUID() + ".db";
        helper = new BookmarkDbHelper(context, name);
    }

    @After public void cleanup() {
        helper.close();
        context.deleteDatabase(name);
    }

    @Test public void upgradeV1PreservesBookmarksAndAddsHierarchy() {
        List<List<Object>> original;
        try (SQLiteDatabase old = legacy(1, "")) {
            old.execSQL("INSERT INTO bookmarks VALUES (3, ' Legacy title ', 'https://old.invalid/#keep', 81)");
            old.execSQL("INSERT INTO bookmarks VALUES (90, 'Older', NULL, 32)");
            original = rows(old, false);
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        assertVersionAndIndex(db);
        assertEquals(original, rows(db, false));
        assertParents(db, 3, 0, 90, 0);
        assertEquals(0, scalar(db, "SELECT SUM(is_folder) FROM bookmarks"));
        assertEquals(ids(3, 90), modelIds(helper.getRecentBookmarks(6)));
        assertTrue(helper.createFolder("New folder", ROOT) > 90);
    }

    @Test public void upgradePartialSchemasChecksEachColumnIndependently() {
        // A version-2 file can record an upgrade even though is_folder was never added.
        try (SQLiteDatabase old = legacy(2, LEGACY_PARENT)) {
            old.execSQL("INSERT INTO bookmarks VALUES (4, 'Old item', '', 111, 7)");
            old.execSQL("INSERT INTO bookmarks VALUES (7, 'Old page', 'https://old.invalid/', 112, 0)");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        assertVersionAndIndex(db);
        assertParents(db, 4, 0, 7, 0);
        assertEquals(0, scalar(db, "SELECT SUM(is_folder) FROM bookmarks"));

        // The opposite partial shape retains the observed folder flags, not guessed URLs.
        helper.close();
        assertTrue(context.deleteDatabase(name));
        try (SQLiteDatabase old = legacy(1, LEGACY_FOLDER)) {
            old.execSQL("INSERT INTO bookmarks VALUES (11, 'Folder', '', 42, 1)");
            old.execSQL("INSERT INTO bookmarks VALUES (12, 'Page', NULL, 43, 0)");
        }
        db = helper.getWritableDatabase();
        assertVersionAndIndex(db);
        assertParents(db, 11, 0, 12, 0);
        assertEquals(ids(11), modelIds(helper.getAllFolders()));
    }

    @Test public void upgradeV2RepairsLinksWithoutDeletingOrRewritingSavedContent() {
        List<List<Object>> original;
        try (SQLiteDatabase old = legacy(2, LEGACY_PARENT + LEGACY_FOLDER)) {
            seed(old, 1, 0, true);              // healthy tree
            seed(old, 2, 1, false);
            seed(old, 3, 1, true);
            seed(old, 4, 3, false);
            seed(old, 5, MISSING, true);        // orphaned branch
            seed(old, 6, 5, false);
            seed(old, 7, 2, false);            // page cannot be a parent
            seed(old, 8, -31, false);
            seed(old, 9, 9, true);             // self-cycle
            seed(old, 10, 12, true);
            seed(old, 11, 10, true);
            seed(old, 12, 11, true);
            seed(old, 13, 11, false);          // child of cyclic branch
            seed(old, 14, 1.5d, true);         // must not be coerced to id 1
            seed(old, 15, "bad link", false); // must not be coerced to (invisible) root
            seed(old, 16, new byte[]{1}, false);
            original = rows(old, true);
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        assertVersionAndIndex(db);
        assertEquals(original, rows(db, true));
        assertParents(db, 1, 0, 2, 1, 3, 1, 4, 3, 5, 0, 6, 5, 7, 0, 8, 0,
                9, 0, 10, 0, 11, 10, 12, 11, 13, 11, 14, 0, 15, 0, 16, 0);
        assertEquals(ids(12, 13), modelIds(helper.getItemsInFolder(11)));
        assertEquals(ids(6), modelIds(helper.getItemsInFolder(5)));
        assertEquals(6, helper.getRecentBookmarks(100).size());

        helper.close();
        db = helper.getWritableDatabase();
        assertEquals(original, rows(db, true));
        assertParents(db, 9, 0, 10, 0, 11, 10, 12, 11);
    }

    @Test public void failedRepairDoesNotKeepAlterOrAdvanceVersion() {
        try (SQLiteDatabase old = legacy(2, LEGACY_PARENT)) {
            old.execSQL("INSERT INTO bookmarks VALUES (1, 'First', '', 11, 94), (2, 'Second', '', 12, 95)");
            old.execSQL("CREATE TRIGGER no_second_repair BEFORE UPDATE ON bookmarks WHEN old.id = 2 "
                    + "BEGIN SELECT RAISE(IGNORE); END");
        }
        assertThrows(SQLiteException.class, () -> helper.getWritableDatabase());
        try (SQLiteDatabase old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)) {
            assertEquals(2, old.getVersion());
            assertParents(old, 1, 94, 2, 95);
            try (Cursor columns = old.rawQuery("PRAGMA table_info(bookmarks)", null)) {
                assertEquals(5, columns.getCount());
            }
            old.execSQL("DROP TRIGGER no_second_repair");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        assertVersionAndIndex(db);
        assertParents(db, 1, 0, 2, 0);
    }

    @Test public void movesRejectSelfDescendantsAndInvalidSourcesOrTargetsAsAUnit() {
        long a = folder("A", ROOT), b = folder("B", a), c = folder("C", b), target = folder("Sibling", ROOT);
        long page = page(ROOT);
        SQLiteDatabase db = helper.getWritableDatabase();
        assertVersionAndIndex(db);
        List<Long> mixed = ids(page, a);
        assertEquals(ids(ROOT, target), modelIds(helper.getMoveDestinations(mixed)));
        assertFalse(helper.moveToFolder(ids(a), a));
        for (long denied : ids(a, b, c, page, MISSING, -1)) {
            assertEquals(BookmarkDbHelper.MOVE_REJECTED, helper.moveItemsToFolder(mixed, denied));
        }
        for (List<Long> invalid : Arrays.asList(ids(page, MISSING), ids(page, ROOT), Arrays.asList(page, null))) {
            assertTrue(helper.getMoveDestinations(invalid).isEmpty());
            assertFalse(helper.moveToFolder(invalid, target));
        }
        assertParents(db, page, ROOT, a, ROOT, b, a, c, b, target, ROOT);
        assertEquals(0, helper.moveItemsToFolder(mixed, ROOT));

        assertEquals(2, helper.moveItemsToFolder(ids(page, a, page), target));
        assertFalse(helper.moveToFolder(mixed, target)); // Still no success on a no-op.
        assertEquals(ids(a, page), modelIds(helper.getItemsInFolder(target)));
        assertParents(db, page, target, a, target, b, a, c, b);
        assertTrue(helper.moveToFolder(mixed, ROOT));
        assertParents(db, page, ROOT, a, ROOT, b, a, c, b);
    }

    @Test public void writeRechecksStalePickerAndReportsOnlyRowsThatActuallyChanged() {
        long moving = folder("Moving", ROOT), destination = folder("Destination", ROOT);
        long page = page(ROOT), alreadyThere = page(destination);
        assertTrue(modelIds(helper.getMoveDestinations(ids(page, moving))).contains(destination));
        assertTrue(helper.moveToFolder(ids(destination), moving));
        assertEquals(BookmarkDbHelper.MOVE_REJECTED, helper.moveItemsToFolder(ids(page, moving), destination));
        SQLiteDatabase db = helper.getWritableDatabase();
        assertParents(db, page, ROOT, moving, ROOT, destination, moving);

        assertEquals(1, helper.moveItemsToFolder(ids(page, alreadyThere), destination));
        helper.close();
        assertParents(helper.getReadableDatabase(), page, destination, alreadyThere, destination, moving, ROOT);
    }

    @Test public void failedSecondMoveWriteRollsBackTheFirst() {
        long one = page(ROOT), two = page(ROOT), target = folder("Target", ROOT);
        SQLiteDatabase db = helper.getWritableDatabase();
        String trigger = "CREATE TRIGGER fail_second BEFORE UPDATE ON bookmarks WHEN old.id = " + two;
        db.execSQL(trigger + " BEGIN SELECT RAISE(IGNORE); END");
        assertEquals(BookmarkDbHelper.MOVE_REJECTED, helper.moveItemsToFolder(ids(one, two), target));
        assertParents(db, one, ROOT, two, ROOT);
        db.execSQL("DROP TRIGGER fail_second");
        db.execSQL(trigger + " BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END");
        assertThrows(SQLiteException.class, () -> helper.moveItemsToFolder(ids(one, two), target));
        assertParents(db, one, ROOT, two, ROOT);
    }

    @Test public void creationAndMoveCannotAttachToCorruptOrDisconnectedDestinations() {
        long normal = folder("Normal", ROOT), page = page(normal);
        SQLiteDatabase db = helper.getWritableDatabase();
        seed(db, 10, 11, true);
        seed(db, 11, 10, true);
        seed(db, 12, MISSING, true);
        seed(db, 13, page, true);
        seed(db, 14, normal + 0.5d, true);
        for (long bad : ids(10, 11, 12, 13, 14, page, MISSING, -1)) {
            assertEquals(-1, helper.createFolder("No folder", bad));
            assertEquals(-1, helper.addBookmarkAndGetId("No bookmark", "https://no.invalid/", bad));
            assertFalse(helper.addBookmark("No bookmark", "https://no.invalid/", bad));
            assertFalse(helper.moveToFolder(ids(page), bad));
        }
        assertEquals(ids(ROOT, normal), modelIds(helper.getMoveDestinations(ids(page))));
        assertEquals(7, scalar(db, "SELECT COUNT(*) FROM bookmarks"));
        assertTrue(helper.getItemsInFolder(page).isEmpty());
        assertEquals(ids(normal), modelIds(helper.getItemsInFolder(ROOT)));
    }

    @Test public void deletionHandlesDeepTreesCyclesAndOverlappingSourcesWithoutTouchingOtherRows() {
        long keep = page(ROOT);
        SQLiteDatabase db = helper.getWritableDatabase();
        seed(db, 10, 11, true);
        seed(db, 11, 10, true);
        seed(db, 12, 11, false);
        seed(db, 13, 12, false); // Invalid edge from a leaf: don't delete that saved page.
        seed(db, 14, 14, true);
        assertTrue(helper.deleteItems(ids(ROOT, 10)).isEmpty());
        assertTrue(helper.deleteItems(ids(10, MISSING)).isEmpty());
        assertEquals(new HashSet<>(ids(10, 11, 12, 14)), helper.deleteItems(ids(10, 12, 11, 14)));
        assertParents(db, keep, ROOT, 13, 12);
        assertEquals(2, scalar(db, "SELECT COUNT(*) FROM bookmarks"));

        int depth = 2_048;
        db.beginTransaction();
        try {
            for (long id = 100; id < 100 + depth; id++) seed(db, id, id == 100 ? ROOT : id - 1, true);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Set<Long> deleted = helper.deleteItems(ids(100, 200));
        assertEquals(depth, deleted.size());
        assertTrue(deleted.contains(100L + depth - 1));
        assertEquals(2, scalar(db, "SELECT COUNT(*) FROM bookmarks"));
        assertEquals(ids(keep), modelIds(helper.getItemsInFolder(ROOT)));
    }

    @Test public void failedChildDeletionDoesNotReportAnyDeletedIds() {
        long folder = folder("Parent", ROOT), child = page(folder), keep = page(ROOT);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.execSQL("CREATE TRIGGER keep_child BEFORE DELETE ON bookmarks WHEN old.id = " + child
                + " BEGIN SELECT RAISE(IGNORE); END");
        assertTrue(helper.deleteItems(ids(folder)).isEmpty());
        assertParents(db, folder, ROOT, child, folder, keep, ROOT);
        db.execSQL("DROP TRIGGER keep_child");
        assertEquals(new HashSet<>(ids(folder, child)), helper.deleteItems(ids(folder)));
        assertEquals(ids(keep), modelIds(helper.getItemsInFolder(ROOT)));
    }

    private SQLiteDatabase legacy(int version, String suffix) {
        SQLiteDatabase old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null);
        old.execSQL(LEGACY_TABLE + suffix + ")");
        old.setVersion(version);
        return old;
    }

    private static void seed(SQLiteDatabase db, long id, Object parent, boolean folder) {
        db.execSQL("INSERT INTO bookmarks (id,title,url,created_at,parent_folder_id,is_folder) VALUES (?,?,?,?,?,?)",
                new Object[]{id, "Item " + id, folder ? "" : "https://fixture.invalid/" + id, id * 3, parent, folder ? 1 : 0});
    }

    private long folder(String title, long parent) {
        long id = helper.createFolder(title, parent);
        assertTrue(id > 0);
        return id;
    }

    private long page(long parent) {
        long id = helper.addBookmarkAndGetId("Page", "https://fixture.invalid/", parent);
        assertTrue(id > 0);
        return id;
    }

    private static void assertVersionAndIndex(SQLiteDatabase db) {
        assertEquals(3, db.getVersion());
        try (Cursor index = db.rawQuery("PRAGMA index_info(bookmarks_parent_idx)", null)) {
            assertTrue(index.moveToFirst());
            assertEquals("parent_folder_id", index.getString(index.getColumnIndexOrThrow("name")));
        }
    }

    private static void assertParents(SQLiteDatabase db, long... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            try (Cursor cursor = db.rawQuery("SELECT parent_folder_id FROM bookmarks WHERE id = ?",
                    new String[]{Long.toString(pairs[i])})) {
                assertTrue("missing id " + pairs[i], cursor.moveToFirst());
                assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(0));
                assertEquals("parent of id " + pairs[i], pairs[i + 1], cursor.getLong(0));
            }
        }
    }

    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private static List<List<Object>> rows(SQLiteDatabase db, boolean folderFlag) {
        List<List<Object>> rows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id,title,url,created_at" + (folderFlag ? ",is_folder" : "")
                + " FROM bookmarks ORDER BY id", null)) {
            while (cursor.moveToNext()) {
                rows.add(Arrays.asList(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                        folderFlag ? cursor.getLong(4) : 0L));
            }
        }
        return rows;
    }

    private static List<Long> ids(long... values) {
        List<Long> result = new ArrayList<>();
        for (long id : values) result.add(id);
        return result;
    }

    private static List<Long> modelIds(List<BookmarkModel> items) {
        List<Long> ids = new ArrayList<>();
        for (BookmarkModel item : items) ids.add(item.getId());
        return ids;
    }
}

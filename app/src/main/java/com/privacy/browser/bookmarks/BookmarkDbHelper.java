package com.privacy.browser.bookmarks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BookmarkDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "bookmarks.db";
    private static final int DATABASE_VERSION = 3;

    public static final String TABLE_BOOKMARKS = "bookmarks";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_TITLE = "title";
    public static final String COLUMN_URL = "url";
    public static final String COLUMN_CREATED_AT = "created_at";
    public static final String COLUMN_PARENT_FOLDER_ID = "parent_folder_id";
    public static final String COLUMN_IS_FOLDER = "is_folder";

    static final int MOVE_REJECTED = -1;

    private static final String PARENT_COLUMN = COLUMN_PARENT_FOLDER_ID + " INTEGER NOT NULL DEFAULT 0";
    private static final String FOLDER_COLUMN = COLUMN_IS_FOLDER + " INTEGER NOT NULL DEFAULT 0";
    private static final String BY_ID = COLUMN_ID + " = ?";
    private static final String[] LINK_COLUMNS = {COLUMN_ID, COLUMN_PARENT_FOLDER_ID, COLUMN_IS_FOLDER};

    private static BookmarkDbHelper instance;

    public static synchronized BookmarkDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new BookmarkDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private BookmarkDbHelper(Context context) {
        this(context, DATABASE_NAME);
    }

    // Also lets instrumentation open a disposable legacy database, never the singleton's data.
    BookmarkDbHelper(Context context, String name) {
        super(context, name, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTableQuery = "CREATE TABLE " + TABLE_BOOKMARKS + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_TITLE + " TEXT NOT NULL, "
                + COLUMN_URL + " TEXT, "
                + COLUMN_CREATED_AT + " INTEGER NOT NULL, "
                + PARENT_COLUMN + ", " + FOLDER_COLUMN + ");";
        db.execSQL(createTableQuery);
        createParentIndex(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            // SQLiteOpenHelper wraps this callback and the version bump in the same transaction.
            // v2 may have been recorded even when the old shared try/catch skipped one ALTER.
            Set<String> columns = new HashSet<>();
            try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + TABLE_BOOKMARKS + ")", null)) {
                int name = cursor.getColumnIndexOrThrow("name");
                while (cursor.moveToNext()) columns.add(cursor.getString(name));
            }
            if (!columns.contains(COLUMN_PARENT_FOLDER_ID)) {
                db.execSQL("ALTER TABLE " + TABLE_BOOKMARKS + " ADD COLUMN " + PARENT_COLUMN);
            }
            if (!columns.contains(COLUMN_IS_FOLDER)) {
                db.execSQL("ALTER TABLE " + TABLE_BOOKMARKS + " ADD COLUMN " + FOLDER_COLUMN);
            }

            Map<Long, BookmarkTree.Link> links = new LinkedHashMap<>();
            try (Cursor cursor = db.query(TABLE_BOOKMARKS, LINK_COLUMNS, null, null, null, null, COLUMN_ID)) {
                while (cursor.moveToNext()) links.put(cursor.getLong(0), readLink(cursor));
            }
            ContentValues values = new ContentValues();
            values.put(COLUMN_PARENT_FOLDER_ID, BookmarkTree.ROOT);
            for (long id : BookmarkTree.linksToReset(links)) {
                if (db.update(TABLE_BOOKMARKS, values, BY_ID, bindId(id)) != 1) {
                    throw new SQLiteException("Could not repair a bookmark parent link");
                }
            }
            createParentIndex(db);
        }
    }

    private static void createParentIndex(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS bookmarks_parent_idx ON " + TABLE_BOOKMARKS
                + " (" + COLUMN_PARENT_FOLDER_ID + ")");
    }

    private static String[] bindId(long id) {
        return new String[]{String.valueOf(id)};
    }

    private static long readParent(Cursor cursor, int index) {
        // Don't let Cursor coerce a NULL, fractional or text parent to a real id (including root).
        return cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER ? cursor.getLong(index) : BookmarkTree.INVALID_PARENT;
    }

    private static boolean readIsFolder(Cursor cursor, int index) {
        return cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(index) == 1;
    }

    private static BookmarkTree.Link readLink(Cursor cursor) {
        return new BookmarkTree.Link(readParent(cursor, 1), readIsFolder(cursor, 2));
    }

    private static BookmarkTree.Link readLink(SQLiteDatabase db, long id) {
        if (id <= BookmarkTree.ROOT) return null;
        try (Cursor cursor = db.query(TABLE_BOOKMARKS, LINK_COLUMNS, BY_ID, bindId(id), null, null, null)) {
            return cursor.moveToFirst() ? readLink(cursor) : null;
        }
    }

    public synchronized long addBookmarkAndGetId(String title, String url, long parentFolderId) {
        if (url == null || url.trim().isEmpty()) return -1;
        if (title == null || title.trim().isEmpty()) title = url;
        return insertItem(title.trim(), url.trim(), parentFolderId, false);
    }

    public synchronized boolean addBookmark(String title, String url) {
        return addBookmark(title, url, BookmarkTree.ROOT);
    }

    public synchronized boolean addBookmark(String title, String url, long parentFolderId) {
        return addBookmarkAndGetId(title, url, parentFolderId) > 0;
    }

    public synchronized long createFolder(String folderName, long parentFolderId) {
        if (folderName == null || folderName.trim().isEmpty()) folderName = "New Folder";
        return insertItem(folderName.trim(), "", parentFolderId, true);
    }

    private long insertItem(String title, String url, long parentId, boolean folder) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            BookmarkTree.Destinations destinations = new BookmarkTree.Destinations(Collections.emptySet(), id -> readLink(db, id));
            if (!destinations.allows(parentId)) return -1;
            ContentValues values = new ContentValues();
            values.put(COLUMN_TITLE, title);
            values.put(COLUMN_URL, url);
            values.put(COLUMN_CREATED_AT, System.currentTimeMillis());
            values.put(COLUMN_PARENT_FOLDER_ID, parentId);
            values.put(COLUMN_IS_FOLDER, folder ? 1 : 0);
            // Unlike insert(), this doesn't log bookmark values on a failed write.
            long id = db.insertOrThrow(TABLE_BOOKMARKS, null, values);
            if (id > 0) db.setTransactionSuccessful();
            return id;
        } catch (SQLiteException e) {
            return -1;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean updateBookmark(long id, String newTitle, String newUrl) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        if (newTitle != null) values.put(COLUMN_TITLE, newTitle.trim());
        if (newUrl != null) values.put(COLUMN_URL, newUrl.trim());
        return db.update(TABLE_BOOKMARKS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)}) > 0;
    }

    public synchronized boolean updateFolderName(long folderId, String newName) {
        if (newName == null || newName.trim().isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TITLE, newName.trim());
        return db.update(TABLE_BOOKMARKS, values, COLUMN_ID + " = ? AND " + COLUMN_IS_FOLDER + " = 1",
                new String[]{String.valueOf(folderId)}) > 0;
    }

    private static Map<Long, BookmarkTree.Link> readSelection(SQLiteDatabase db, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        Map<Long, BookmarkTree.Link> selected = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id == null) return Collections.emptyMap();
            if (selected.containsKey(id)) continue;
            BookmarkTree.Link link = readLink(db, id);
            if (link == null) return Collections.emptyMap();
            selected.put(id, link);
        }
        return selected;
    }

    /** True only when at least one row changed and the entire move committed. */
    public synchronized boolean moveToFolder(List<Long> ids, long targetFolderId) {
        return moveItemsToFolder(ids, targetFolderId) > 0;
    }

    /** Returns the number of changed parents, zero for a no-op, or MOVE_REJECTED. */
    synchronized int moveItemsToFolder(List<Long> ids, long targetFolderId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Map<Long, BookmarkTree.Link> selected = readSelection(db, ids);
            if (selected.isEmpty()) return MOVE_REJECTED;
            BookmarkTree.Destinations destinations = new BookmarkTree.Destinations(selected.keySet(), id -> readLink(db, id));
            if (!destinations.allows(targetFolderId)) return MOVE_REJECTED;

            ContentValues values = new ContentValues();
            values.put(COLUMN_PARENT_FOLDER_ID, targetFolderId);
            int moved = 0;
            for (Map.Entry<Long, BookmarkTree.Link> source : selected.entrySet()) {
                if (source.getValue().parent == targetFolderId) continue;
                if (db.update(TABLE_BOOKMARKS, values, BY_ID, bindId(source.getKey())) != 1) return MOVE_REJECTED;
                moved++;
            }
            db.setTransactionSuccessful();
            return moved;
        } finally {
            db.endTransaction();
        }
    }

    /** Advisory picker contents; writes always check the current links again inside their transaction. */
    synchronized List<BookmarkModel> getMoveDestinations(List<Long> ids) {
        Map<Long, BookmarkTree.Link> selected = readSelection(getReadableDatabase(), ids);
        if (selected.isEmpty()) return Collections.emptyList();
        List<BookmarkModel> folders = getAllFolders();
        Map<Long, BookmarkTree.Link> links = new HashMap<>();
        for (BookmarkModel folder : folders) {
            links.put(folder.getId(), new BookmarkTree.Link(folder.getParentFolderId(), true));
        }
        BookmarkTree.Destinations destinations = new BookmarkTree.Destinations(selected.keySet(), links::get);
        folders.removeIf(folder -> folder.getId() <= BookmarkTree.ROOT || !destinations.allows(folder.getId()));
        folders.add(0, new BookmarkModel(BookmarkTree.ROOT, "Bookmarks (Root)", null, 0, BookmarkTree.ROOT, true, 0));
        return folders;
    }

    public synchronized boolean removeBookmark(String url) {
        if (url == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        int rows = db.delete(TABLE_BOOKMARKS, COLUMN_URL + " = ? AND " + COLUMN_IS_FOLDER + " = 0", new String[]{url.trim()});
        return rows > 0;
    }

    public synchronized boolean isBookmarked(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_BOOKMARKS, new String[]{COLUMN_ID},
                COLUMN_URL + " = ? AND " + COLUMN_IS_FOLDER + " = 0",
                new String[]{url.trim()}, null, null, null);
        boolean exists = cursor != null && cursor.moveToFirst();
        if (cursor != null) cursor.close();
        return exists;
    }

    public synchronized List<BookmarkModel> getItemsInFolder(long folderId) {
        List<BookmarkModel> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        if (folderId != BookmarkTree.ROOT) {
            BookmarkTree.Link folder = readLink(db, folderId);
            if (folder == null || !folder.folder) return list;
        }

        // Folders first (alphabetical), then bookmarks (latest first)
        String orderBy = COLUMN_IS_FOLDER + " DESC, " +
                "CASE WHEN " + COLUMN_IS_FOLDER + " = 1 THEN " + COLUMN_TITLE + " END ASC, " +
                COLUMN_CREATED_AT + " DESC";

        try (Cursor cursor = db.query(TABLE_BOOKMARKS, null,
                COLUMN_PARENT_FOLDER_ID + " = ?",
                bindId(folderId), null, null, orderBy)) {
            int idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID);
            int titleIdx = cursor.getColumnIndexOrThrow(COLUMN_TITLE);
            int urlIdx = cursor.getColumnIndexOrThrow(COLUMN_URL);
            int createdIdx = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT);
            int parentIdx = cursor.getColumnIndexOrThrow(COLUMN_PARENT_FOLDER_ID);
            int isFolderIdx = cursor.getColumnIndexOrThrow(COLUMN_IS_FOLDER);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIdx);
                boolean isFolder = readIsFolder(cursor, isFolderIdx);
                int childCount = isFolder ? countFolderChildren(db, id) : 0;

                list.add(new BookmarkModel(
                        id,
                        cursor.getString(titleIdx),
                        cursor.getString(urlIdx),
                        cursor.getLong(createdIdx),
                        readParent(cursor, parentIdx),
                        isFolder,
                        childCount
                ));
            }
        }
        return list;
    }

    private int countFolderChildren(SQLiteDatabase db, long folderId) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_BOOKMARKS
                + " WHERE " + COLUMN_PARENT_FOLDER_ID + " = ?", bindId(folderId))) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized List<BookmarkModel> getAllFolders() {
        List<BookmarkModel> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE_BOOKMARKS, null,
                COLUMN_IS_FOLDER + " = 1", null, null, null, COLUMN_TITLE + " ASC")) {
            int idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID);
            int titleIdx = cursor.getColumnIndexOrThrow(COLUMN_TITLE);
            int urlIdx = cursor.getColumnIndexOrThrow(COLUMN_URL);
            int createdIdx = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT);
            int parentIdx = cursor.getColumnIndexOrThrow(COLUMN_PARENT_FOLDER_ID);
            int folderIdx = cursor.getColumnIndexOrThrow(COLUMN_IS_FOLDER);
            while (cursor.moveToNext()) {
                if (!readIsFolder(cursor, folderIdx)) continue;
                list.add(new BookmarkModel(cursor.getLong(idIdx), cursor.getString(titleIdx), cursor.getString(urlIdx),
                        cursor.getLong(createdIdx), readParent(cursor, parentIdx), true, 0));
            }
        }
        return list;
    }

    public synchronized List<BookmarkModel> getRecentBookmarks(int limit) {
        List<BookmarkModel> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE_BOOKMARKS,
                new String[] { COLUMN_ID, COLUMN_TITLE, COLUMN_URL, COLUMN_CREATED_AT }, COLUMN_IS_FOLDER + " = 0",
                null, null, null, COLUMN_CREATED_AT + " DESC", String.valueOf(Math.max(1, Math.min(6, limit))))) {
            while (cursor.moveToNext()) {
                result.add(new BookmarkModel(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), 0, false, 0));
            }
        }
        return result;
    }

    public synchronized List<BookmarkModel> getAllBookmarks() {
        return getItemsInFolder(0);
    }

    public synchronized int getTotalBookmarkCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_BOOKMARKS + " WHERE " + COLUMN_IS_FOLDER + " = 0", null);
        int count = 0;
        if (c != null && c.moveToFirst()) {
            count = c.getInt(0);
            c.close();
        }
        return count;
    }

    public synchronized List<BookmarkModel> searchBookmarks(String query, int limit) {
        List<BookmarkModel> list = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return list;
        SQLiteDatabase db = getReadableDatabase();
        String wild = "%" + query.trim() + "%";
        Cursor cursor = db.query(TABLE_BOOKMARKS, null,
                COLUMN_TITLE + " LIKE ? OR " + COLUMN_URL + " LIKE ?",
                new String[]{wild, wild}, null, null, COLUMN_IS_FOLDER + " DESC, " + COLUMN_CREATED_AT + " DESC",
                limit > 0 ? String.valueOf(limit) : null);

        if (cursor != null && cursor.moveToFirst()) {
            int idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID);
            int titleIdx = cursor.getColumnIndexOrThrow(COLUMN_TITLE);
            int urlIdx = cursor.getColumnIndexOrThrow(COLUMN_URL);
            int createdIdx = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT);
            int parentIdx = cursor.getColumnIndexOrThrow(COLUMN_PARENT_FOLDER_ID);
            int isFolderIdx = cursor.getColumnIndexOrThrow(COLUMN_IS_FOLDER);

            do {
                long id = cursor.getLong(idIdx);
                boolean isFolder = readIsFolder(cursor, isFolderIdx);
                int childCount = isFolder ? countFolderChildren(db, id) : 0;

                list.add(new BookmarkModel(
                        id,
                        cursor.getString(titleIdx),
                        cursor.getString(urlIdx),
                        cursor.getLong(createdIdx),
                        readParent(cursor, parentIdx),
                        isFolder,
                        childCount
                ));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return list;
    }

    /** Delete only committed subtrees; callers use the returned ids to clear previews afterwards. */
    public synchronized Set<Long> deleteItems(List<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Map<Long, BookmarkTree.Link> roots = readSelection(db, ids);
            if (roots.isEmpty()) return Collections.emptySet();
            Set<Long> deletion = BookmarkTree.subtree(roots.keySet(), id -> folderChildren(db, id));
            for (long id : deletion) {
                if (db.delete(TABLE_BOOKMARKS, BY_ID, bindId(id)) != 1) return Collections.emptySet();
            }
            db.setTransactionSuccessful();
            return deletion;
        } finally {
            db.endTransaction();
        }
    }

    private static List<Long> folderChildren(SQLiteDatabase db, long id) {
        List<Long> children = new ArrayList<>();
        // Don't treat a reference to a bookmark (or a missing row) as ownership of a subtree.
        try (Cursor cursor = db.rawQuery("SELECT child.id FROM bookmarks AS folder JOIN bookmarks AS child "
                + "ON child.parent_folder_id = folder.id WHERE folder.id = ? AND folder.is_folder = 1",
                bindId(id))) {
            while (cursor.moveToNext()) children.add(cursor.getLong(0));
        }
        return children;
    }

    public synchronized String getFolderName(long folderId) {
        if (folderId == 0) return "Bookmarks";
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_BOOKMARKS, new String[]{COLUMN_TITLE},
                COLUMN_ID + " = ? AND " + COLUMN_IS_FOLDER + " = 1",
                new String[]{String.valueOf(folderId)}, null, null, null);
        String name = "Folder";
        if (c != null && c.moveToFirst()) {
            name = c.getString(0);
            c.close();
        }
        return name;
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_BOOKMARKS, null, null);
    }
}

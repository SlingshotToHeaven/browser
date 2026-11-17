package com.privacy.browser.bookmarks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BookmarkDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "bookmarks.db";
    private static final int DATABASE_VERSION = 2;

    public static final String TABLE_BOOKMARKS = "bookmarks";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_TITLE = "title";
    public static final String COLUMN_URL = "url";
    public static final String COLUMN_CREATED_AT = "created_at";
    public static final String COLUMN_PARENT_FOLDER_ID = "parent_folder_id";
    public static final String COLUMN_IS_FOLDER = "is_folder";

    private static BookmarkDbHelper instance;

    public static synchronized BookmarkDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new BookmarkDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private BookmarkDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTableQuery = "CREATE TABLE " + TABLE_BOOKMARKS + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_TITLE + " TEXT NOT NULL, "
                + COLUMN_URL + " TEXT, "
                + COLUMN_CREATED_AT + " INTEGER NOT NULL, "
                + COLUMN_PARENT_FOLDER_ID + " INTEGER NOT NULL DEFAULT 0, "
                + COLUMN_IS_FOLDER + " INTEGER NOT NULL DEFAULT 0"
                + ");";
        db.execSQL(createTableQuery);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_BOOKMARKS + " ADD COLUMN " + COLUMN_PARENT_FOLDER_ID + " INTEGER NOT NULL DEFAULT 0;");
                db.execSQL("ALTER TABLE " + TABLE_BOOKMARKS + " ADD COLUMN " + COLUMN_IS_FOLDER + " INTEGER NOT NULL DEFAULT 0;");
            } catch (Exception ignored) {
                // In case columns were partially added
            }
        }
    }

    public synchronized long addBookmarkAndGetId(String title, String url, long parentFolderId) {
        if (url == null || url.trim().isEmpty()) return -1;
        if (title == null || title.trim().isEmpty()) title = url;

        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TITLE, title.trim());
        values.put(COLUMN_URL, url.trim());
        values.put(COLUMN_CREATED_AT, System.currentTimeMillis());
        values.put(COLUMN_PARENT_FOLDER_ID, parentFolderId);
        values.put(COLUMN_IS_FOLDER, 0);

        long result = db.insertWithOnConflict(TABLE_BOOKMARKS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (result == -1) {
            Cursor c = db.query(TABLE_BOOKMARKS, new String[]{COLUMN_ID}, COLUMN_URL + " = ?", new String[]{url.trim()}, null, null, null);
            if (c != null && c.moveToFirst()) {
                result = c.getLong(0);
                c.close();
            }
        }
        return result;
    }

    public synchronized boolean addBookmark(String title, String url) {
        return addBookmark(title, url, 0);
    }

    public synchronized boolean addBookmark(String title, String url, long parentFolderId) {
        if (url == null || url.trim().isEmpty()) return false;
        if (title == null || title.trim().isEmpty()) title = url;

        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TITLE, title.trim());
        values.put(COLUMN_URL, url.trim());
        values.put(COLUMN_CREATED_AT, System.currentTimeMillis());
        values.put(COLUMN_PARENT_FOLDER_ID, parentFolderId);
        values.put(COLUMN_IS_FOLDER, 0);

        long result = db.insertWithOnConflict(TABLE_BOOKMARKS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return result != -1;
    }

    public synchronized long createFolder(String folderName, long parentFolderId) {
        if (folderName == null || folderName.trim().isEmpty()) folderName = "New Folder";

        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TITLE, folderName.trim());
        values.put(COLUMN_URL, "");
        values.put(COLUMN_CREATED_AT, System.currentTimeMillis());
        values.put(COLUMN_PARENT_FOLDER_ID, parentFolderId);
        values.put(COLUMN_IS_FOLDER, 1);

        return db.insert(TABLE_BOOKMARKS, null, values);
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

    public synchronized boolean moveToFolder(List<Long> ids, long targetFolderId) {
        if (ids == null || ids.isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(COLUMN_PARENT_FOLDER_ID, targetFolderId);
            for (Long id : ids) {
                // Prevent moving a folder into itself
                if (id == targetFolderId) continue;
                db.update(TABLE_BOOKMARKS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
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

        // Folders first (alphabetical), then bookmarks (latest first)
        String orderBy = COLUMN_IS_FOLDER + " DESC, " +
                "CASE WHEN " + COLUMN_IS_FOLDER + " = 1 THEN " + COLUMN_TITLE + " END ASC, " +
                COLUMN_CREATED_AT + " DESC";

        Cursor cursor = db.query(TABLE_BOOKMARKS, null,
                COLUMN_PARENT_FOLDER_ID + " = ?",
                new String[]{String.valueOf(folderId)}, null, null, orderBy);

        if (cursor != null && cursor.moveToFirst()) {
            int idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID);
            int titleIdx = cursor.getColumnIndexOrThrow(COLUMN_TITLE);
            int urlIdx = cursor.getColumnIndexOrThrow(COLUMN_URL);
            int createdIdx = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT);
            int parentIdx = cursor.getColumnIndexOrThrow(COLUMN_PARENT_FOLDER_ID);
            int isFolderIdx = cursor.getColumnIndexOrThrow(COLUMN_IS_FOLDER);

            do {
                long id = cursor.getLong(idIdx);
                boolean isFolder = cursor.getInt(isFolderIdx) == 1;
                int childCount = isFolder ? countFolderChildren(db, id) : 0;

                list.add(new BookmarkModel(
                        id,
                        cursor.getString(titleIdx),
                        cursor.getString(urlIdx),
                        cursor.getLong(createdIdx),
                        cursor.getLong(parentIdx),
                        isFolder,
                        childCount
                ));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return list;
    }

    private int countFolderChildren(SQLiteDatabase db, long folderId) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_BOOKMARKS + " WHERE " + COLUMN_PARENT_FOLDER_ID + " = ?",
                new String[]{String.valueOf(folderId)});
        int count = 0;
        if (c != null && c.moveToFirst()) {
            count = c.getInt(0);
            c.close();
        }
        return count;
    }

    public synchronized List<BookmarkModel> getAllFolders() {
        List<BookmarkModel> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_BOOKMARKS, null,
                COLUMN_IS_FOLDER + " = 1",
                null, null, null, COLUMN_TITLE + " ASC");

        if (cursor != null && cursor.moveToFirst()) {
            int idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID);
            int titleIdx = cursor.getColumnIndexOrThrow(COLUMN_TITLE);
            int urlIdx = cursor.getColumnIndexOrThrow(COLUMN_URL);
            int createdIdx = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT);
            int parentIdx = cursor.getColumnIndexOrThrow(COLUMN_PARENT_FOLDER_ID);

            do {
                list.add(new BookmarkModel(
                        cursor.getLong(idIdx),
                        cursor.getString(titleIdx),
                        cursor.getString(urlIdx),
                        cursor.getLong(createdIdx),
                        cursor.getLong(parentIdx),
                        true,
                        0
                ));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return list;
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
                boolean isFolder = cursor.getInt(isFolderIdx) == 1;
                int childCount = isFolder ? countFolderChildren(db, id) : 0;

                list.add(new BookmarkModel(
                        id,
                        cursor.getString(titleIdx),
                        cursor.getString(urlIdx),
                        cursor.getLong(createdIdx),
                        cursor.getLong(parentIdx),
                        isFolder,
                        childCount
                ));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return list;
    }

    public synchronized void deleteItems(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) {
                deleteItemRecursive(db, id);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void deleteItemRecursive(SQLiteDatabase db, long id) {
        // Find children if folder
        Cursor c = db.query(TABLE_BOOKMARKS, new String[]{COLUMN_ID},
                COLUMN_PARENT_FOLDER_ID + " = ?", new String[]{String.valueOf(id)}, null, null, null);
        if (c != null) {
            List<Long> children = new ArrayList<>();
            while (c.moveToNext()) {
                children.add(c.getLong(0));
            }
            c.close();
            for (Long childId : children) {
                deleteItemRecursive(db, childId);
            }
        }
        db.delete(TABLE_BOOKMARKS, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
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

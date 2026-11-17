package com.privacy.browser.history;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class HistoryDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "history.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_HISTORY = "history";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_TITLE = "title";
    private static final String COLUMN_URL = "url";
    private static final String COLUMN_VISITED_AT = "visited_at";

    private static HistoryDbHelper instance;

    public static synchronized HistoryDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new HistoryDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private HistoryDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTableQuery = "CREATE TABLE " + TABLE_HISTORY + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_TITLE + " TEXT NOT NULL, "
                + COLUMN_URL + " TEXT NOT NULL, "
                + COLUMN_VISITED_AT + " INTEGER NOT NULL"
                + ");";
        db.execSQL(createTableQuery);
        db.execSQL("CREATE INDEX idx_history_visited_at ON " + TABLE_HISTORY + " (" + COLUMN_VISITED_AT + " DESC);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_HISTORY);
        onCreate(db);
    }

    public synchronized void addVisit(String title, String url) {
        if (url == null || url.trim().isEmpty() || url.startsWith("about:") || isInternalHomeUrl(url)) return;
        if (title == null || title.trim().isEmpty()) title = url;

        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TITLE, title.trim());
        values.put(COLUMN_URL, url.trim());
        values.put(COLUMN_VISITED_AT, System.currentTimeMillis());

        db.insert(TABLE_HISTORY, null, values);
    }

    public static boolean isInternalHomeUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        return trimmed.equals("file:///android_asset/home.html")
                || trimmed.startsWith("file:///android_asset/home.html")
                || trimmed.startsWith("file:///android_asset/");
    }

    public synchronized List<HistoryModel> getAllHistory(int limit) {
        List<HistoryModel> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String limitClause = limit > 0 ? String.valueOf(limit) : null;
        Cursor cursor = db.query(TABLE_HISTORY, null, null, null, null, null,
                COLUMN_VISITED_AT + " DESC", limitClause);

        if (cursor != null && cursor.moveToFirst()) {
            int idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID);
            int titleIdx = cursor.getColumnIndexOrThrow(COLUMN_TITLE);
            int urlIdx = cursor.getColumnIndexOrThrow(COLUMN_URL);
            int visitedIdx = cursor.getColumnIndexOrThrow(COLUMN_VISITED_AT);

            do {
                list.add(new HistoryModel(
                        cursor.getLong(idIdx),
                        cursor.getString(titleIdx),
                        cursor.getString(urlIdx),
                        cursor.getLong(visitedIdx)
                ));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return list;
    }

    public synchronized boolean deleteVisit(long id) {
        SQLiteDatabase db = getWritableDatabase();
        int rows = db.delete(TABLE_HISTORY, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
        return rows > 0;
    }

    public synchronized List<HistoryModel> searchHistory(String query, int limit) {
        List<HistoryModel> list = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return list;
        SQLiteDatabase db = getReadableDatabase();
        String limitClause = limit > 0 ? String.valueOf(limit) : null;
        String wild = "%" + query.trim() + "%";
        Cursor cursor = db.query(TABLE_HISTORY, null,
                COLUMN_TITLE + " LIKE ? OR " + COLUMN_URL + " LIKE ?",
                new String[]{wild, wild}, null, null,
                COLUMN_VISITED_AT + " DESC", limitClause);

        if (cursor != null && cursor.moveToFirst()) {
            int idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID);
            int titleIdx = cursor.getColumnIndexOrThrow(COLUMN_TITLE);
            int urlIdx = cursor.getColumnIndexOrThrow(COLUMN_URL);
            int visitedIdx = cursor.getColumnIndexOrThrow(COLUMN_VISITED_AT);

            do {
                list.add(new HistoryModel(
                        cursor.getLong(idIdx),
                        cursor.getString(titleIdx),
                        cursor.getString(urlIdx),
                        cursor.getLong(visitedIdx)
                ));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return list;
    }

    public synchronized void deleteVisits(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) {
                db.delete(TABLE_HISTORY, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized int deleteVisitsByDomain(String domain) {
        if (domain == null || domain.trim().isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        String wild = "%" + domain.trim() + "%";
        return db.delete(TABLE_HISTORY, COLUMN_URL + " LIKE ?", new String[]{wild});
    }

    public synchronized int clearHistorySince(long timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_HISTORY, COLUMN_VISITED_AT + " >= ?", new String[]{String.valueOf(timestamp)});
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_HISTORY, null, null);
    }
}

package com.privacy.downloader.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;

import java.util.ArrayList;
import java.util.List;

public class DownloadDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "downloads.db";
    private static final int DATABASE_VERSION = 3;

    public static final String TABLE_DOWNLOADS = "downloads";
    public static final String COL_ID = "id";
    public static final String COL_URL = "url";
    public static final String COL_ORIGINAL_URL = "original_url";
    public static final String COL_FILE_NAME = "file_name";
    public static final String COL_DEST_PATH = "dest_path";
    public static final String COL_TEMP_PATH = "temp_path";
    public static final String COL_MIME_TYPE = "mime_type";
    public static final String COL_TOTAL_BYTES = "total_bytes";
    public static final String COL_DOWNLOADED_BYTES = "downloaded_bytes";
    public static final String COL_STATUS = "status";
    public static final String COL_NUM_SEGMENTS = "num_segments";
    public static final String COL_SUPPORTS_RANGE = "supports_range";
    public static final String COL_ETAG = "etag";
    public static final String COL_LAST_MODIFIED = "last_modified";
    public static final String COL_COOKIES = "cookies";
    public static final String COL_USER_AGENT = "user_agent";
    public static final String COL_REFERRER = "referrer";
    public static final String COL_CREATED_TIME = "created_time";
    public static final String COL_FINISHED_TIME = "finished_time";
    public static final String COL_ERROR_MESSAGE = "error_message";
    public static final String COL_WIFI_ONLY = "wifi_only";
    public static final String COL_AUDIO_URL = "audio_url";
    public static final String COL_AUDIO_BYTES = "audio_bytes";

    public static final String TABLE_SEGMENTS = "download_segments";
    public static final String COL_SEG_TASK_ID = "task_id";
    public static final String COL_SEG_INDEX = "segment_index";
    public static final String COL_SEG_START = "start_byte";
    public static final String COL_SEG_END = "end_byte";
    public static final String COL_SEG_CURRENT = "current_byte";
    public static final String COL_SEG_STATUS = "status";

    private static DownloadDbHelper instance;

    public static synchronized DownloadDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DownloadDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DownloadDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createDownloadsTable = "CREATE TABLE " + TABLE_DOWNLOADS + " ("
                + COL_ID + " TEXT PRIMARY KEY, "
                + COL_URL + " TEXT NOT NULL, "
                + COL_ORIGINAL_URL + " TEXT, "
                + COL_FILE_NAME + " TEXT, "
                + COL_DEST_PATH + " TEXT, "
                + COL_TEMP_PATH + " TEXT, "
                + COL_MIME_TYPE + " TEXT, "
                + COL_TOTAL_BYTES + " INTEGER DEFAULT -1, "
                + COL_DOWNLOADED_BYTES + " INTEGER DEFAULT 0, "
                + COL_STATUS + " TEXT NOT NULL, "
                + COL_NUM_SEGMENTS + " INTEGER DEFAULT 4, "
                + COL_SUPPORTS_RANGE + " INTEGER DEFAULT 0, "
                + COL_ETAG + " TEXT, "
                + COL_LAST_MODIFIED + " TEXT, "
                + COL_COOKIES + " TEXT, "
                + COL_USER_AGENT + " TEXT, "
                + COL_REFERRER + " TEXT, "
                + COL_CREATED_TIME + " INTEGER, "
                + COL_FINISHED_TIME + " INTEGER, "
                + COL_ERROR_MESSAGE + " TEXT, "
                + COL_WIFI_ONLY + " INTEGER DEFAULT 0, "
                + COL_AUDIO_URL + " TEXT, "
                + COL_AUDIO_BYTES + " INTEGER DEFAULT -1)";
        db.execSQL(createDownloadsTable);

        String createSegmentsTable = "CREATE TABLE " + TABLE_SEGMENTS + " ("
                + COL_SEG_TASK_ID + " TEXT NOT NULL, "
                + COL_SEG_INDEX + " INTEGER NOT NULL, "
                + COL_SEG_START + " INTEGER NOT NULL, "
                + COL_SEG_END + " INTEGER NOT NULL, "
                + COL_SEG_CURRENT + " INTEGER NOT NULL, "
                + COL_SEG_STATUS + " TEXT NOT NULL, "
                + "PRIMARY KEY (" + COL_SEG_TASK_ID + ", " + COL_SEG_INDEX + "))";
        db.execSQL(createSegmentsTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_WIFI_ONLY + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_AUDIO_URL + " TEXT");
                db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_AUDIO_BYTES + " INTEGER DEFAULT -1");
            } catch (Exception ignored) {}
        }
    }

    public synchronized void insertOrUpdate(DownloadItem item) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_ID, item.getId());
        cv.put(COL_URL, item.getUrl());
        cv.put(COL_ORIGINAL_URL, item.getOriginalUrl());
        cv.put(COL_FILE_NAME, item.getFileName());
        cv.put(COL_DEST_PATH, item.getDestinationPath());
        cv.put(COL_TEMP_PATH, item.getTempPath());
        cv.put(COL_MIME_TYPE, item.getMimeType());
        cv.put(COL_TOTAL_BYTES, item.getTotalBytes());
        cv.put(COL_DOWNLOADED_BYTES, item.getDownloadedBytes());
        cv.put(COL_STATUS, item.getStatus().name());
        cv.put(COL_NUM_SEGMENTS, item.getNumSegments());
        cv.put(COL_SUPPORTS_RANGE, item.isSupportsRange() ? 1 : 0);
        cv.put(COL_ETAG, item.getEtag());
        cv.put(COL_LAST_MODIFIED, item.getLastModified());
        cv.put(COL_COOKIES, item.getCookies());
        cv.put(COL_USER_AGENT, item.getUserAgent());
        cv.put(COL_REFERRER, item.getReferrer());
        cv.put(COL_CREATED_TIME, item.getCreatedTime());
        cv.put(COL_FINISHED_TIME, item.getFinishedTime());
        cv.put(COL_ERROR_MESSAGE, item.getErrorMessage());
        cv.put(COL_WIFI_ONLY, item.isWifiOnly() ? 1 : 0);
        cv.put(COL_AUDIO_URL, item.getAudioUrl());
        cv.put(COL_AUDIO_BYTES, item.getAudioBytes());

        db.insertWithOnConflict(TABLE_DOWNLOADS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        if (item.getSegments() != null && !item.getSegments().isEmpty()) {
            saveSegments(item.getId(), item.getSegments());
        }
    }

    public synchronized void saveSegments(String taskId, List<DownloadSegment> segments) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (DownloadSegment seg : segments) {
                ContentValues cv = new ContentValues();
                cv.put(COL_SEG_TASK_ID, taskId);
                cv.put(COL_SEG_INDEX, seg.getIndex());
                cv.put(COL_SEG_START, seg.getStartByte());
                cv.put(COL_SEG_END, seg.getEndByte());
                cv.put(COL_SEG_CURRENT, seg.getCurrentByte());
                cv.put(COL_SEG_STATUS, seg.getStatus().name());
                db.insertWithOnConflict(TABLE_SEGMENTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void updateProgress(String taskId, long downloadedBytes) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED_BYTES, downloadedBytes);
        db.update(TABLE_DOWNLOADS, cv, COL_ID + "=?", new String[]{taskId});
    }

    public synchronized void updateStatus(String taskId, DownloadStatus status, String errorMessage) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS, status.name());
        cv.put(COL_ERROR_MESSAGE, errorMessage);
        if (status == DownloadStatus.COMPLETED) {
            cv.put(COL_FINISHED_TIME, System.currentTimeMillis());
        }
        db.update(TABLE_DOWNLOADS, cv, COL_ID + "=?", new String[]{taskId});
    }

    public synchronized void updateUrl(String taskId, String newUrl) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_URL, newUrl);
        cv.put(COL_STATUS, DownloadStatus.PAUSED.name());
        cv.put(COL_ERROR_MESSAGE, (String) null);
        db.update(TABLE_DOWNLOADS, cv, COL_ID + "=?", new String[]{taskId});
    }

    public synchronized DownloadItem getDownload(String taskId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_DOWNLOADS, null, COL_ID + "=?", new String[]{taskId}, null, null, null)) {
            if (c.moveToFirst()) {
                DownloadItem item = cursorToDownloadItem(c);
                item.setSegments(getSegments(taskId));
                return item;
            }
        }
        return null;
    }

    public synchronized List<DownloadItem> getAllDownloads() {
        List<DownloadItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_DOWNLOADS, null, null, null, null, null, COL_CREATED_TIME + " DESC")) {
            while (c.moveToNext()) {
                DownloadItem item = cursorToDownloadItem(c);
                item.setSegments(getSegments(item.getId()));
                list.add(item);
            }
        }
        return list;
    }

    public synchronized List<DownloadSegment> getSegments(String taskId) {
        List<DownloadSegment> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_SEGMENTS, null, COL_SEG_TASK_ID + "=?", new String[]{taskId}, null, null, COL_SEG_INDEX + " ASC")) {
            while (c.moveToNext()) {
                int index = c.getInt(c.getColumnIndexOrThrow(COL_SEG_INDEX));
                long start = c.getLong(c.getColumnIndexOrThrow(COL_SEG_START));
                long end = c.getLong(c.getColumnIndexOrThrow(COL_SEG_END));
                long current = c.getLong(c.getColumnIndexOrThrow(COL_SEG_CURRENT));
                String statusStr = c.getString(c.getColumnIndexOrThrow(COL_SEG_STATUS));
                DownloadStatus status = DownloadStatus.valueOf(statusStr);
                list.add(new DownloadSegment(taskId, index, start, end, current, status));
            }
        }
        return list;
    }

    public synchronized void deleteDownload(String taskId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SEGMENTS, COL_SEG_TASK_ID + "=?", new String[]{taskId});
        db.delete(TABLE_DOWNLOADS, COL_ID + "=?", new String[]{taskId});
    }

    public synchronized void resetActiveToPaused() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS, DownloadStatus.PAUSED.name());
        db.update(TABLE_DOWNLOADS, cv, COL_STATUS + " IN (?, ?, ?)",
                new String[]{DownloadStatus.PENDING.name(), DownloadStatus.CONNECTING.name(), DownloadStatus.RUNNING.name()});
    }

    public synchronized void clearCompletedDownloads() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM " + TABLE_SEGMENTS + " WHERE " + COL_SEG_TASK_ID + " IN (SELECT " + COL_ID + " FROM " + TABLE_DOWNLOADS + " WHERE " + COL_STATUS + " = ?)",
                new Object[]{DownloadStatus.COMPLETED.name()});
        db.delete(TABLE_DOWNLOADS, COL_STATUS + "=?", new String[]{DownloadStatus.COMPLETED.name()});
    }

    private DownloadItem cursorToDownloadItem(Cursor c) {
        DownloadItem item = new DownloadItem();
        item.setId(c.getString(c.getColumnIndexOrThrow(COL_ID)));
        item.setUrl(c.getString(c.getColumnIndexOrThrow(COL_URL)));
        item.setOriginalUrl(c.getString(c.getColumnIndexOrThrow(COL_ORIGINAL_URL)));
        item.setFileName(c.getString(c.getColumnIndexOrThrow(COL_FILE_NAME)));
        item.setDestinationPath(c.getString(c.getColumnIndexOrThrow(COL_DEST_PATH)));
        item.setTempPath(c.getString(c.getColumnIndexOrThrow(COL_TEMP_PATH)));
        item.setMimeType(c.getString(c.getColumnIndexOrThrow(COL_MIME_TYPE)));
        item.setTotalBytes(c.getLong(c.getColumnIndexOrThrow(COL_TOTAL_BYTES)));
        item.setDownloadedBytes(c.getLong(c.getColumnIndexOrThrow(COL_DOWNLOADED_BYTES)));
        item.setStatus(DownloadStatus.valueOf(c.getString(c.getColumnIndexOrThrow(COL_STATUS))));
        item.setNumSegments(c.getInt(c.getColumnIndexOrThrow(COL_NUM_SEGMENTS)));
        item.setSupportsRange(c.getInt(c.getColumnIndexOrThrow(COL_SUPPORTS_RANGE)) == 1);
        item.setEtag(c.getString(c.getColumnIndexOrThrow(COL_ETAG)));
        item.setLastModified(c.getString(c.getColumnIndexOrThrow(COL_LAST_MODIFIED)));
        item.setCookies(c.getString(c.getColumnIndexOrThrow(COL_COOKIES)));
        item.setUserAgent(c.getString(c.getColumnIndexOrThrow(COL_USER_AGENT)));
        item.setReferrer(c.getString(c.getColumnIndexOrThrow(COL_REFERRER)));
        item.setCreatedTime(c.getLong(c.getColumnIndexOrThrow(COL_CREATED_TIME)));
        item.setFinishedTime(c.getLong(c.getColumnIndexOrThrow(COL_FINISHED_TIME)));
        item.setErrorMessage(c.getString(c.getColumnIndexOrThrow(COL_ERROR_MESSAGE)));
        int wifiIdx = c.getColumnIndex(COL_WIFI_ONLY);
        if (wifiIdx != -1) {
            item.setWifiOnly(c.getInt(wifiIdx) == 1);
        }
        int audioUrlIdx = c.getColumnIndex(COL_AUDIO_URL);
        if (audioUrlIdx != -1) {
            item.setAudioUrl(c.getString(audioUrlIdx));
        }
        int audioBytesIdx = c.getColumnIndex(COL_AUDIO_BYTES);
        if (audioBytesIdx != -1) {
            item.setAudioBytes(c.getLong(audioBytesIdx));
        }
        return item;
    }
}

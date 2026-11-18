package com.privacy.downloader.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.CancellationSignal;
import android.os.Trace;

import com.privacy.downloader.engine.DownloadQueue;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.util.DownloadDestination;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.function.Predicate;

public class DownloadDbHelper extends SQLiteOpenHelper implements DownloadQueue.Store {

    private static final String DATABASE_NAME = "downloads.db";
    private static final int DATABASE_VERSION = 8;

    public static final String TABLE_DOWNLOADS = "downloads";
    public static final String COL_ID = "id";
    public static final String COL_URL = "url";
    public static final String COL_ORIGINAL_URL = "original_url";
    public static final String COL_FILE_NAME = "file_name";
    public static final String COL_DEST_PATH = "dest_path";
    private static final String COL_NAME_SEARCH = "name_search";
    private static final String COL_NAME_ORDER = "name_order";
    private static final String COL_DESTINATION_KEY = "destination_key";
    private static final String COL_EXPECTED_SHA256 = "expected_sha256";
    private static final String COL_ATTEMPT_TOKEN = "attempt_token";
    private static final String COL_PUBLISHED_IDENTITY = "published_identity";
    public static final String COL_TEMP_PATH = "temp_path";
    public static final String COL_MIME_TYPE = "mime_type";
    public static final String COL_TOTAL_BYTES = "total_bytes";
    public static final String COL_DOWNLOADED_BYTES = "downloaded_bytes";
    public static final String COL_STATUS = "status";
    public static final String COL_NUM_SEGMENTS = "num_segments";
    public static final String COL_SUPPORTS_RANGE = "supports_range";
    public static final String COL_ETAG = "etag";
    private static final String COL_VERIFIED_RANGE_URL = "verified_range_url";
    private static final String COL_FULL_DOWNLOAD_ONLY = "full_download_only";
    public static final String COL_LAST_MODIFIED = "last_modified";
    // Kept only for compatibility with older SQLite versions. Never read or populated.
    private static final String COL_COOKIES = "cookies";
    private static final String COL_USER_AGENT = "user_agent";
    private static final String COL_REFERRER = "referrer";
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

    private static final String UNKEYED_PATHS = "(destination_key IS NULL AND dest_path IS NOT NULL) OR temp_path IS NOT NULL";
    private static final String PATHS_INDEX = "CREATE INDEX downloads_unkeyed_paths ON downloads (id) WHERE " + UNKEYED_PATHS;
    private static final String QUEUE_ROW = COL_ID + "=? AND " + COL_STATUS + "=? AND " + COL_ATTEMPT_TOKEN;
    private static final String TRANSFERRING = COL_STATUS + " IN ('CONNECTING','RUNNING')";
    private static final String WORKER = COL_ID + "=? AND " + COL_ATTEMPT_TOKEN + "=?";
    private static final String ACTIVE_WORKER = WORKER + " AND " + TRANSFERRING;
    private static final String STAGING_WORKER = ACTIVE_WORKER + " AND " + COL_DEST_PATH + "=? AND " + COL_TEMP_PATH;
    private static final String CHECKPOINT_WORKER = WORKER + " AND " + COL_TEMP_PATH + "=? AND " + COL_STATUS
            + " IN ('RUNNING','PAUSED','PENDING','WAITING_FOR_WIFI','WAITING_FOR_SCHEDULE','WAITING_FOR_BATTERY')";
    private static final String PUBLISHING_WORKER = WORKER + " AND " + COL_DEST_PATH + "=? AND " + COL_TEMP_PATH + "=? AND "
            + COL_DESTINATION_KEY + " IS NOT NULL AND " + COL_STATUS + "='RUNNING'";
    private static final String SHAREABLE_ROW = COL_ID + "=? AND " + COL_STATUS + "='COMPLETED' AND "
            + COL_ATTEMPT_TOKEN + " IS NOT NULL AND " + COL_DESTINATION_KEY + " IS NOT NULL AND "
            + COL_TEMP_PATH + " IS NULL AND " + COL_PUBLISHED_IDENTITY + " IS NOT NULL AND "
            + COL_TOTAL_BYTES + ">=0 AND " + COL_TOTAL_BYTES + "=" + COL_DOWNLOADED_BYTES;

    private static final String[] TASK_COLUMNS = {COL_ID, COL_ATTEMPT_TOKEN, COL_URL, COL_ORIGINAL_URL, COL_FILE_NAME,
            COL_DEST_PATH, COL_TEMP_PATH, COL_MIME_TYPE, COL_EXPECTED_SHA256, COL_TOTAL_BYTES, COL_DOWNLOADED_BYTES,
            COL_STATUS, COL_NUM_SEGMENTS, COL_SUPPORTS_RANGE, COL_ETAG, COL_VERIFIED_RANGE_URL, COL_FULL_DOWNLOAD_ONLY,
            COL_LAST_MODIFIED, COL_CREATED_TIME, COL_FINISHED_TIME, COL_ERROR_MESSAGE, COL_WIFI_ONLY, COL_AUDIO_URL, COL_AUDIO_BYTES};
    // Only the prefix is needed for orphaned blob intake. Never materialize a queued data: payload here.
    private static final String[] QUEUE_COLUMNS = {COL_ID, COL_STATUS, COL_ATTEMPT_TOKEN, COL_WIFI_ONLY, COL_ERROR_MESSAGE,
            "substr(" + COL_URL + ",1,5)"};

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
                + COL_DESTINATION_KEY + " TEXT, "
                + COL_EXPECTED_SHA256 + " TEXT, "
                + COL_ATTEMPT_TOKEN + " TEXT, "
                + COL_PUBLISHED_IDENTITY + " TEXT, "
                + COL_TEMP_PATH + " TEXT, "
                + COL_MIME_TYPE + " TEXT, "
                + COL_TOTAL_BYTES + " INTEGER DEFAULT -1, "
                + COL_DOWNLOADED_BYTES + " INTEGER DEFAULT 0, "
                + COL_STATUS + " TEXT NOT NULL, "
                + COL_NUM_SEGMENTS + " INTEGER DEFAULT 4, "
                + COL_SUPPORTS_RANGE + " INTEGER DEFAULT 0, "
                + COL_ETAG + " TEXT, "
                + COL_VERIFIED_RANGE_URL + " TEXT, "
                + COL_FULL_DOWNLOAD_ONLY + " INTEGER DEFAULT 0, "
                + COL_LAST_MODIFIED + " TEXT, "
                + COL_COOKIES + " TEXT, "
                + COL_USER_AGENT + " TEXT, "
                + COL_REFERRER + " TEXT, "
                + COL_CREATED_TIME + " INTEGER, "
                + COL_FINISHED_TIME + " INTEGER, "
                + COL_ERROR_MESSAGE + " TEXT, "
                + COL_WIFI_ONLY + " INTEGER DEFAULT 0, "
                + COL_AUDIO_URL + " TEXT, "
                + COL_AUDIO_BYTES + " INTEGER DEFAULT -1, "
                + COL_NAME_SEARCH + " TEXT NOT NULL DEFAULT '', "
                + COL_NAME_ORDER + " TEXT NOT NULL DEFAULT '')";
        db.execSQL(createDownloadsTable);
        createDestinationIndex(db);
        createQueryIndexes(db);

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
        if (oldVersion < 4) {
            // NULL on old rows, even if the legacy transfer recorded an ETag.
            db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_VERIFIED_RANGE_URL + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_FULL_DOWNLOAD_ONLY + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 5) {
            // Legacy rows may already share a destination. NULL preserves them without claiming ownership.
            db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_DESTINATION_KEY + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_EXPECTED_SHA256 + " TEXT");
            createDestinationIndex(db);
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_ATTEMPT_TOKEN + " TEXT");
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_PUBLISHED_IDENTITY + " TEXT");
            db.execSQL("UPDATE " + TABLE_DOWNLOADS + " SET " + COL_COOKIES + "=NULL, " + COL_USER_AGENT + "=NULL, "
                    + COL_REFERRER + "=NULL WHERE " + COL_COOKIES + " IS NOT NULL OR " + COL_USER_AGENT + " IS NOT NULL OR "
                    + COL_REFERRER + " IS NOT NULL");
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_NAME_SEARCH + " TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE_DOWNLOADS + " ADD COLUMN " + COL_NAME_ORDER + " TEXT NOT NULL DEFAULT ''");
            backfillNames(db);
            createQueryIndexes(db);
        }
    }

    private static void createQueryIndexes(SQLiteDatabase db) {
        db.execSQL(DownloadHistory.STATUS_INDEX);
        db.execSQL(DownloadHistory.DATE_INDEX);
        db.execSQL(DownloadHistory.SIZE_INDEX);
        db.execSQL(DownloadHistory.NAME_INDEX);
        db.execSQL(DownloadHistory.COMPLETED_SIZE_INDEX);
        db.execSQL(DownloadHistory.COMPLETED_NAME_INDEX);
        db.execSQL(DownloadHistory.DUPLICATE_INDEX);
        db.execSQL(PATHS_INDEX);
    }

    private static void backfillNames(SQLiteDatabase db) {
        // Snapshot each small batch before updating it, inside SQLiteOpenHelper's upgrade transaction.
        Long after = null;
        for (;;) {
            Map<Long, String> names = new HashMap<>();
            try (Cursor cursor = db.query(TABLE_DOWNLOADS, new String[]{"rowid", COL_FILE_NAME},
                    after == null ? null : "rowid>?", after == null ? null : new String[]{after.toString()},
                    null, null, "rowid", "256")) {
                while (cursor.moveToNext()) {
                    after = cursor.getLong(0);
                    names.put(after, cursor.getString(1));
                }
            }
            if (names.isEmpty()) return;
            for (Map.Entry<Long, String> row : names.entrySet()) {
                ContentValues values = new ContentValues(2);
                putNameKeys(values, row.getValue());
                db.update(TABLE_DOWNLOADS, values, "rowid=?", new String[]{row.getKey().toString()});
            }
        }
    }

    private static void putNameKeys(ContentValues values, String name) {
        values.put(COL_NAME_SEARCH, DownloadHistory.searchKey(name));
        values.put(COL_NAME_ORDER, DownloadHistory.nameKey(name));
    }

    private static void createDestinationIndex(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX downloads_destination_key ON " + TABLE_DOWNLOADS + " (" + COL_DESTINATION_KEY + ")");
    }

    /** Reserve and insert together; a failed insert must not replace another job. */
    public synchronized void enqueueDownload(DownloadItem item, File requested) throws IOException {
        item.setExpectedSha256(DownloadDestination.normalizeSha256(item.getExpectedSha256()));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            File chosen = DownloadDestination.choose(requested, pathClaims(db, item.getId()));
            item.setFileName(chosen.getName());
            item.setDestinationPath(chosen.getPath());
            item.setTempPath(null);
            item.setAttemptToken(null);
            item.setStatus(DownloadStatus.PENDING);
            ContentValues cv = transferValues(item);
            cv.put(COL_ID, item.getId());
            cv.put(COL_FILE_NAME, chosen.getName());
            putNameKeys(cv, chosen.getName());
            cv.put(COL_DEST_PATH, chosen.getPath());
            cv.put(COL_DESTINATION_KEY, DownloadDestination.key(chosen));
            cv.put(COL_EXPECTED_SHA256, item.getExpectedSha256());
            cv.put(COL_ORIGINAL_URL, item.getOriginalUrl());
            cv.put(COL_CREATED_TIME, item.getCreatedTime());
            cv.put(COL_FINISHED_TIME, 0);
            cv.put(COL_WIFI_ONLY, item.isWifiOnly() ? 1 : 0);
            cv.put(COL_AUDIO_URL, item.getAudioUrl());
            cv.put(COL_AUDIO_BYTES, item.getAudioBytes());
            cv.put(COL_STATUS, DownloadStatus.PENDING.name());
            db.insertOrThrow(TABLE_DOWNLOADS, null, cv);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** One transaction both claims a legacy name if needed and installs a new worker token. */
    @Override
    public synchronized boolean claim(DownloadItem item, File requested, String token) throws IOException {
        if (item.getStatus() != DownloadStatus.PENDING) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String condition = currentRow(item);
            String[] args = rowArgs(item);
            String reservedKey;
            try (Cursor row = db.query(TABLE_DOWNLOADS, new String[]{COL_DESTINATION_KEY}, condition, args, null, null, null)) {
                if (!row.moveToFirst()) return false;
                reservedKey = row.getString(0);
            }
            File destination = new File(item.getDestinationPath());
            // The name might be a complete hard link, an interrupted copy, or an unrelated file.
            // None of these possibilities proves a publication committed. Leave both paths alone.
            DownloadDestination.requireVacant(destination);
            ContentValues claim = new ContentValues();
            if (reservedKey == null) {
                DownloadDestination.requireVacant(requested);
                destination = DownloadDestination.choose(requested, pathClaims(db, item.getId()));
                claim.put(COL_DESTINATION_KEY, DownloadDestination.key(destination));
                claim.put(COL_DEST_PATH, destination.getPath());
                claim.put(COL_FILE_NAME, destination.getName());
                putNameKeys(claim, destination.getName());
            } else if (!reservedKey.equals(DownloadDestination.key(destination))) {
                throw new IOException("The reserved download folder has changed. Restore it or queue a new download.");
            }
            claim.put(COL_ATTEMPT_TOKEN, token);
            claim.put(COL_STATUS, DownloadStatus.CONNECTING.name());
            claim.putNull(COL_ERROR_MESSAGE);
            if (db.update(TABLE_DOWNLOADS, claim, condition, args) != 1) return false;
            db.setTransactionSuccessful();
            item.setAttemptToken(token);
            item.setStatus(DownloadStatus.CONNECTING);
            item.setErrorMessage(null);
            item.setFileName(destination.getName());
            item.setDestinationPath(destination.getPath());
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private static Predicate<String> pathClaims(SQLiteDatabase db, String exceptId) throws IOException {
        Set<String> unkeyed = new HashSet<>();
        // Legacy destinations and staging paths can have filesystem aliases. Resolve the small residual
        // set as before; modern destination claims already have a canonical key and an exact index.
        try (Cursor row = db.query(TABLE_DOWNLOADS, new String[]{COL_DESTINATION_KEY, COL_DEST_PATH, COL_TEMP_PATH},
                "(" + UNKEYED_PATHS + ") AND " + COL_ID + "<>?", new String[]{exceptId}, null, null, null)) {
            while (row.moveToNext()) {
                if (row.isNull(0)) addPathKey(unkeyed, row.getString(1));
                addPathKey(unkeyed, row.getString(2));
            }
        }
        return key -> unkeyed.contains(key) || pathKeyExists(db, key, exceptId);
    }

    private static boolean pathKeyExists(SQLiteDatabase db, String key, String exceptId) {
        try (Cursor row = db.query(TABLE_DOWNLOADS, new String[]{COL_ID}, COL_DESTINATION_KEY + "=? AND " + COL_ID + "<>?",
                new String[]{key, exceptId}, null, null, null, "1")) {
            return row.moveToFirst();
        }
    }

    private static void addPathKey(Set<String> keys, String path) throws IOException {
        if (path != null) {
            File file = new File(path);
            if (file.isAbsolute() && file.getParentFile() != null) keys.add(DownloadDestination.key(file));
        }
    }

    private static String currentRow(DownloadItem expected) {
        return currentRow(expected.getAttemptToken());
    }

    private static String currentRow(String token) {
        // Android's String[] bind arguments cannot bind null.
        return QUEUE_ROW + (token == null ? " IS NULL" : "=?");
    }

    private static String[] rowArgs(DownloadItem expected) {
        return rowArgs(expected.getId(), expected.getStatus(), expected.getAttemptToken());
    }

    private static String[] rowArgs(String id, DownloadStatus status, String token) {
        return token == null ? new String[]{id, status.name()} : new String[]{id, status.name(), token};
    }

    @Override
    public synchronized boolean transition(DownloadItem expected, DownloadStatus next, String reason) {
        return getWritableDatabase().update(TABLE_DOWNLOADS, stateValues(next, reason),
                currentRow(expected), rowArgs(expected)) == 1;
    }

    private static ContentValues stateValues(DownloadStatus state, String reason) {
        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS, state.name());
        cv.put(COL_ERROR_MESSAGE, reason);
        return cv;
    }

    /** A deliberate link edit or Start over changes the transfer and segments as one snapshot. */
    @Override
    public synchronized boolean saveRetry(DownloadItem replacement, DownloadStatus previousState) {
        ContentValues cv = transferValues(replacement);
        cv.put(COL_STATUS, DownloadStatus.PENDING.name());
        cv.putNull(COL_ERROR_MESSAGE);
        cv.putNull(COL_ATTEMPT_TOKEN);
        cv.put(COL_FINISHED_TIME, 0);
        return saveTransferAndSegments(replacement, cv, currentRow(replacement),
                rowArgs(replacement.getId(), previousState, replacement.getAttemptToken()));
    }

    /** Bind a worker's staging path without changing queue state or the destination claim. */
    public synchronized boolean saveWorkerStaging(DownloadItem item, String token, String previousTempPath) {
        if (token == null) return false;
        String where = STAGING_WORKER;
        String id = item.getId(), dest = item.getDestinationPath();
        String[] args = previousTempPath == null ? new String[]{id, token, dest} : new String[]{id, token, dest, previousTempPath};
        where += previousTempPath == null ? " IS NULL" : "=?";
        return saveTransferAndSegments(item, transferValues(item), where, args);
    }

    private boolean saveTransferAndSegments(DownloadItem item, ContentValues cv, String where, String[] args) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (db.update(TABLE_DOWNLOADS, cv, where, args) != 1) return false;
            replaceSegments(db, item.getId(), item.getSegments());
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private static ContentValues transferValues(DownloadItem item) {
        ContentValues cv = new ContentValues();
        cv.put(COL_URL, item.getUrl());
        cv.put(COL_TEMP_PATH, item.getTempPath());
        cv.put(COL_MIME_TYPE, item.getMimeType());
        cv.put(COL_TOTAL_BYTES, item.getTotalBytes());
        cv.put(COL_DOWNLOADED_BYTES, item.getDownloadedBytes());
        cv.put(COL_NUM_SEGMENTS, item.getNumSegments());
        cv.put(COL_SUPPORTS_RANGE, item.isSupportsRange() ? 1 : 0);
        cv.put(COL_ETAG, item.getEtag());
        cv.put(COL_VERIFIED_RANGE_URL, item.getVerifiedRangeUrl());
        cv.put(COL_FULL_DOWNLOAD_ONLY, item.isFullDownloadOnly() ? 1 : 0);
        cv.put(COL_LAST_MODIFIED, item.getLastModified());
        return cv;
    }

    /** Draining workers can save synced bytes, but cannot transition a stopped row or write into a replacement. */
    public synchronized boolean saveCheckpoint(String taskId, String token, String tempPath,
                                               List<DownloadSegment> segments, long downloadedBytes) {
        if (token == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put(COL_DOWNLOADED_BYTES, downloadedBytes);
            if (db.update(TABLE_DOWNLOADS, cv, CHECKPOINT_WORKER, new String[]{taskId, token, tempPath}) != 1) return false;
            replaceSegments(db, taskId, segments);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private static void replaceSegments(SQLiteDatabase db, String taskId, List<DownloadSegment> segments) {
        db.delete(TABLE_SEGMENTS, COL_SEG_TASK_ID + "=?", new String[]{taskId});
        if (segments == null) return;
        for (DownloadSegment seg : segments) {
            ContentValues cv = new ContentValues();
            cv.put(COL_SEG_TASK_ID, taskId);
            cv.put(COL_SEG_INDEX, seg.getIndex());
            cv.put(COL_SEG_START, seg.getStartByte());
            cv.put(COL_SEG_END, seg.getEndByte());
            synchronized (seg) {
                cv.put(COL_SEG_CURRENT, seg.getCurrentByte());
                cv.put(COL_SEG_STATUS, seg.getStatus().name());
            }
            db.insertOrThrow(TABLE_SEGMENTS, null, cv);
        }
    }

    public synchronized boolean updateWorkerProgress(String taskId, String token, long downloadedBytes) {
        if (token == null) return false;
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED_BYTES, downloadedBytes);
        return getWritableDatabase().update(TABLE_DOWNLOADS, cv, ACTIVE_WORKER, new String[]{taskId, token}) == 1;
    }

    public synchronized boolean updateWorkerStatus(String taskId, String token, DownloadStatus status, String errorMessage) {
        if (token == null || (status != DownloadStatus.RUNNING && status != DownloadStatus.FAILED)) return false;
        return getWritableDatabase().update(TABLE_DOWNLOADS, stateValues(status, errorMessage),
                ACTIVE_WORKER, new String[]{taskId, token}) == 1;
    }

    public synchronized boolean recordPublication(String taskId, String token, String destination, String staging,
                                                  long size, String mime, long finishedAt, String identity) {
        if (token == null) return false;
        ContentValues cv = stateValues(DownloadStatus.COMPLETED, null);
        cv.put(COL_TOTAL_BYTES, size);
        cv.put(COL_DOWNLOADED_BYTES, size);
        cv.put(COL_FINISHED_TIME, finishedAt);
        cv.put(COL_MIME_TYPE, mime);
        cv.putNull(COL_TEMP_PATH);
        cv.put(COL_PUBLISHED_IDENTITY, identity);
        return getWritableDatabase().update(TABLE_DOWNLOADS, cv, PUBLISHING_WORKER,
                new String[]{taskId, token, destination, staging}) == 1;
    }

    public static final class PublishedDownload {
        public final String token, path, key, identity, name, mime;
        public final long size;

        private PublishedDownload(Cursor row) {
            token = row.getString(0);
            path = row.getString(1);
            key = row.getString(2);
            identity = row.getString(3);
            name = row.getString(4);
            mime = row.getString(5);
            size = row.getLong(6);
        }
    }

    /** A stale UI object or caller-provided filename is never proof of publication. */
    public synchronized PublishedDownload getPublishedDownload(String id) {
        try (Cursor row = getReadableDatabase().query(TABLE_DOWNLOADS,
                new String[]{COL_ATTEMPT_TOKEN, COL_DEST_PATH, COL_DESTINATION_KEY, COL_PUBLISHED_IDENTITY,
                        COL_FILE_NAME, COL_MIME_TYPE, COL_TOTAL_BYTES},
                SHAREABLE_ROW, new String[]{id}, null, null, null)) {
            return row.moveToFirst() ? new PublishedDownload(row) : null;
        }
    }

    @Override
    public synchronized DownloadItem getDownload(String taskId) {
        try (Cursor c = getReadableDatabase().query(TABLE_DOWNLOADS, TASK_COLUMNS,
                COL_ID + "=?", new String[]{taskId}, null, null, null)) {
            return c.moveToFirst() ? cursorToDownloadItem(c) : null;
        }
    }

    /** An explicit diagnostics snapshot; never a history or notification read. */
    public synchronized DownloadItem getDownloadWithSegments(String taskId) {
        DownloadItem item = getDownload(taskId);
        if (item != null) item.setSegments(getSegments(taskId));
        return item;
    }

    public long countDownloads() {
        return DatabaseUtils.queryNumEntries(getReadableDatabase(), TABLE_DOWNLOADS);
    }

    @Override
    public synchronized List<DownloadItem> getDownloadsIn(DownloadStatus... states) {
        String[] args = new String[states.length];
        String[] markers = new String[states.length];
        Arrays.fill(markers, "?");
        for (int i = 0; i < states.length; i++) args[i] = states[i].name();
        List<DownloadItem> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE_DOWNLOADS, QUEUE_COLUMNS,
                COL_STATUS + " IN (" + String.join(",", markers) + ")", args, null, null, DownloadHistory.DATE + " ASC, " + COL_ID)) {
            while (c.moveToNext()) {
                DownloadItem item = new DownloadItem();
                item.setId(c.getString(0));
                item.setStatus(DownloadStatus.valueOf(c.getString(1)));
                item.setAttemptToken(c.getString(2));
                item.setWifiOnly(c.getInt(3) == 1);
                item.setErrorMessage(c.getString(4));
                item.setUrl(c.getString(5));
                items.add(item);
            }
        }
        return items;
    }

    public boolean hasAutomaticDownloads() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM downloads WHERE " + DownloadHistory.AUTOMATIC + " LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    public DownloadHistory.Duplicate findIncomplete(String filename) {
        try (Cursor c = getReadableDatabase().rawQuery(DownloadHistory.DUPLICATE_SQL, new String[]{DownloadHistory.nameKey(filename)})) {
            return c.moveToFirst() ? new DownloadHistory.Duplicate(c.getString(0), c.getLong(1)) : null;
        }
    }

    /** Only call on a reader thread. Cursor windows contain card fields, not URL payloads or checkpoints. */
    public DownloadHistory.Page readPage(DownloadHistory.Query query, DownloadHistory.Row after, CancellationSignal cancel) {
        Trace.beginSection("Downloads.page");
        try {
            DownloadHistory.Sql sql = query.page(after);
            List<DownloadHistory.Row> rows = new ArrayList<>(DownloadHistory.PAGE_SIZE);
            try (Cursor c = getReadableDatabase().rawQuery(sql.text, sql.args, cancel)) {
                while (rows.size() < DownloadHistory.PAGE_SIZE && c.moveToNext()) rows.add(readCard(c));
                return new DownloadHistory.Page(rows, rows.size() == DownloadHistory.PAGE_SIZE && c.moveToNext());
            }
        } finally {
            Trace.endSection();
        }
    }

    public List<DownloadHistory.Row> readChangedRows(DownloadHistory.Query query, List<String> ids, CancellationSignal cancel) {
        if (ids.isEmpty() || ids.size() > DownloadHistory.ID_BATCH) throw new IllegalArgumentException("Expected a bounded set of job IDs");
        Trace.beginSection("Downloads.changedRows");
        try {
            DownloadHistory.Sql sql = query.ids(ids, true);
            List<DownloadHistory.Row> rows = new ArrayList<>(ids.size());
            try (Cursor c = getReadableDatabase().rawQuery(sql.text, sql.args, cancel)) {
                while (c.moveToNext()) rows.add(readCard(c));
            }
            return rows;
        } finally {
            Trace.endSection();
        }
    }

    /** An explicit Select All reads IDs only. A selection moving to another filter intersects by ID. */
    public Set<String> readMatchingIds(DownloadHistory.Query query, Collection<String> subset, CancellationSignal cancel) {
        Set<String> ids = new HashSet<>();
        if (subset == null) readIds(query.ids(null, false), cancel, ids);
        else {
            List<String> batch = new ArrayList<>(DownloadHistory.ID_BATCH);
            for (String id : subset) {
                batch.add(id);
                if (batch.size() == DownloadHistory.ID_BATCH) {
                    readIds(query.ids(batch, false), cancel, ids);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) readIds(query.ids(batch, false), cancel, ids);
        }
        return ids;
    }

    private void readIds(DownloadHistory.Sql sql, CancellationSignal cancel, Set<String> out) {
        try (Cursor c = getReadableDatabase().rawQuery(sql.text, sql.args, cancel)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
    }

    private static DownloadHistory.Row readCard(Cursor c) {
        return new DownloadHistory.Row(c.getString(0), c.getString(1), c.getString(2), DownloadStatus.valueOf(c.getString(3)),
                c.getLong(4), c.getLong(5), c.getInt(6), c.getString(7), c.getLong(8), c.getString(9));
    }

    @Override
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

    /** Only call after the attempt has drained; keep the name claimed until optional cleanup is done. */
    @Override
    public synchronized boolean deleteDownload(DownloadItem expected, boolean deleteFile) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (db.delete(TABLE_DOWNLOADS, currentRow(expected), rowArgs(expected)) != 1) return false;
            db.delete(TABLE_SEGMENTS, COL_SEG_TASK_ID + "=?", new String[]{expected.getId()});
            if (deleteFile) deleteArtifacts(db, expected);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public synchronized int clearCompletedExcept(Set<String> owned) {
        String where = COL_STATUS + "='COMPLETED' AND " + COL_ID + " IS NOT NULL";
        String[] args = owned.toArray(new String[0]);
        if (args.length != 0) where += " AND " + COL_ID + " NOT IN (" + String.join(",", Collections.nCopies(args.length, "?")) + ")";
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_SEGMENTS, COL_SEG_TASK_ID + " IN (SELECT " + COL_ID + " FROM " + TABLE_DOWNLOADS
                    + " WHERE " + where + ")", args);
            int removed = db.delete(TABLE_DOWNLOADS, where, args);
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }

    private static void deleteArtifacts(SQLiteDatabase db, DownloadItem item) {
        if (item.getDestinationPath() == null) return;
        File destination = new File(item.getDestinationPath());
        if (!destination.isAbsolute()) return;
        try {
            String destinationKey = DownloadDestination.key(destination);
            Predicate<String> otherPaths = pathClaims(db, item.getId());
            // This is an explicit user deletion. Do not touch an occupied uncommitted destination.
            if (item.getStatus() == DownloadStatus.COMPLETED && !otherPaths.test(destinationKey)
                    && Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(destination.toPath());
            }
            if (item.getTempPath() != null) {
                File staging = new File(item.getTempPath());
                String name = staging.getName();
                String stageKey = DownloadDestination.key(staging);
                if (staging.isAbsolute() && (name.startsWith("download-") || name.startsWith("stream-")) && name.endsWith(".part")
                        && staging.getParentFile().equals(destination.getParentFile())
                        && !stageKey.equals(destinationKey) && !otherPaths.test(stageKey)
                        && Files.isRegularFile(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(staging.toPath());
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Storage can be absent or read-only. Removing history doesn't guarantee file removal.
        }
    }

    @Override
    public synchronized int recoverInterrupted() {
        ContentValues cv = stateValues(DownloadStatus.PENDING, null);
        cv.putNull(COL_ATTEMPT_TOKEN);
        return getWritableDatabase().update(TABLE_DOWNLOADS, cv, TRANSFERRING, null);
    }

    private DownloadItem cursorToDownloadItem(Cursor c) {
        DownloadItem item = new DownloadItem();
        item.setId(c.getString(c.getColumnIndexOrThrow(COL_ID)));
        item.setAttemptToken(c.getString(c.getColumnIndexOrThrow(COL_ATTEMPT_TOKEN)));
        item.setUrl(c.getString(c.getColumnIndexOrThrow(COL_URL)));
        item.setOriginalUrl(c.getString(c.getColumnIndexOrThrow(COL_ORIGINAL_URL)));
        item.setFileName(c.getString(c.getColumnIndexOrThrow(COL_FILE_NAME)));
        item.setDestinationPath(c.getString(c.getColumnIndexOrThrow(COL_DEST_PATH)));
        item.setTempPath(c.getString(c.getColumnIndexOrThrow(COL_TEMP_PATH)));
        item.setMimeType(c.getString(c.getColumnIndexOrThrow(COL_MIME_TYPE)));
        item.setExpectedSha256(c.getString(c.getColumnIndexOrThrow(COL_EXPECTED_SHA256)));
        item.setTotalBytes(c.getLong(c.getColumnIndexOrThrow(COL_TOTAL_BYTES)));
        item.setDownloadedBytes(c.getLong(c.getColumnIndexOrThrow(COL_DOWNLOADED_BYTES)));
        item.setStatus(DownloadStatus.valueOf(c.getString(c.getColumnIndexOrThrow(COL_STATUS))));
        item.setNumSegments(c.getInt(c.getColumnIndexOrThrow(COL_NUM_SEGMENTS)));
        item.setSupportsRange(c.getInt(c.getColumnIndexOrThrow(COL_SUPPORTS_RANGE)) == 1);
        item.setEtag(c.getString(c.getColumnIndexOrThrow(COL_ETAG)));
        item.setVerifiedRangeUrl(c.getString(c.getColumnIndexOrThrow(COL_VERIFIED_RANGE_URL)));
        item.setFullDownloadOnly(c.getInt(c.getColumnIndexOrThrow(COL_FULL_DOWNLOAD_ONLY)) == 1);
        item.setLastModified(c.getString(c.getColumnIndexOrThrow(COL_LAST_MODIFIED)));
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

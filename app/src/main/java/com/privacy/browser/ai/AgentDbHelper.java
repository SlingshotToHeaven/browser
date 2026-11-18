package com.privacy.browser.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AgentDbHelper extends SQLiteOpenHelper implements AgentSessionManager.Transcript {

    private static final String TAG = "AgentDbHelper";
    private static final String DATABASE_NAME = "agent_chat.db";
    private static final int DATABASE_VERSION = 2;
    private static final int MAX_PROJECTED_ATTACHMENTS = 32_768;
    private static final int MESSAGE_EXCERPT_CHARS = 32_768;
    static final String ADD_IMAGE_PATH = "ALTER TABLE messages ADD COLUMN image_path TEXT";
    static final String DROP_RETIRED_CONTEXT = "DROP TABLE IF EXISTS contexts";
    static final String CLEAR_RETIRED_ARGS = "UPDATE messages SET tool_args = NULL WHERE tool_args IS NOT NULL";

    // Table: sessions
    public static final String TABLE_SESSIONS = "sessions";
    public static final String COL_SESSION_ID = "session_id";
    public static final String COL_TITLE = "title";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_IS_ACTIVE = "is_active";

    // Table: messages
    public static final String TABLE_MESSAGES = "messages";
    public static final String COL_MSG_ID = "id";
    public static final String COL_MSG_SESSION_ID = "session_id";
    public static final String COL_MSG_TYPE = "type";
    public static final String COL_MSG_CONTENT = "content";
    public static final String COL_MSG_TOOL_NAME = "tool_name";
    public static final String COL_MSG_TOOL_ARGS = "tool_args";
    public static final String COL_MSG_IN_PROGRESS = "in_progress";
    public static final String COL_MSG_IS_RESOLVED = "is_resolved";
    public static final String COL_MSG_IMAGE_PATH = "image_path";
    public static final String COL_MSG_CREATED_AT = "created_at";

    private static AgentDbHelper instance;
    private final File filesDir;

    public static synchronized AgentDbHelper getInstance(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            instance = new AgentDbHelper(app, DATABASE_NAME, app.getFilesDir());
        }
        return instance;
    }

    // The instrumentation fixture opens an isolated legacy database and attachment directory.
    AgentDbHelper(Context context, String databaseName, File filesDir) {
        super(context, databaseName, null, DATABASE_VERSION);
        this.filesDir = filesDir;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createSessions = "CREATE TABLE " + TABLE_SESSIONS + " ("
                + COL_SESSION_ID + " TEXT PRIMARY KEY, "
                + COL_TITLE + " TEXT NOT NULL, "
                + COL_CREATED_AT + " INTEGER NOT NULL, "
                + COL_UPDATED_AT + " INTEGER NOT NULL, "
                + COL_IS_ACTIVE + " INTEGER NOT NULL DEFAULT 1"
                + ");";

        String createMessages = "CREATE TABLE " + TABLE_MESSAGES + " ("
                + COL_MSG_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_MSG_SESSION_ID + " TEXT NOT NULL, "
                + COL_MSG_TYPE + " INTEGER NOT NULL, "
                + COL_MSG_CONTENT + " TEXT, "
                + COL_MSG_TOOL_NAME + " TEXT, "
                + COL_MSG_TOOL_ARGS + " TEXT, "
                + COL_MSG_IN_PROGRESS + " INTEGER NOT NULL DEFAULT 0, "
                + COL_MSG_IS_RESOLVED + " INTEGER NOT NULL DEFAULT 0, "
                + COL_MSG_IMAGE_PATH + " TEXT, "
                + COL_MSG_CREATED_AT + " INTEGER NOT NULL"
                + ");";

        String createIndex = "CREATE INDEX idx_msg_session ON " + TABLE_MESSAGES + " (" + COL_MSG_SESSION_ID + ");";

        db.execSQL(createSessions);
        db.execSQL(createMessages);
        db.execSQL(createIndex);
        // Fresh installs do not create the retired provider context table.
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) throw new SQLiteException("Unsupported assistant database upgrade");
        // Some v1 installs created messages before the image_path column existed. Do not
        // drop or rebuild either transcript table; the owner can remove old replay data later.
        try (Cursor columns = db.rawQuery("PRAGMA table_info(messages)", null)) {
            int name = columns.getColumnIndexOrThrow("name");
            while (columns.moveToNext()) {
                if (COL_MSG_IMAGE_PATH.equalsIgnoreCase(columns.getString(name))) return;
            }
        }
        db.execSQL(ADD_IMAGE_PATH);
    }

    public static class SessionRecord {
        public final String sessionId;
        public final String title;
        public final long createdAt;
        public final long updatedAt;
        public final boolean isActive;
        public int messageCount;

        public SessionRecord(String sessionId, String title, long createdAt, long updatedAt, boolean isActive) {
            this.sessionId = sessionId;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.isActive = isActive;
        }
    }

    // -------------------------------------------------------------------------
    // Session Operations
    // -------------------------------------------------------------------------

    @Override public synchronized void createSession(String sessionId, String title) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();

        // Mark all other sessions as inactive
        ContentValues deactiveValues = new ContentValues();
        deactiveValues.put(COL_IS_ACTIVE, 0);
        db.update(TABLE_SESSIONS, deactiveValues, null, null);

        ContentValues values = new ContentValues();
        values.put(COL_SESSION_ID, sessionId);
        values.put(COL_TITLE, title != null && !title.isEmpty() ? title : "New Conversation");
        values.put(COL_CREATED_AT, now);
        values.put(COL_UPDATED_AT, now);
        values.put(COL_IS_ACTIVE, 1);

        db.insertWithOnConflict(TABLE_SESSIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override public synchronized void updateSessionTitle(String sessionId, String title) {
        if (sessionId == null || title == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TITLE, title);
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_SESSIONS, values, COL_SESSION_ID + " = ?", new String[]{sessionId});
    }

    @Override public synchronized void setActiveSession(String sessionId) {
        if (sessionId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues deactive = new ContentValues();
        deactive.put(COL_IS_ACTIVE, 0);
        db.update(TABLE_SESSIONS, deactive, null, null);

        ContentValues active = new ContentValues();
        active.put(COL_IS_ACTIVE, 1);
        active.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_SESSIONS, active, COL_SESSION_ID + " = ?", new String[]{sessionId});
    }

    @Override public synchronized String getActiveSessionId() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_SESSIONS, new String[]{COL_SESSION_ID},
                    COL_IS_ACTIVE + " = 1", null, null, null, COL_UPDATED_AT + " DESC", "1");
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }

            // Fallback to most recently updated session
            if (cursor != null) cursor.close();
            cursor = db.query(TABLE_SESSIONS, new String[]{COL_SESSION_ID},
                    null, null, null, null, COL_UPDATED_AT + " DESC", "1");
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching active session ID", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public synchronized List<SessionRecord> getAllSessions() {
        List<SessionRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_SESSIONS, null, null, null, null, null, COL_UPDATED_AT + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                int idIdx = cursor.getColumnIndexOrThrow(COL_SESSION_ID);
                int titleIdx = cursor.getColumnIndexOrThrow(COL_TITLE);
                int createdIdx = cursor.getColumnIndexOrThrow(COL_CREATED_AT);
                int updatedIdx = cursor.getColumnIndexOrThrow(COL_UPDATED_AT);
                int activeIdx = cursor.getColumnIndexOrThrow(COL_IS_ACTIVE);

                do {
                    String sId = cursor.getString(idIdx);
                    String title = cursor.getString(titleIdx);
                    long created = cursor.getLong(createdIdx);
                    long updated = cursor.getLong(updatedIdx);
                    boolean isActive = cursor.getInt(activeIdx) == 1;

                    SessionRecord rec = new SessionRecord(sId, title, created, updated, isActive);
                    rec.messageCount = getMessageCount(sId);
                    list.add(rec);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying all sessions", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public synchronized int getMessageCount(String sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_MESSAGES + " WHERE " + COL_MSG_SESSION_ID + " = ?", new String[]{sessionId});
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    @Override public void deleteSession(String sessionId) {
        if (sessionId == null) return;
        String[] args = {sessionId};
        Set<String> candidates;
        synchronized (this) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                candidates = attachmentNames(db, sessionId);
                db.delete(TABLE_MESSAGES, COL_MSG_SESSION_ID + " = ?", args);
                try (Cursor row = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'contexts'", null)) {
                    if (row.moveToFirst()) db.delete("contexts", COL_SESSION_ID + " = ?", args);
                }
                db.delete(TABLE_SESSIONS, COL_SESSION_ID + " = ?", args);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        // No file disappears before the corresponding row deletion commits. Other chats
        // can reference the same file under a different spelling of the private path.
        if (candidates != null && !candidates.isEmpty()) AgentAttachmentManager.pruneDeletedSession(this, candidates);
    }

    @Override public synchronized String getSessionTitle(String sessionId) {
        try (Cursor row = getReadableDatabase().query(TABLE_SESSIONS, new String[]{COL_TITLE},
                COL_SESSION_ID + " = ?", new String[]{sessionId}, null, null, null, "1")) {
            return row.moveToFirst() ? row.getString(0) : null;
        }
    }

    static final class LegacyCleanup {
        final boolean replayCleared;
        final AgentLegacyFiles.Sweep files;

        LegacyCleanup(boolean replayCleared, AgentLegacyFiles.Sweep files) {
            this.replayCleared = replayCleared;
            this.files = files;
        }
    }

    /** Explicit local maintenance, called off the UI thread. Never reads old JSON or image bytes. */
    LegacyCleanup clearLegacyData() {
        boolean replayCleared = false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL(DROP_RETIRED_CONTEXT);
                db.execSQL(CLEAR_RETIRED_ARGS);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            replayCleared = true;
            return new LegacyCleanup(true, pruneUnusedImages(null));
        } catch (RuntimeException unavailable) {
            // Acknowledge only operations that completed, without logging stored data or paths.
            return new LegacyCleanup(replayCleared, new AgentLegacyFiles.Sweep(0, false));
        }
    }

    AgentLegacyFiles.Sweep pruneUnusedImages(Set<String> candidates) {
        // New messages never add attachment references, so a complete snapshot is conservative
        // even if a chat is deleted after this read. No file work is inside a DB transaction.
        return AgentLegacyFiles.removeUnused(filesDir, attachmentNames(getReadableDatabase(), null), candidates);
    }

    private static Set<String> attachmentNames(SQLiteDatabase db, String sessionId) {
        // Project at most a filename per row, even for old/corrupt multi-megabyte values.
        String sql = "SELECT DISTINCT substr(rtrim(image_path, '/'), -" + AgentLegacyFiles.MAX_NAME_LENGTH
                + ") FROM messages WHERE image_path IS NOT NULL"
                + (sessionId == null ? "" : " AND session_id = ?") + " LIMIT " + (MAX_PROJECTED_ATTACHMENTS + 1);
        Set<String> names = new HashSet<>();
        try (Cursor rows = db.rawQuery(sql, sessionId == null ? null : new String[]{sessionId})) {
            int count = 0;
            while (rows.moveToNext()) {
                if (++count > MAX_PROJECTED_ATTACHMENTS) return null;
                String name = AgentLegacyFiles.referenceName(rows.getString(0));
                if (name != null) names.add(name);
            }
        }
        return names;
    }

    // -------------------------------------------------------------------------
    // Message Operations
    // -------------------------------------------------------------------------

    @Override public synchronized void insertMessage(String sessionId, AiChatAdapter.ChatMessage message) {
        if (sessionId == null || message == null) return;
        // Do not persist transient thinking state
        if (message.type == AiChatAdapter.TYPE_THINKING) return;

        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();

        ContentValues values = new ContentValues();
        values.put(COL_MSG_SESSION_ID, sessionId);
        values.put(COL_MSG_TYPE, message.type);
        values.put(COL_MSG_CONTENT, message.content);
        values.put(COL_MSG_TOOL_NAME, message.toolName);
        values.put(COL_MSG_IN_PROGRESS, message.inProgress ? 1 : 0);
        values.put(COL_MSG_IS_RESOLVED, 1); // legacy column; saved messages never authorize an action
        // Existing paths are read-only legacy references. No new attachment can be saved.
        values.put(COL_MSG_CREATED_AT, now);

        db.insert(TABLE_MESSAGES, null, values);

        // Update session timestamp
        ContentValues sVal = new ContentValues();
        sVal.put(COL_UPDATED_AT, now);
        db.update(TABLE_SESSIONS, sVal, COL_SESSION_ID + " = ?", new String[]{sessionId});
    }

    @Override public synchronized List<AiChatAdapter.ChatMessage> getMessages(String sessionId) {
        List<AiChatAdapter.ChatMessage> list = new ArrayList<>();
        if (sessionId == null) return list;

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            // Avoid reading an oversized legacy row into CursorWindow. Stored text is unchanged.
            String[] projection = {COL_MSG_TYPE, "substr(content, 1, " + (MESSAGE_EXCERPT_CHARS + 1) + ") AS content",
                    "substr(tool_name, 1, 64) AS tool_name", COL_MSG_IN_PROGRESS,
                    "CASE WHEN length(image_path) <= 1024 THEN image_path ELSE NULL END AS image_path"};
            cursor = db.query(TABLE_MESSAGES, projection, COL_MSG_SESSION_ID + " = ?",
                    new String[]{sessionId}, null, null, COL_MSG_ID + " ASC");
            if (cursor != null && cursor.moveToFirst()) {
                int typeIdx = cursor.getColumnIndexOrThrow(COL_MSG_TYPE);
                int contentIdx = cursor.getColumnIndexOrThrow(COL_MSG_CONTENT);
                int toolNameIdx = cursor.getColumnIndexOrThrow(COL_MSG_TOOL_NAME);
                int inProgressIdx = cursor.getColumnIndexOrThrow(COL_MSG_IN_PROGRESS);
                int imagePathIdx = cursor.getColumnIndex(COL_MSG_IMAGE_PATH);

                do {
                    int type = cursor.getInt(typeIdx);
                    String content = cursor.getString(contentIdx);
                    if (content != null && content.codePointCount(0, content.length()) > MESSAGE_EXCERPT_CHARS) {
                        content = content.substring(0, content.offsetByCodePoints(0, MESSAGE_EXCERPT_CHARS)) + "\n[Message excerpt]";
                    }
                    String toolName = cursor.getString(toolNameIdx);
                    boolean inProgress = cursor.getInt(inProgressIdx) == 1;
                    if (inProgress) content = (content == null ? "" : content + " — ") + "Interrupted; outcome unknown";
                    String imagePath = imagePathIdx != -1 ? cursor.getString(imagePathIdx) : null;

                    AiChatAdapter.ChatMessage msg = new AiChatAdapter.ChatMessage(type, content, toolName, false, imagePath);
                    list.add(msg);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not read assistant transcript: " + e.getClass().getSimpleName());
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }
}

package com.privacy.browser.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AgentDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "AgentDbHelper";
    private static final String DATABASE_NAME = "agent_chat.db";
    private static final int DATABASE_VERSION = 1;

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

    // Table: contexts
    public static final String TABLE_CONTEXTS = "contexts";
    public static final String COL_CTX_SESSION_ID = "session_id";
    public static final String COL_CTX_RAW_JSON = "raw_contents_json";
    public static final String COL_CTX_PENDING_MODEL = "pending_model_content";

    private static AgentDbHelper instance;

    public static synchronized AgentDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new AgentDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private AgentDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
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

        String createContexts = "CREATE TABLE " + TABLE_CONTEXTS + " ("
                + COL_CTX_SESSION_ID + " TEXT PRIMARY KEY, "
                + COL_CTX_RAW_JSON + " TEXT NOT NULL, "
                + COL_CTX_PENDING_MODEL + " TEXT"
                + ");";

        db.execSQL(createSessions);
        db.execSQL(createMessages);
        db.execSQL(createIndex);
        db.execSQL(createContexts);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Ensure backwards compatibility for existing tables
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + TABLE_MESSAGES + ")", null);
            boolean hasImagePath = false;
            if (cursor != null) {
                int nameIdx = cursor.getColumnIndex("name");
                while (cursor.moveToNext()) {
                    if (COL_MSG_IMAGE_PATH.equalsIgnoreCase(cursor.getString(nameIdx))) {
                        hasImagePath = true;
                        break;
                    }
                }
            }
            if (!hasImagePath) {
                db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_MSG_IMAGE_PATH + " TEXT;");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to check or alter image_path column", e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONTEXTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SESSIONS);
        onCreate(db);
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

    public synchronized void createSession(String sessionId, String title) {
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

    public synchronized void updateSessionTitle(String sessionId, String title) {
        if (sessionId == null || title == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TITLE, title);
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_SESSIONS, values, COL_SESSION_ID + " = ?", new String[]{sessionId});
    }

    public synchronized void setActiveSession(String sessionId) {
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

    public synchronized String getActiveSessionId() {
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

    public synchronized Set<String> getAllReferencedAttachmentPaths() {
        Set<String> set = new HashSet<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_MESSAGES, new String[]{COL_MSG_IMAGE_PATH},
                    COL_MSG_IMAGE_PATH + " IS NOT NULL", null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String path = cursor.getString(0);
                    if (path != null && !path.isEmpty()) {
                        set.add(path);
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying referenced image paths", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return set;
    }

    public synchronized void deleteSession(String sessionId) {
        if (sessionId == null) return;
        SQLiteDatabase db = getWritableDatabase();

        // Delete associated image files from disk
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_MESSAGES, new String[]{COL_MSG_IMAGE_PATH},
                    COL_MSG_SESSION_ID + " = ? AND " + COL_MSG_IMAGE_PATH + " IS NOT NULL",
                    new String[]{sessionId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String path = cursor.getString(0);
                    AgentAttachmentManager.deleteAttachment(path);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning image attachments for session: " + sessionId, e);
        } finally {
            if (cursor != null) cursor.close();
        }

        db.delete(TABLE_MESSAGES, COL_MSG_SESSION_ID + " = ?", new String[]{sessionId});
        db.delete(TABLE_CONTEXTS, COL_CTX_SESSION_ID + " = ?", new String[]{sessionId});
        db.delete(TABLE_SESSIONS, COL_SESSION_ID + " = ?", new String[]{sessionId});
    }

    // -------------------------------------------------------------------------
    // Message Operations
    // -------------------------------------------------------------------------

    public synchronized void insertMessage(String sessionId, AiChatAdapter.ChatMessage message) {
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
        values.put(COL_MSG_TOOL_ARGS, message.toolArgs != null ? message.toolArgs.toString() : null);
        values.put(COL_MSG_IN_PROGRESS, message.inProgress ? 1 : 0);
        values.put(COL_MSG_IS_RESOLVED, message.isResolved ? 1 : 0);
        values.put(COL_MSG_IMAGE_PATH, message.imagePath);
        values.put(COL_MSG_CREATED_AT, now);

        db.insert(TABLE_MESSAGES, null, values);

        // Update session timestamp
        ContentValues sVal = new ContentValues();
        sVal.put(COL_UPDATED_AT, now);
        db.update(TABLE_SESSIONS, sVal, COL_SESSION_ID + " = ?", new String[]{sessionId});
    }

    public synchronized void updateLastToolMessage(String sessionId, String summaryText) {
        if (sessionId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_MESSAGES, new String[]{COL_MSG_ID},
                    COL_MSG_SESSION_ID + " = ? AND " + COL_MSG_TYPE + " = ?",
                    new String[]{sessionId, String.valueOf(AiChatAdapter.TYPE_TOOL)},
                    null, null, COL_MSG_ID + " DESC", "1");
            if (cursor != null && cursor.moveToFirst()) {
                long lastId = cursor.getLong(0);
                ContentValues v = new ContentValues();
                v.put(COL_MSG_CONTENT, summaryText);
                v.put(COL_MSG_IN_PROGRESS, 0);
                db.update(TABLE_MESSAGES, v, COL_MSG_ID + " = ?", new String[]{String.valueOf(lastId)});
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating last tool message in DB", e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public synchronized List<AiChatAdapter.ChatMessage> getMessages(String sessionId) {
        List<AiChatAdapter.ChatMessage> list = new ArrayList<>();
        if (sessionId == null) return list;

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_MESSAGES, null, COL_MSG_SESSION_ID + " = ?",
                    new String[]{sessionId}, null, null, COL_MSG_ID + " ASC");
            if (cursor != null && cursor.moveToFirst()) {
                int typeIdx = cursor.getColumnIndexOrThrow(COL_MSG_TYPE);
                int contentIdx = cursor.getColumnIndexOrThrow(COL_MSG_CONTENT);
                int toolNameIdx = cursor.getColumnIndexOrThrow(COL_MSG_TOOL_NAME);
                int toolArgsIdx = cursor.getColumnIndexOrThrow(COL_MSG_TOOL_ARGS);
                int inProgressIdx = cursor.getColumnIndexOrThrow(COL_MSG_IN_PROGRESS);
                int resolvedIdx = cursor.getColumnIndexOrThrow(COL_MSG_IS_RESOLVED);
                int imagePathIdx = cursor.getColumnIndex(COL_MSG_IMAGE_PATH);

                do {
                    int type = cursor.getInt(typeIdx);
                    String content = cursor.getString(contentIdx);
                    String toolName = cursor.getString(toolNameIdx);
                    String toolArgsStr = cursor.getString(toolArgsIdx);
                    boolean inProgress = cursor.getInt(inProgressIdx) == 1;
                    boolean isResolved = cursor.getInt(resolvedIdx) == 1;
                    String imagePath = imagePathIdx != -1 ? cursor.getString(imagePathIdx) : null;

                    JSONObject toolArgs = null;
                    if (toolArgsStr != null && !toolArgsStr.isEmpty()) {
                        try {
                            toolArgs = new JSONObject(toolArgsStr);
                        } catch (Exception ignored) {
                        }
                    }

                    AiChatAdapter.ChatMessage msg = new AiChatAdapter.ChatMessage(type, content, toolName, toolArgs, inProgress, imagePath);
                    msg.isResolved = isResolved;
                    list.add(msg);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching messages for session", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Context Operations (Raw Gemini conversationContents)
    // -------------------------------------------------------------------------

    public synchronized void saveContext(String sessionId, JSONArray conversationContents, JSONObject pendingModelContent) {
        if (sessionId == null || conversationContents == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CTX_SESSION_ID, sessionId);
        values.put(COL_CTX_RAW_JSON, conversationContents.toString());
        values.put(COL_CTX_PENDING_MODEL, pendingModelContent != null ? pendingModelContent.toString() : null);

        db.insertWithOnConflict(TABLE_CONTEXTS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized JSONArray getContext(String sessionId) {
        if (sessionId == null) return new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_CONTEXTS, new String[]{COL_CTX_RAW_JSON},
                    COL_CTX_SESSION_ID + " = ?", new String[]{sessionId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String rawJson = cursor.getString(0);
                if (rawJson != null && !rawJson.isEmpty()) {
                    return new JSONArray(rawJson);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading conversation context", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return new JSONArray();
    }

    public synchronized JSONObject getPendingModelContent(String sessionId) {
        if (sessionId == null) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_CONTEXTS, new String[]{COL_CTX_PENDING_MODEL},
                    COL_CTX_SESSION_ID + " = ?", new String[]{sessionId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String raw = cursor.getString(0);
                if (raw != null && !raw.isEmpty()) {
                    return new JSONObject(raw);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading pending model content", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }
}

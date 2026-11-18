package com.privacy.browser.ai;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real v1-to-v2 SQLiteOpenHelper upgrade and cleanup, in a new DB and private fixture directory. */
public class AssistantLegacyDataDeviceTest {
    private static final String KEEP = "img_12345678-1234-1234-1234-000000000001.webp";
    private static final String ORPHAN = "img_12345678-1234-1234-1234-000000000002.jpg";
    private static final String DELETED_CHAT = "img_12345678-1234-1234-1234-000000000003.png";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String name = "assistant-legacy-fixture-" + UUID.randomUUID() + ".db";
    private Path root;
    private AgentDbHelper helper;

    @After public void cleanFixture() {
        if (helper != null) helper.close();
        context.deleteDatabase(name);
        if (root != null) deleteFixtureTree(root.toFile());
    }

    @Test public void upgradeKeepsOldMessagesAndNeverReplaysOrAutoDeletesLegacyData() throws Exception {
        seed(true);
        Path attached = fixtureFile(KEEP);
        Path orphan = fixtureFile(ORPHAN);
        Path other = fixtureFile("keep.txt");
        open();
        SQLiteDatabase db = helper.getWritableDatabase();
        assertEquals(2, db.getVersion());
        List<AiChatAdapter.ChatMessage> messages = helper.getMessages("original");
        assertEquals(2, messages.size());
        assertEquals("Old conversation", helper.getSessionTitle("original"));
        assertEquals("Original message", messages.get(0).content);
        assertEquals(attached.toString(), messages.get(0).imagePath);
        assertFalse(messages.get(0).inProgress);
        assertTrue(messages.get(1).content.endsWith("[Message excerpt]"));
        assertTrue("Disk text is not truncated", scalar(db, "SELECT length(content) FROM messages WHERE id = 2") > 2_000_000);
        assertEquals(1, scalar(db, "SELECT count(*) FROM contexts"));
        assertTrue(Files.exists(orphan));

        AgentDbHelper.LegacyCleanup cleanup = helper.clearLegacyData();
        assertTrue(cleanup.replayCleared);
        assertTrue(cleanup.files.complete);
        assertEquals(1, cleanup.files.removed);
        assertEquals(0, scalar(db, "SELECT count(*) FROM sqlite_master WHERE name = 'contexts'"));
        assertEquals(0, scalar(db, "SELECT count(*) FROM messages WHERE tool_args IS NOT NULL"));
        assertEquals(2, helper.getMessages("original").size());
        assertTrue(Files.exists(attached));
        assertTrue(Files.exists(other));
        assertFalse(Files.exists(orphan));
        helper.close();
        open();
        assertEquals("Original message", helper.getMessages("original").get(0).content);
        assertEquals(0, helper.clearLegacyData().files.removed);
    }

    @Test public void supportedReferencedPreviewStillDecodesFromPrivateStorageAfterCleanup() throws Exception {
        seed(true);
        Path attached = root.resolve("agent_images").resolve(KEEP);
        Bitmap image = Bitmap.createBitmap(1024, 640, Bitmap.Config.ARGB_8888);
        try (OutputStream file = Files.newOutputStream(attached)) {
            image.eraseColor(0xff2060a0);
            assertTrue(image.compress(Bitmap.CompressFormat.WEBP, 90, file));
        } finally { image.recycle(); }
        Path oversized = fixtureFile(ORPHAN);
        try (RandomAccessFile file = new RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(4 * 1024 * 1024 + 1);
        }
        assertNull(preview(oversized));
        open();
        assertTrue(helper.clearLegacyData().files.complete);
        assertFalse(Files.exists(oversized));
        assertEquals(attached.toString(), helper.getMessages("original").get(0).imagePath);
        Bitmap decoded = preview(attached);
        assertNotNull("Referenced preview must stay locally readable", decoded);
        try {
            assertTrue(decoded.getWidth() > 0 && decoded.getWidth() <= 480);
            assertTrue(decoded.getHeight() > 0 && decoded.getHeight() <= 320);
        } finally { decoded.recycle(); }
    }

    @Test public void newInstallNeverCreatesProviderReplayTable() throws Exception {
        root = Files.createTempDirectory(context.getCacheDir().toPath(), "agent-legacy-");
        open();
        SQLiteDatabase db = helper.getWritableDatabase();
        assertEquals(2, db.getVersion());
        assertEquals(0, scalar(db, "SELECT count(*) FROM sqlite_master WHERE name = 'contexts'"));
        assertTrue(helper.clearLegacyData().files.complete);
        assertFalse(Files.exists(root.resolve("agent_images")));
    }

    @Test public void missingLegacyImageColumnUpgradesWithoutRebuildingTheTranscript() throws Exception {
        seed(false);
        open();
        assertEquals(2, helper.getWritableDatabase().getVersion());
        assertEquals("Original message", helper.getMessages("original").get(0).content);
        assertNull(helper.getMessages("original").get(0).imagePath);
        helper.insertMessage("original", new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_USER, "New message", null, false,
                root.resolve("agent_images").resolve(KEEP).toString()));
        List<AiChatAdapter.ChatMessage> rows = helper.getMessages("original");
        assertEquals(3, rows.size());
        assertEquals("New message", rows.get(2).content);
        assertNull("New messages do not retain attachments", rows.get(2).imagePath);
    }

    @Test public void failedSqlCleanupRollsBackAndDoesNotTouchFiles() throws Exception {
        seed(true);
        Path orphan = fixtureFile(ORPHAN);
        open();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.execSQL("CREATE TRIGGER fixture_fail BEFORE UPDATE OF tool_args ON messages "
                + "BEGIN SELECT RAISE(ABORT, 'fixture write refusal'); END");
        AgentDbHelper.LegacyCleanup rejected = helper.clearLegacyData();
        assertFalse(rejected.replayCleared);
        assertFalse(rejected.files.complete);
        assertEquals(1, scalar(db, "SELECT count(*) FROM contexts"));
        assertEquals(1, scalar(db, "SELECT count(*) FROM messages WHERE tool_args IS NOT NULL"));
        assertTrue(Files.isRegularFile(orphan));
        db.execSQL("DROP TRIGGER fixture_fail");
        assertTrue(helper.clearLegacyData().replayCleared);
        assertFalse(Files.exists(orphan));
    }

    @Test public void deletingChatOnlyPrunesItsUnusedFilesAfterCommit() throws Exception {
        seed(true);
        Path shared = fixtureFile(KEEP);
        Path chatOnly = fixtureFile(DELETED_CHAT);
        Path unrelated = fixtureFile(ORPHAN);
        open();
        SQLiteDatabase db = helper.getWritableDatabase();
        helper.createSession("delete-me", "Discarded fixture chat");
        addMessage(db, "delete-me", "A shared legacy attachment", "/old-spelling/agent_images/" + KEEP, true);
        addMessage(db, "delete-me", "A unique attachment", chatOnly.toString(), true);
        db.execSQL("CREATE TRIGGER fixture_fail BEFORE DELETE ON sessions "
                + "BEGIN SELECT RAISE(ABORT, 'fixture delete refusal'); END");
        assertThrows(SQLiteException.class, () -> helper.deleteSession("delete-me"));
        assertEquals(2, helper.getMessages("delete-me").size());
        assertTrue(Files.isRegularFile(chatOnly));
        db.execSQL("DROP TRIGGER fixture_fail");
        helper.deleteSession("delete-me");
        assertTrue(helper.getMessages("delete-me").isEmpty());
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (Files.exists(chatOnly) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30);
        assertFalse("Expected committed orphan deletion", Files.exists(chatOnly));
        assertTrue(Files.exists(shared));
        assertTrue(Files.exists(unrelated));
    }

    private Bitmap preview(Path file) throws InterruptedException {
        Context isolated = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root.toFile(); }
        };
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AgentAttachmentManager.preview(isolated, file.toString(), result -> {
            bitmap.set(result);
            received.countDown();
        }));
        assertTrue("No callback from local image preview", received.await(5, TimeUnit.SECONDS));
        return bitmap.get();
    }

    private void seed(boolean withImagePath) throws Exception {
        root = Files.createTempDirectory(context.getCacheDir().toPath(), "agent-legacy-");
        Files.createDirectory(root.resolve("agent_images"));
        File file = context.getDatabasePath(name);
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE sessions (session_id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL, is_active INTEGER NOT NULL DEFAULT 1)");
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, type INTEGER NOT NULL, "
                    + "content TEXT, tool_name TEXT, tool_args TEXT, in_progress INTEGER NOT NULL DEFAULT 0, "
                    + "is_resolved INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL" + (withImagePath ? ", image_path TEXT" : "") + ")");
            db.execSQL("CREATE INDEX idx_msg_session ON messages(session_id)");
            db.execSQL("CREATE TABLE contexts (session_id TEXT PRIMARY KEY, raw_contents_json TEXT NOT NULL, pending_model_content TEXT)");
            db.execSQL("INSERT INTO sessions VALUES ('original', 'Old conversation', 1, 1, 1)");
            addMessage(db, "original", "Original message", root.resolve("agent_images").resolve(KEEP).toString(), withImagePath);
            addMessage(db, "original", "Long old message", null, withImagePath);
            db.execSQL("UPDATE messages SET tool_args = '{}' WHERE id = 1");
            db.execSQL("UPDATE messages SET content = hex(zeroblob(1100000)) WHERE id = 2");
            db.execSQL("INSERT INTO contexts VALUES ('original', hex(zeroblob(1100000)), 'inert old pending call')");
            db.setVersion(1);
        }
    }

    private void open() { helper = new AgentDbHelper(context, name, root.toFile()); }
    private Path fixtureFile(String filename) throws Exception { return Files.write(root.resolve("agent_images").resolve(filename), new byte[]{42}); }

    private static void addMessage(SQLiteDatabase db, String id, String content, String imagePath, boolean withImagePath) {
        ContentValues row = new ContentValues();
        row.put("session_id", id);
        row.put("type", AiChatAdapter.TYPE_USER);
        row.put("content", content);
        row.put("created_at", 1);
        if (withImagePath && imagePath != null) row.put("image_path", imagePath);
        db.insertOrThrow("messages", null, row);
    }

    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private static void deleteFixtureTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixtureTree(child);
        file.delete();
    }
}

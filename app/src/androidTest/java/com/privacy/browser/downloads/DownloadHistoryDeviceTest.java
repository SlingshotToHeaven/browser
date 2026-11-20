package com.privacy.browser.downloads;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.downloader.R;
import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.db.DownloadHistory;
import com.privacy.downloader.db.DownloadHistory.Filter;
import com.privacy.downloader.db.DownloadHistory.Page;
import com.privacy.downloader.db.DownloadHistory.Query;
import com.privacy.downloader.db.DownloadHistory.Row;
import com.privacy.downloader.db.DownloadHistory.Sort;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.ui.DownloadsActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real SQLiteOpenHelper SQL and RecyclerView delivery; run only in a disposable Android debug profile. */
public class DownloadHistoryDeviceTest {
    private final String prefix = "perf20-" + UUID.randomUUID() + "-";
    private Instrumentation instrumentation;
    private Context context;
    private DownloadDbHelper db;
    private DownloadsActivity activity;

    @Before public void setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        db = DownloadDbHelper.getInstance(context);
    }

    @After public void cleanup() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        db.getWritableDatabase().delete("downloads", "substr(id,1,?)=?", new String[]{Integer.toString(prefix.length()), prefix});
    }

    @Test public void realSqlPagesAcrossTiesAndChangedRowsStayFiltered() {
        seed(117);
        Set<String> expected = new HashSet<>();
        for (int n = 0; n < 117; n++) expected.add(prefix + n);
        for (Sort sort : Sort.values()) {
            Query query = new Query(Filter.ARCHIVES, sort, prefix.toUpperCase(Locale.US) + "RÉ%_");
            Set<String> found = new HashSet<>();
            Row after = null;
            for (int n = 0; n < 3; n++) {
                Page page = db.readPage(query, after, new CancellationSignal());
                assertEquals(n < 2 ? DownloadHistory.PAGE_SIZE : 21, page.rows.size());
                for (Row row : page.rows) {
                    if (after != null) assertTrue(sort.compare(after, row) < 0);
                    assertTrue(found.add(row.id));
                    after = row;
                }
                assertEquals(n < 2, page.more);
            }
            assertEquals(expected, found);
            assertEquals(expected, db.readMatchingIds(query, null, null));
        }
        Query complete = new Query(Filter.COMPLETED, Sort.NAME_DESC, prefix);
        List<Row> changed = db.readChangedRows(complete, List.of(prefix + "0", prefix + "missing"), null);
        assertEquals(1, changed.size());
        assertEquals(prefix + "0", changed.get(0).id);
        assertTrue(db.readChangedRows(new Query(Filter.ACTIVE, Sort.NEWEST, prefix), List.of(prefix + "0"), null).isEmpty());
    }

    @Test public void v7UpgradeBackfillsUnicodeWithoutTouchingPublicationFields() {
        try (SQLiteDatabase legacy = SQLiteDatabase.create(null)) {
            legacy.execSQL("CREATE TABLE downloads(id TEXT PRIMARY KEY, url TEXT NOT NULL, file_name TEXT, "
                    + "status TEXT NOT NULL, created_time INTEGER, total_bytes INTEGER, downloaded_bytes INTEGER, "
                    + "published_identity TEXT, attempt_token TEXT, destination_key TEXT, dest_path TEXT, temp_path TEXT)");
            legacy.execSQL("INSERT INTO downloads VALUES('one','fixture','RÉ%_Σ.ZIP','COMPLETED',5,8,8,'identity','token','key','/unused',NULL)");
            legacy.execSQL("INSERT INTO downloads VALUES('two','fixture','İ.ZIP','PAUSED',3,8,4,NULL,'other',NULL,'/legacy','/staged')");
            db.onUpgrade(legacy, 7, 8);
            try (Cursor rows = legacy.rawQuery("SELECT name_search,name_order,published_identity,attempt_token,destination_key,temp_path "
                    + "FROM downloads ORDER BY id", null)) {
                assertTrue(rows.moveToNext());
                assertEquals("ré%_σ.zip", rows.getString(0));
                assertEquals("ré%_σ.zip", rows.getString(1));
                assertEquals("identity", rows.getString(2));
                assertEquals("token", rows.getString(3));
                assertEquals("key", rows.getString(4));
                assertTrue(rows.moveToNext());
                assertEquals("i\u0307.zip", rows.getString(0));
                assertEquals("i.zip", rows.getString(1));
                assertEquals("/staged", rows.getString(5));
            }
        }
    }

    @Test public void scrollAppendsThenDeletionChangesOnlyTheMatchingResult() {
        seed(57);
        activity = (DownloadsActivity) instrumentation.startActivitySync(new Intent(context, DownloadsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        RecyclerView list = activity.findViewById(R.id.list_downloads);
        EditText search = activity.findViewById(R.id.edit_search);
        instrumentation.runOnMainSync(() -> {
            search.setText("no match");
            search.setText(prefix + "RÉ%_"); // An older request must not win this replacement.
        });
        awaitCount(list, 48);
        instrumentation.getUiAutomation();
        await(() -> onMain(() -> list.getChildAt(0) != null), "First card was never laid out");
        instrumentation.runOnMainSync(() -> assertTrue(list.getChildAt(0).performLongClick()));
        await(() -> {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            for (AccessibilityNodeInfo select : root.findAccessibilityNodeInfosByText("Select")) {
                if (!"Select".contentEquals(select.getText() == null ? "" : select.getText())) continue;
                AccessibilityNodeInfo parent = select.getParent();
                if (parent != null && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            return false;
        }, "Could not enter selection mode");
        TextView selected = activity.findViewById(R.id.downloads_text_selected_count);
        await(() -> onMain(() -> "1 selected".contentEquals(selected.getText())), "First item was not selected");
        instrumentation.runOnMainSync(() -> assertTrue(activity.findViewById(R.id.downloads_btn_select_all).performClick()));
        await(() -> onMain(() -> "57 selected".contentEquals(selected.getText())), "Select All omitted unloaded rows");
        awaitCount(list, 48); // The selected IDs extend beyond the loaded cards.
        instrumentation.runOnMainSync(() -> activity.findViewById(R.id.downloads_btn_close_selection).performClick());
        instrumentation.runOnMainSync(() -> list.scrollToPosition(47));
        awaitCount(list, 57);
        DownloadEngine.getInstance(context).delete(prefix + "1", false);
        awaitCount(list, 56);
        instrumentation.runOnMainSync(() -> search.setText(prefix + "absent"));
        awaitCount(list, 0);
    }

    private boolean onMain(java.util.function.BooleanSupplier predicate) {
        java.util.concurrent.atomic.AtomicBoolean value = new java.util.concurrent.atomic.AtomicBoolean();
        instrumentation.runOnMainSync(() -> value.set(predicate.getAsBoolean()));
        return value.get();
    }

    private void await(java.util.function.BooleanSupplier predicate, String error) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            if (predicate.getAsBoolean()) return;
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(error);
    }

    private void awaitCount(RecyclerView list, int expected) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        AtomicInteger count = new AtomicInteger();
        do {
            instrumentation.runOnMainSync(() -> count.set(list.getAdapter().getItemCount()));
            if (count.get() == expected) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(expected, count.get());
    }

    private void seed(int count) {
        SQLiteDatabase store = db.getWritableDatabase();
        store.beginTransaction();
        try {
            for (int n = 0; n < count; n++) {
                String name = prefix + "RÉ%_" + n % 11 + ".ZIP";
                String lower = name.toLowerCase(Locale.US);
                store.execSQL("INSERT INTO downloads (id,url,status,file_name,name_search,name_order,total_bytes,downloaded_bytes,created_time) "
                        + "VALUES(?,'fixture','COMPLETED',?,?,?,?,?,?)", new Object[]{prefix + n, name, lower, lower, n % 7, n % 7, n % 5});
            }
            store.setTransactionSuccessful();
        } finally {
            store.endTransaction();
        }
    }
}

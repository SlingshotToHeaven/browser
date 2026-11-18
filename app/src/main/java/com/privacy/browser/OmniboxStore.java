package com.privacy.browser;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Trace;
import android.util.Log;

import com.privacy.browser.bookmarks.BookmarkDbHelper;
import com.privacy.browser.history.HistoryDbHelper;
import com.privacy.browser.search.OmniboxSuggestion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Worker-only suggestion queries. The full history/bookmark searches keep their own semantics. */
final class OmniboxStore {
    private static final int WINDOW_ROWS = 1024;
    private static final String MATCH = " AND length(url) BETWEEN 1 AND 8192"
            + " AND (title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\')";
    // LIMIT inside the ordered subquery caps candidates *before* matching/sorting. Keep the
    // history index and bookmark rowid order usable even when no row can match.
    private static final String HISTORY_SQL = "SELECT substr(title,1,512), url FROM ("
            + "SELECT id, title, url, visited_at FROM history ORDER BY visited_at DESC, id LIMIT " + WINDOW_ROWS
            + ") WHERE 1=1" + MATCH + " ORDER BY visited_at DESC, id LIMIT 3";
    private static final String BOOKMARK_SQL = "SELECT substr(title,1,512), url FROM ("
            + "SELECT id, title, url, is_folder, created_at FROM bookmarks ORDER BY id DESC LIMIT " + WINDOW_ROWS
            + ") WHERE is_folder = 0" + MATCH + " ORDER BY created_at DESC, id LIMIT 3";

    private final BookmarkDbHelper bookmarks;
    private final HistoryDbHelper history;

    OmniboxStore(BookmarkDbHelper bookmarks, HistoryDbHelper history) {
        this.bookmarks = bookmarks;
        this.history = history;
    }

    List<OmniboxSuggestion> find(String query, boolean includeHistory, BooleanSupplier canceled) {
        Trace.beginSection("Browser.omnibox.lookup");
        try {
            List<OmniboxSuggestion> rows = new ArrayList<>(6);
            String pattern = literalSubstring(query);
            String[] args = {pattern, pattern};
            append(rows, bookmarks, BOOKMARK_SQL, args, OmniboxSuggestion.Type.BOOKMARK, R.drawable.ic_bookmark, canceled);
            if (includeHistory) append(rows, history, HISTORY_SQL, args, OmniboxSuggestion.Type.HISTORY, R.drawable.ic_history, canceled);
            return rows;
        } finally {
            Trace.endSection();
        }
    }

    static String literalSubstring(String query) {
        return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static void append(List<OmniboxSuggestion> rows, SQLiteOpenHelper helper, String sql, String[] args,
                               OmniboxSuggestion.Type type, int icon, BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) return;
        try {
            // Opening may wait for the helper. Do not hold its monitor across a cursor scan or
            // reopen a separate database; the next lookup reads from the same live database.
            SQLiteDatabase db = helper.getReadableDatabase();
            if (canceled.getAsBoolean()) return;
            try (Cursor cursor = db.rawQuery(sql, args)) {
                while (!canceled.getAsBoolean() && cursor.moveToNext()) {
                    String url = cursor.getString(1);
                    if (!url.trim().isEmpty()) rows.add(new OmniboxSuggestion(type, cursor.getString(0), url, url, icon));
                }
            }
        } catch (RuntimeException unavailable) {
            // Best-effort: leave fallback and any results from the other store. Do not log input,
            // URLs, or exception messages (which may include query data).
            if (!canceled.getAsBoolean()) Log.w("OmniboxStore", "Local suggestions are unavailable for one store");
        }
    }
}

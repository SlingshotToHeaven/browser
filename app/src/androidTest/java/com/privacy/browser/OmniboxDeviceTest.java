package com.privacy.browser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;

import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.browser.bookmarks.BookmarkDbHelper;
import com.privacy.browser.history.HistoryDbHelper;
import com.privacy.browser.search.OmniboxSuggestion;
import com.privacy.browser.search.SearchEngineManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Disposable debug install; actual Activity, ListView, and SQLite, without navigating to a network site. */
public class OmniboxDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final BookmarkDbHelper bookmarks = BookmarkDbHelper.getInstance(context);
    private final HistoryDbHelper history = HistoryDbHelper.getInstance(context);
    private final String token = "address" + UUID.randomUUID().toString().replace("-", "");
    private final String shared = "https://omnibox.invalid/" + token + "/shared";
    private final String visit = "https://omnibox.invalid/" + token + "/visit";
    private MainActivity activity;
    private long folder;
    private boolean previousAutocomplete;

    @Before public void seed() {
        previousAutocomplete = SearchEngineManager.isAutocompleteEnabled(context);
        SearchEngineManager.setAutocompleteEnabled(context, true);
        assertTrue(bookmarks.addBookmark("Book " + token, shared));
        folder = bookmarks.createFolder("Folder " + token, 0);
        assertTrue(folder > 0);
        history.addVisit("Duplicate " + token, shared);
        history.addVisit("Visit " + token, visit);
    }

    @After public void clean() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        if (folder > 0) bookmarks.deleteItems(Collections.singletonList(folder));
        bookmarks.removeBookmark(shared);
        history.getWritableDatabase().delete("history", "url IN (?,?)", new String[]{shared, visit});
        SearchEngineManager.setAutocompleteEnabled(context, previousAutocomplete);
    }

    @Test public void fallbackPrecedesActualLocalRowsAndKeepsOnlyNavigableBookmarksAndUniqueHistory() throws Exception {
        startHome();
        ui(() -> {
            enter(token);
            assertEquals(1, list().getAdapter().getCount());
            assertEquals(OmniboxSuggestion.Type.SEARCH, row(0).getType());
            return null;
        });
        assertTrue(await(() -> targets(OmniboxSuggestion.Type.BOOKMARK).equals(Collections.singletonList(shared))
                && targets(OmniboxSuggestion.Type.HISTORY).equals(Collections.singletonList(visit))));
        ui(() -> {
            SearchEngineManager.setAutocompleteEnabled(context, false);
            enter(token + "new");
            assertFalse(dropdown().isShown());
            assertEquals(0, list().getAdapter().getCount());
            return null;
        });
    }

    @Test public void privateLookupSkipsHistoryWithoutStartingAPrivateTab() {
        OmniboxStore store = new OmniboxStore(bookmarks, history);
        List<OmniboxSuggestion> regular = store.find(token, true, () -> false);
        assertTrue(regular.stream().anyMatch(s -> s.getType() == OmniboxSuggestion.Type.HISTORY));
        List<OmniboxSuggestion> privateRows = store.find(token, false, () -> false);
        assertEquals(1, privateRows.size());
        assertEquals(OmniboxSuggestion.Type.BOOKMARK, privateRows.get(0).getType());
        assertEquals(shared, privateRows.get(0).getTargetUrl());
        assertTrue(store.find(token, true, () -> true).isEmpty());
    }

    @Test public void blockedStoreDoesNotBlockTypingAndOldRowsCannotActAfterEditOrTabChange() throws Exception {
        startHome();
        // Prime a real row so that the test can deliver an old ListView click after new text.
        ui(() -> { enter(token); return null; });
        assertTrue(await(() -> targets(OmniboxSuggestion.Type.BOOKMARK).equals(Collections.singletonList(shared))));
        TabsManager tabs = field("tabsManager", TabsManager.class);
        ThreadPoolExecutor worker = field("omniboxWorker", ThreadPoolExecutor.class);
        int second = ui(() -> {
            tabs.queueBackgroundTab(InternalHome.URL, false);
            return tabs.getTabCount() - 1;
        });
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread holdStore = new Thread(() -> {
            synchronized (bookmarks) {
                locked.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            }
        });
        holdStore.start();
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            ui(() -> {
                int index = -1;
                for (int i = 0; i < list().getAdapter().getCount(); i++) {
                    if (row(i).getType() == OmniboxSuggestion.Type.BOOKMARK) index = i;
                }
                assertTrue(index >= 0);
                long id = list().getAdapter().getItemId(index);
                View old = list().getAdapter().getView(index, null, list());
                enter(token + " missing");
                list().performItemClick(old, index, id);
                assertTrue(edit().hasFocus());
                assertTrue(dropdown().isShown());
                assertTrue(targets(OmniboxSuggestion.Type.BOOKMARK).isEmpty());
                return null;
            });
            assertTrue(await(() -> worker.getActiveCount() == 1));
            assertTrue("Editor returned before the helper monitor was released", holdStore.isAlive());
            ui(() -> {
                enter(token); // could match the old row again, but belongs to the outgoing tab
                tabs.switchTab(second);
                assertFalse(edit().hasFocus());
                assertFalse(dropdown().isShown());
                return null;
            });
        } finally {
            release.countDown();
            holdStore.join(5_000);
        }
        assertTrue(await(() -> worker.getActiveCount() == 0 && worker.getQueue().isEmpty()));
        instrumentation.waitForIdleSync();
        ui(() -> {
            assertFalse(dropdown().isShown());
            assertEquals(0, list().getAdapter().getCount());
            return null;
        });
    }

    private void startHome() throws Exception {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_LOAD_URL, InternalHome.URL)
                .putExtra(MainActivity.EXTRA_OPEN_IN_NEW_TAB, true);
        activity = (MainActivity) instrumentation.startActivitySync(intent);
        TabsManager tabs = field("tabsManager", TabsManager.class);
        assertTrue(await(() -> {
            TabModel tab = tabs.getActiveTab();
            return activity.hasWindowFocus() && edit().isEnabled() && tab != null && tab.getWebView() != null
                    && InternalHome.URL.equals(tab.getUrl()) && "Privacy Browser".equals(tab.getTitle());
        }));
    }

    private EditText edit() { return activity.findViewById(R.id.edit_url); }
    private View dropdown() { return activity.findViewById(R.id.omnibox_suggestions_container); }
    private ListView list() { return activity.findViewById(R.id.omnibox_suggestions_list); }
    private OmniboxSuggestion row(int i) { return (OmniboxSuggestion) list().getAdapter().getItem(i); }
    private void enter(String value) {
        assertTrue(edit().requestFocus());
        edit().setText(value);
    }

    private List<String> targets(OmniboxSuggestion.Type type) {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < list().getAdapter().getCount(); i++) {
            OmniboxSuggestion row = row(i);
            if (row.getType() == type) urls.add(row.getTargetUrl());
        }
        return urls;
    }

    private <T> T field(String name, Class<T> type) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(activity));
    }

    private boolean await(Supplier<Boolean> predicate) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 5_000;
        do {
            if (ui(predicate)) return true;
            Thread.sleep(20);
        } while (SystemClock.uptimeMillis() < end);
        return false;
    }

    private <T> T ui(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.get()); }
            catch (Throwable failure) { problem.set(failure); }
        });
        if (problem.get() != null) throw new AssertionError(problem.get());
        return result.get();
    }
}

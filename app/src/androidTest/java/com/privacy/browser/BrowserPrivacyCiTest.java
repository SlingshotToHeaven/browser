package com.privacy.browser;

import android.os.Process;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.privacy.browser.bookmarks.BookmarkDbHelper;
import com.privacy.browser.history.HistoryDbHelper;
import com.privacy.browser.history.HistoryModel;
import com.privacy.browser.settings.SettingsActivity;
import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.engine.DownloadTask;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** Use the actual all-sites choices, explicit second confirmation, and visible result. */
public final class BrowserPrivacyCiTest extends BrowserRecoveryCiSupport {
    private static final String MARKERS = "/privacy/markers", LOGIN = "/privacy/login";
    private static final String BOOKMARK = A + "/privacy/bookmark-sentinel";
    private static final String PAYLOAD = "synthetic-privacy-download";
    private static final String[] CHOICES = {"WebView cookies", "WebView URL cache", "Legacy Web Storage request",
            "History and saved tabs (closes tabs)"};
    private static final String[] PLAN = {"WebView cookies for all sites (signs you out)", "WebView URL cache for all sites",
            "Request legacy Web Storage deletion for all sites", "Browser history and saved tabs (closes all tabs)"};
    private static final String[] STORAGE_KEYS = {"localStorage", "indexedDB", "cacheStorage", "worker"};

    private enum Choice {
        CANCEL(-1, null),
        COOKIES(0, "WebView cookies: removed"),
        HTTP_CACHE(1, "WebView URL cache: clear requested"),
        LEGACY_STORAGE(2, "Legacy Web Storage: deletion requested; no completion callback"),
        HISTORY_TABS(3, "History and saved tabs: removed");

        final int category;
        final String result;
        Choice(int category, String result) { this.category = category; this.result = result; }
    }

    @Test public void nativePrivateEntriesDoNotOpenRegularTabs() throws Exception {
        launch(main().putExtra(MainActivity.EXTRA_OPEN_IN_INCOGNITO, true)
                .putExtra(MainActivity.EXTRA_LOAD_URL, A + "/private-denied"));
        privateExplanation();
        waitPage(InternalHome.URL);
        TabModel original = ui(this::selected);
        String outcome = shell("am start -W --activity-single-top -n " + COMPONENT
                + " --ez " + MainActivity.EXTRA_OPEN_IN_INCOGNITO + " true --es " + MainActivity.EXTRA_LOAD_URL + " " + A + "/private-denied");
        assertTrue(outcome, outcome.contains("Status: ok"));
        privateExplanation();
        waitPage(InternalHome.URL);
        click(R.id.btn_tabs);
        tap("tabs_btn_new_incognito", null);
        privateExplanation();
        waitPage(InternalHome.URL);
        ui(() -> {
            assertEquals(1, tabs().getTabCount());
            assertSame(original, selected());
            assertFalse(selected().isIncognito());
            return null;
        });
        assertEquals(0, count("a GET /private-denied"));
        observe("cold and warm private intents and tab-picker private entry refused; only the intended home tab; target not fetched");
    }

    @Test public void choosePrivacyClearInNativeUi() throws Exception {
        Choice choice = selectedChoice();
        String downloadId = seedUnrelated();
        open(A + LOGIN, A + MARKERS);
        TabModel first = ui(this::selected);
        JSONObject a = plantAndWarm(A);
        TabModel second = newTab(B + LOGIN);
        waitPage(B + MARKERS);
        JSONObject b = plantAndWarm(B);
        assertCookies(true);
        ui(() -> { CookieManager.getInstance().flush(); return null; }); // establish durable seed, not a second clear
        Set<Long> visits = historyIds();
        assertTrue("Positive app history control missing", visits.size() >= 2);
        waitFor("both fixture tab states persisted", 8000, () -> {
            TabSession.Index candidate = index();
            return candidate.tabs.size() == 2 && second.getId().equals(candidate.selected)
                    && candidate.tabs.stream().allMatch(row -> row.state != null && Files.isRegularFile(sessionDir().resolve(row.filename())));
        });
        TabSession.Index saved = index();
        JSONObject seed = new JSONObject().put("choice", choice.name()).put("pid", Process.myPid())
                .put("visits", new JSONArray(visits)).put("session", TabSession.encode(saved))
                .put("a", a).put("b", b).put("download", downloadId);
        checkUnrelated(downloadId);
        review(choice == Choice.CANCEL ? new int[]{0, 1, 2, 3} : new int[]{choice.category});
        if (choice == Choice.CANCEL) {
            tap("android:id/button2", "Cancel");
            waitPage(B + MARKERS);
            assertNull("Cancelled review unexpectedly produced a cleanup result", node(null, "Cleanup result"));
        } else {
            clearAndExpect(choice.result);
        }
        observe(choice == Choice.CANCEL ? "cancelled the second confirmation with all categories selected" : "UI result: " + choice.result);
        assertCookies(choice != Choice.COOKIES);
        checkUnrelated(downloadId);
        if (choice == Choice.HISTORY_TABS) {
            waitPage(InternalHome.URL);
            waitFor("only a new Home in the on-disk session", 8000, () -> homeOnly(index()));
            assertClearedSession(saved);
        } else {
            assertSavedSession(seed, saved);
            // Inspect the two live documents for observations; cold probes below use fresh documents.
            checkStorage(choice, "live A", a, storage(ui(first::getWebView), false));
            checkStorage(choice, "live B", b, storage(ui(second::getWebView), false));
        }
        assertEquals("Seed A was not requested exactly once", 1, cacheRequests(A));
        assertEquals("Seed B was not requested exactly once", 1, cacheRequests(B));
        Files.write(baseline(), seed.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test public void verifyPrivacyChoiceInNewProcess() throws Exception {
        Choice choice = selectedChoice();
        JSONObject seed = new JSONObject(new String(Files.readAllBytes(baseline()), StandardCharsets.UTF_8));
        assertEquals(choice.name(), seed.getString("choice"));
        assertNotEquals("Expected a new browser process", seed.getInt("pid"), Process.myPid());
        TabSession.Index saved = TabSession.decode(seed.getString("session"));
        boolean cookiePresent = choice != Choice.COOKIES;
        assertCookies(cookiePresent);
        if (choice == Choice.HISTORY_TABS) assertClearedSession(saved);
        else assertSavedSession(seed, saved);
        launch(main());
        if (choice == Choice.HISTORY_TABS) {
            waitPage(InternalHome.URL);
            assertEquals(1, (int) ui(() -> tabs().getTabCount()));
            assertClearedSession(saved);
            assertEquals(0, count("a GET /privacy/markers") + count("b GET /privacy/markers"));
            observe("new process restored only Home; original history, tab IDs and state files gone before deliberate site probes");
            // Deliberate new visits, which cannot replant the login or any storage marker.
            ui(() -> { load(selected(), B + MARKERS); return null; });
        }
        waitPage(B + MARKERS);
        if (choice != Choice.HISTORY_TABS) assertEquals("Restored original selection", saved.selected, ui(() -> selected().getId()));
        checkColdOrigin(choice, seed, B, "b", cookiePresent);
        if (choice == Choice.HISTORY_TABS) {
            newTab(A + MARKERS);
        } else {
            ui(() -> {
                TabModel original = tabs().getTabs().stream().filter(tab -> (A + MARKERS).equals(tab.getUrl())).findFirst().orElseThrow();
                tabs().switchTab(tabs().getTabs().indexOf(original));
                return null;
            });
        }
        waitPage(A + MARKERS);
        checkColdOrigin(choice, seed, A, "a", cookiePresent);
        assertEquals("Probes must not seed new cookies", 0, count("a GET /privacy/login") + count("b GET /privacy/login"));
        assertCookies(cookiePresent);
        if (choice != Choice.HISTORY_TABS) assertSavedSession(seed, saved);
        checkUnrelated(seed.getString("download"));
        observe("after restart: both HTTP logins " + (cookiePresent ? "preserved" : "absent")
                + "; HTTP-cache probes " + (choice == Choice.HTTP_CACHE ? "both missed, then hit" : "both hit")
                + "; completed file and row, bookmark and zoom setting preserved");
    }

    private void checkColdOrigin(Choice choice, JSONObject seed, String origin, String label, boolean signed) throws Exception {
        assertEquals("HTTP document cookie marker from " + label, signed ? "yes" : "no", js("document.body.dataset.signed"));
        int total = count(label + " GET /privacy/markers");
        assertTrue("No fresh marker HTTP response at " + label, total > 0);
        assertEquals("Observed wire cookie at " + label, signed ? total : 0, count(label + " GET /privacy/markers signed"));
        assertEquals(0, cacheRequests(origin));
        fetchCache();
        int requests = cacheRequests(origin);
        JSONObject after = storage(ui(() -> selected().getWebView()), false);
        checkStorage(choice, "cold " + label.toUpperCase(java.util.Locale.ROOT), seed.getJSONObject(label), after);
        assertEquals("Unexpected cache request count after restart for " + label, choice == Choice.HTTP_CACHE ? 1 : 0, requests);
        fetchCache();
        assertEquals("Second cold fetch did not use the cached response for " + label, requests, cacheRequests(origin));
    }

    private JSONObject plantAndWarm(String origin) throws Exception {
        assertEquals("yes", js("document.body.dataset.signed"));
        JSONObject initial = storage(ui(() -> selected().getWebView()), true);
        assertEquals("present", initial.getString("localStorage"));
        assertEquals(0, cacheRequests(origin));
        fetchCache();
        assertEquals(1, cacheRequests(origin));
        fetchCache();
        assertEquals("No network-free positive cache control", 1, cacheRequests(origin));
        return initial;
    }

    private Choice selectedChoice() { return Choice.valueOf(arg("privacy_choice")); }
    private Path baseline() { return app.getFilesDir().toPath().resolve("privacy-fixture-baseline.json"); }

    private void privateExplanation() throws Exception {
        waitNode(null, "Private tabs unavailable");
        assertTrue(waitNode("android:id/message", null).getText().toString().contains("nothing was deleted automatically"));
        tap("android:id/button2", "Close");
    }

    private void review(int... selections) throws Exception {
        click(R.id.btn_site_security);
        assertEquals(BrowserDataPolicy.SITE_CLEAR_UNAVAILABLE, waitNode("site_info_data_note", null).getText().toString());
        assertCookies(true); // opening Site Info must not perform any clear
        tap("site_info_btn_clear_browser", null);
        waitNode(null, "Clear browsing data — all sites");
        List<String> expected = new ArrayList<>();
        for (int i : selections) {
            var choice = waitNode(null, CHOICES[i]);
            assertTrue("Expected an initially unchecked category", choice.isCheckable() && !choice.isChecked());
            tap(null, CHOICES[i]);
            waitFor("exact category checked", 3000, () -> {
                var item = node(null, CHOICES[i]);
                return item != null && item.isChecked();
            });
            expected.add(PLAN[i]);
        }
        tap("android:id/button1", "Review");
        waitNode(null, "Confirm all-sites cleanup");
        String plan = waitNode("android:id/message", null).getText().toString();
        assertEquals(String.join("\n", expected), plan.split("\n\n", 2)[0]);
        assertTrue(plan.contains("does not guarantee removal"));
        assertCookies(true); // even after selection and review, no deletion without explicit confirmation
    }

    private void clearAndExpect(String result) throws Exception {
        tap("android:id/button1", "Clear selected data");
        waitNode(null, "Cleanup result");
        assertEquals(result, waitNode("android:id/message", null).getText().toString());
        tap("android:id/button1", "Close");
        waitFor("cleanup result closed", 4000, () -> ui(() -> !BrowserDataCleaner.inProgress() && activity.hasWindowFocus()));
    }

    private String seedUnrelated() throws Exception {
        assertTrue(BookmarkDbHelper.getInstance(app).addBookmarkAndGetId("Privacy test bookmark", BOOKMARK, 0) > 0);
        assertTrue(app.getSharedPreferences(SettingsActivity.PREFS_BROWSER, 0).edit().putInt(SettingsActivity.KEY_PAGE_ZOOM, 115).commit());
        Path path = app.getFilesDir().toPath().resolve("privacy-test-download.txt");
        DownloadItem item = new DownloadItem();
        item.setId(UUID.randomUUID().toString());
        item.setUrl("data:text/plain," + PAYLOAD);
        item.setMimeType("text/plain");
        item.setFileName(path.getFileName().toString());
        item.setDestinationPath(path.toString());
        DownloadDbHelper db = DownloadDbHelper.getInstance(app);
        db.enqueueDownload(item, path.toFile());
        assertTrue(db.claim(item, path.toFile(), UUID.randomUUID().toString()));
        new DownloadTask(item, db, null, null).run(); // local synthetic bytes; no download transport retest
        assertEquals(item.getErrorMessage(), DownloadStatus.COMPLETED, item.getStatus());
        return item.getId();
    }

    private void checkUnrelated(String id) throws Exception {
        assertTrue("Bookmark sentinel disappeared", BookmarkDbHelper.getInstance(app).isBookmarked(BOOKMARK));
        assertEquals("Zoom sentinel changed", 115, SettingsActivity.getPageZoom(app));
        DownloadDbHelper db = DownloadDbHelper.getInstance(app);
        var file = db.getPublishedDownload(id);
        assertNotNull("Completed-download sentinel disappeared", file);
        assertEquals(DownloadStatus.COMPLETED, db.getDownload(id).getStatus());
        assertEquals(PAYLOAD, new String(Files.readAllBytes(Path.of(file.path)), StandardCharsets.UTF_8));
    }

    private void assertCookies(boolean present) throws Exception {
        for (String origin : new String[]{A, B}) {
            String cookie = ui(() -> CookieManager.getInstance().getCookie(origin + MARKERS));
            assertEquals("Fixture cookie presence at " + origin, present, cookie != null && cookie.contains("recovery_fixture=1"));
        }
    }

    private void assertSavedSession(JSONObject seed, TabSession.Index saved) throws Exception {
        JSONArray previous = seed.getJSONArray("visits");
        Set<Long> now = historyIds();
        for (int i = 0; i < previous.length(); i++) assertTrue("Unselected history changed", now.contains(previous.getLong(i)));
        assertEquals(saved.tabs.stream().map(row -> row.id).collect(Collectors.toSet()),
                index().tabs.stream().map(row -> row.id).collect(Collectors.toSet()));
    }

    private void assertClearedSession(TabSession.Index old) throws Exception {
        assertTrue("History not empty", historyIds().isEmpty());
        TabSession.Index current = index();
        assertTrue("Expected only Home in the saved session", homeOnly(current));
        for (TabSession.Row row : old.tabs) {
            assertNotEquals("Old tab identity kept", row.id, current.selected);
            assertFalse("Cleared tab still has its old state blob", Files.exists(sessionDir().resolve(row.filename())));
        }
    }

    private Set<Long> historyIds() {
        return HistoryDbHelper.getInstance(app).getAllHistory(0).stream().map(HistoryModel::getId).collect(Collectors.toSet());
    }

    private static boolean homeOnly(TabSession.Index index) {
        return index.tabs.size() == 1 && InternalHome.URL.equals(index.tabs.get(0).url)
                && index.tabs.get(0).id.equals(index.selected);
    }

    private JSONObject storage(WebView view, boolean plant) throws Exception {
        assertEquals("Storage fixture script not available", "function", js(view, "typeof window.inspectFixture"));
        js(view, plant ? "window.plantFixture(); null" : "window.inspectFixture(); null");
        JSONObject[] found = {null};
        waitFor("synthetic storage observations", 6500, () -> {
            Object response = js(view, "JSON.stringify(window.fixtureStorage || null)");
            if (!(response instanceof String text) || text.equals("null")) return false;
            found[0] = new JSONObject(text);
            for (String key : STORAGE_KEYS) assertTrue(Set.of("present", "absent", "unavailable").contains(found[0].getString(key)));
            return true;
        });
        return found[0];
    }

    private void checkStorage(Choice choice, String phase, JSONObject before, JSONObject after) throws Exception {
        List<String> markers = new ArrayList<>();
        for (String key : STORAGE_KEYS) markers.add(key + "=" + before.getString(key) + "->" + after.getString(key));
        observe(phase + ": " + String.join(", ", markers));
        if (choice != Choice.LEGACY_STORAGE) for (String key : STORAGE_KEYS) {
            assertEquals(phase + " unselected " + key + " changed", before.getString(key), after.getString(key));
        }
    }

    private int cacheRequests(String origin) throws Exception { return count((A.equals(origin) ? "a" : "b") + " GET /privacy/cacheable"); }

    private void fetchCache() throws Exception {
        js("window.fetchFixture(); null");
        waitFor("cacheable fixture body", 6000, () -> "cache-fixture".equals(js("window.fixtureCache")));
    }
}

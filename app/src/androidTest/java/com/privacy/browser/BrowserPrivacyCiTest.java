package com.privacy.browser;

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

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** Use the actual all-sites choices, explicit second confirmation, and visible result. */
public final class BrowserPrivacyCiTest extends BrowserRecoveryCiSupport {
    private static final String MARKERS = "/privacy/markers", LOGIN = "/privacy/login";
    private static final String DOWNLOAD_ID = "privacy-preserve-download";
    private static final String BOOKMARK = A + "/privacy/bookmark-sentinel";
    private static final String PAYLOAD = "synthetic-privacy-download";
    private static final String[] CHOICES = {"WebView cookies", "WebView URL cache", "Legacy Web Storage request",
            "History and saved tabs (closes tabs)"};
    private static final String[] PLAN = {"WebView cookies for all sites (signs you out)", "WebView URL cache for all sites",
            "Request legacy Web Storage deletion for all sites", "Browser history and saved tabs (closes all tabs)"};
    private static final String[] STORAGE_KEYS = {"local", "indexedDB", "cacheStorage", "worker"};
    private TabModel first, second;
    private Set<Long> visits;
    private TabSession.Index saved;

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

    @Test public void cancelConfirmationKeepsAllSeededData() throws Exception {
        seed(false);
        warmCache();
        review(0, 1, 2, 3);
        tap("android:id/button2", "Cancel");
        waitFor("cancelled review closed without a cleanup result", 4000, () ->
                node(null, "Confirm all-sites cleanup") == null && node(null, "Cleanup result") == null);
        waitPage(B + MARKERS);
        assertCookies(true);
        assertLocalMarkers();
        assertOriginalHistoryAndTabs();
        checkUnrelated();
        fetchCache();
        assertEquals(1, cacheRequests());
        observe("Site Info only offered all-sites review; cancelling all four selections preserved both cookies, local markers, cached response, history, tabs and app sentinels");
    }

    @Test public void clearOnlyCookiesOnBothOrigins() throws Exception {
        seed(false);
        review(0);
        clearAndExpect("WebView cookies: removed");
        assertCookies(false);
        assertLocalMarkers();
        assertOriginalHistoryAndTabs();
        checkUnrelated();
        observe("provider-confirmed cookie-only removal on both origins; localStorage, history and tabs preserved");
    }

    @Test public void cookieRemovalPersistsAcrossRestart() throws Exception {
        assertCookies(false);
        TabSession.Index beforeLaunch = index();
        assertEquals(2, beforeLaunch.tabs.size());
        launch(main());
        waitPage(B + MARKERS);
        assertEquals("no", js("document.body.dataset.signed"));
        assertEquals(beforeLaunch.selected, ui(() -> selected().getId()));
        assertEquals("present", storage(ui(() -> selected().getWebView()), false, false).getString("local"));
        waitFor("fresh unsigned HTTP response from restored second origin", 9000,
                () -> count("b GET /privacy/markers") > 0 && count("b GET /privacy/markers signed") == 0);
        TabModel other = ui(() -> {
            TabModel tab = tabs().getTabs().stream().filter(candidate -> (A + MARKERS).equals(candidate.getUrl())).findFirst().orElseThrow();
            tabs().switchTab(tabs().getTabs().indexOf(tab));
            return tab;
        });
        waitPage(A + MARKERS);
        assertEquals("no", js("document.body.dataset.signed"));
        assertEquals("present", storage(ui(other::getWebView), false, false).getString("local"));
        assertTrue(count("a GET /privacy/markers") > 0);
        assertEquals(0, count("a GET /privacy/markers signed"));
        assertEquals(0, count("a GET /privacy/login") + count("b GET /privacy/login"));
        assertFalse(historyIds().isEmpty());
        checkUnrelated();
        observe("new process visited both restored origins without the cookie on the wire; both localStorage markers and app sentinels survived");
    }

    @Test public void clearOnlyUrlCacheAndObserveRealRefetch() throws Exception {
        seed(false);
        warmCache();
        review(1);
        clearAndExpect("WebView URL cache: clear requested");
        click(R.id.btn_reload);
        waitPage(B + MARKERS);
        fetchCache();
        assertEquals("A previously cached response did not refetch", 2, cacheRequests());
        assertCookies(true);
        assertLocalMarkers();
        assertOriginalHistoryAndTabs();
        checkUnrelated();
        observe("cacheable resource fetched once, reused without HTTP, then fetched once more after URL-cache-only request; not a full cache-clear claim");
    }

    @Test public void legacyStorageReportsRequestAndObservesProviderOutcome() throws Exception {
        seed(true);
        var beforeA = storage(ui(first::getWebView), false, false);
        var beforeB = storage(ui(second::getWebView), false, false);
        assertEquals("present", beforeA.getString("local"));
        assertEquals("present", beforeB.getString("local"));
        review(2);
        clearAndExpect("Legacy Web Storage: deletion requested; no completion callback");
        JSONObject afterB = reloadAndInspect(second, B);
        JSONObject afterA = reloadAndInspect(first, A);
        assertCookies(true);
        assertOriginalHistoryAndTabs();
        checkUnrelated();
        observe("storage A: " + changes(beforeA, afterA));
        observe("storage B: " + changes(beforeB, afterB));
        observe("legacy request disclosed no completion callback; observed markers only, not secure erasure or profile deletion");
    }

    @Test public void clearOnlyHistoryAndTabs() throws Exception {
        seed(false);
        review(3);
        clearAndExpect("History and saved tabs: removed");
        waitPage(InternalHome.URL);
        assertTrue(historyIds().isEmpty());
        assertCookies(true);
        checkUnrelated();
        waitFor("only a new Home in the on-disk session", 8000, () -> homeOnly(index()));
        for (TabSession.Row old : saved.tabs) {
            assertTrue(index().tabs.stream().noneMatch(row -> old.id.equals(row.id)));
            assertFalse("Cleared tab still has its old state blob", Files.exists(sessionDir().resolve(old.filename())));
        }
        observe("history actually removed, both previous tabs and their saved states retired; cookies and app sentinels kept");
    }

    @Test public void historyAndTabRemovalPersistsAcrossRestart() throws Exception {
        assertTrue(homeOnly(index()));
        launch(main());
        waitPage(InternalHome.URL);
        assertEquals(1, (int) ui(() -> tabs().getTabCount()));
        assertTrue(historyIds().isEmpty());
        assertCookies(true);
        assertEquals(0, count("a GET /privacy/markers") + count("b GET /privacy/markers"));
        checkUnrelated();
        observe("new process restored only Home, no history or old site fetch; both unselected cookies and app sentinels persisted");
    }

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

    private void seed(boolean advancedStorage) throws Exception {
        seedUnrelated();
        open(A + LOGIN, A + MARKERS);
        assertEquals("yes", js("document.body.dataset.signed"));
        first = ui(this::selected);
        assertEquals("present", storage(ui(first::getWebView), true, advancedStorage).getString("local"));
        second = newTab(B + LOGIN);
        waitPage(B + MARKERS);
        assertEquals("yes", js("document.body.dataset.signed"));
        assertEquals("present", storage(ui(second::getWebView), true, advancedStorage).getString("local"));
        assertCookies(true);
        visits = historyIds();
        assertTrue("Positive app history control missing", visits.size() >= 2);
        waitFor("both fixture tab states persisted", 8000, () -> {
            TabSession.Index candidate = index();
            if (candidate.tabs.size() != 2 || !second.getId().equals(candidate.selected)
                    || candidate.tabs.stream().anyMatch(row -> row.state == null || !Files.isRegularFile(sessionDir().resolve(row.filename())))) return false;
            saved = candidate;
            return true;
        });
        checkUnrelated();
    }

    private void seedUnrelated() throws Exception {
        assertTrue(BookmarkDbHelper.getInstance(app).addBookmarkAndGetId("Privacy test bookmark", BOOKMARK, 0) > 0);
        assertTrue(app.getSharedPreferences(SettingsActivity.PREFS_BROWSER, 0).edit().putInt(SettingsActivity.KEY_PAGE_ZOOM, 115).commit());
        Path path = app.getFilesDir().toPath().resolve("privacy-test-download.txt");
        DownloadItem item = new DownloadItem();
        item.setId(DOWNLOAD_ID);
        item.setUrl("data:text/plain," + PAYLOAD);
        item.setMimeType("text/plain");
        item.setFileName(path.getFileName().toString());
        item.setDestinationPath(path.toString());
        DownloadDbHelper db = DownloadDbHelper.getInstance(app);
        db.enqueueDownload(item, path.toFile());
        assertTrue(db.claim(item, path.toFile(), "privacy-fixture-attempt"));
        new DownloadTask(item, db, null, null).run(); // local synthetic bytes; no download transport retest
        assertEquals(item.getErrorMessage(), DownloadStatus.COMPLETED, item.getStatus());
    }

    private void checkUnrelated() throws Exception {
        assertTrue(BookmarkDbHelper.getInstance(app).isBookmarked(BOOKMARK));
        assertEquals(115, SettingsActivity.getPageZoom(app));
        DownloadDbHelper db = DownloadDbHelper.getInstance(app);
        var file = db.getPublishedDownload(DOWNLOAD_ID);
        assertNotNull("Completed-download sentinel disappeared", file);
        assertEquals(DownloadStatus.COMPLETED, db.getDownload(DOWNLOAD_ID).getStatus());
        assertEquals(PAYLOAD, new String(Files.readAllBytes(Path.of(file.path)), StandardCharsets.UTF_8));
    }

    private void assertCookies(boolean present) throws Exception {
        for (String origin : new String[]{A, B}) {
            String cookie = ui(() -> CookieManager.getInstance().getCookie(origin + MARKERS));
            assertEquals("Fixture cookie presence at " + origin, present, cookie != null && cookie.contains("recovery_fixture=1"));
        }
    }

    private void assertLocalMarkers() throws Exception {
        assertEquals("present", storage(ui(first::getWebView), false, false).getString("local"));
        assertEquals("present", storage(ui(second::getWebView), false, false).getString("local"));
    }

    private void assertOriginalHistoryAndTabs() throws Exception {
        assertTrue("Unselected history changed", historyIds().containsAll(visits));
        Set<String> ids = saved.tabs.stream().map(row -> row.id).collect(Collectors.toSet());
        assertEquals(ids, ui(() -> tabs().getTabs().stream().map(TabModel::getId).collect(Collectors.toSet())));
        assertEquals(ids, index().tabs.stream().map(row -> row.id).collect(Collectors.toSet()));
    }

    private Set<Long> historyIds() {
        return HistoryDbHelper.getInstance(app).getAllHistory(0).stream().map(HistoryModel::getId).collect(Collectors.toSet());
    }

    private static boolean homeOnly(TabSession.Index index) {
        return index.tabs.size() == 1 && InternalHome.URL.equals(index.tabs.get(0).url)
                && index.tabs.get(0).id.equals(index.selected);
    }

    private JSONObject reloadAndInspect(TabModel tab, String origin) throws Exception {
        ui(() -> { tabs().switchTab(tabs().getTabs().indexOf(tab)); return null; });
        waitPage(origin + MARKERS);
        click(R.id.btn_reload);
        waitPage(origin + MARKERS);
        return storage(ui(tab::getWebView), false, false);
    }

    private JSONObject storage(WebView view, boolean plant, boolean advanced) throws Exception {
        js(view, plant ? "window.plantFixture(" + advanced + "); null" : "window.inspectFixture(); null");
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

    private static String changes(JSONObject before, JSONObject after) throws Exception {
        List<String> markers = new ArrayList<>();
        for (String key : STORAGE_KEYS) markers.add(key + "=" + before.getString(key) + "->" + after.getString(key));
        return String.join(", ", markers);
    }

    private int cacheRequests() throws Exception { return count("b GET /privacy/cacheable"); }

    private void fetchCache() throws Exception {
        js("window.fetchFixture(); null");
        waitFor("cacheable fixture body", 6000, () -> "cache-fixture".equals(js("window.fixtureCache")));
    }

    private void warmCache() throws Exception {
        assertEquals(0, cacheRequests());
        fetchCache();
        assertEquals(1, cacheRequests());
        fetchCache();
        assertEquals("No network-free positive cache control", 1, cacheRequests());
    }
}

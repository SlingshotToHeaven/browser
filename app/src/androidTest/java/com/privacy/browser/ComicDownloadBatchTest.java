package com.privacy.browser;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.webkit.WebViewFeature;

import com.privacy.browser.comic.ComicDownloadDialog;
import com.privacy.browser.comic.ComicManager;
import com.privacy.browser.settings.SettingsActivity;

import org.json.JSONArray;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class ComicDownloadBatchTest extends DownloadBrowserBatchSupport {
    private static final String TITLE = "Batch comic";
    private static final String RED = "/images/red.png", GREEN = "/images/green.png", BLUE = "/images/blue.png";

    @Test public void orderedArchiveAndRepeatDoNotReplaceExistingArchive() throws Exception {
        chapter("ok");
        assertPlan(4);
        startArchive();
        saved();
        var first = archive("Batch comic.cbz");
        verifyArchive(first, RED, GREEN, RED, BLUE);
        byte[] previous = Files.readAllBytes(first);
        tapBounds(controlNode("btn_start_comic_download"), false); // Done
        startArchive();
        saved();
        verifyArchive(archive("Batch comic_1.cbz"), RED, GREEN, RED, BLUE);
        assertArrayEquals(previous, Files.readAllBytes(first));
        assertEquals(0, db.countDownloads()); // The comic path doesn't record download-history success.
        noStage();
        evidence("app-private CBZ checked byte for byte with ZipFile; repeated page preserved; second archive suffixed");
    }

    @Test public void missingPageStopsArchive() throws Exception { failure("missing", "HTTP 404"); }
    @Test public void truncatedPageStopsArchive() throws Exception { failure("short", "complete image"); }
    @Test public void stalledPageHitsNormalReadTimeout() throws Exception { failure("slow", "time"); }

    @Test public void malformedAndOversizedPlansCannotOfferAnArchive() throws Exception {
        chapter("ok");
        assertPlan(4); // A positive detector control with the same provider/settings.
        for (String invalid : new String[]{"known-gap", "oversized"}) {
            navigate("/comic/" + invalid);
            assertEquals(invalid.equals("known-gap") ? 3 : 151, ((Number) js("window.chapterImages.length")).intValue());
            android.os.SystemClock.sleep(900);
            assertNull(ui(() -> ComicManager.getDetectedComic(selected().pageKey())));
            assertNull(node("fab_comic_download", null));
            assertNull(node("btn_start_comic_download", null));
        }
        assertEquals(0, requests(RED).length());
        noArchive();
    }

    @Test public void closeDuringSlowPage() throws Exception { cancel("close"); }
    @Test public void navigateDuringSlowPage() throws Exception { cancel("navigate"); }
    @Test public void backgroundDuringSlowPage() throws Exception { cancel("background"); }

    @Test public void verificationRetriesOnlyBlockedPage() throws Exception {
        chapter("verify");
        startArchive();
        View verifier = verificationView();
        tapBounds(waitNode(null, "Set fixture cookie"), false);
        waitFor("cookie set by actual challenge WebView click", () -> ui(() -> {
            String cookie = android.webkit.CookieManager.getInstance().getCookie(BASE + "/gated-page");
            return cookie != null && cookie.contains("comic_fixture_verified=1");
        }));
        tapBounds(controlNode("btn_captcha_done"), false);
        saved();
        waitFor("verification WebView detached", () -> ui(() -> verifier.getParent() == null));
        assertEquals(1, requests(RED).length());
        assertEquals(1, requests(BLUE).length());
        JSONArray calls = requests("/gated-page");
        assertEquals(3, calls.length()); // Worker 403, WebView 403, worker 200.
        assertEquals(403, calls.getJSONObject(0).getInt("code"));
        assertEquals(403, calls.getJSONObject(1).getInt("code"));
        assertEquals(200, calls.getJSONObject(2).getInt("code"));
        assertTrue(calls.getJSONObject(2).getBoolean("verified_cookie"));
        verifyArchive(archive("Batch comic.cbz"), RED, BLUE, BLUE);
        noStage();
    }

    @Test public void backDismissesVerifierAndDisposesItsView() throws Exception {
        chapter("verify");
        startArchive();
        View verifier = verificationView();
        waitNode(null, "Set fixture cookie");
        shell("input keyevent KEYCODE_BACK");
        waitFor("verification WebView detached after Back", () -> ui(() -> verifier.getParent() == null));
        waitFor("visible verification cancellation", () -> status().contains("verification was canceled"));
        assertEquals(1, requests(RED).length());
        assertEquals(0, requests(BLUE).length());
        assertEquals(2, requests("/gated-page").length());
        noArchive();
    }

    private void chapter(String name) throws Exception {
        assumeTrue("Provider has no origin-aware discovery listener", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
        SettingsActivity.setComicDownloaderEnabled(app, true);
        open("/comic/" + name);
    }

    private void assertPlan(int count) throws Exception {
        waitFor("ordered reader plan", () -> ui(() -> {
            ComicManager.ComicItem item = ComicManager.getDetectedComic(selected().pageKey());
            return item != null && count == item.getPageCount();
        }));
    }

    private void startArchive() throws Exception {
        tapBounds(controlNode("fab_comic_download"), false);
        AccessibilityNodeInfo edit = controlNode("edit_comic_title");
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TITLE);
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text));
        tapBounds(controlNode("btn_start_comic_download"), false);
    }

    private String status() {
        AccessibilityNodeInfo status = node("tv_comic_download_status", null);
        return status == null || status.getText() == null ? "" : status.getText().toString();
    }

    private void saved() throws Exception { waitFor("actual archive Saved status", () -> status().startsWith("Detected pages saved to:")); }

    private Path archive(String name) { return directory.resolve("Comics").resolve(name); }

    private void verifyArchive(Path out, String... paths) throws Exception {
        assertTrue(Files.isRegularFile(out) && Files.size(out) < 32 * 1024);
        assertTrue(status().endsWith(out.toFile().getAbsolutePath()));
        try (ZipFile zip = new ZipFile(out.toFile())) {
            assertEquals(paths.length, zip.size());
            var entries = zip.entries();
            for (int i = 0; i < paths.length; i++) {
                var entry = entries.nextElement();
                assertEquals(String.format(Locale.ROOT, "page_%04d.png", i + 1), entry.getName());
                try (var bytes = zip.getInputStream(entry)) { assertArrayEquals(fetch(paths[i]), read(bytes, 4096)); }
            }
        }
    }

    private void failure(String scenario, String detail) throws Exception {
        chapter(scenario);
        startArchive();
        waitFor("explicit page two failure", () -> status().startsWith("Page 2 of 3:") && status().endsWith("No archive was saved."));
        assertTrue(status(), status().contains(detail));
        assertEquals(0, requests(BLUE).length());
        noArchive();
    }

    private void cancel(String action) throws Exception {
        chapter("slow");
        startArchive();
        waitFor("worker entered slow response", () -> requests("/slow-page").length() == 1);
        assertTrue("Worker must own a stage before the cancelling action", hasStage());
        switch (action) {
            case "close": tapBounds(controlNode("btn_close_comic_dialog"), false); break;
            case "navigate": navigate("/comic/ok"); break;
            case "background": shell("input keyevent KEYCODE_HOME"); break;
            default: fail("Unknown synthetic action");
        }
        waitFor("comic dialog retired", () -> ui(() -> activityField("comicDownloadDialog", ComicDownloadDialog.class) == null));
        noStage();
        assertEquals(0, requests(BLUE).length());
        noArchive();
    }

    private View verificationView() throws Exception {
        controlNode("btn_captcha_done");
        return ui(() -> {
            ComicDownloadDialog comic = activityField("comicDownloadDialog", ComicDownloadDialog.class);
            Field running = ComicDownloadDialog.class.getDeclaredField("running");
            running.setAccessible(true);
            Object attempt = running.get(comic);
            Field field = attempt.getClass().getDeclaredField("challenge");
            field.setAccessible(true);
            Dialog challenge = (Dialog) field.get(attempt);
            FrameLayout container = challenge.findViewById(R.id.captcha_webview_container);
            assertEquals(1, container.getChildCount());
            return container.getChildAt(0);
        });
    }

    private boolean hasStage() {
        String[] stages = app.getCacheDir().list((dir, name) -> name.startsWith("comic-") && name.endsWith(".part"));
        return stages != null && stages.length != 0;
    }

    private void noStage() throws Exception { waitFor("comic stage removed after I/O drain", 14000, () -> !hasStage()); }

    private void noArchive() throws Exception {
        noStage();
        var folder = directory.resolve("Comics").toFile();
        assertTrue("A refused attempt left comic output", !folder.exists() || folder.list().length == 0);
        assertEquals(0, db.countDownloads());
    }
}

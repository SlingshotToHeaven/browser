package com.privacy.browser;

import android.app.AlertDialog;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebBackForwardList;
import android.webkit.WebViewRenderProcess;
import android.widget.EditText;

import com.privacy.browser.settings.SettingsActivity;
import com.privacy.downloader.db.DownloadDbHelper;

import org.junit.Test;

import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public final class BrowserRecoveryCiTest extends BrowserRecoveryCiSupport {
    private static final String ONE = A + "/session/one", TWO = A + "/session/two", HELD = A + "/session/held";
    private static final String SIBLING = A + "/renderer/sibling", RENDERER = A + "/renderer/active";

    /** Host kills this process without finishing the Activity or granting an onPause checkpoint. */
    @Test public void stagePendingNavigation() throws Exception {
        open(A + "/session/login", ONE);
        assertEquals("yes", js("document.body.dataset.signed"));
        TabModel original = ui(this::selected);
        ui(() -> { load(original, TWO); return null; });
        waitPage(TWO);
        assertEquals("Committed page two", js("document.querySelector('h1').textContent"));
        String id = original.getId();
        waitCheckpoint(id, TWO);
        assertTrue(ui(() -> original.getWebView().canGoBack()));
        ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        waitPage(ONE);
        checkHistoryAtOne();
        waitCheckpoint(id, ONE);
        ui(() -> {
            CookieManager.getInstance().flush();
            load(original, HELD);
            return null;
        });
        waitFor("pending document header held by fixture", 7000, () -> count("a GET /session/held") == 1
                && ui(() -> HELD.equals(original.navigation.target()) && !original.navigation.ready() && ONE.equals(original.getUrl())));
        observe("pending navigation held; ready for host force-stop");
        SystemClock.sleep(30_000);
        fail("Host did not force-stop the live pending navigation");
    }

    @Test public void verifyPendingNavigationRecovery() throws Exception {
        int previousPid = Integer.parseInt(arg("cut_pid"));
        assertNotEquals("Expected a fresh process", previousPid, Process.myPid());
        launch(main()); // ordinary launcher intent: no URL override or synthetic recovery state
        waitPage(ONE);
        assertEquals(arg("cut_selected"), ui(() -> selected().getId()));
        checkHistoryAtOne();
        click(R.id.btn_menu);
        tap("menu_nav_forward", null);
        waitPage(TWO);
        ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        waitPage(ONE);
        checkHistoryAtOne();
        int signedBefore = count("a GET /session/one signed");
        click(R.id.btn_reload);
        waitFor("fresh signed HTTP response after restart", 10_000,
                () -> count("a GET /session/one signed") > signedBefore);
        waitPage(ONE);
        assertEquals("yes", js("document.body.dataset.signed"));
        assertEquals(1, count("a GET /session/held"));
        observe("same saved tab; restored Back index and Forward entry; traversed both; fresh HTTP login survived; no replay of pending URL");
    }

    private void waitCheckpoint(String id, String url) throws Exception {
        waitFor("native state for the committed URL published on disk: " + url, 8000, () -> {
            TabSession.Index saved = index();
            return id.equals(saved.selected) && saved.tabs.stream().anyMatch(row -> id.equals(row.id) && url.equals(row.url)
                    && row.state != null && Files.isRegularFile(sessionDir().resolve(row.filename())));
        });
    }

    private void checkHistoryAtOne() throws Exception {
        ui(() -> {
            WebBackForwardList entries = selected().getWebView().copyBackForwardList();
            int current = entries.getCurrentIndex();
            assertTrue("No Forward history after a committed Back", current >= 0 && current + 1 < entries.getSize());
            assertEquals(ONE, entries.getItemAtIndex(current).getUrl());
            assertEquals(TWO, entries.getItemAtIndex(current + 1).getUrl());
            assertTrue(selected().getWebView().canGoForward());
            return null;
        });
    }

    @Test public void backgroundCommitDoesNotOverwriteForegroundEdit() throws Exception {
        open(ONE, ONE);
        TabModel foreground = ui(this::selected);
        TabModel background = newTab(HELD);
        waitFor("actual pending background fixture request", 7000, () -> count("a GET /session/held") == 1);
        ui(() -> { tabs().switchTab(tabs().getTabs().indexOf(foreground)); return null; });
        waitPage(ONE);
        ui(() -> {
            EditText address = activity.findViewById(R.id.edit_url);
            assertTrue(address.requestFocus());
            address.setText("Synthetic foreground draft");
            return null;
        });
        assertEquals("released", fixture("/control/release"));
        waitFor("background WebView really committed and finished", 9000, () -> ui(() -> ready(background, HELD)));
        ui(() -> {
            EditText address = activity.findViewById(R.id.edit_url);
            assertSame(foreground, selected());
            assertTrue(address.hasFocus());
            assertEquals("Synthetic foreground draft", address.getText().toString());
            assertEquals(View.GONE, activity.findViewById(R.id.page_progress).getVisibility());
            assertTrue(activity.findViewById(R.id.btn_site_security).isEnabled());
            return null;
        });
        observe("a held second tab committed in the background; foreground selection, draft, progress and security control stayed put");
    }

    @Test public void restoreRendererAndRejectOldNativeConfirmation() throws Exception {
        SettingsActivity.setFloatingDownloadButtonEnabled(app, true);
        open(SIBLING, SIBLING);
        TabModel sibling = ui(this::selected);
        TabModel owner = newTab(RENDERER);
        waitPage(RENDERER);
        PrivacyWebView dead = ui(owner::getWebView);
        TabNavigation.Page retired = ui(() -> owner.navigation.page());
        WebViewRenderProcess process = ui(dead::getWebViewRenderProcess);
        assumeTrue("Separate WebView renderer not available", process != null);
        long rows = DownloadDbHelper.getInstance(app).countDownloads();
        AlertDialog old = offerMedia(retired, 1);
        View oldConfirm = ui(() -> old.findViewById(com.privacy.downloader.R.id.prompt_btn_download));
        assertNotNull(oldConfirm);
        assertTrue(ui(oldConfirm::isEnabled));
        assumeTrue("WebView renderer could not be terminated", ui(process::terminate));
        waitFor("native renderer loss detached the original view and its confirmation", 12_000, () -> ui(() -> owner.getWebView() == null
                && owner.navigation.rendererLost() && !showing("mediaDownloadPrompt") && !oldConfirm.isAttachedToWindow()));
        observe("actual WebView renderer terminated and original page/view detached");
        click(R.id.btn_reload);
        waitPage(RENDERER);
        assertEquals("Renderer active fixture", js("document.querySelector('h1').textContent"));
        assertEquals(2, count("a GET /renderer/active"));
        TabNavigation.Page replacement = ui(() -> owner.navigation.page());
        AlertDialog fresh = offerMedia(replacement, 2); // positive control; leave the new prompt unconfirmed
        ui(() -> {
            assertSame(owner, selected());
            assertFalse(activity.isFinishing());
            assertFalse(activity.isDestroyed());
            assertNotSame(dead, owner.getWebView());
            assertNotSame(retired, replacement);
            assertFalse(owner.navigation.owns(dead));
            assertFalse(retired.media.isObserving());
            assertTrue("Old native click listener not exercised", oldConfirm.performClick());
            assertSame("Old action affected the new prompt", fresh, field("mediaDownloadPrompt", AlertDialog.class));
            assertTrue(fresh.isShowing());
            fresh.findViewById(com.privacy.downloader.R.id.prompt_btn_cancel).performClick();
            return null;
        });
        ins.waitForIdleSync();
        assertEquals(rows, DownloadDbHelper.getInstance(app).countDownloads());
        assertEquals(0, count("a GET /renderer/clip.mp4"));
        boolean siblingLost = ui(() -> {
            tabs().switchTab(tabs().getTabs().indexOf(sibling));
            return sibling.navigation.rendererLost();
        });
        if (siblingLost) tap(null, "Restore tab");
        waitPage(SIBLING);
        assertSame(sibling, ui(this::selected));
        observe("same activity and tab restored; fresh prompt worked; detached confirmation could not change it or queue; sibling "
                + (siblingLost ? "also required restoration" : "remained usable"));
    }

    private AlertDialog offerMedia(TabNavigation.Page page, int expectedProbes) throws Exception {
        waitFor("media discovery available on the selected view", 7000, () -> ui(page.media::isObserving));
        click(R.id.fab_video_download);
        waitFor("real options dialog", 11_000, () -> ui(() -> showing("videoQualityDialog")));
        assertEquals("Fresh metadata probe for this page", expectedProbes, count("a HEAD /renderer/clip.mp4"));
        assertTrue(ui(() -> field("videoQualityDialog", AlertDialog.class).findViewById(R.id.dialog_btn_download).performClick()));
        waitFor("real page-owned download confirmation", 7000, () -> ui(() -> showing("mediaDownloadPrompt")));
        return ui(() -> field("mediaDownloadPrompt", AlertDialog.class));
    }

    private boolean showing(String name) throws Exception {
        AlertDialog dialog = field(name, AlertDialog.class);
        return dialog != null && dialog.isShowing();
    }
}

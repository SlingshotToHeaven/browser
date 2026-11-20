package com.privacy.browser;

import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.webkit.WebViewFeature;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.engine.DownloadEngine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Targeted follow-ups. No real profile, account, or download transport is needed. */
public final class BrowserFollowupCiTest extends BrowserRecoveryCiSupport {
    private static final String FRAME = "http://127.0.0.1:8027";
    private static final String CHILD = "http://127.0.0.1:8028/offer";
    private static final String STARTUP = "http://127.0.0.1:8014";

    @Test public void coldPrivateIntentRefused() throws Exception {
        launch(privateIntent());
        refusal();
        waitPage(InternalHome.URL);
        unchanged(ui(this::selected), InternalHome.URL);
        observe("cold external private URL refused; ordinary Home only and no target request");
    }

    @Test public void warmPrivateIntentPreservesRegularPage() throws Exception {
        String committed = A + "/session/one";
        open(A + "/session/login", committed);
        assertEquals("yes", js("document.body.dataset.signed"));
        TabModel previous = ui(this::selected);
        observe("warm control: signed-in regular tab committed");
        // Deliver through Android's ActivityManager to the singleTask Activity, not a
        // nested am start -W inside instrumentation and not a direct onNewIntent call.
        ui(() -> { app.startActivity(privateIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return null; });
        refusal();
        unchanged(previous, committed);
        assertTrue(ui(() -> CookieManager.getInstance().getCookie(committed)).contains("recovery_fixture=1"));
        observe("warm private intent refused without changing the existing tab or its cookie");
    }

    @Test public void tabPickerPrivateActionRefused() throws Exception {
        launch(main());
        waitPage(InternalHome.URL);
        TabModel previous = ui(this::selected);
        click(R.id.btn_tabs);
        tap("tabs_btn_new_incognito", null);
        refusal();
        unchanged(previous, InternalHome.URL);
        observe("native tab-picker private action refused without admitting another tab");
    }

    @Test public void bundledHomePrivateActionRefused() throws Exception {
        assumeTrue("Home actions need an origin-aware message listener", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
        launch(main());
        waitPage(InternalHome.URL);
        TabModel previous = ui(this::selected);
        waitFor("bundled Home bound its native action endpoint", 8000, () ->
                Boolean.TRUE.equals(js("!!(window.PrivacyHome && window.__privacyHomeToken)")));
        tapWeb("[onclick=\"openIncognito()\"]");
        refusal();
        unchanged(previous, InternalHome.URL);
        observe("trusted tap on bundled Home's private action reached native refusal; no new tab");
    }

    @Test public void foreignFrameBlobIsNotAdmittedButSamePageCanOfferOneAtTopLevel() throws Exception {
        assumeTrue("Provider lacks origin-aware messaging", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
        Path output = Files.createTempDirectory(app.getFilesDir().toPath(), "frame-followup-");
        DownloadEngine.getInstance(app).setDefaultDownloadDirectory(output.toString());
        DownloadDbHelper db = DownloadDbHelper.getInstance(app);
        long rows = db.countDownloads();

        // Use the exact same local attachment page for the positive control. Cancel
        // before any queue admission; this does not test file transport again.
        open(CHILD, CHILD);
        waitFor("top-level fixture anchor ready", 6000, () -> frameCount("ready-top") == 1);
        tapWeb("#offer");
        waitFor("genuine top-level touch and click", 7000, () -> frameCount("down-top") > 0 && frameCount("tap-top") == 1);
        tap("prompt_btn_cancel", null);
        waitPage(CHILD);
        observe("trusted tap on the attachment as a top-level page opened the real confirmation; cancelled before queueing");
        assertEquals(rows, db.countDownloads());

        ui(() -> { load(selected(), FRAME + "/parent"); return null; });
        waitPage(FRAME + "/parent");
        waitFor("foreign origin created its synthetic attachment anchor", 7000, () -> frameCount("ready-frame") == 1);
        tapWeb("#child");
        waitFor("fixture server saw trusted input and the actual link click from the child", 7000,
                () -> frameCount("down-frame") > 0 && frameCount("tap-frame") == 1);
        long end = SystemClock.uptimeMillis() + 1200;
        do {
            assertNull("Foreign frame opened a native download prompt", node("prompt_btn_download", null));
            assertEquals("Foreign frame changed the download queue", rows, db.countDownloads());
            assertFalse(ui(() -> selected().hasPageTransfer()));
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < end);
        waitPage(FRAME + "/parent");
        try (var files = Files.list(output)) { assertEquals("Unexpected output or staging file", 0, files.count()); }
        observe("child-origin server acknowledged trusted input and click; no confirmation, job, page transfer, or output");
    }

    @Test public void coldLaunchOnlyLoadsSelectedThenExplicitlySelectedTab() throws Exception {
        List<TabSession.Row> seeded = seedStartup(false);
        launch(main());
        waitPage(STARTUP + "/tab/30");
        startupInventory(seeded, 29, 1);
        observe("first committed selection and one live view verified before switching to row 7");
        ui(() -> { tabs().switchTab(6); return null; });
        waitPage(STARTUP + "/tab/7");
        startupInventory(seeded, 6, 2);
        observe("30 URL-only rows restored; only the selected view created until another saved row was selected");
    }

    @Test public void missingNativeStateFallsBackToSelectedUrlWithoutLoadingOthers() throws Exception {
        List<TabSession.Row> seeded = seedStartup(true);
        launch(main());
        waitPage(STARTUP + "/tab/30");
        startupInventory(seeded, 29, 1);
        assertEquals("PERF-02 tab 30", js("document.querySelector('h1').textContent"));
        assertFalse("Missing state must not borrow another history stack", ui(() -> selected().getWebView().canGoBack()));
        observe("missing saved state file fell back to the selected URL, with no other history or tab loads");
    }

    @Test public void incomingLinkWinsBeforeSavedTabCanNavigate() throws Exception {
        List<TabSession.Row> seeded = seedStartup(false);
        open(STARTUP + "/incoming", STARTUP + "/incoming");
        startupInventory(seeded, 29, 1);
        assertEquals("PERF-02 incoming link", js("document.querySelector('h1').textContent"));
        observe("admitted launch link took precedence over the saved selection's URL; dormant rows were retained");
    }

    private List<TabSession.Row> seedStartup(boolean missingState) throws Exception {
        assertTrue("Only seed a new disposable profile", index().tabs.isEmpty());
        List<TabSession.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) rows.add(new TabSession.Row(UUID.randomUUID().toString(), STARTUP + "/tab/" + i,
                "Unopened fixture tab " + i, false, false, i == 30 && missingState ? UUID.randomUUID().toString() : null));
        Files.createDirectories(sessionDir());
        Files.write(sessionDir().resolve("index.json"), TabSession.encode(new TabSession.Index(rows, rows.get(29).id))
                .getBytes(StandardCharsets.UTF_8));
        if (missingState) assertFalse(Files.exists(sessionDir().resolve(rows.get(29).filename())));
        return rows;
    }

    private void startupInventory(List<TabSession.Row> rows, int selectedIndex, int live) throws Exception {
        ui(() -> {
            assertEquals(30, tabs().getTabCount());
            assertEquals(30, rows.size());
            for (int i = 0; i < rows.size(); i++) assertEquals(rows.get(i).id, tabs().getTabs().get(i).getId());
            assertEquals(rows.get(selectedIndex).id, selected().getId());
            assertEquals(live, tabs().getTabs().stream().filter(tab -> tab.getWebView() != null).count());
            return null;
        });
    }

    private Intent privateIntent() {
        return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(A + "/private-denied"), app, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_IN_INCOGNITO, true).putExtra(MainActivity.EXTRA_LOAD_URL, A + "/private-denied");
    }

    private void refusal() throws Exception {
        waitNode(null, "Private tabs unavailable");
        assertTrue(waitNode("android:id/message", null).getText().toString().contains("nothing was deleted automatically"));
        tap("android:id/button2", "Close");
    }

    private void unchanged(TabModel previous, String url) throws Exception {
        waitPage(url);
        ui(() -> {
            assertEquals(1, tabs().getTabCount());
            assertSame(previous, selected());
            assertFalse(selected().isIncognito());
            assertFalse(activity.getIntent().getBooleanExtra(MainActivity.EXTRA_OPEN_IN_INCOGNITO, false));
            return null;
        });
        assertEquals(0, count("a GET /private-denied"));
        assertEquals(0, DownloadDbHelper.getInstance(app).countDownloads());
    }

    private int frameCount(String name) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(FRAME + "/counts").openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(1500);
        try (InputStream stream = connection.getInputStream()) {
            return new JSONObject(new String(stream.readNBytes(4096), StandardCharsets.UTF_8)).optInt(name);
        } finally { connection.disconnect(); }
    }

    /** Real Android touch, after scrolling/validating the WebView hit target; never JS click. */
    private void tapWeb(String selector) throws Exception {
        WebView view = ui(() -> selected().getWebView());
        String match = "document.querySelector(" + JSONObject.quote(selector) + ")";
        assertEquals(true, js(view, match + ".scrollIntoView({block:'center',inline:'center'}); true"));
        CountDownLatch done = new CountDownLatch(1);
        ui(() -> {
            view.postVisualStateCallback(1, new WebView.VisualStateCallback() {
                @Override public void onComplete(long id) { done.countDown(); }
            });
            return null;
        });
        assertTrue("No WebView visual milestone", done.await(6, TimeUnit.SECONDS));
        JSONArray hit = (JSONArray) js(view, "(()=>{const e=" + match + ",r=e.getBoundingClientRect(),v=visualViewport;"
                + "const x=r.left+r.width/2,y=r.top+r.height/2,s=devicePixelRatio*v.scale;"
                + "const node=document.elementFromPoint(x,y),style=getComputedStyle(e);"
                + "return [e===node||e.contains(node),(x-v.offsetLeft)*s,(y-v.offsetTop)*s,"
                + "r.width,r.height,style.display,style.visibility,(node||{}).tagName||'none']})()");
        assertTrue("Tap point was covered or off-screen: " + hit, hit.getBoolean(0));
        int[] screen = ui(() -> {
            int x = (int) Math.round(hit.getDouble(1)), y = (int) Math.round(hit.getDouble(2));
            Rect rect = new Rect();
            assertTrue(view.getLocalVisibleRect(rect) && rect.contains(x + view.getScrollX(), y + view.getScrollY()));
            int[] offset = new int[2];
            view.getLocationOnScreen(offset);
            return new int[]{offset[0] + x, offset[1] + y};
        });
        long since = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(since, SystemClock.uptimeMillis(), action, screen[0], screen[1], 0);
            try { event.setSource(InputDevice.SOURCE_TOUCHSCREEN); ins.sendPointerSync(event); }
            finally { event.recycle(); }
        }
    }
}

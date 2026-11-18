package com.privacy.browser;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewFeature;

import com.privacy.browser.comic.ComicManager;
import com.privacy.browser.settings.SettingsActivity;
import com.privacy.downloader.db.DownloadDbHelper;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Run only on a disposable debug profile with tests/fixtures/media_discovery.py and adb reverse tcp:8016 tcp:8016. */
public class MediaDiscoveryDeviceTest {
    private static final String BASE = "http://127.0.0.1:8016";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity activity;
    private boolean video, comics;

    @Before public void prepare() throws Exception {
        video = SettingsActivity.isFloatingDownloadButtonEnabled(context);
        comics = SettingsActivity.isComicDownloaderEnabled(context);
        SettingsActivity.setFloatingDownloadButtonEnabled(context, true);
        SettingsActivity.setComicDownloaderEnabled(context, true);
        fixture("/reset");
    }

    @After public void restore() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        SettingsActivity.setFloatingDownloadButtonEnabled(context, video);
        SettingsActivity.setComicDownloaderEnabled(context, comics);
    }

    @Test public void directAndPlaylistOptionsAreExplicitAnonymousAndDoNotRepeatOnRefresh() throws Exception {
        launch("/options");
        JSONObject before = stats();
        assertEquals(0, before.optInt("HEAD /f/Clip.mp4"));
        assertEquals(0, before.optInt("HEAD /f/Range.mp4"));
        tapOptions();
        assertTrue("Expected format picker", await(() -> showing("videoQualityDialog")));
        JSONObject opened = stats();
        assertEquals(1, opened.optInt("HEAD /f/Clip.mp4"));
        assertEquals(1, opened.optInt("HEAD /f/Range.mp4"));
        assertEquals(before.optInt("GET /f/Range.mp4 (range)") + 1, opened.optInt("GET /f/Range.mp4 (range)"));
        assertEquals(before.optInt("GET /f/Hls/Master.m3u8") + 1, opened.optInt("GET /f/Hls/Master.m3u8"));
        assertEquals(0, opened.optInt("GET /f/Hls/Low.m3u8"));
        assertEquals(0, opened.optInt("GET /f/Hls/High.m3u8"));
        for (String hdr : new String[]{"Cookie", "Authorization", "Referer"}) {
            assertEquals(0, opened.optInt("HEAD /f/Clip.mp4 [" + hdr + " present]"));
            assertEquals(before.optInt("GET /f/Hls/Master.m3u8 [" + hdr + " present]"),
                    opened.optInt("GET /f/Hls/Master.m3u8 [" + hdr + " present]"));
        }
        AlertDialog initial = ui(() -> dialog("videoQualityDialog"));
        ui(() -> {
            LinearLayout formats = initial.findViewById(R.id.dialog_quality_container);
            assertEquals(5, formats.getChildCount()); // direct clip, range clip, master and two variants
            int variants = 0;
            for (int i = 0; i < formats.getChildCount(); i++) {
                TextView label = formats.getChildAt(i).findViewById(R.id.item_quality_label);
                String quality = label.getText().toString();
                if (quality.contains("360p") || quality.contains("720p")) variants++;
            }
            assertEquals(2, variants);
            assertTrue(initial.findViewById(R.id.dialog_btn_refresh).performClick());
            return null;
        });
        assertTrue(await(() -> showing("videoQualityDialog") && dialog("videoQualityDialog") != initial));
        assertEquals(opened.toString(), stats().toString());

        // Keep a reference to the detached confirmation button to exercise the tap guard, too.
        assertTrue(ui(() -> dialog("videoQualityDialog").findViewById(R.id.dialog_btn_download).performClick()));
        assertTrue(await(() -> showing("mediaDownloadPrompt")));
        View stale = ui(() -> dialog("mediaDownloadPrompt").findViewById(com.privacy.downloader.R.id.prompt_btn_download));
        long queuedBefore = DownloadDbHelper.getInstance(context).countDownloads();
        changeRoute("/after-options");
        assertTrue(await(() -> current("/after-options") && !showing("mediaDownloadPrompt")));
        ui(stale::performClick);
        assertEquals(queuedBefore, DownloadDbHelper.getInstance(context).countDownloads());
    }

    @Test public void spaCommitDuringMetadataRetiresWorkBeforeAnotherProbeOrDialog() throws Exception {
        launch("/slow");
        TabNavigation.Page page = ui(() -> selected().navigation.page());
        tapOptions();
        long expires = SystemClock.uptimeMillis() + 1300;
        while (stats().optInt("HEAD /f/hold.mp4") == 0 && SystemClock.uptimeMillis() < expires) SystemClock.sleep(20);
        assertEquals(1, stats().optInt("HEAD /f/hold.mp4"));
        changeRoute("/ordinary");
        assertTrue(await(() -> current("/ordinary") && !page.media.isObserving()));
        SystemClock.sleep(2000); // Allow the pending connection's idle timeout to elapse.
        assertEquals(0, stats().optInt("HEAD /f/next.mp4"));
        assertEquals(0, page.media.count());
        assertFalse(ui(() -> showing("videoQualityDialog") || field("mediaOptions", Object.class) != null));
    }

    @Test public void enabledSwitchesGatePerDocumentWorkAndOrderedReaderPlans() throws Exception {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
        launch("/reader");
        TabNavigation.Page page = ui(() -> selected().navigation.page());
        assertTrue(await(() -> ComicManager.getDetectedComic(page.key) != null));
        ComicManager.ComicItem plan = ComicManager.getDetectedComic(page.key);
        assertEquals(3, plan.imageUrls.size());
        assertEquals(plan.imageUrls.get(0), plan.imageUrls.get(2));
        assertTrue(page.media.isObserving());
        SettingsActivity.setComicDownloaderEnabled(context, false);
        SettingsActivity.setFloatingDownloadButtonEnabled(context, false);
        assertTrue(await(() -> !page.media.isObserving() && ComicManager.getDetectedComic(page.key) == null
                && activity.findViewById(R.id.fab_video_download).getVisibility() == View.GONE));
        assertEquals(0, stats().length());
        SettingsActivity.setComicDownloaderEnabled(context, true);
        SettingsActivity.setFloatingDownloadButtonEnabled(context, true);
        assertTrue(await(() -> page.media.isObserving() && ComicManager.getDetectedComic(page.key) != null));
        assertEquals(plan.imageUrls, ComicManager.getDetectedComic(page.key).imageUrls);
    }

    private void launch(String path) throws Exception {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_LOAD_URL, BASE + path).putExtra(MainActivity.EXTRA_OPEN_IN_NEW_TAB, true);
        activity = (MainActivity) instrumentation.startActivitySync(intent);
        assertTrue("Check the disposable profile, fixture and adb reverse", await(() -> current(path)
                && selected().navigation.finished() && activity.hasWindowFocus()));
    }

    private boolean current(String path) {
        TabModel tab = selected();
        return tab != null && tab.navigation.ready() && BASE.concat(path).equals(tab.getUrl());
    }

    private void changeRoute(String path) throws Exception {
        ui(() -> {
            selected().getWebView().evaluateJavascript("history.pushState({},''," + JSONObject.quote(path) + ");", null);
            return null;
        });
    }

    private void tapOptions() throws Exception {
        assertTrue(ui(() -> activity.findViewById(R.id.fab_video_download).performClick()));
    }

    private JSONObject stats() throws Exception { return new JSONObject(fixture("/stats")); }

    private String fixture(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(1500);
        try (InputStream data = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[1024];
            for (int n; (n = data.read(block)) != -1;) {
                bytes.write(block, 0, n);
                assertTrue(bytes.size() <= 8192);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }

    private TabModel selected() {
        TabsManager tabs = field("tabsManager", TabsManager.class);
        return tabs == null ? null : tabs.getActiveTab();
    }
    private AlertDialog dialog(String name) { return field(name, AlertDialog.class); }
    private boolean showing(String name) { AlertDialog d = dialog(name); return d != null && d.isShowing(); }

    private <T> T field(String name, Class<T> type) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(activity));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private boolean await(Supplier<Boolean> ready) throws Exception {
        long expires = SystemClock.uptimeMillis() + 15000;
        do {
            if (ui(ready)) return true;
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < expires);
        return false;
    }

    private <T> T ui(Supplier<T> operation) throws Exception {
        FutureTask<T> result = new FutureTask<>(operation::get);
        instrumentation.runOnMainSync(result);
        return result.get();
    }
}

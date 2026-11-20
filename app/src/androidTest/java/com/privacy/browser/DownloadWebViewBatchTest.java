package com.privacy.browser;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;

import androidx.webkit.WebViewFeature;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.privacy.downloader.model.DownloadStatus.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Synthetic pages in the real browser; runs only in the batch's disposable AVD. */
public class DownloadWebViewBatchTest extends DownloadBrowserBatchSupport {
    private static final String BLOB = "/blob_download.html";
    private static final String BLOB_SHA = "3906393794f65f3ce67995cc639367320caa7f1c8554e6f7f6603948e1fb3bbf";
    private static final int BLOB_BYTES = 262147;
    private static final int CSV_BYTES = 118796;
    private static final String CSV_SHA = "14474f4a1a4ba155fd15dd82d2ec168c219e85094a4e2f87e72b806179cf92ba";

    @Test public void realLongPressKeepsScopeOnlyUntilAForeignHop() throws Exception {
        open("/start?query=not-a-referer");
        longPressDownload("/session/local.csv", "local.csv");
        checkWire("/session/local.csv", 8024, true, 206, 206);
        longPressDownload("/session/redirect.csv", "redirect.csv");
        checkWire("/session/redirect.csv", 8024, true, 302);
        checkWire("/session/foreign.csv", 8025, false, 206, 206);
        longPressDownload("/session/round-trip.csv", "round-trip.csv");
        checkWire("/session/round-trip.csv", 8024, true, 302);
        checkWire("/session/bounce.csv", 8025, false, 302);
        checkWire("/session/return.csv", 8024, false, 206, 206);
        evidence("scoped probe and body; foreign and returned origin anonymous; three exact CSVs");
    }

    @Test public void externalShareLinkIsAnonymous() throws Exception {
        open("/start?query=not-a-referer"); // Cookie exists for target path, but this handoff is unbound.
        assertTrue(shell("am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "
                + BASE + "/session/external.bin -n com.browser/com.privacy.browser.MainActivity").contains("Status: ok"));
        confirm("external.bin");
        completed(bind("external.bin"), CSV_BYTES, CSV_SHA);
        checkWire("/session/external.bin", 8024, false, 206, 206);
        evidence("external ACTION_SEND from shell UID; both real HTTP requests anonymous; exact fixture bytes");
    }

    @Test public void privateIntentNeverNavigatesOrDownloads() throws Exception {
        open("/start");
        long count = db.countDownloads();
        String active = ui(() -> selected().getUrl());
        int tabs = ui(() -> tabs().getTabs().size());
        assertTrue(shell("am start -W -a android.intent.action.VIEW -d " + BASE + "/range/private-refused.bin --ez "
                + MainActivity.EXTRA_OPEN_IN_INCOGNITO + " true -n com.browser/com.privacy.browser.MainActivity").contains("Status: ok"));
        // Require the actual user-facing refusal, not merely lack of a request before intent dispatch.
        waitNode(null, "Private tabs unavailable");
        assertEquals(active, ui(() -> selected().getUrl()));
        assertEquals(tabs, ui(() -> tabs().getTabs().size()).intValue());
        assertEquals(count, db.countDownloads());
        assertEquals(0, requests("/range/private-refused.bin").length());
        assertNull(node("prompt_btn_download", null));
        evidence("actual private-intent refusal without new tab, prompt, row or fetch");
    }

    @Test public void dismissBeforeFetchAndQueuedAdmission() throws Exception {
        blob("normal");
        long count = db.countDownloads();
        tapDom("document.querySelector('#download')", false);
        assertTrue(controlNode("prompt_btn_cancel").performAction(AccessibilityNodeInfo.ACTION_CLICK));
        waitFor("blob prompt dismissed", () -> node("prompt_btn_download", null) == null);
        assertEquals(0, counter("status"));
        assertEquals(count, db.countDownloads());
        assertEquals(0, directory.toFile().list().length);
        constraints(false, false, 2, true, 0, 0);
        String id = acceptBlob("queued-blob.bin");
        state(id, WAITING_FOR_SCHEDULE);
        assertEquals(0, counter("status"));
        assertEquals(0, directory.toFile().list().length);
        constraints(false, false, 2, false, 0, 0);
        completed(id, BLOB_BYTES, BLOB_SHA);
        assertEquals(1, counter("status"));
        evidence("dismiss and wait had no fetch or stage; exactly one fetch after admission; sha256=" + BLOB_SHA);
    }

    @Test public void cancellationDrainsTheBlobAndTheNextOfferIsIndependent() throws Exception {
        blob("stall");
        String id = acceptBlob("cancelled-blob.bin");
        firstBlobChunk();
        engine.cancel(id);
        blobRejected(id, CANCELLED, null);
        waitFor("blob page lease released", () -> !ui(() -> selected().hasPageTransfer()));
        navigate(BLOB);
        completed(acceptBlob("after-cancel-blob.bin"), BLOB_BYTES, BLOB_SHA);
        blobRejected(id, CANCELLED, null);
        assertEquals(1, counter("status"));
    }

    @Test public void sameAddressReloadCannotCompleteOldJob() throws Exception {
        blob("stall");
        String id = acceptBlob("reload-blob.bin");
        firstBlobChunk();
        TabNavigation.Page old = ui(() -> selected().navigation.page());
        ui(() -> { selected().getWebView().reload(); return null; });
        waitFor("new same-URL document committed", () -> ui(() -> ready(BLOB) && selected().navigation.page() != old));
        blobRejected(id, FAILED, "original page");
        assertEquals(0, counter("status"));
    }

    @Test public void closingTheSourceTabRevokesTheRunningRead() throws Exception {
        blob("stall");
        String id = acceptBlob("closed-blob.bin");
        firstBlobChunk();
        TabModel original = ui(this::selected);
        ui(() -> { tabs().closeTab(tabs().getActiveIndex(), activity); return null; });
        waitFor("original source tab removed", () -> ui(() -> !tabs().getTabs().contains(original)));
        blobRejected(id, FAILED, "original page");
    }

    @Test public void shortBlobRejectsAndCleansUp() throws Exception {
        blob("short");
        String id = acceptBlob("short-blob.bin");
        blobRejected(id, FAILED, null);
        String reason = db.getDownload(id).getErrorMessage();
        // The actual page script may reject a declared short body before the native
        // reader could see an end frame. Either way no file may be published.
        assertTrue(reason, reason != null && (reason.contains("before all expected bytes")
                || reason.contains("The page could not read the blob")));
        assertEquals(5, counter("chunks"));
        assertEquals(1, counter("status"));
    }

    @Test public void realIdleTimeoutCleansUpStalledBlob() throws Exception {
        blob("stall");
        String id = acceptBlob("timeout-blob.bin");
        firstBlobChunk();
        waitFor("normal Android blob idle timeout", 44000, () -> db.getDownload(id).getStatus() == FAILED);
        blobRejected(id, FAILED, "timed out");
    }

    @Test public void occupiedBlobReservationIsNotOverwrittenOrFetched() throws Exception {
        blob("normal");
        constraints(false, false, 2, true, 0, 0);
        String id = acceptBlob("occupied-blob.bin");
        DownloadItem waiting = state(id, WAITING_FOR_SCHEDULE);
        var path = Paths.get(waiting.getDestinationPath());
        byte[] sentinel = "independent fixture".getBytes(StandardCharsets.US_ASCII);
        Files.write(path, sentinel, StandardOpenOption.CREATE_NEW);
        constraints(false, false, 2, false, 0, 0);
        DownloadItem failed = state(id, FAILED);
        drain();
        assertTrue(failed.getErrorMessage().contains("destination is occupied"));
        assertNull(db.getPublishedDownload(id));
        assertEquals(0, counter("status"));
        assertArrayEquals(sentinel, Files.readAllBytes(path));
        assertEquals(1, directory.toFile().list().length);
    }

    @Test public void crossOriginFrameCannotUseTheMainPageBlobIntake() throws Exception {
        supportedBlob();
        open("/frames");
        long count = db.countDownloads();
        waitFor("foreign frame button loaded", () -> Boolean.TRUE.equals(js("!!window.fixtureFrame")));
        var view = ui(() -> selected().getWebView());
        CountDownLatch rendered = new CountDownLatch(1);
        ui(() -> {
            view.postVisualStateCallback(1, new WebView.VisualStateCallback() {
                @Override public void onComplete(long id) { rendered.countDown(); }
            });
            return null;
        });
        assertTrue("Foreign frame not ready to draw", rendered.await(6, TimeUnit.SECONDS));
        AccessibilityNodeInfo button = node(null, "Request frame blob");
        if (button != null) tapBounds(button, false);
        else tapDom("document.querySelector('iframe')", false);
        waitFor("trusted pointer reached the foreign fixture frame", 4000, () -> Boolean.TRUE.equals(js("!!window.fixtureFramePointer")));
        waitFor("trusted click attempted a foreign frame download", 5000, () -> Boolean.TRUE.equals(js("!!window.fixtureFrameTapped")));
        SystemClock.sleep(700);
        assertNull(node("prompt_btn_download", null));
        assertEquals(count, db.countDownloads());
        assertEquals(0, directory.toFile().list().length);
        evidence("trusted foreign-frame click observed by the fixture; no prompt, row, or output");
    }

    public static void assertCall(JSONObject call, int port, boolean scoped, int status, boolean body) throws Exception {
        assertEquals(port, call.getInt("port"));
        assertEquals("GET", call.getString("method"));
        assertEquals(status, call.getInt("code"));
        assertEquals(scoped, call.getBoolean("fixture_cookie"));
        assertEquals(scoped, call.getBoolean("cookie"));
        assertEquals(scoped, call.getBoolean("origin_only"));
        assertEquals(scoped, call.getBoolean("referer"));
        assertFalse(call.getBoolean("auth"));
        assertEquals(0, call.getInt("start"));
        assertEquals(body, call.getBoolean("conditional"));
        assertEquals(body ? CSV_BYTES - 1 : 0, call.getInt("end"));
    }

    private void checkWire(String route, int port, boolean scoped, int... statuses) throws Exception {
        JSONArray log = requests(route);
        assertEquals(route, statuses.length, log.length());
        for (int i = 0; i < statuses.length; i++) assertCall(log.getJSONObject(i), port, scoped, statuses[i], i == 1);
    }

    private void longPressDownload(String href, String name) throws Exception {
        tapDom("document.querySelector('a[href=" + JSONObject.quote(href) + "]')", true);
        AccessibilityNodeInfo action = waitNode(null, "Download Link with Manager");
        tapBounds(action, false);
        confirm(name);
        completed(bind(name), CSV_BYTES, CSV_SHA);
    }

    private int counter(String id) throws Exception {
        return ((Number) js("Number(document.querySelector('#" + id + "').textContent.split(': ')[1])")).intValue();
    }

    private void supportedBlob() { assumeTrue("Provider has no origin-aware message listener", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)); }

    private void blob(String mode) throws Exception {
        supportedBlob();
        open(BLOB);
        // Fixture controls the source body behavior, never authorizes or starts a download.
        js("document.querySelector('#mode').value=" + JSONObject.quote(mode));
    }

    private String acceptBlob(String name) throws Exception {
        tapDom("document.querySelector('#download')", false);
        confirm(name);
        return bind(name);
    }

    private void firstBlobChunk() throws Exception { waitFor("first real Blob stream chunk", () -> counter("chunks") == 1); }

    private void blobRejected(String id, DownloadStatus expected, String reason) throws Exception {
        DownloadItem row = state(id, expected);
        drain();
        if (reason != null) assertTrue(row.getErrorMessage(), row.getErrorMessage() != null && row.getErrorMessage().contains(reason));
        notPublished(row);
        assertFalse("An aborted blob left a stage", java.util.Arrays.stream(directory.toFile().list()).anyMatch(n -> n.endsWith(".part")));
    }

    private void confirm(String name) throws Exception {
        AccessibilityNodeInfo edit = controlNode("prompt_edit_filename");
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name);
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text));
        assertEquals(name, controlNode("prompt_edit_filename").getText().toString());
        AccessibilityNodeInfo one = node("prompt_pill_thread_1", null);
        if (one != null) tapBounds(one, false);
        tapBounds(controlNode("prompt_btn_download"), false);
    }

}

package com.privacy.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Android ports: history/Parcel work on the WebView owner, file and cookie writes off-main. */
final class NativeTabSession {
    private static final String PREFS = "browser_session";
    private static final String LEGACY_TABS = "saved_tabs";
    private static final String LEGACY_SELECTION = "active_tab_idx";
    private static final String TAG = "TabSession";
    private static TabSessionWriter journal; // initialized by the startup worker, owner leases stay on main
    // Retain only the newest queued selection while a read is in flight.
    private static final Executor reads = new ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> new Thread(task, "browser-session-read"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    // One running boundary and one trailing stop. Don't drop a newer stop during the active flush.
    private static final Executor cookies = new ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> new Thread(task, "browser-cookie-flush"),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private NativeTabSession() {}

    static synchronized TabSessionWriter journal(Context context) {
        if (journal != null) return journal;
        Context app = context.getApplicationContext();
        TabSessionFiles files = new TabSessionFiles(new File(app.getFilesDir(), "saved_tabs").toPath(),
                new TabSessionFiles.Legacy() {
                    @Override public TabSession.Index read() throws org.json.JSONException {
                        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                        return TabSession.fromLegacy(prefs.getString(LEGACY_TABS, null), prefs.getInt(LEGACY_SELECTION, 0));
                    }

                    @Override public boolean clear() {
                        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .remove(LEGACY_TABS).remove(LEGACY_SELECTION).commit();
                    }
                }, NativeTabSession::warn);
        Executor disk = worker("browser-session-io");
        journal = new TabSessionWriter(files, task -> disk.execute(() -> {
            Trace.beginSection("Browser.session.publish");
            try { task.run(); } finally { Trace.endSection(); }
        }), NativeTabSession::warn);
        return journal;
    }

    static void readState(TabSessionWriter writer, Object owner, TabSession.Row row, Consumer<TabSession.StateRead> done) {
        Handler main = new Handler(Looper.getMainLooper());
        reads.execute(() -> {
            Trace.beginSection("Browser.session.readBlob");
            try {
                TabSession.StateRead result = writer.readState(owner, row);
                main.post(() -> done.accept(result));
            } finally { Trace.endSection(); }
        });
    }

    static TabSessionCheckpoint.Scheduler scheduler() {
        Handler main = new Handler(Looper.getMainLooper());
        return new TabSessionCheckpoint.Scheduler() {
            @Override public long now() { return SystemClock.uptimeMillis(); }
            @Override public void postAt(Runnable task, long uptime) { main.postAtTime(task, uptime); }
            @Override public void cancel(Runnable task) { main.removeCallbacks(task); }
        };
    }

    static TabSessionCheckpoint.History history(WebView view, String url) {
        Trace.beginSection("Browser.session.probe");
        try { return shape(view.copyBackForwardList(), url); }
        catch (RuntimeException unavailable) { return null; }
        finally { Trace.endSection(); }
    }

    /** Null is a transient failure; an empty array means the snapshot exceeded the parcel limit. */
    static byte[] capture(WebView view, String url, TabSessionCheckpoint.History expected) {
        Trace.beginSection("Browser.session.capture");
        try {
            Bundle bundle = new Bundle();
            if (!expected.equals(shape(view.saveState(bundle), url))) return null;
            Parcel parcel = Parcel.obtain();
            try {
                bundle.writeToParcel(parcel, 0);
                return parcel.dataSize() <= TabSession.MAX_STATE ? parcel.marshall() : new byte[0];
            } finally { parcel.recycle(); }
        } catch (RuntimeException unavailable) {
            warn("Native tab state could not be captured");
            return null;
        } finally { Trace.endSection(); }
    }

    static TabSessionCheckpoint.History restore(WebView view, byte[] bytes, String url) {
        if (bytes == null || bytes.length == 0 || bytes.length > TabSession.MAX_STATE) return null;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            return shape(view.restoreState(Bundle.CREATOR.createFromParcel(parcel)), url);
        } catch (RuntimeException unavailable) {
            warn("Native tab state could not be restored");
            return null;
        } finally { parcel.recycle(); }
    }

    private static TabSessionCheckpoint.History shape(WebBackForwardList list, String expectedUrl) {
        if (list == null || list.getCurrentIndex() < 0 || list.getCurrentIndex() >= list.getSize()) return null;
        WebHistoryItem current = list.getCurrentItem();
        if (current == null || !expectedUrl.equals(current.getUrl())) return null;
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < list.getSize(); i++) {
            WebHistoryItem item = list.getItemAtIndex(i);
            if (item == null) return null;
            entries.add(item.getUrl());
            entries.add(item.getOriginalUrl());
        }
        return new TabSessionCheckpoint.History(list.getCurrentIndex(), entries);
    }

    /** Keep an explicit background boundary, not a flush on navigation, selection or pause. */
    static void flushCookiesAfterStop() {
        cookies.execute(() -> {
            Trace.beginSection("Browser.cookies.stopFlush");
            try { CookieManager.getInstance().flush(); }
            catch (RuntimeException failure) { warn("WebView cookie flush could not be confirmed"); }
            finally { Trace.endSection(); }
        });
    }

    private static Executor worker(String name) {
        return new ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> new Thread(task, name));
    }

    private static void warn(String message) { Log.w(TAG, message); }
}

package com.privacy.browser;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real MainActivity on one disposable CI profile; probes observe, not replace, browser logic. */
public abstract class BrowserRecoveryCiSupport {
    protected static final String A = "http://127.0.0.1:8026";
    protected static final String B = "http://localhost:8026";
    protected static final String COMPONENT = "com.browser/com.privacy.browser.MainActivity";
    protected final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
    protected final Context app = ins.getTargetContext();
    protected MainActivity activity;

    @Before public void requireDisposableProfile() throws Exception {
        assumeTrue("Only the disposable browser CI AVD", "ci-avd".equals(arg("browser_recovery")));
        assumeTrue("Not an emulator", "1".equals(shell("getprop ro.kernel.qemu")) || "1".equals(shell("getprop ro.boot.qemu")));
        assertEquals("com.browser", app.getPackageName());
        var automation = ins.getUiAutomation();
        AccessibilityServiceInfo flags = automation.getServiceInfo();
        flags.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(flags);
    }

    @After public void finishActivity() throws Exception {
        if (activity != null) ui(() -> { activity.finish(); return null; });
    }

    protected static String arg(String key) { return InstrumentationRegistry.getArguments().getString(key); }

    protected void launch(Intent intent) {
        activity = (MainActivity) ins.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    protected Intent main() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(app, MainActivity.class);
    }

    protected void open(String requested, String committed) throws Exception {
        launch(main().putExtra(MainActivity.EXTRA_LOAD_URL, requested));
        waitPage(committed);
    }

    protected void waitPage(String url) throws Exception {
        waitFor("committed fixture " + url, 16_000, () -> ui(() -> ready(selected(), url) && activity.hasWindowFocus()));
    }

    protected static boolean ready(TabModel tab, String url) {
        return tab != null && tab.getWebView() != null && url.equals(tab.getUrl()) && url.equals(tab.getWebView().getUrl())
                && tab.navigation.ready() && tab.navigation.finished();
    }

    protected TabsManager tabs() throws Exception { return field("tabsManager", TabsManager.class); }
    protected TabModel selected() throws Exception { TabsManager tabs = tabs(); return tabs == null ? null : tabs.getActiveTab(); }

    protected <T> T field(String name, Class<T> type) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(activity));
    }

    protected void load(TabModel tab, String url) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("loadInTab", TabModel.class, String.class);
        method.setAccessible(true);
        method.invoke(activity, tab, url);
    }

    protected TabModel newTab(String url) throws Exception {
        return ui(() -> { TabModel tab = tabs().createTab(activity, url); load(tab, url); return tab; });
    }

    protected void click(int id) throws Exception {
        assertTrue("Visible browser control did not accept click: " + id, ui(() -> {
            View view = activity.findViewById(id);
            return view != null && view.isShown() && view.isEnabled() && view.performClick();
        }));
    }

    protected AccessibilityNodeInfo node(String id, String text) {
        AccessibilityNodeInfo root = ins.getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> candidates = id == null ? root.findAccessibilityNodeInfosByText(text)
                : root.findAccessibilityNodeInfosByViewId(id.contains(":") ? id : "com.browser:id/" + id);
        for (AccessibilityNodeInfo entry : candidates) {
            CharSequence value = entry.getText();
            if (entry.isVisibleToUser() && "com.browser".contentEquals(entry.getPackageName())
                    && (text == null || (value != null && text.equalsIgnoreCase(value.toString())))) return entry;
        }
        return null;
    }

    protected AccessibilityNodeInfo waitNode(String id, String text) throws Exception {
        AccessibilityNodeInfo[] result = {null};
        waitFor("visible UI " + (id == null ? text : id), 9000, () -> (result[0] = node(id, text)) != null);
        return result[0];
    }

    protected void tap(String id, String text) throws Exception {
        AccessibilityNodeInfo control = waitNode(id, text);
        assertTrue("Control is disabled", control.isEnabled());
        Rect bounds = new Rect();
        control.getBoundsInScreen(bounds);
        assertFalse("Control has no visible bounds", bounds.isEmpty());
        long when = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(when, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0);
            try { event.setSource(InputDevice.SOURCE_TOUCHSCREEN); ins.sendPointerSync(event); }
            finally { event.recycle(); }
        }
    }

    protected Object js(WebView view, String script) throws Exception {
        CompletableFuture<String> value = new CompletableFuture<>();
        ui(() -> { view.evaluateJavascript(script, value::complete); return null; });
        return new JSONTokener(value.get(5, TimeUnit.SECONDS)).nextValue();
    }

    protected Object js(String script) throws Exception { return js(ui(() -> selected().getWebView()), script); }

    protected Path sessionDir() { return app.getFilesDir().toPath().resolve("saved_tabs"); }

    protected TabSession.Index index() throws Exception {
        Path path = sessionDir().resolve("index.json");
        return Files.isRegularFile(path) ? TabSession.decode(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)) : TabSession.EMPTY;
    }

    protected String fixture(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(A + path).openConnection();
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(1500);
        try (InputStream in = conn.getInputStream()) {
            byte[] bytes = in.readNBytes(8193);
            assertTrue("Oversized control reply", bytes.length <= 8192);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally { conn.disconnect(); }
    }

    protected int count(String route) throws Exception { return new JSONObject(fixture("/control/counts")).optInt(route); }

    protected String shell(String command) throws Exception {
        try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.getUiAutomation().executeShellCommand(command))) {
            return new String(in.readNBytes(8192), StandardCharsets.UTF_8).trim();
        }
    }

    protected void observe(String message) {
        Bundle status = new Bundle();
        status.putString("browser_recovery_evidence", message);
        ins.sendStatus(3, status);
    }

    protected static void waitFor(String condition, long millis, Callable<Boolean> check) throws Exception {
        long end = SystemClock.uptimeMillis() + millis;
        do {
            if (check.call()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < end);
        fail("Timed out: " + condition);
    }

    protected <T> T ui(Callable<T> operation) throws Exception {
        FutureTask<T> result = new FutureTask<>(operation);
        ins.runOnMainSync(result);
        return result.get(6, TimeUnit.SECONDS);
    }
}

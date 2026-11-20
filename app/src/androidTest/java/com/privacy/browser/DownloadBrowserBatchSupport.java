package com.privacy.browser;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import com.privacy.browser.downloads.DownloadBatchSupport;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Coordinates Android UI and the real committed WebView on a synthetic fixture. */
public abstract class DownloadBrowserBatchSupport extends DownloadBatchSupport {
    protected void open(String path) throws Exception {
        UiAutomation automation = ins.getUiAutomation();
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        activity = ins.startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_LOAD_URL, BASE + path).putExtra(MainActivity.EXTRA_OPEN_IN_NEW_TAB, true));
        waitFor("focused committed fixture page " + path, () -> ui(() -> ready(path) && activity.hasWindowFocus()));
    }

    protected void navigate(String path) throws Exception {
        TabNavigation.Page previous = ui(() -> selected().navigation.page());
        ui(() -> { selected().getWebView().loadUrl(BASE + path); return null; });
        waitFor("new committed fixture page " + path, () -> ui(() -> ready(path) && selected().navigation.page() != previous));
    }

    protected boolean ready(String path) throws Exception {
        TabModel tab = selected();
        return tab != null && tab.navigation.ready() && tab.navigation.finished() && (BASE + path).equals(tab.getUrl());
    }

    protected TabsManager tabs() throws Exception { return activityField("tabsManager", TabsManager.class); }
    protected TabModel selected() throws Exception { TabsManager tabs = tabs(); return tabs == null ? null : tabs.getActiveTab(); }

    protected <T> T activityField(String name, Class<T> type) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(activity));
    }

    protected Object js(String script) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        ui(() -> { selected().getWebView().evaluateJavascript(script, result::complete); return null; });
        return new JSONTokener(result.get(5, TimeUnit.SECONDS)).nextValue();
    }

    protected AccessibilityNodeInfo controlNode(String id) throws Exception { return waitNode(id, null); }

    protected AccessibilityNodeInfo waitNode(String id, String text) throws Exception {
        AccessibilityNodeInfo[] found = {null};
        waitFor("UI control " + (id == null ? text : id), () -> (found[0] = node(id, text)) != null);
        return found[0];
    }

    protected AccessibilityNodeInfo node(String id, String text) {
        AccessibilityNodeInfo root = ins.getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        var choices = id != null ? root.findAccessibilityNodeInfosByViewId("com.browser:id/" + id) : root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo candidate : choices) {
            if (candidate.isVisibleToUser() && candidate.isEnabled() && "com.browser".contentEquals(candidate.getPackageName())
                    && (text == null || text.contentEquals(candidate.getText() == null ? "" : candidate.getText()))) return candidate;
        }
        return null;
    }

    protected void tapBounds(AccessibilityNodeInfo control, boolean hold) {
        Rect bounds = new Rect();
        control.getBoundsInScreen(bounds);
        assertFalse(bounds.isEmpty());
        touch(bounds.centerX(), bounds.centerY(), hold);
    }

    protected void tapDom(String expression, boolean hold) throws Exception { tapDom(expression, hold, false); }

    protected void tapDom(String expression, boolean hold, boolean frame) throws Exception {
        String center = frame ? "[r.left+window.fixtureFrame[0],r.top+window.fixtureFrame[1],devicePixelRatio]"
                : "[r.left+r.width/2,r.top+r.height/2,devicePixelRatio]";
        JSONArray rectangle = (JSONArray) js("(()=>{let a=" + expression + ";a.scrollIntoView({block:'center'});let r=a.getBoundingClientRect();return " + center + ";})()");
        int[] at = ui(() -> { int[] offset = new int[2]; selected().getWebView().getLocationOnScreen(offset); return offset; });
        touch(at[0] + (float) (rectangle.getDouble(0) * rectangle.getDouble(2)),
                at[1] + (float) (rectangle.getDouble(1) * rectangle.getDouble(2)), hold);
    }

    private void touch(float x, float y, boolean hold) {
        long since = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(since, since, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        ins.sendPointerSync(down);
        down.recycle();
        if (hold) SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 250);
        MotionEvent up = MotionEvent.obtain(since, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        ins.sendPointerSync(up);
        up.recycle();
    }
}

package com.privacy.browser;

import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.privacy.downloader.net.RequestContext;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.function.Predicate;

/** Per-view, per-commit message endpoints. Page messages never publish URL/title or navigation state. */
final class TabPageMessages {
    private static final String HOME = "PrivacyHome";
    private static final String PAGE = "PrivacyWebPage";

    interface Receiver {
        void receive(TabNavigation.Page page, String kind, JSONObject payload, JavaScriptReplyProxy reply);
    }

    private final WebView view;
    private final Predicate<TabNavigation.Page> current;
    private final Receiver receiver;
    private final boolean homeSupported;
    private final boolean pageSupported;
    private PageMessageGate gate;
    private TabNavigation.Page page;
    private String url;
    private volatile String homeDelivery;

    TabPageMessages(WebView view, boolean regular, Predicate<TabNavigation.Page> current, Receiver receiver) {
        this.view = view;
        this.current = current;
        this.receiver = receiver;
        homeSupported = regular && attach(HOME, InternalHome.ORIGIN, true);
        pageSupported = regular && attach(PAGE, "*", false);
    }

    private boolean attach(String name, String origin, boolean home) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false;
        try {
            WebViewCompat.addWebMessageListener(view, name, Collections.singleton(origin),
                    (v, message, source, main, reply) -> onMessage(home, v, message, source, main, reply));
            return true;
        } catch (RuntimeException e) {
            return false; // No frame-blind JavaScriptInterface fallback.
        }
    }

    String issueHomeDelivery() {
        String proof = java.util.UUID.randomUUID().toString();
        homeDelivery = proof;
        return proof;
    }

    void invalidate() {
        page = null;
        gate = null;
        url = null;
    }

    void bind(TabNavigation.Page page, String url) {
        invalidate();
        boolean home = InternalHome.isHome(url);
        String origin = home ? InternalHome.ORIGIN : RequestContext.origin(url);
        if (origin == null || (home ? !homeSupported : !pageSupported || InternalHome.ownsHost(url))) return;
        this.page = page;
        this.url = url;
        this.gate = new PageMessageGate(origin);
        if (home) {
            activateHome();
        } else {
            evaluate("const bridge=window." + PAGE + "; if(!bridge)return;"
                    + "const token=" + JSONObject.quote(gate.token) + ";"
                    + "window.__privacyPageReport=function(kind,value,picker){"
                    + "bridge.postMessage(JSON.stringify({token,kind,value,picker}));};");
        }
    }

    void activateHome() {
        if (gate != null && InternalHome.isHome(url)) {
            evaluate("window.__privacyHomeToken=" + JSONObject.quote(gate.token) + ";"
                    + "if(window.__privacyHomeReady)window.__privacyHomeReady();");
        }
    }

    boolean hasPageEndpoint() { return gate != null && !InternalHome.isHome(url); }

    String armPicker(String kind) {
        return gate != null && !InternalHome.isHome(url) && current.test(page)
                ? gate.armPicker(kind, SystemClock.uptimeMillis()) : null;
    }

    void cancelPicker() {
        if (gate != null) gate.cancelPicker();
    }

    void evaluate(String body) {
        if (page != null && current.test(page)) {
            view.evaluateJavascript("(function(){if(location.href!==" + JSONObject.quote(url) + ")return;"
                    + body + "\n})();", null);
        }
    }

    private void onMessage(boolean home, WebView sourceView, WebMessageCompat message, Uri sourceOrigin,
                           boolean mainFrame, JavaScriptReplyProxy reply) {
        if (sourceView != view || gate == null || !mainFrame || sourceOrigin == null || !current.test(page)) return;
        String source = sourceOrigin.toString();
        if (home != InternalHome.isHome(url) || (home && !InternalHome.allowsMessage(mainFrame, source, url))) return;
        // String messages only. ArrayBuffer data must not be coerced or parsed as a string.
        if (message.getType() != WebMessageCompat.TYPE_STRING) return;
        String data = message.getData();
        if (!gate.allowsFrame(mainFrame, source, data) || (home && data.length() > 512)) return;
        long now = SystemClock.uptimeMillis();
        if (!gate.admitsPayload(now) || !PageMessageGate.shallowObject(data)) return;
        try {
            JSONObject input = new JSONObject(data);
            if (!gate.token.equals(input.optString("token"))) return;
            String kind = input.getString("kind");
            if (home) {
                String proof = homeDelivery;
                if (proof == null || !proof.equals(input.optString("delivery"))) return;
                receiver.receive(page, "home:" + kind, input, reply);
            } else if ("comic".equals(kind) || "comic-manual".equals(kind)) {
                receiver.receive(page, kind, input, reply);
            } else if (("element".equals(kind) || "image".equals(kind))
                    && gate.consumePicker(kind, input.optString("picker"), now)) {
                receiver.receive(page, kind, input, reply);
            }
        } catch (JSONException ignored) {
            // Treat every page payload as untrusted input.
        }
    }

    void close(boolean live) {
        invalidate();
        homeDelivery = null;
        if (live) {
            if (homeSupported) remove(HOME);
            if (pageSupported) remove(PAGE);
        }
    }

    private void remove(String name) {
        try { WebViewCompat.removeWebMessageListener(view, name); }
        catch (RuntimeException ignored) {}
    }
}

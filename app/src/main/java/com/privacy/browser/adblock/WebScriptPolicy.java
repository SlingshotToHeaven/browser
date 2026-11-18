package com.privacy.browser.adblock;

import android.content.Context;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewFeature;

import com.privacy.browser.FingerprintDefenseManager;
import com.privacy.browser.InternalHome;
import com.privacy.browser.adblock.rules.UserFiltersManager;
import com.privacy.downloader.net.RequestContext;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

/** One policy and set of document-start registrations per WebView, owned by the UI thread. */
public final class WebScriptPolicy {
    private static final AtomicLong REVISION = new AtomicLong();
    private final WebView owner;
    private final AdBlockManager manager;
    private final long installedRevision;
    private ScriptHandler fingerprint;
    private ScriptHandler youtube;
    private boolean closed;

    public WebScriptPolicy(WebView view) {
        this(view, true);
    }

    // A test can force the same path used by providers without document-start support.
    WebScriptPolicy(WebView view, boolean allowDocumentStart) {
        owner = view;
        manager = AdBlockManager.getInstance(view.getContext());
        installedRevision = REVISION.get();
        // Framework settings apply to the whole view. Set this before any navigation,
        // including native restore, POST, redirects and child-frame loads.
        boolean enabled = !manager.hasNoScriptRules();
        owner.getSettings().setJavaScriptEnabled(enabled);
        if (enabled && allowDocumentStart && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            fingerprint = FingerprintDefenseManager.attach(view.getContext(), view);
            youtube = YouTubeAdDefuser.register(view, manager);
        }
    }

    /** The same conservative fallback for secondary views that load network pages. */
    public static boolean javaScriptEnabled(Context context) {
        return !AdBlockManager.getInstance(context).hasNoScriptRules();
    }

    public static boolean allowsPageJavaScript(WebView view) {
        return view != null && javaScriptEnabled(view.getContext()) && view.getSettings().getJavaScriptEnabled();
    }

    public static void changed() { REVISION.incrementAndGet(); }

    public static long revision() { return REVISION.get(); }

    public boolean isCurrent() { return !closed && installedRevision == REVISION.get(); }

    public boolean allowsScripts(WebView view) {
        return owner == view && isCurrent() && allowsPageJavaScript(owner);
    }

    private boolean allowsAdBlockScripts(String url) {
        return allowsScripts(owner) && RequestContext.origin(url) != null && !InternalHome.ownsHost(url)
                && url.equals(owner.getUrl()) && manager.isAdBlockActiveForSite(url);
    }

    /** Caller verifies that this is the tab's current, committed main document. */
    public void onCommittedPage(String url) {
        if (!allowsAdBlockScripts(url)) return;
        if (youtube == null) YouTubeAdDefuser.injectOnCommittedPage(owner, url, manager);
        UserScriptManager.getInstance(owner.getContext()).injectOnPage(owner, url, UserScriptManager.RunAt.DOCUMENT_START);
    }

    /** Caller verifies that the finish event belongs to that same current document. */
    public void onFinishedPage(String url) {
        if (!allowsAdBlockScripts(url)) return;
        boolean cosmetic = manager.isSiteCosmeticEnabled(url);
        AntiAdblockDefuser.inject(owner, url, cosmetic, manager.isAntiAdblockDefuserEnabled());
        if (cosmetic) {
            String css = UserFiltersManager.getInstance(owner.getContext())
                    .getCosmeticCssForDomain(android.net.Uri.parse(url).getHost());
            if (!css.isEmpty()) {
                owner.evaluateJavascript(InternalHome.guardExternalPage(url,
                        "if(!document.getElementById('__user_ublock_filters')){"
                                + "const root=document.head||document.documentElement;if(!root)return;"
                                + "const style=document.createElement('style');style.id='__user_ublock_filters';"
                                + "style.textContent=" + JSONObject.quote(css) + ";root.appendChild(style);}"), null);
            }
        }
        if (manager.isSitePopupsBlocked(url)) PopupClickDefuser.inject(owner, url);
        UserScriptManager.getInstance(owner.getContext()).injectOnPage(owner, url, UserScriptManager.RunAt.DOCUMENT_END);
    }

    public void close(boolean live) {
        if (closed) return;
        closed = true;
        if (live) {
            remove(fingerprint);
            remove(youtube);
        }
        fingerprint = null;
        youtube = null;
    }

    private static void remove(ScriptHandler registration) {
        if (registration == null) return;
        try { registration.remove(); }
        catch (RuntimeException ignored) {} // the owning WebView is destroyed after close
    }
}

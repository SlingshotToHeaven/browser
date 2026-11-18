package com.privacy.browser;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Manages tab lifecycles, memory reclamation, and active tab transitions.
 */
public class TabsManager {

    public interface TabChangeListener {
        void onTabSwitched(TabModel tab);
        void onTabCountChanged(int count);
        default void onTabClosed(TabModel tab) {}
    }

    interface ViewFactory { PrivacyWebView create(Context context); }

    private final List<TabModel> tabs = new ArrayList<>();
    private final ViewFactory views;
    private long lastUse;
    private int activeIndex = -1;
    private TabChangeListener listener;

    public TabsManager() { this(PrivacyWebView::createTabView); }

    TabsManager(ViewFactory views) { this.views = views; }

    void touchView(TabModel tab) {
        if (tab.getWebView() != null) tab.lastLiveUse = ++lastUse;
    }

    TabModel evictionTarget(int inactiveLimit, Predicate<TabModel> pinned) {
        return TabViewBudget.victim(tabs, getActiveTab(), tab -> tab.getWebView() != null, pinned,
                tab -> tab.lastLiveUse, inactiveLimit);
    }

    public void setListener(TabChangeListener listener) {
        this.listener = listener;
    }

    public TabModel createTab(@NonNull Context context, @NonNull String url) {
        return createTab(context, url, false);
    }

    public TabModel createTab(@NonNull Context context, @NonNull String url, boolean isIncognito) {
        PrivateTabPolicy.requireAvailable(isIncognito);
        PrivacyWebView webView = views.create(context);
        TabModel tab = new TabModel(webView, url);
        tab.setIncognito(isIncognito);
        if (isIncognito) {
            tab.setTitle("Private Tab");
            webView.getSettings().setSaveFormData(false);
        }
        return addTab(tab, false);
    }

    TabModel queueBackgroundTab(String url, boolean isIncognito) {
        PrivateTabPolicy.requireAvailable(isIncognito);
        // A queued URL must round-trip in the session format; don't silently queue Home instead.
        if (url == null || !url.equals(TabSession.recoveryUrl(url))) return null;
        TabSession.Row row = new TabSession.Row(UUID.randomUUID().toString(), url, null, false, false, null);
        return addTab(new TabModel(row), true);
    }

    /** Install the complete regular-tab index without creating views or firing selection callbacks. */
    void restoreIndex(TabSession.Index index) {
        if (!tabs.isEmpty()) throw new IllegalStateException("Tab index already installed");
        for (TabSession.Row row : index.tabs) {
            if (row.id.equals(index.selected)) activeIndex = tabs.size();
            tabs.add(new TabModel(row));
        }
    }

    private TabModel addTab(TabModel tab, boolean inBackground) {
        tabs.add(tab);
        touchView(tab);
        if (!inBackground) {
            activeIndex = tabs.size() - 1;
        }

        if (listener != null) {
            if (!inBackground) {
                listener.onTabSwitched(tab);
            }
            listener.onTabCountChanged(tabs.size());
        }
        return tab;
    }

    public void switchTab(int index) {
        if (index >= 0 && index < tabs.size()) {
            activeIndex = index;
            touchView(tabs.get(index));
            if (listener != null) {
                listener.onTabSwitched(tabs.get(activeIndex));
            }
        }
    }

    public void closeTab(int index, @NonNull Context context) {
        if (index < 0 || index >= tabs.size()) return;

        TabModel previouslySelected = getActiveTab();
        close(tabs.remove(index));

        if (tabs.isEmpty()) {
            openReplacementHome(context);
            return;
        }
        activeIndex = tabs.indexOf(previouslySelected);
        if (activeIndex < 0) activeIndex = Math.min(index, tabs.size() - 1);
        TabModel selected = tabs.get(activeIndex);
        if (listener != null) {
            if (selected != previouslySelected) {
                touchView(selected);
                listener.onTabSwitched(selected);
            }
            listener.onTabCountChanged(tabs.size());
        }
    }

    public void closeAllTabs(@NonNull Context context) {
        for (TabModel tab : tabs) close(tab);
        tabs.clear();
        activeIndex = -1;
        openReplacementHome(context);
    }

    private void close(TabModel tab) {
        if (listener != null) listener.onTabClosed(tab);
        tab.destroy();
    }

    private void openReplacementHome(Context context) {
        TabModel home = createTab(context, InternalHome.URL);
        home.getWebView().loadUrl(InternalHome.URL);
    }

    /** Activity destruction must not notify listeners or create a replacement tab. */
    public void dispose() {
        listener = null;
        for (TabModel tab : tabs) tab.destroy();
        tabs.clear();
        activeIndex = -1;
    }

    @Nullable
    public TabModel getActiveTab() {
        if (activeIndex >= 0 && activeIndex < tabs.size()) {
            return tabs.get(activeIndex);
        }
        return null;
    }

    public List<TabModel> getTabs() {
        return Collections.unmodifiableList(tabs);
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public int getTabCount() {
        return tabs.size();
    }
}

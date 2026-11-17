package com.privacy.browser;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages tab lifecycles, memory reclamation, and active tab transitions.
 */
public class TabsManager {

    public interface TabChangeListener {
        void onTabSwitched(TabModel tab);
        void onTabCountChanged(int count);
    }

    private final List<TabModel> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private TabChangeListener listener;

    public void setListener(TabChangeListener listener) {
        this.listener = listener;
    }

    public TabModel createTab(@NonNull Context context, @NonNull String url) {
        return createTab(context, url, false);
    }

    public TabModel createTab(@NonNull Context context, @NonNull String url, boolean isIncognito) {
        return createTab(context, url, isIncognito, false);
    }

    public TabModel createTab(@NonNull Context context, @NonNull String url, boolean isIncognito, boolean inBackground) {
        PrivacyWebView webView = new PrivacyWebView(context);
        TabModel tab = new TabModel(webView, url);
        tab.setIncognito(isIncognito);
        if (isIncognito) {
            tab.setTitle("Private Tab");
            webView.getSettings().setSaveFormData(false);
        }
        tabs.add(tab);
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
            if (listener != null) {
                listener.onTabSwitched(tabs.get(activeIndex));
            }
        }
    }

    public void closeTab(int index, @NonNull Context context) {
        if (index < 0 || index >= tabs.size()) return;

        TabModel closingTab = tabs.remove(index);
        closingTab.destroy();

        if (tabs.isEmpty()) {
            // Automatically spawn a fresh blank home tab if all closed
            createTab(context, MainActivity.DEFAULT_HOME_URL);
            return;
        }

        if (activeIndex >= tabs.size()) {
            activeIndex = tabs.size() - 1;
        } else if (activeIndex > index) {
            activeIndex--;
        }

        if (listener != null) {
            listener.onTabSwitched(tabs.get(activeIndex));
            listener.onTabCountChanged(tabs.size());
        }
    }

    public void closeAllTabs(@NonNull Context context) {
        for (TabModel tab : tabs) {
            tab.destroy();
        }
        tabs.clear();
        activeIndex = -1;
        createTab(context, MainActivity.DEFAULT_HOME_URL);
    }

    @Nullable
    public TabModel getActiveTab() {
        if (activeIndex >= 0 && activeIndex < tabs.size()) {
            return tabs.get(activeIndex);
        }
        return null;
    }

    public List<TabModel> getTabs() {
        return tabs;
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public int getTabCount() {
        return tabs.size();
    }
}

package com.privacy.browser.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebStorage;
import android.webkit.WebView;

import com.privacy.browser.MainActivity;
import com.privacy.browser.TabModel;
import com.privacy.browser.TabsManager;
import com.privacy.browser.adblock.AdBlockFileManager;
import com.privacy.browser.adblock.AdBlockManager;
import com.privacy.browser.bookmarks.BookmarkDbHelper;
import com.privacy.browser.bookmarks.BookmarkModel;
import com.privacy.browser.history.HistoryDbHelper;
import com.privacy.browser.history.HistoryModel;
import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ToolDispatcher {

    public interface ExecutionCallback {
        void onCompleted(String resultJson);
        void onError(String error);
    }

    private final Context context;
    private final Handler mainHandler;
    private WebView currentWebView;
    private TabsManager tabsManager;
    private MainActivity mainActivity;

    public ToolDispatcher(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setCurrentWebView(WebView webView) {
        this.currentWebView = webView;
    }

    public void setActivityAndTabs(MainActivity activity, TabsManager tabsManager) {
        this.mainActivity = activity;
        this.tabsManager = tabsManager;
    }

    public boolean isConfirmationRequired(String toolName) {
        return "history_delete".equals(toolName)
                || "bookmarks_delete".equals(toolName)
                || "downloads_delete".equals(toolName)
                || "privacy_clear_site_data".equals(toolName)
                || "adblock_save_and_commit".equals(toolName)
                || "automation_schedule_task".equals(toolName)
                || "automation_cancel_task".equals(toolName)
                || "termux_execute_command".equals(toolName);
    }

    public void executeTool(final String name, final JSONObject args, final ExecutionCallback callback) {
        try {
            switch (name) {
                // Group 1: Navigation & Tabs
                case "nav_open_url":
                    handleNavOpenUrl(args, callback);
                    break;
                case "nav_get_active_tab_info":
                    handleNavGetActiveTabInfo(callback);
                    break;
                case "nav_reload_page":
                    handleNavReload(args, callback);
                    break;
                case "nav_go_back_or_forward":
                    handleNavBackForward(args, callback);
                    break;
                case "nav_list_tabs":
                    handleNavListTabs(callback);
                    break;
                case "nav_switch_tab":
                    handleNavSwitchTab(args, callback);
                    break;
                case "nav_close_tab":
                    handleNavCloseTab(args, callback);
                    break;

                // Group 2: DOM Console
                case "dom_click_element":
                    handleDomClick(args, callback);
                    break;
                case "dom_type_text":
                    handleDomType(args, callback);
                    break;
                case "dom_get_page_content":
                    handleDomGetContent(args, callback);
                    break;
                case "dom_scroll_page":
                    handleDomScroll(args, callback);
                    break;
                case "dom_find_elements":
                    handleDomFindElements(args, callback);
                    break;
                case "dom_select_dropdown":
                    handleDomSelectDropdown(args, callback);
                    break;

                // Group 3: Vision
                case "vision_capture_viewport":
                    handleVisionCaptureViewport(args, callback);
                    break;
                case "vision_capture_element":
                    handleVisionCaptureElement(args, callback);
                    break;

                // Group 4: History, Bookmarks & Downloads
                case "history_search":
                    handleHistorySearch(args, callback);
                    break;
                case "history_delete":
                    handleHistoryDelete(args, callback);
                    break;
                case "bookmarks_search":
                    handleBookmarksSearch(args, callback);
                    break;
                case "bookmarks_create":
                    handleBookmarksCreate(args, callback);
                    break;
                case "bookmarks_delete":
                    handleBookmarksDelete(args, callback);
                    break;
                case "downloads_list":
                    handleDownloadsList(args, callback);
                    break;
                case "downloads_delete":
                    handleDownloadsDelete(args, callback);
                    break;

                // Group 5: Storage & Cookies
                case "privacy_get_site_data":
                    handlePrivacyGetSiteData(args, callback);
                    break;
                case "privacy_clear_site_data":
                    handlePrivacyClearSiteData(args, callback);
                    break;

                // Group 6: Adblock Self-Healing
                case "adblock_inspect_page":
                    handleAdblockInspectPage(callback);
                    break;
                case "adblock_test_rule":
                    handleAdblockTestRule(args, callback);
                    break;
                case "adblock_save_and_commit":
                    handleAdblockSaveAndCommit(args, callback);
                    break;

                // Group 7: Timed Automations
                case "automation_schedule_task":
                    handleAutomationSchedule(args, callback);
                    break;
                case "automation_list_tasks":
                    handleAutomationList(callback);
                    break;
                case "automation_cancel_task":
                    handleAutomationCancel(args, callback);
                    break;
                case "termux_execute_command":
                    handleTermuxExecuteCommand(args, callback);
                    break;

                default:
                    callback.onError("Tool '" + name + "' is not implemented or unrecognized.");
                    break;
            }
        } catch (Exception e) {
            callback.onError("Execution failed: " + e.getMessage());
        }
    }

    // --- Navigation & Tab Handlers ---
    private void handleNavOpenUrl(final JSONObject args, final ExecutionCallback callback) {
        final String url = args.optString("url", "");
        final boolean inNewTab = args.optBoolean("in_new_tab", false);
        if (url.isEmpty()) {
            callback.onError("Missing destination URL");
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (inNewTab && tabsManager != null && mainActivity != null) {
                    TabModel tab = tabsManager.createTab(mainActivity, url);
                    tabsManager.switchTab(tabsManager.getTabCount() - 1);
                    callback.onCompleted("{\"status\":\"opened_in_new_tab\",\"url\":\"" + url + "\"}");
                } else if (currentWebView != null) {
                    currentWebView.loadUrl(url);
                    callback.onCompleted("{\"status\":\"success\",\"loaded_url\":\"" + url + "\"}");
                } else {
                    callback.onError("No active WebView available.");
                }
            }
        });
    }

    private void handleNavGetActiveTabInfo(final ExecutionCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentWebView != null) {
                    try {
                        JSONObject info = new JSONObject();
                        info.put("url", currentWebView.getUrl());
                        info.put("title", currentWebView.getTitle());
                        info.put("scroll_y", currentWebView.getScrollY());
                        callback.onCompleted(info.toString());
                    } catch (Exception e) {
                        callback.onError(e.getMessage());
                    }
                } else {
                    callback.onError("No active WebView available.");
                }
            }
        });
    }

    private void handleNavReload(final JSONObject args, final ExecutionCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentWebView != null) {
                    currentWebView.reload();
                    callback.onCompleted("{\"status\":\"reloaded\"}");
                } else {
                    callback.onError("No active WebView available.");
                }
            }
        });
    }

    private void handleNavBackForward(final JSONObject args, final ExecutionCallback callback) {
        final String direction = args.optString("direction", "back");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentWebView != null) {
                    if ("forward".equalsIgnoreCase(direction)) {
                        if (currentWebView.canGoForward()) {
                            currentWebView.goForward();
                            callback.onCompleted("{\"status\":\"navigated_forward\"}");
                        } else {
                            callback.onError("Cannot go forward; end of history.");
                        }
                    } else {
                        if (currentWebView.canGoBack()) {
                            currentWebView.goBack();
                            callback.onCompleted("{\"status\":\"navigated_back\"}");
                        } else {
                            callback.onError("Cannot go back; start of history.");
                        }
                    }
                } else {
                    callback.onError("No active WebView available.");
                }
            }
        });
    }

    private void handleNavListTabs(final ExecutionCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tabsManager != null) {
                    try {
                        JSONArray arr = new JSONArray();
                        List<TabModel> tabs = tabsManager.getTabs();
                        for (int i = 0; i < tabs.size(); i++) {
                            TabModel t = tabs.get(i);
                            JSONObject o = new JSONObject();
                            o.put("index", i);
                            o.put("id", t.getId());
                            o.put("title", t.getTitle());
                            o.put("url", t.getUrl());
                            o.put("is_active", i == tabsManager.getActiveIndex());
                            arr.put(o);
                        }
                        JSONObject res = new JSONObject();
                        res.put("count", tabs.size());
                        res.put("tabs", arr);
                        callback.onCompleted(res.toString());
                    } catch (Exception e) {
                        callback.onError(e.getMessage());
                    }
                } else {
                    callback.onError("TabsManager is not available.");
                }
            }
        });
    }

    private void handleNavSwitchTab(final JSONObject args, final ExecutionCallback callback) {
        final int index = args.optInt("tab_id", -1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tabsManager != null && index >= 0 && index < tabsManager.getTabCount()) {
                    tabsManager.switchTab(index);
                    callback.onCompleted("{\"status\":\"switched_tab\",\"index\":" + index + "}");
                } else {
                    callback.onError("Invalid tab index: " + index);
                }
            }
        });
    }

    private void handleNavCloseTab(final JSONObject args, final ExecutionCallback callback) {
        final int index = args.optInt("tab_id", -1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tabsManager != null && mainActivity != null && index >= 0 && index < tabsManager.getTabCount()) {
                    tabsManager.closeTab(index, mainActivity);
                    callback.onCompleted("{\"status\":\"closed_tab\",\"index\":" + index + "}");
                } else {
                    callback.onError("Invalid tab index to close: " + index);
                }
            }
        });
    }

    // --- DOM Handlers ---
    private void handleDomClick(final JSONObject args, final ExecutionCallback callback) {
        final String selector = args.optString("css_selector", "");
        final int index = args.optInt("index", 0);
        if (selector.isEmpty()) {
            callback.onError("Missing css_selector");
            return;
        }

        final String js = "(function() {"
                + "  var els = document.querySelectorAll('" + selector.replace("'", "\\'") + "');"
                + "  if (!els || els.length <= " + index + ") return JSON.stringify({status:'not_found'});"
                + "  var el = els[" + index + "];"
                + "  el.scrollIntoView({behavior:'smooth', block:'center'});"
                + "  el.click();"
                + "  return JSON.stringify({status:'clicked', tag: el.tagName, text: el.innerText ? el.innerText.substring(0, 50) : ''});"
                + "})();";

        executeJsOnWebView(js, callback);
    }

    private void handleDomType(final JSONObject args, final ExecutionCallback callback) {
        final String selector = args.optString("css_selector", "");
        final String text = args.optString("text", "");
        final boolean clearFirst = args.optBoolean("clear_first", false);
        final boolean pressEnter = args.optBoolean("press_enter", false);

        if (selector.isEmpty()) {
            callback.onError("Missing css_selector");
            return;
        }

        final String js = "(function() {"
                + "  var el = document.querySelector('" + selector.replace("'", "\\'") + "');"
                + "  if (!el) return JSON.stringify({status:'not_found'});"
                + "  el.focus();"
                + (clearFirst ? "el.value = '';" : "")
                + "  el.value = (el.value || '') + '" + text.replace("'", "\\'") + "';"
                + "  el.dispatchEvent(new Event('input', { bubbles: true }));"
                + "  el.dispatchEvent(new Event('change', { bubbles: true }));"
                + (pressEnter ? "el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));" : "")
                + "  return JSON.stringify({status:'typed', current_value: el.value});"
                + "})();";

        executeJsOnWebView(js, callback);
    }

    private void handleDomGetContent(final JSONObject args, final ExecutionCallback callback) {
        final String format = args.optString("format", "text");
        String js = "";
        if ("headings".equals(format)) {
            js = "(function() {"
                    + "  var headings = Array.from(document.querySelectorAll('h1, h2, h3')).map(function(h) { return h.innerText.trim(); });"
                    + "  return JSON.stringify({headings: headings});"
                    + "})();";
        } else if ("links".equals(format)) {
            js = "(function() {"
                    + "  var links = Array.from(document.querySelectorAll('a[href]')).slice(0, 30).map(function(a) { return {text: a.innerText.trim(), href: a.href}; });"
                    + "  return JSON.stringify({links: links});"
                    + "})();";
        } else {
            js = "(function() {"
                    + "  return JSON.stringify({title: document.title, url: window.location.href, text_sample: document.body ? document.body.innerText.substring(0, 500) : ''});"
                    + "})();";
        }
        executeJsOnWebView(js, callback);
    }

    private void handleDomScroll(final JSONObject args, final ExecutionCallback callback) {
        final String dir = args.optString("direction", "down");
        final int px = args.optInt("pixels", 500);

        String js = "";
        if ("top".equals(dir)) {
            js = "window.scrollTo({top: 0, behavior: 'smooth'}); JSON.stringify({status:'scrolled_top'});";
        } else if ("bottom".equals(dir)) {
            js = "window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'}); JSON.stringify({status:'scrolled_bottom'});";
        } else if ("up".equals(dir)) {
            js = "window.scrollBy({top: -" + px + ", behavior: 'smooth'}); JSON.stringify({status:'scrolled_up', pixels: " + px + "});";
        } else {
            js = "window.scrollBy({top: " + px + ", behavior: 'smooth'}); JSON.stringify({status:'scrolled_down', pixels: " + px + "});";
        }
        executeJsOnWebView(js, callback);
    }

    private void handleDomFindElements(final JSONObject args, final ExecutionCallback callback) {
        final String selector = args.optString("css_selector", "");
        if (selector.isEmpty()) {
            callback.onError("Missing css_selector");
            return;
        }

        final String js = "(function() {"
                + "  var els = document.querySelectorAll('" + selector.replace("'", "\\'") + "');"
                + "  var out = [];"
                + "  for (var i = 0; i < Math.min(els.length, 15); i++) {"
                + "    var el = els[i];"
                + "    var rect = el.getBoundingClientRect();"
                + "    out.push({"
                + "      index: i,"
                + "      tag: el.tagName,"
                + "      id: el.id,"
                + "      className: el.className,"
                + "      text: el.innerText ? el.innerText.trim().substring(0, 60) : '',"
                + "      visible: (rect.width > 0 && rect.height > 0)"
                + "    });"
                + "  }"
                + "  return JSON.stringify({count: els.length, elements: out});"
                + "})();";

        executeJsOnWebView(js, callback);
    }

    private void handleDomSelectDropdown(final JSONObject args, final ExecutionCallback callback) {
        final String selector = args.optString("css_selector", "");
        final String option = args.optString("option_value_or_text", "");
        if (selector.isEmpty()) {
            callback.onError("Missing css_selector");
            return;
        }

        final String js = "(function() {"
                + "  var sel = document.querySelector('" + selector.replace("'", "\\'") + "');"
                + "  if (!sel || sel.tagName !== 'SELECT') return JSON.stringify({status:'not_found'});"
                + "  var val = '" + option.replace("'", "\\'") + "';"
                + "  for (var i = 0; i < sel.options.length; i++) {"
                + "    if (sel.options[i].value === val || sel.options[i].text.trim() === val) {"
                + "      sel.selectedIndex = i;"
                + "      sel.dispatchEvent(new Event('change', { bubbles: true }));"
                + "      return JSON.stringify({status:'selected', selected_text: sel.options[i].text});"
                + "    }"
                + "  }"
                + "  return JSON.stringify({status:'option_not_found'});"
                + "})();";

        executeJsOnWebView(js, callback);
    }

    // --- Vision Handlers ---
    private void handleVisionCaptureViewport(JSONObject args, final ExecutionCallback callback) {
        VisionToolExecutor.captureViewportBase64(currentWebView, new VisionToolExecutor.VisionCallback() {
            @Override
            public void onCaptured(String base64Jpeg) {
                callback.onCompleted("{\"status\":\"captured\",\"image_attached\":true,\"bytes\":" + base64Jpeg.length() + "}");
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    private void handleVisionCaptureElement(JSONObject args, final ExecutionCallback callback) {
        String selector = args.optString("css_selector", "");
        VisionToolExecutor.captureElementBase64(currentWebView, selector, new VisionToolExecutor.VisionCallback() {
            @Override
            public void onCaptured(String base64Jpeg) {
                callback.onCompleted("{\"status\":\"element_cropped\",\"image_attached\":true,\"bytes\":" + base64Jpeg.length() + "}");
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    // --- History & Bookmarks Handlers ---
    private void handleHistorySearch(JSONObject args, ExecutionCallback callback) {
        String query = args.optString("query", "");
        int limit = args.optInt("limit", 25);
        HistoryDbHelper db = HistoryDbHelper.getInstance(context);
        List<HistoryModel> results = db.searchHistory(query, limit);

        try {
            JSONArray arr = new JSONArray();
            for (HistoryModel m : results) {
                JSONObject obj = new JSONObject();
                obj.put("id", m.getId());
                obj.put("title", m.getTitle());
                obj.put("url", m.getUrl());
                obj.put("visited_at", m.getVisitedAt());
                arr.put(obj);
            }
            JSONObject res = new JSONObject();
            res.put("count", results.size());
            res.put("items", arr);
            callback.onCompleted(res.toString());
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private void handleHistoryDelete(JSONObject args, ExecutionCallback callback) {
        HistoryDbHelper db = HistoryDbHelper.getInstance(context);
        JSONArray idsArray = args.optJSONArray("ids");
        String queryFilter = args.optString("query_filter", "");

        int deletedCount = 0;
        if (idsArray != null && idsArray.length() > 0) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < idsArray.length(); i++) {
                ids.add(idsArray.optLong(i));
            }
            db.deleteVisits(ids);
            deletedCount = ids.size();
        } else if (!queryFilter.isEmpty()) {
            List<HistoryModel> matches = db.searchHistory(queryFilter, 500);
            List<Long> ids = new ArrayList<>();
            for (HistoryModel m : matches) {
                ids.add(m.getId());
            }
            db.deleteVisits(ids);
            deletedCount = ids.size();
        }

        callback.onCompleted("{\"status\":\"deleted\",\"count\":" + deletedCount + "}");
    }

    private void handleBookmarksSearch(JSONObject args, ExecutionCallback callback) {
        String query = args.optString("query", "");
        BookmarkDbHelper db = BookmarkDbHelper.getInstance(context);
        List<BookmarkModel> results = db.searchBookmarks(query, 50);

        try {
            JSONArray arr = new JSONArray();
            for (BookmarkModel b : results) {
                if (!b.isFolder()) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", b.getId());
                    obj.put("title", b.getTitle());
                    obj.put("url", b.getUrl());
                    arr.put(obj);
                }
            }
            JSONObject res = new JSONObject();
            res.put("count", arr.length());
            res.put("items", arr);
            callback.onCompleted(res.toString());
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private void handleBookmarksCreate(JSONObject args, ExecutionCallback callback) {
        String title = args.optString("title", "");
        String url = args.optString("url", "");
        if (url.isEmpty()) {
            callback.onError("Missing bookmark URL");
            return;
        }
        BookmarkDbHelper db = BookmarkDbHelper.getInstance(context);
        boolean success = db.addBookmark(title, url);
        callback.onCompleted("{\"status\":\"" + (success ? "created" : "failed") + "\",\"title\":\"" + title + "\",\"url\":\"" + url + "\"}");
    }

    private void handleBookmarksDelete(JSONObject args, ExecutionCallback callback) {
        BookmarkDbHelper db = BookmarkDbHelper.getInstance(context);
        JSONArray idsArray = args.optJSONArray("ids");
        String urlPattern = args.optString("url_pattern", "");

        int deletedCount = 0;
        if (idsArray != null && idsArray.length() > 0) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < idsArray.length(); i++) {
                ids.add(idsArray.optLong(i));
            }
            db.deleteItems(ids);
            deletedCount = ids.size();
        } else if (!urlPattern.isEmpty()) {
            List<BookmarkModel> matches = db.searchBookmarks(urlPattern, 500);
            List<Long> ids = new ArrayList<>();
            for (BookmarkModel b : matches) {
                ids.add(b.getId());
            }
            db.deleteItems(ids);
            deletedCount = ids.size();
        }

        callback.onCompleted("{\"status\":\"deleted\",\"count\":" + deletedCount + "}");
    }

    // --- Downloads Handlers ---
    private void handleDownloadsList(JSONObject args, ExecutionCallback callback) {
        String namePattern = args.optString("name_pattern", "").toLowerCase();
        DownloadDbHelper db = DownloadDbHelper.getInstance(context);
        List<DownloadItem> all = db.getAllDownloads();

        try {
            JSONArray arr = new JSONArray();
            for (DownloadItem item : all) {
                if (namePattern.isEmpty() || (item.getFileName() != null && item.getFileName().toLowerCase().contains(namePattern))) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", item.getId());
                    obj.put("file_name", item.getFileName());
                    obj.put("dest_path", item.getDestinationPath());
                    obj.put("total_bytes", item.getTotalBytes());
                    obj.put("status", item.getStatus() != null ? item.getStatus().name() : "");
                    arr.put(obj);
                }
            }
            JSONObject res = new JSONObject();
            res.put("count", arr.length());
            res.put("downloads", arr);
            callback.onCompleted(res.toString());
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private void handleDownloadsDelete(JSONObject args, ExecutionCallback callback) {
        JSONArray filePaths = args.optJSONArray("file_paths");
        boolean removeFilesFromDisk = args.optBoolean("remove_files_from_disk", true);

        if (filePaths == null || filePaths.length() == 0) {
            callback.onError("Missing file_paths array.");
            return;
        }

        DownloadDbHelper db = DownloadDbHelper.getInstance(context);
        List<DownloadItem> all = db.getAllDownloads();
        int deleted = 0;

        for (int i = 0; i < filePaths.length(); i++) {
            String path = filePaths.optString(i);
            for (DownloadItem item : all) {
                if (path.equals(item.getDestinationPath()) || path.equals(item.getFileName()) || path.equals(item.getId())) {
                    db.deleteDownload(item.getId());
                    if (removeFilesFromDisk && item.getDestinationPath() != null) {
                        try {
                            File f = new File(item.getDestinationPath());
                            if (f.exists()) f.delete();
                        } catch (Exception ignored) {}
                    }
                    deleted++;
                    break;
                }
            }
        }

        callback.onCompleted("{\"status\":\"deleted\",\"count\":" + deleted + "}");
    }

    // --- Privacy Handlers ---
    private void handlePrivacyGetSiteData(final JSONObject args, final ExecutionCallback callback) {
        final String domain = args.optString("domain", "");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    CookieManager cm = CookieManager.getInstance();
                    String cookies = cm.getCookie("https://" + domain);
                    int count = 0;
                    if (cookies != null && !cookies.isEmpty()) {
                        count = cookies.split(";").length;
                    }
                    JSONObject res = new JSONObject();
                    res.put("domain", domain);
                    res.put("cookie_count", count);
                    callback.onCompleted(res.toString());
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    private void handlePrivacyClearSiteData(final JSONObject args, final ExecutionCallback callback) {
        final String domain = args.optString("domain", "*");
        final boolean clearCookies = args.optBoolean("clear_cookies", true);
        final boolean clearCache = args.optBoolean("clear_cache", true);
        final boolean clearStorage = args.optBoolean("clear_storage", true);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (clearCookies) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                    }
                    if (clearCache && currentWebView != null) {
                        currentWebView.clearCache(true);
                    }
                    if (clearStorage) {
                        WebStorage.getInstance().deleteAllData();
                    }
                    callback.onCompleted("{\"status\":\"success\",\"cleared_domain\":\"" + domain + "\"}");
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    // --- Adblock Self-Healing Handlers ---
    private void handleAdblockInspectPage(final ExecutionCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    AdBlockManager abm = AdBlockManager.getInstance(context);
                    String tabId = null;
                    if (tabsManager != null && tabsManager.getActiveTab() != null) {
                        tabId = tabsManager.getActiveTab().getId();
                    }

                    int blockedCount = abm.getPageBlockedCount(tabId);
                    int totalReq = abm.getPageRequestCount(tabId);
                    Map<String, Integer> blockedHosts = abm.getBlockedHostsForPage(tabId);

                    JSONObject res = new JSONObject();
                    res.put("blocked_requests_count", blockedCount);
                    res.put("total_requests_count", totalReq);

                    JSONArray hostsArr = new JSONArray();
                    for (Map.Entry<String, Integer> entry : blockedHosts.entrySet()) {
                        JSONObject h = new JSONObject();
                        h.put("host", entry.getKey());
                        h.put("count", entry.getValue());
                        hostsArr.put(h);
                    }
                    res.put("blocked_hosts", hostsArr);

                    // Check page for anti-adblock overlay signatures in DOM
                    String checkJs = "(function() {"
                            + "  var hasBlocker = !!document.querySelector('.ad-block-message, .adblock-overlay, .adblock-backdrop, #blockadblock, [class*=\"adblock\"], [id*=\"adblock\"]');"
                            + "  var overflowHidden = (document.body && window.getComputedStyle(document.body).overflow === 'hidden');"
                            + "  return JSON.stringify({detected_overlay: hasBlocker, scroll_locked: overflowHidden});"
                            + "})();";

                    currentWebView.evaluateJavascript(checkJs, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String val) {
                            try {
                                if (val != null && val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                                    val = val.substring(1, val.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                                }
                                JSONObject domState = new JSONObject(val != null ? val : "{}");
                                res.put("dom_inspection", domState);
                                callback.onCompleted(res.toString());
                            } catch (Exception e) {
                                callback.onCompleted(res.toString());
                            }
                        }
                    });

                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    private void handleAdblockTestRule(final JSONObject args, final ExecutionCallback callback) {
        final String ruleText = args.optString("rule_text", "");
        final String ruleType = args.optString("rule_type", "cosmetic");

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentWebView == null) {
                    callback.onError("No active WebView to test rule.");
                    return;
                }

                if ("cosmetic".equalsIgnoreCase(ruleType)) {
                    // Extract CSS selector
                    String selector = ruleText;
                    if (selector.contains("##")) {
                        selector = selector.substring(selector.indexOf("##") + 2);
                    }

                    // Dynamically inject CSS style tag in-memory
                    String injectCssJs = "(function() {"
                            + "  var style = document.createElement('style');"
                            + "  style.id = 'agent_test_cosmetic_rule';"
                            + "  style.innerHTML = '" + selector.replace("'", "\\'") + " { display: none !important; visibility: hidden !important; }';"
                            + "  document.head.appendChild(style);"
                            + "  if (document.body) document.body.style.overflow = 'auto !important';"
                            + "  return JSON.stringify({status:'injected', tested_selector: '" + selector.replace("'", "\\'") + "'});"
                            + "})();";

                    executeJsOnWebView(injectCssJs, callback);

                } else if ("scriptlet".equalsIgnoreCase(ruleType)) {
                    // Inject raw scriptlet into DOM
                    executeJsOnWebView(ruleText, callback);
                } else {
                    callback.onCompleted("{\"status\":\"network_rules_require_full_reload\"}");
                }
            }
        });
    }

    private void handleAdblockSaveAndCommit(final JSONObject args, final ExecutionCallback callback) {
        final String ruleText = args.optString("rule_text", "");
        final String targetFile = args.optString("target_file", "filters/cosmetic_rules.txt");
        final String commitMsg = args.optString("commit_message", "Add filter rule via Agent");

        if (ruleText.isEmpty()) {
            callback.onError("Missing rule_text");
            return;
        }

        try {
            AdBlockFileManager fm = AdBlockFileManager.getInstance(context);
            AdBlockFileManager.Category cat = AdBlockFileManager.Category.COSMETIC;
            String fileName = AdBlockFileManager.FILE_USER_COSMETIC;

            if (targetFile.contains("network") || targetFile.contains("hosts")) {
                cat = AdBlockFileManager.Category.NETWORK;
                fileName = AdBlockFileManager.FILE_USER_NETWORK;
            }

            String currentContent = fm.readFileContent(cat, fileName);
            String newContent = currentContent + "\n" + ruleText + "\n";
            boolean saved = fm.writeFileContent(cat, fileName, newContent);

            callback.onCompleted("{\"status\":\"" + (saved ? "saved_and_committed" : "failed") + "\",\"file\":\"" + fileName + "\",\"commit_message\":\"" + commitMsg + "\"}");
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    // --- Automation Handlers ---
    private void handleAutomationSchedule(JSONObject args, ExecutionCallback callback) {
        String title = args.optString("title", "Scheduled Routine");
        long triggerTime = args.optLong("trigger_time_epoch_ms", System.currentTimeMillis() + 60000);
        String recipeJson = args.optString("recipe_json", "[]");

        String id = UUID.randomUUID().toString();
        AutomationManager.AutomationTask task = new AutomationManager.AutomationTask(id, title, triggerTime, recipeJson);
        AutomationManager am = new AutomationManager(context);
        am.scheduleTask(task);

        callback.onCompleted("{\"status\":\"scheduled\",\"task_id\":\"" + id + "\",\"title\":\"" + title + "\",\"trigger_time_epoch_ms\":" + triggerTime + "}");
    }

    private void handleAutomationList(ExecutionCallback callback) {
        AutomationManager am = new AutomationManager(context);
        List<AutomationManager.AutomationTask> tasks = am.listTasks();
        try {
            JSONArray arr = new JSONArray();
            for (AutomationManager.AutomationTask t : tasks) {
                arr.put(t.toJson());
            }
            JSONObject res = new JSONObject();
            res.put("count", tasks.size());
            res.put("tasks", arr);
            callback.onCompleted(res.toString());
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private void handleAutomationCancel(JSONObject args, ExecutionCallback callback) {
        String taskId = args.optString("task_id", "");
        AutomationManager am = new AutomationManager(context);
        boolean cancelled = am.cancelTask(taskId);
        callback.onCompleted("{\"status\":\"" + (cancelled ? "cancelled" : "not_found") + "\",\"task_id\":\"" + taskId + "\"}");
    }

    private void handleTermuxExecuteCommand(final JSONObject args, final ExecutionCallback callback) {
        final String command = args.optString("command", "").trim();
        final int timeout = args.optInt("timeout_seconds", 30);

        if (command.isEmpty()) {
            callback.onError("Missing 'command' argument for termux_execute_command.");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                AiConfigManager configManager = new AiConfigManager(context);
                String endpoint = configManager.getTermuxEndpoint();
                if (endpoint == null || endpoint.isEmpty()) {
                    endpoint = AiConfigManager.DEFAULT_TERMUX_ENDPOINT;
                }
                String execUrl = endpoint.endsWith("/") ? (endpoint + "exec") : (endpoint + "/exec");

                try {
                    JSONObject payload = new JSONObject();
                    payload.put("command", command);
                    payload.put("timeout", timeout);

                    URL url = new URL(execUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout((timeout + 10) * 1000);
                    conn.setDoOutput(true);

                    byte[] postBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(postBytes.length);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postBytes);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    if (code >= 200 && code < 300) {
                        callback.onCompleted(sb.toString());
                    } else {
                        callback.onError("Termux exec failed (" + code + "): " + sb.toString());
                    }

                } catch (Exception e) {
                    callback.onError("Termux Bridge connection failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void executeJsOnWebView(final String js, final ExecutionCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentWebView != null) {
                    currentWebView.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value != null && value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                                value = value.substring(1, value.length() - 1)
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\");
                            }
                            callback.onCompleted(value != null ? value : "{}");
                        }
                    });
                } else {
                    callback.onError("No active WebView available.");
                }
            }
        });
    }
}

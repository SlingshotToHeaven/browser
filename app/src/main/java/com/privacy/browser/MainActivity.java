package com.privacy.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Trace;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import com.privacy.browser.ai.AssistantApproval;
import com.privacy.browser.adblock.WebScriptPolicy;
import com.privacy.browser.bookmarks.BookmarkDbHelper;
import com.privacy.browser.bookmarks.BookmarkModel;
import com.privacy.browser.comic.ComicDetector;
import com.privacy.browser.comic.ComicDownloadDialog;
import com.privacy.browser.comic.ComicManager;
import com.privacy.browser.history.HistoryDbHelper;
import com.privacy.browser.history.HistoryModel;
import com.privacy.downloader.interceptor.BlobDownloadManager;
import com.privacy.downloader.interceptor.BrowserDownloadListener;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.ui.DownloadPromptDialog;
import com.privacy.downloader.ui.DownloadsActivity;
import com.privacy.downloader.ui.LinkGrabberActivity;
import com.privacy.browser.bookmarks.BookmarksActivity;
import com.privacy.browser.history.HistoryActivity;
import com.privacy.browser.search.OmniboxSuggestion;
import com.privacy.browser.search.OmniboxSuggestionsAdapter;
import com.privacy.browser.search.SearchEngineManager;
import com.privacy.browser.settings.SettingsActivity;
import com.privacy.browser.media.MediaPageScripts;
import com.privacy.browser.media.MediaSniffer;
import com.privacy.browser.ui.VideoQualityDialog;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private FrameLayout webViewContainer;
    private EditText editUrl;
    private ProgressBar progressBar;
    private TextView btnShield;
    private ImageView btnReload;
    private TextView btnTabs;
    private ImageView btnMenu;

    // Floating Video Download FAB & Badge
    private FrameLayout fabVideoDownload;
    private TextView fabVideoBadge;
    private ImageView fabVideoIcon;

    // Floating Comic / Manga Download FAB & Badge
    private LinearLayout fabContainer;
    private FrameLayout fabComicDownload;
    private com.privacy.browser.ai.AiFloatingButtonView aiFloatingButton;
    private com.privacy.browser.ai.AgentBottomSheet agentBottomSheet;
    private TextView fabComicBadge;
    private ComicDownloadDialog comicDownloadDialog;

    // Fullscreen HTML5 Video Support
    private LinearLayout topAppBar;
    private LinearLayout toolbarContainer;
    private boolean isTopBarHidden = false;
    private float currentToolbarTranslation = 0f;
    private ValueAnimator toolbarSnapAnimator;
    private FrameLayout fullscreenVideoContainer;
    private View customVideoView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int originalOrientation;
    private int originalSystemUiVisibility;

    // Omnibox Suggestions UI
    private View omniboxContainer;
    private ListView omniboxList;
    private OmniboxSuggestionsAdapter omniboxAdapter;
    private OmniboxRequests omniboxRequests;
    private OmniboxRequests.Request visibleOmniboxRequest;
    private final OmniboxClipboard omniboxClipboard = new OmniboxClipboard();
    private final ThreadPoolExecutor omniboxWorker = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> new Thread(task, "Browser-omnibox"));

    // Find in Page UI
    private LinearLayout findBar;
    private EditText editFind;
    private ImageView btnFindPrev;
    private ImageView btnFindNext;
    private ImageView btnFindClose;
    private View viewAddressScrim;

    private TrackerBlocker trackerBlocker;
    private TabsManager tabsManager;
    private BookmarkDbHelper bookmarkDbHelper;
    private HistoryDbHelper historyDbHelper;
    private boolean resumed;
    private long assistantForegroundEpoch;
    private final Object assistantActivityIdentity = new Object();
    private boolean disposing;
    private TabModel displayedTab;
    private TabNavigation.Page discoveryPage;
    private TabModel discoveryOwner;
    private boolean videoDiscoveryEnabled, comicDiscoveryEnabled;
    private String comicObservation, comicScanToken;
    private boolean comicFinalChecked;
    private MediaOptions mediaOptions;
    private AlertDialog videoQualityDialog, mediaDownloadPrompt;
    private final SharedPreferences.OnSharedPreferenceChangeListener discoverySettings = (prefs, key) -> {
        if (key == null || SettingsActivity.KEY_FLOATING_DOWNLOAD_BUTTON.equals(key)
                || SettingsActivity.KEY_COMIC_DOWNLOADER_ENABLED.equals(key)) refreshDiscoverySettings();
    };
    private PrivacyWebView fullscreenOwner;
    private TabNavigation.Page fullscreenPage;
    private android.app.Dialog pagePickerDialog;
    private AlertDialog pageContextMenu;
    private boolean isAddressBarFirstTap = false;

    public static final String EXTRA_LOAD_URL = "com.privacy.browser.EXTRA_LOAD_URL";
    public static final String EXTRA_OPEN_IN_NEW_TAB = "com.privacy.browser.EXTRA_OPEN_IN_NEW_TAB";
    public static final String EXTRA_OPEN_IN_INCOGNITO = "com.privacy.browser.EXTRA_OPEN_IN_INCOGNITO";

    public static final String DEFAULT_HOME_URL = InternalHome.URL;
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private TabSessionWriter sessionWriter;
    private Object sessionOwner;
    private TabSessionCheckpoint<TabModel> session;
    private String currentAppTheme;
    private boolean replacingSession;
    private boolean startupReady;
    private Intent pendingLaunch;
    private static final String STARTUP_PENDING = "browser.startup.pending";
    private static final long TAB_TRIM_DELAY_MS = 120;
    private LazyTabRestore<TabModel> lazyRestore;
    private long checkedScriptRevision = -1;
    private final TabPreviews tabPreviews = new TabPreviews();
    private AlertDialog tabGrid;
    private AlertDialog closeTabsConfirmation;
    private RecyclerView.Adapter<?> tabGridAdapter;
    private TextView tabGridTitle;
    private final Handler tabResourceWork = new Handler(Looper.getMainLooper());
    private final ShieldRefresh shieldRefresh = new ShieldRefresh(new ShieldRefresh.Scheduler() {
        @Override public void later(Runnable action, long delayMs) { tabResourceWork.postDelayed(action, delayMs); }
        @Override public void cancel(Runnable action) { tabResourceWork.removeCallbacks(action); }
    }, this::renderVisibleShieldCount);
    private final ShieldRefresh mediaBadgeRefresh = new ShieldRefresh(new ShieldRefresh.Scheduler() {
        @Override public void later(Runnable action, long delayMs) { tabResourceWork.postDelayed(action, delayMs); }
        @Override public void cancel(Runnable action) { tabResourceWork.removeCallbacks(action); }
    }, this::renderMediaBadge);
    private int shieldIcon, shieldCount = -1;
    private final Runnable trimTabs = this::trimNextInactiveTab;
    private boolean trimPosted;
    private int inactiveTarget = TabViewBudget.INACTIVE;
    private final Handler resumeWork = new Handler(Looper.getMainLooper());
    private final Runnable deferredResumeWork = () -> {
        if (resumed && startupReady && !disposing) {
            BrowserStartup.resumeMaintenance(this);
            if (sessionWritable()) sessionWriter.maintainWhenIdle(sessionOwner);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        currentAppTheme = ThemeManager.getAppTheme(this);
        ThemeManager.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bookmarkDbHelper = BookmarkDbHelper.getInstance(this);
        historyDbHelper = HistoryDbHelper.getInstance(this);
        tabsManager = new TabsManager(this::allocateTabView);
        initViews();
        if (savedInstanceState == null || savedInstanceState.getBoolean(STARTUP_PENDING)) pendingLaunch = getIntent();
        setBrowserControlsReady(false);
        beginStartup();
        checkNotificationPermission();
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void beginStartup() {
        showTabStatus(getString(R.string.browser_preparing), 0, null);
        BrowserStartup.request(this, ready -> {
            if (disposing || isFinishing() || startupReady) return;
            if (ready == null) {
                showTabStatus(getString(R.string.browser_prepare_failed), R.string.browser_prepare_retry, this::beginStartup);
                return;
            }
            trackerBlocker = ready.protection;
            initTabs(ready.journal);
        });
    }

    private void setBrowserControlsReady(boolean ready) {
        for (View control : new View[]{editUrl, btnReload, btnTabs, btnMenu, btnShield, aiFloatingButton}) {
            if (control != null) control.setEnabled(ready);
        }
    }

    private void showTabStatus(String message, int actionLabel, @Nullable Runnable retry) {
        webViewContainer.removeAllViews();
        LinearLayout state = new LinearLayout(this);
        state.setOrientation(LinearLayout.VERTICAL);
        state.setGravity(Gravity.CENTER);
        TextView status = new TextView(this);
        status.setText(message);
        state.addView(status);
        if (retry != null) {
            android.widget.Button button = new android.widget.Button(this);
            button.setText(actionLabel);
            button.setOnClickListener(v -> retry.run());
            state.addView(button);
        }
        webViewContainer.addView(state, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(STARTUP_PENDING, !startupReady && pendingLaunch != null);
    }

    private void initViews() {
        topAppBar = findViewById(R.id.top_app_bar);
        toolbarContainer = findViewById(R.id.toolbar_container);
        fullscreenVideoContainer = findViewById(R.id.fullscreen_video_container);
        webViewContainer = findViewById(R.id.webview_container);
        editUrl = findViewById(R.id.edit_url);
        progressBar = findViewById(R.id.page_progress);
        btnShield = findViewById(R.id.btn_shield);
        btnReload = findViewById(R.id.btn_reload);
        btnTabs = findViewById(R.id.btn_tabs);
        btnMenu = findViewById(R.id.btn_menu);

        findBar = findViewById(R.id.find_bar);
        editFind = findViewById(R.id.edit_find);
        btnFindPrev = findViewById(R.id.btn_find_prev);
        btnFindNext = findViewById(R.id.btn_find_next);
        btnFindClose = findViewById(R.id.btn_find_close);
        viewAddressScrim = findViewById(R.id.view_address_scrim);

        if (viewAddressScrim != null) {
            viewAddressScrim.setOnClickListener(v -> dismissKeyboard());
        }

        Handler omniboxUi = new Handler(Looper.getMainLooper());
        OmniboxStore store = new OmniboxStore(bookmarkDbHelper, historyDbHelper);
        omniboxRequests = new OmniboxRequests(new OmniboxRequests.Ui() {
            @Override public void later(Runnable action, long ms) { omniboxUi.postDelayed(action, ms); }
            @Override public void remove(Runnable action) { omniboxUi.removeCallbacks(action); }
            @Override public void post(Runnable action) { omniboxUi.post(action); }
        }, omniboxWorker, store::find, this::canShowOmniboxRequest, this::publishLocalOmnibox);
        omniboxContainer = findViewById(R.id.omnibox_suggestions_container);
        omniboxList = findViewById(R.id.omnibox_suggestions_list);
        if (omniboxList != null) {
            omniboxAdapter = new OmniboxSuggestionsAdapter(this);
            omniboxList.setAdapter(omniboxAdapter);
            omniboxList.setOnItemClickListener((parent, view, position, id) -> {
                if (!omniboxRequests.isCurrent(visibleOmniboxRequest) || omniboxContainer.getVisibility() != View.VISIBLE) return;
                OmniboxSuggestion item = omniboxAdapter.clickedItem(view, position, id);
                if (item != null) {
                    navigateToInput(item.getTargetUrl());
                    dismissKeyboard();
                }
            });
        }

        editUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigateToInput(editUrl.getText().toString());
                dismissKeyboard();
                return true;
            }
            return false;
        });

        ImageView btnClearUrl = findViewById(R.id.btn_clear_url);
        if (btnClearUrl != null) {
            btnClearUrl.setOnClickListener(v -> {
                editUrl.setText("");
                editUrl.requestFocus();
                showKeyboard(editUrl);
            });
        }

        editUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showTopBar(false);
                if (viewAddressScrim != null) {
                    viewAddressScrim.setVisibility(View.VISIBLE);
                }
                editUrl.post(() -> editUrl.selectAll());
                if (btnClearUrl != null) {
                    btnClearUrl.setVisibility(editUrl.length() == 0 ? View.GONE : View.VISIBLE);
                }
                updateOmniboxSuggestions(editUrl.getText());
            } else {
                isAddressBarFirstTap = false;
                if (viewAddressScrim != null) {
                    viewAddressScrim.setVisibility(View.GONE);
                }
                if (btnClearUrl != null) {
                    btnClearUrl.setVisibility(View.GONE);
                }
                hideOmniboxSuggestions();
                TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                if (active != null) {
                    displayFormattedUrl(active.getUrl());
                }
            }
        });

        editUrl.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (!editUrl.hasFocus()) {
                    isAddressBarFirstTap = true;
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (isAddressBarFirstTap) {
                    isAddressBarFirstTap = false;
                    editUrl.requestFocus();
                    showKeyboard(editUrl);
                    editUrl.post(() -> editUrl.selectAll());
                    v.performClick();
                    return true;
                }
            } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                isAddressBarFirstTap = false;
            }
            return false;
        });

        editUrl.setOnClickListener(v -> {
            if (!editUrl.hasFocus()) {
                editUrl.requestFocus();
                editUrl.selectAll();
            } else if (visibleOmniboxRequest == null) {
                // A dialog or pause may have ended the last edit without clearing view focus.
                updateOmniboxSuggestions(editUrl.getText());
            }
        });

        editUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (btnClearUrl != null && editUrl.hasFocus()) {
                    btnClearUrl.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
                }
                if (editUrl.hasFocus()) {
                    updateOmniboxSuggestions(s);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnReload.setOnClickListener(v -> {
            TabModel active = tabsManager.getActiveTab();
            if (active == null) return;
            PrivacyWebView view = active.getWebView();
            if (view == null) {
                restoreLostTab(active);
            } else if (active.navigation.progress() < 100) {
                view.stopLoading();
                invalidatePage(active);
                renderChrome(active);
            } else {
                reloadTab(active);
            }
        });

        btnTabs.setOnClickListener(v -> showTabsDialog());
        btnMenu.setOnClickListener(v -> showBrowserMenu());
        btnShield.setOnClickListener(v -> showShieldDetails());

        View btnSiteSecurity = findViewById(R.id.btn_site_security);
        if (btnSiteSecurity != null) {
            btnSiteSecurity.setOnClickListener(v -> {
                TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                if (active != null) {
                    showSiteConnectionDetails(active);
                }
            });
        }

        // Handled via editUrl.setOnClickListener above

        setupFindInPage();
        setupVideoDownloadFab();
        setupComicDownloadFab();
        setupAiFloatingButton();
    }

    private void setupAiFloatingButton() {
        aiFloatingButton = findViewById(R.id.ai_floating_button);
        if (aiFloatingButton != null) {
            aiFloatingButton.setOnAiButtonClickListener(() -> {
                if (agentBottomSheet == null) {
                    agentBottomSheet = new com.privacy.browser.ai.AgentBottomSheet(this, aiFloatingButton);
                }
                if (agentBottomSheet.isShowing()) agentBottomSheet.dismiss();
                else {
                    markAssistantDocument(activeTab());
                    agentBottomSheet.show();
                }
            });
            updateAiFloatingButtonVisibility();
        }
    }

    private void updateAiFloatingButtonVisibility() {
        if (aiFloatingButton != null) {
            com.privacy.browser.ai.AiConfigManager config = new com.privacy.browser.ai.AiConfigManager(this);
            aiFloatingButton.setVisibility(config.isAgentEnabled() ? View.VISIBLE : View.GONE);
        }
    }

    private void setupComicDownloadFab() {
        fabContainer = findViewById(R.id.fab_container);
        fabComicDownload = findViewById(R.id.fab_comic_download);
        fabComicBadge = findViewById(R.id.fab_comic_badge);

        if (fabComicDownload != null) {
            setupMovableFabTouchListener(fabComicDownload, this::onComicDownloadFabClicked);
        }
    }

    private void onComicDownloadFabClicked() {
        TabModel active = activeTab();
        if (!canScanComic(active)) return;
        PrivacyWebView view = active.getWebView();
        TabNavigation.Page page = active.navigation.page();
        ComicManager.ComicItem item = ComicManager.getDetectedComic(page.key);
        if (item == null) return;
        dismissComicDownload();
        ComicDownloadDialog shown = ComicDownloadDialog.show(this, item, downloadContext(active),
                () -> canScanComic(active) && currentPage(active, view, page, true)
                        && ComicManager.getDetectedComic(page.key) == item);
        comicDownloadDialog = shown;
        if (shown != null) shown.setOnDismissListener(d -> {
            if (comicDownloadDialog == shown) comicDownloadDialog = null;
        });
    }

    private void dismissComicDownload() {
        ComicDownloadDialog dialog = comicDownloadDialog;
        comicDownloadDialog = null;
        if (dialog != null) dialog.dismiss();
    }

    private void updateFabComicDownload(int count) {
        if (fabComicDownload == null) return;
        if (!comicDiscoveryEnabled || comicObservation == null || customVideoView != null || isCurrentTabOnHomePage()) {
            fabComicDownload.setVisibility(View.GONE);
            return;
        }
        if (count >= 1) {
            fabComicDownload.setVisibility(View.VISIBLE);
            if (fabComicBadge != null) {
                fabComicBadge.setText(String.valueOf(count));
            }
        } else {
            fabComicDownload.setVisibility(View.GONE);
        }
    }

    private void setupVideoDownloadFab() {
        fabVideoDownload = findViewById(R.id.fab_video_download);
        fabVideoBadge = findViewById(R.id.fab_video_badge);
        fabVideoIcon = findViewById(R.id.fab_video_icon);

        if (fabVideoDownload != null) {
            setupMovableFabTouchListener(fabVideoDownload, this::onFloatingDownloadFabClicked);
        }

    }

    private void setupMovableFabTouchListener(View fab, Runnable onClickAction) {
        fab.setOnClickListener(view -> onClickAction.run());
        fab.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private float startX, startY;
            private static final int CLICK_THRESHOLD = 15; // pixels

            @Override
            public boolean onTouch(View view, android.view.MotionEvent event) {
                View target = fabContainer != null ? fabContainer : view;
                View root = findViewById(android.R.id.content);
                if (root == null) {
                    root = (View) target.getParent();
                }

                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        dX = target.getTranslationX() - startX;
                        dY = target.getTranslationY() - startY;
                        return true;

                    case android.view.MotionEvent.ACTION_MOVE:
                        float targetTransX = event.getRawX() + dX;
                        float targetTransY = event.getRawY() + dY;

                        if (root != null) {
                            int[] rootLoc = new int[2];
                            root.getLocationOnScreen(rootLoc);
                            int rootWidth = root.getWidth();
                            int rootHeight = root.getHeight();

                            // The default un-translated position of the container
                            float originalLeft = target.getLeft();
                            float originalTop = target.getTop();
                            float targetW = target.getWidth();
                            float targetH = target.getHeight();

                            // Clamping translated bounds strictly within the root screen area
                            float minTransX = -originalLeft;
                            float maxTransX = rootWidth - originalLeft - targetW;
                            float minTransY = -originalTop;
                            float maxTransY = rootHeight - originalTop - targetH;

                            targetTransX = Math.max(minTransX, Math.min(targetTransX, maxTransX));
                            targetTransY = Math.max(minTransY, Math.min(targetTransY, maxTransY));
                        }

                        target.setTranslationX(targetTransX);
                        target.setTranslationY(targetTransY);
                        return true;

                    case android.view.MotionEvent.ACTION_UP:
                        float diffX = Math.abs(event.getRawX() - startX);
                        float diffY = Math.abs(event.getRawY() - startY);
                        if (diffX < CLICK_THRESHOLD && diffY < CLICK_THRESHOLD) {
                            view.performClick();
                        }
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    private static RequestContext downloadContext(TabModel tab) {
        if (tab.isIncognito()) return RequestContext.fromBrowser(null, null, true);
        PrivacyWebView view = tab.getWebView();
        if (view != null && view.getWebViewClient() instanceof PrivacyWebViewClient) {
            return ((PrivacyWebViewClient) view.getWebViewClient()).downloadContext(view);
        }
        return RequestContext.anonymous();
    }

    private boolean rejectPrivateDownloads(TabModel tab) {
        if (!tab.isIncognito()) return false;
        Toast.makeText(this, RequestContext.PRIVATE_UNAVAILABLE, Toast.LENGTH_LONG).show();
        return true;
    }

    private void onFloatingDownloadFabClicked() {
        openMediaOptions(activeTab());
    }

    private static final class MediaOptions {
        final TabModel tab;
        final PrivacyWebView view;
        final TabNavigation.Page page;
        boolean collecting = true;
        Runnable timeout;
        AlertDialog progress;

        MediaOptions(TabModel tab) {
            this.tab = tab;
            view = tab.getWebView();
            page = tab.navigation.page();
        }
    }

    private boolean mediaOptionsCurrent(MediaOptions q) {
        return videoDiscoveryEnabled && q.page != null && discoveryPage == q.page && tabGrid == null
                && customVideoView == null && !q.tab.isIncognito() && currentPage(q.tab, q.view, q.page, true);
    }

    private void openMediaOptions(TabModel tab) {
        if (tab == null || rejectPrivateDownloads(tab) || mediaOptions != null
                || videoQualityDialog != null || mediaDownloadPrompt != null) return;
        MediaOptions q = new MediaOptions(tab);
        if (!mediaOptionsCurrent(q) || !q.page.media.isObserving()) return;
        mediaOptions = q;
        if (allowsPageScripts(tab, q.view)) {
            q.timeout = () -> collectMediaOptions(q, null);
            tabResourceWork.postDelayed(q.timeout, 800);
            evaluateMediaSnapshot(tab, q.view, MediaPageScripts.OPTIONS_JS, value -> collectMediaOptions(q, value));
        } else {
            collectMediaOptions(q, null);
        }
    }

    private void collectMediaOptions(MediaOptions q, String value) {
        if (mediaOptions != q || !q.collecting) return;
        if (!mediaOptionsCurrent(q)) { dismissVideoOptions(); return; }
        q.collecting = false;
        if (q.timeout != null) tabResourceWork.removeCallbacks(q.timeout);
        q.timeout = null;
        Predicate<String> allowed = mediaOptionsPolicy(q.view);
        List<String> embeds = new ArrayList<>();
        JSONArray items = mediaSnapshot(value);
        for (int i = 0; items != null && i < Math.min(items.length(), 64); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String url = boundedWebUrl(item.optString("url"));
            if (url == null || !allowed.test(url)) continue;
            String type = item.optString("type");
            if ("embed".equals(type)) {
                if (embeds.size() < 16) embeds.add(url);
            } else q.page.media.elementHint(url, type, q.tab.getTitle());
        }
        String player = allowsPageScripts(q.tab, q.view) ? q.tab.getUrl() : null;
        boolean started = q.page.media.enrich(player, q.tab.getTitle(), embeds, allowed,
                () -> tabResourceWork.post(() -> presentMediaOptions(q)));
        if (!started) { presentMediaOptions(q); return; }
        q.progress = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                .setMessage(R.string.media_options_loading)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (mediaOptions == q) dismissVideoOptions();
                }).create();
        q.progress.setOnDismissListener(dialog -> { if (mediaOptions == q) dismissVideoOptions(); });
        q.progress.show();
        // A queued job or a provider's blocking I/O must not keep the UI waiting indefinitely.
        q.timeout = () -> {
            if (mediaOptions != q) return;
            q.page.media.cancelEnrichment();
            presentMediaOptions(q);
        };
        tabResourceWork.postDelayed(q.timeout, 8000);
    }

    private void presentMediaOptions(MediaOptions q) {
        if (mediaOptions != q) return;
        mediaOptions = null;
        if (q.timeout != null) tabResourceWork.removeCallbacks(q.timeout);
        if (q.progress != null) q.progress.dismiss();
        if (!mediaOptionsCurrent(q)) return;
        List<MediaSniffer.SniffedMedia> options = q.page.media.snapshot();
        Predicate<String> allowed = mediaOptionsPolicy(q.view);
        options.removeIf(row -> !allowed.test(row.getUrl()));
        if (options.isEmpty()) {
            if (allowsPageScripts(q.tab, q.view)) extractAndGrabPageLinks(q.tab);
            else Toast.makeText(this, R.string.media_no_links, Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = VideoQualityDialog.show(this, options, () -> mediaOptionsCurrent(q),
                (url, mime, name, bytes, audio, audioSize) -> confirmMediaOption(q, url, mime, name, bytes, audio, audioSize),
                allowsPageScripts(q.tab, q.view) ? () -> extractAndGrabPageLinks(q.tab) : null, () -> {
                    videoQualityDialog = null;
                    openMediaOptions(q.tab);
                });
        videoQualityDialog = dialog;
        if (dialog != null) dialog.setOnDismissListener(d -> { if (videoQualityDialog == dialog) videoQualityDialog = null; });
    }

    private void confirmMediaOption(MediaOptions q, String url, String mime, String name,
                                    long size, String audio, long audioSize) {
        java.util.function.BooleanSupplier current = () -> {
            if (!mediaOptionsCurrent(q)) return false;
            Predicate<String> allowed = mediaOptionsPolicy(q.view);
            return allowed.test(url) && (audio == null || allowed.test(audio));
        };
        if (!current.getAsBoolean()) return;
        AlertDialog prompt = DownloadPromptDialog.showForPage(this, url, downloadContext(q.tab),
                mime, name, size, audio, audioSize, current);
        mediaDownloadPrompt = prompt;
        if (prompt != null) prompt.setOnDismissListener(d -> { if (mediaDownloadPrompt == prompt) mediaDownloadPrompt = null; });
    }

    private void dismissVideoOptions() {
        MediaOptions q = mediaOptions;
        mediaOptions = null;
        if (q != null) {
            if (q.timeout != null) tabResourceWork.removeCallbacks(q.timeout);
            q.page.media.cancelEnrichment();
            if (q.progress != null) q.progress.dismiss();
        }
        AlertDialog dialog = videoQualityDialog;
        videoQualityDialog = null;
        if (dialog != null) dialog.dismiss();
        AlertDialog prompt = mediaDownloadPrompt;
        mediaDownloadPrompt = null;
        if (prompt != null) prompt.dismiss();
    }

    private void renderMediaBadge(String key) {
        TabModel tab = activeTab();
        if (resumed && !disposing && customVideoView == null && tab != null && tab.navigation.ready()
                && discoveryPage != null && discoveryPage == tab.navigation.page() && key.equals(discoveryPage.key)) {
            updateFabVideoDownload(discoveryPage.media.count());
        }
    }

    private void syncMediaDiscovery(TabModel tab, String nativeUrl, boolean finalCheck) {
        syncMediaDiscovery(tab, nativeUrl, finalCheck, false);
    }

    private void syncMediaDiscovery(TabModel tab, String nativeUrl, boolean finalCheck, boolean settleRoute) {
        if (tab != activeTab()) return;
        PrivacyWebView view = tab == null ? null : tab.getWebView();
        TabNavigation.Page page = tab == null ? null : tab.navigation.page();
        if ((!videoDiscoveryEnabled && !comicDiscoveryEnabled) || page == null || view == null
                || !foreground(tab, view) || tab.isIncognito() || InternalHome.ownsHost(nativeUrl) || RequestContext.origin(nativeUrl) == null) {
            stopMediaDiscovery();
            return;
        }
        if (discoveryPage != page) {
            stopMediaDiscovery();
            discoveryPage = page;
            discoveryOwner = tab;
        }
        if (videoDiscoveryEnabled && !page.media.isObserving()) {
            mediaBadgeRefresh.show(page.key);
            page.media.observe(() -> mediaBadgeRefresh.changed(page.key));
        }
        if (comicDiscoveryEnabled && tab.messages != null && tab.messages.hasPageEndpoint()
                && currentPage(tab, view, page, true) && allowsPageScripts(tab, view)) {
            if (comicObservation == null) {
                comicObservation = java.util.UUID.randomUUID().toString();
                comicFinalChecked = finalCheck;
                evaluateComic(tab, ComicDetector.start(comicObservation, finalCheck, settleRoute));
            } else if (finalCheck && !comicFinalChecked) {
                comicFinalChecked = true;
                evaluateComic(tab, ComicDetector.recheck(comicObservation));
            }
        }
    }

    private void evaluateComic(TabModel tab, String script) {
        // This slice measures native dispatch, not the asynchronous renderer work.
        Trace.beginSection("Browser.comic.dispatch");
        try { tab.messages.evaluate(script); } finally { Trace.endSection(); }
    }

    private void stopComicDiscovery() {
        String previous = comicObservation;
        comicObservation = comicScanToken = null;
        comicFinalChecked = false;
        if (previous == null) return;
        dismissComicDownload();
        ComicManager.clearTab(discoveryPage.key);
        if (discoveryOwner.navigation.page() == discoveryPage && discoveryOwner.messages != null) {
            discoveryOwner.messages.evaluate(ComicDetector.stop(previous));
        }
    }

    private void stopMediaDiscovery() {
        dismissVideoOptions();
        mediaBadgeRefresh.show(null);
        stopComicDiscovery();
        if (discoveryPage != null) discoveryPage.media.suspend();
        discoveryPage = null;
        discoveryOwner = null;
    }

    private void refreshDiscoverySettings() {
        videoDiscoveryEnabled = SettingsActivity.isFloatingDownloadButtonEnabled(this);
        comicDiscoveryEnabled = SettingsActivity.isComicDownloaderEnabled(this);
        if (!resumed || !startupReady || disposing) return;
        if (!videoDiscoveryEnabled) {
            dismissVideoOptions();
            mediaBadgeRefresh.show(null);
            if (discoveryPage != null) discoveryPage.media.suspend();
        }
        if (!comicDiscoveryEnabled) stopComicDiscovery();
        TabModel tab = activeTab();
        if (tab != null) syncMediaDiscovery(tab, tab.navigation.target(), tab.navigation.finished());
        updateFloatingActionButtonsForActiveTab();
    }

    private void scanComicPages(TabModel tab) {
        if (!canScanComic(tab)) return;
        comicScanToken = java.util.UUID.randomUUID().toString();
        evaluateComic(tab, ComicDetector.manual(comicObservation, comicScanToken));
    }

    private boolean canScanComic(TabModel tab) {
        if (!comicDiscoveryEnabled || tab == null || tab.isIncognito() || tab.messages == null
                || discoveryOwner != tab || comicObservation == null || !tab.messages.hasPageEndpoint()) return false;
        PrivacyWebView view = tab.getWebView();
        return view != null && currentPage(tab, view, discoveryPage, true) && allowsPageScripts(tab, view);
    }

    private static Predicate<String> mediaOptionsPolicy(PrivacyWebView view) {
        WebViewClient client = view.getWebViewClient();
        return client instanceof PrivacyWebViewClient ? ((PrivacyWebViewClient) client).mediaOptionsPolicy() : url -> false;
    }

    private static void evaluateMediaSnapshot(TabModel tab, PrivacyWebView view, String script, Consumer<String> result) {
        view.evaluateJavascript("(function(){if(location.href!==" + JSONObject.quote(tab.getUrl()) + ")return [];return "
                + script + ";})()", result::accept);
    }

    private static JSONArray mediaSnapshot(String value) {
        if (value == null || value.length() > PageMessageGate.MAX_MESSAGE || !value.startsWith("[")
                || !PageMessageGate.shallowObject("{\"items\":" + value + "}")) return null;
        try { return new JSONArray(value); } catch (org.json.JSONException ignored) { return null; }
    }

    private void updateFabVideoDownload(int count) {
        if (fabVideoDownload == null) return;
        if (!videoDiscoveryEnabled || discoveryPage == null || customVideoView != null || isCurrentTabOnHomePage()) {
            fabVideoDownload.setVisibility(View.GONE);
            return;
        }

        // Always visible when setting is enabled
        fabVideoDownload.setVisibility(View.VISIBLE);

        if (count > 0) {
            // Active Highlighted State
            fabVideoDownload.setBackgroundResource(R.drawable.bg_floating_download_fab);
            if (fabVideoIcon != null) {
                fabVideoIcon.setColorFilter(Color.parseColor("#00E5FF"));
            }
            if (fabVideoBadge != null) {
                fabVideoBadge.setText(String.valueOf(count));
                fabVideoBadge.setVisibility(View.VISIBLE);
            }
        } else {
            // Idle Faded State
            fabVideoDownload.setBackgroundResource(R.drawable.bg_floating_download_fab_idle);
            if (fabVideoIcon != null) {
                fabVideoIcon.setColorFilter(Color.parseColor("#555566"));
            }
            if (fabVideoBadge != null) {
                fabVideoBadge.setVisibility(View.GONE);
            }
        }
    }

    private void setupFindInPage() {
        editFind.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                TabModel active = tabsManager.getActiveTab();
                if (active != null && !TextUtils.isEmpty(s)) {
                    active.getWebView().findAllAsync(s.toString());
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnFindPrev.setOnClickListener(v -> {
            TabModel active = tabsManager.getActiveTab();
            if (active != null && active.getWebView() != null) active.getWebView().findNext(false);
        });

        btnFindNext.setOnClickListener(v -> {
            TabModel active = tabsManager.getActiveTab();
            if (active != null && active.getWebView() != null) active.getWebView().findNext(true);
        });

        btnFindClose.setOnClickListener(v -> {
            findBar.setVisibility(View.GONE);
            showTopBar(true);
            TabModel active = tabsManager.getActiveTab();
            if (active != null && active.getWebView() != null) active.getWebView().clearMatches();
            dismissKeyboard();
        });
    }

    // --- Dynamic Top Bar Auto-Hide / Show on Scroll ---

    private boolean isTopBarLocked() {
        if (editUrl != null && editUrl.hasFocus()) {
            return true;
        }
        if (omniboxContainer != null && omniboxContainer.getVisibility() == View.VISIBLE) {
            return true;
        }
        if (viewAddressScrim != null && viewAddressScrim.getVisibility() == View.VISIBLE) {
            return true;
        }
        if (findBar != null && findBar.getVisibility() == View.VISIBLE) {
            return true;
        }
        if (fullscreenVideoContainer != null && fullscreenVideoContainer.getVisibility() == View.VISIBLE) {
            return true;
        }
        return false;
    }

    private int getToolbarHeightPx() {
        if (toolbarContainer != null && toolbarContainer.getHeight() > 0) {
            return toolbarContainer.getHeight();
        }
        return (int) (54 * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void cancelTopBarAnimation() {
        if (toolbarSnapAnimator != null) {
            if (toolbarSnapAnimator.isRunning()) {
                toolbarSnapAnimator.cancel();
            }
            toolbarSnapAnimator = null;
        }
    }

    private void animateTopBarTranslation(float targetY, int duration) {
        cancelTopBarAnimation();
        if (topAppBar == null || webViewContainer == null) return;
        if (Math.abs(currentToolbarTranslation - targetY) < 1f) {
            currentToolbarTranslation = targetY;
            topAppBar.setTranslationY(targetY);
            webViewContainer.setTranslationY(targetY);
            isTopBarHidden = (targetY < 0f);
            return;
        }

        toolbarSnapAnimator = ValueAnimator.ofFloat(currentToolbarTranslation, targetY);
        toolbarSnapAnimator.setDuration(duration);
        toolbarSnapAnimator.setInterpolator(new DecelerateInterpolator());
        toolbarSnapAnimator.addUpdateListener(animation -> {
            float val = (Float) animation.getAnimatedValue();
            currentToolbarTranslation = val;
            if (topAppBar != null) topAppBar.setTranslationY(val);
            if (webViewContainer != null) webViewContainer.setTranslationY(val);
        });
        toolbarSnapAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isTopBarHidden = (currentToolbarTranslation < 0f);
                toolbarSnapAnimator = null;
            }
        });
        toolbarSnapAnimator.start();
    }

    private void hideTopBar(boolean animated) {
        if (isTopBarLocked()) return;
        cancelTopBarAnimation();
        isTopBarHidden = true;
        int barHeight = getToolbarHeightPx();
        float targetY = -barHeight;

        if (animated) {
            animateTopBarTranslation(targetY, 200);
        } else {
            currentToolbarTranslation = targetY;
            if (topAppBar != null) topAppBar.setTranslationY(targetY);
            if (webViewContainer != null) webViewContainer.setTranslationY(targetY);
        }
    }

    private void showTopBar(boolean animated) {
        cancelTopBarAnimation();
        isTopBarHidden = false;

        if (animated) {
            animateTopBarTranslation(0f, 200);
        } else {
            currentToolbarTranslation = 0f;
            if (topAppBar != null) topAppBar.setTranslationY(0f);
            if (webViewContainer != null) webViewContainer.setTranslationY(0f);
        }
    }

    private float handleToolbarDrag(float deltaY) {
        if (isTopBarLocked()) return 0f;

        cancelTopBarAnimation();
        int maxCollapse = getToolbarHeightPx();
        float oldTrans = currentToolbarTranslation;
        float newTrans = oldTrans;

        if (deltaY > 0) {
            // Dragging upwards: collapse toolbar towards -maxCollapse
            if (currentToolbarTranslation > -maxCollapse) {
                newTrans = Math.max(-maxCollapse, currentToolbarTranslation - deltaY);
            }
        } else if (deltaY < 0) {
            // Dragging downwards: reveal toolbar towards 0
            if (currentToolbarTranslation < 0f) {
                newTrans = Math.min(0f, currentToolbarTranslation - deltaY);
            }
        }

        float consumed = oldTrans - newTrans;
        if (consumed != 0f) {
            currentToolbarTranslation = newTrans;
            isTopBarHidden = (newTrans <= -maxCollapse);
            if (topAppBar != null) topAppBar.setTranslationY(newTrans);
            if (webViewContainer != null) webViewContainer.setTranslationY(newTrans);
        }
        return consumed;
    }

    private void handleToolbarRelease() {
        if (isTopBarLocked()) return;
        int maxCollapse = getToolbarHeightPx();
        if (currentToolbarTranslation == 0f || currentToolbarTranslation == -maxCollapse) {
            return; // Already settled
        }

        // Snap to fully collapsed or fully shown based on midpoint
        float target = (currentToolbarTranslation < -maxCollapse * 0.5f) ? -maxCollapse : 0f;
        animateTopBarTranslation(target, 160);
    }

    private void handlePageScroll(int scrollY, int oldScrollY) {
        if (isTopBarLocked()) {
            if (isTopBarHidden) {
                showTopBar(false);
            }
            return;
        }

        // Always reveal immediately when reaching or above the top of the webpage
        if (scrollY <= 0) {
            if (currentToolbarTranslation < 0f && (toolbarSnapAnimator == null || !toolbarSnapAnimator.isRunning())) {
                showTopBar(true);
            }
        }
    }

    private void initTabs(TabSessionWriter journal) {
        sessionWriter = journal;
        TabSessionWriter.Restore saved = sessionWriter.open();
        sessionOwner = saved.owner;
        session = new TabSessionCheckpoint<>(NativeTabSession.scheduler(), new TabSessionCheckpoint.Source<TabModel>() {
            @Override public TabSession.Row metadata(TabModel tab) {
                return TabSession.regular(tab.getId(), tab.sessionUrl(), tab.getTitle(), tab.isDesktopMode(),
                        tab.navigation.rendererLost(), tab.isIncognito());
            }

            @Override public TabSessionCheckpoint.History history(TabModel tab, TabSession.Row row) {
                PrivacyWebView view = tab.getWebView();
                if (!sessionWritable() || tab.isIncognito() || !row.url.equals(tab.getUrl())
                        || !tab.navigation.canSaveHistory() || !currentPage(tab, view, tab.navigation.page(), false)) return null;
                return NativeTabSession.history(view, row.url);
            }

            @Override public byte[] save(TabModel tab, TabSession.Row row, TabSessionCheckpoint.History expected) {
                return NativeTabSession.capture(tab.getWebView(), row.url, expected);
            }
        }, frame -> sessionWriter.submit(sessionOwner, frame));

        lazyRestore = new LazyTabRestore<>((row, done) -> NativeTabSession.readState(sessionWriter, sessionOwner, row, done),
                this::materializeRestoredTab);
        tabsManager.setListener(new TabsManager.TabChangeListener() {
            @Override public void onTabSwitched(TabModel tab) {
                Trace.beginSection("Browser.tabs.switch");
                try {
                    if (sessionWritable()) session.select(regularTabId(tab));
                    if (customVideoView != null) hideFullscreenVideo();
                    showTopBar(false);
                    attachTabToView(tab);
                    refreshTabGrid();
                    tabResourcesChanged();
                } finally {
                    Trace.endSection();
                }
            }

            @Override public void onTabCountChanged(int count) {
                btnTabs.setText(String.valueOf(count));
                sessionTabsChanged();
                refreshTabGrid();
                tabResourcesChanged();
            }

            @Override public void onTabClosed(TabModel tab) {
                tabPreviews.remove(tab);
                if (discoveryOwner == tab) stopMediaDiscovery();
                if (fullscreenOwner != null && fullscreenOwner == tab.getWebView()) hideFullscreenVideo();
                if (displayedTab == tab) {
                    lazyRestore.cancel();
                    dismissPageContextMenu();
                    dismissComicDownload();
                    cancelPicker(tab);
                }
            }
        });

        tabsManager.restoreIndex(saved.index);
        for (int i = 0; i < saved.index.tabs.size(); i++) {
            session.restore(tabsManager.getTabs().get(i), saved.index.tabs.get(i), null);
        }
        startupReady = true;
        setBrowserControlsReady(true);
        // Resolve any launch link before touching the previous selection's WebView or history blob.
        Intent launch = pendingLaunch;
        pendingLaunch = null;
        handleIncomingIntent(launch);
        if (tabsManager.getTabCount() == 0) {
            String home = getDefaultHomeUrl();
            loadInTab(tabsManager.createTab(this, home), home);
        } else if (displayedTab != activeTab()) {
            attachTabToView(activeTab());
        }
        btnTabs.setText(String.valueOf(tabsManager.getTabCount()));
        sessionTabsChanged();
        scheduleResumeWork();
    }

    private static String regularTabId(TabModel tab) {
        return tab == null || tab.isIncognito() ? null : tab.getId();
    }

    private boolean sessionWritable() {
        return !disposing && !replacingSession && session != null && sessionWriter.owns(sessionOwner);
    }

    private void sessionTabsChanged() {
        if (sessionWritable()) session.tabsChanged(tabsManager.getTabs(), regularTabId(activeTab()));
    }

    private void sessionMetadataChanged(TabModel tab) {
        if (sessionWritable()) session.metadataChanged(tab);
    }

    private void sessionHistoryChanged(TabModel tab, PrivacyWebView view, String eventUrl) {
        if (sessionWritable() && !tab.isIncognito() && eventUrl != null && eventUrl.equals(tab.getUrl())
                && currentPage(tab, view, tab.navigation.page(), false)) session.historyChanged(tab);
    }

    private void checkpointSession() {
        if (sessionWritable()) session.checkpointNow();
    }

    private boolean isLivePinned(TabModel tab) {
        // Fullscreen belongs to the selected view; switching exits it, not background-pins it.
        return tab.keepLive || tab.hasPageTransfer();
    }

    private PrivacyWebView allocateTabView(Context context) {
        // Normal excess is drained outside the switch. If a burst consumes the one-view
        // headroom before then, make room for this allocation first.
        if (sessionWritable()) {
            TabModel selected = activeTab();
            boolean selectedCounts = selected != null && selected.getWebView() != null && !isLivePinned(selected);
            int limit = TabViewBudget.inactiveBeforeAllocation(selectedCounts);
            TabModel victim;
            while ((victim = tabsManager.evictionTarget(limit, this::isLivePinned)) != null) {
                if (!parkInactiveTab(victim, true)) break;
            }
        }
        return PrivacyWebView.createTabView(context);
    }

    private boolean parkInactiveTab(TabModel tab, boolean preserveHistory) {
        PrivacyWebView view = tab.getWebView();
        if (tab == activeTab() || view == null || !sessionWritable() || (preserveHistory && isLivePinned(tab))) return false;
        Trace.beginSection("Browser.tabs.evict");
        try {
            if (preserveHistory) {
                tabPreviews.cancel(tab); // a previous snapshot can represent the parked row
            } else {
                // A rule change must retire the old page and history even if pinned.
                tabPreviews.remove(tab);
                session.forgetHistory(tab);
            }
            TabSession.Row row = session.checkpointForEviction(tab);
            if (row == null || tab == activeTab()) return false;
            if (!row.url.equals(tab.getUrl())) tabPreviews.remove(tab);
            if (!tab.park(view, row)) return false;
            traceTabResources();
            refreshTabGrid();
            return true;
        } finally { Trace.endSection(); }
    }

    private void traceTabResources() {
        if (tabsManager != null && Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
            Trace.setCounter("Browser.tabs.liveViews", TabViewBudget.count(tabsManager.getTabs(), tab -> tab.getWebView() != null));
        }
    }

    private void tabResourcesChanged() {
        traceTabResources();
        scheduleTabTrim();
    }

    private void scheduleTabTrim() {
        if (trimPosted || !sessionWritable() || isFinishing()) return;
        if (tabsManager.evictionTarget(inactiveTarget, this::isLivePinned) == null) {
            inactiveTarget = TabViewBudget.INACTIVE;
            return;
        }
        trimPosted = tabResourceWork.postDelayed(trimTabs, TAB_TRIM_DELAY_MS);
    }

    private void trimNextInactiveTab() {
        trimPosted = false;
        if (!sessionWritable() || isFinishing()) return;
        TabModel victim = tabsManager.evictionTarget(inactiveTarget, this::isLivePinned);
        if (victim != null && parkInactiveTab(victim, true)) scheduleTabTrim();
        else inactiveTarget = TabViewBudget.INACTIVE;
    }

    private void cancelTabTrim() {
        tabResourceWork.removeCallbacks(trimTabs);
        trimPosted = false;
        inactiveTarget = TabViewBudget.INACTIVE;
    }

    private void trimTabMemory() {
        tabPreviews.clear();
        refreshTabGrid();
        cancelTabTrim();
        inactiveTarget = 0;
        scheduleTabTrim();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !disposing) trimTabMemory();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (!disposing) trimTabMemory();
    }

    private void scheduleTabPreview(TabModel tab) {
        if (tab == null || tab != activeTab() || !resumed || disposing || tabGrid != null
                || tab.isIncognito() || !tab.navigation.canPreview() || InternalHome.ownsHost(tab.getUrl())
                || RequestContext.origin(tab.getUrl()) == null) return;
        PrivacyWebView view = tab.getWebView();
        TabNavigation.Page page = tab.navigation.page();
        tabPreviews.schedule(tab, view, page, getWindow(), () -> tabGrid == null && customVideoView == null
                && hasWindowFocus() && !editUrl.hasFocus() && !viewAddressScrim.isShown()
                && !omniboxContainer.isShown() && !findBar.isShown()
                && (toolbarSnapAnimator == null || !toolbarSnapAnimator.isRunning())
                && !BrowserDataCleaner.inProgress() && sessionWritable() && tab.navigation.canPreview()
                && view.getParent() == webViewContainer && currentPage(tab, view, page, true));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            hideOmniboxSuggestions();
            tabPreviews.cancel();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (disposing || isFinishing()) return;
        if (!startupReady) pendingLaunch = intent;
        else if (sessionWritable()) handleIncomingIntent(intent);
    }

    private String extractUrlFromIntent(Intent intent) {
        if (intent == null) return null;

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && !sharedText.trim().isEmpty()) {
                Pattern pattern = Pattern.compile("https?://[^\\s\"',;<>]+");
                Matcher matcher = pattern.matcher(sharedText);
                if (matcher.find()) {
                    return matcher.group();
                }
            }
        } else if (intent.getData() != null) {
            return intent.getData().toString();
        }
        return null;
    }

    private boolean isDownloadableUrl(String url) {
        if (url == null) return false;
        String cleanUrl = url.toLowerCase(Locale.US);
        int queryIdx = cleanUrl.indexOf('?');
        if (queryIdx != -1) {
            cleanUrl = cleanUrl.substring(0, queryIdx);
        }
        int hashIdx = cleanUrl.indexOf('#');
        if (hashIdx != -1) {
            cleanUrl = cleanUrl.substring(0, hashIdx);
        }
        String[] extensions = {
            ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz",
            ".apk", ".xapk", ".apks", ".iso", ".dmg", ".bin", ".exe",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv",
            ".mp3", ".flac", ".wav", ".aac", ".ogg", ".m4a",
            ".torrent"
        };
        for (String ext : extensions) {
            if (cleanUrl.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_OPEN_IN_INCOGNITO, false)) {
            setIntent(new Intent(this, MainActivity.class));
            explainPrivateUnavailable();
            return;
        }

        if (rejectExternalRecipe(intent)) {
            setIntent(new Intent(this, MainActivity.class));
            return;
        }

        String directUrl = intent.getStringExtra(EXTRA_LOAD_URL);
        boolean direct = directUrl != null && !directUrl.trim().isEmpty();
        String url = navigationTarget(direct ? directUrl : extractUrlFromIntent(intent));
        if (url == null) return;

        if (!direct && isDownloadableUrl(url)) {
            DownloadPromptDialog.show(this, url, RequestContext.anonymous(), null, null, -1);
            return;
        }
        dismissTabGrid();
        // Don't instantiate a view for an invalid link or restore the previous selection
        // before an admitted link has decided which tab will be visible.
        TabModel target = direct && !intent.getBooleanExtra(EXTRA_OPEN_IN_NEW_TAB, false) ? activeTab() : null;
        if (target == null) target = tabsManager.createTab(this, url);
        loadInTab(target, url);
    }

    static String navigationTarget(String candidate) {
        if (candidate == null) return null;
        String url = InternalHome.migrate(candidate.trim());
        return "about:blank".equals(url) || (RequestContext.origin(url) != null
                && (!InternalHome.ownsHost(url) || InternalHome.isHome(url))) ? url : null;
    }

    private boolean rejectExternalRecipe(Intent intent) {
        if (intent == null || !intent.hasExtra("automation_recipe_json")) return false;
        Toast.makeText(this, "Recipe intent blocked. Review recipes manually in the Agent menu.", Toast.LENGTH_LONG).show();
        return true;
    }

    private void materializeRestoredTab(TabModel tab, TabSession.Row row, TabSession.StateRead result) {
        if (!resumed || isFinishing() || !sessionWritable() || activeTab() != tab
                || tab.recovery() != row || tab.getWebView() != null) return;
        tab.discardRecovery();
        PrivacyWebView view = allocateTabView(this);
        tab.navigation.replaceView(view);
        tabResourcesChanged();
        applyDesktopMode(tab);
        setupClientsForTab(tab);
        ThemeManager.applyWebTheme(view, row.url);
        TabSessionCheckpoint.History history = null;
        if (result.bytes != null) {
            Trace.beginSection("Browser.tabs.nativeRestore");
            try { history = NativeTabSession.restore(view, result.bytes, row.url); }
            finally { Trace.endSection(); }
            if (history == null) {
                // A rejected provider bundle may have mutated its view. Do not load on top of it.
                rebuildPolicyView(tab, view);
                tab.navigation.seedRestoredUrl(row.url);
                tab.setTitle(row.title);
            }
        }
        if (history != null) {
            session.restore(tab, row, history);
            displayTab(tab);
        } else {
            session.forgetHistory(tab);
            if (tab.getWebView() == view) displayTab(tab); // rebuilt views are already attached
            Trace.beginSection("Browser.tabs.urlFallback");
            Trace.beginSection(result.status == TabSession.StateRead.Status.LOADED ? "Browser.tabs.stateRejected"
                    : result.status == TabSession.StateRead.Status.MISSING ? "Browser.tabs.stateMissing"
                    : result.status == TabSession.StateRead.Status.CORRUPT ? "Browser.tabs.stateCorrupt" : "Browser.tabs.urlOnly");
            try { loadInTab(tab, row.url); }
            finally { Trace.endSection(); Trace.endSection(); }
        }
    }

    private TabModel activeTab() {
        return tabsManager == null ? null : tabsManager.getActiveTab();
    }

    private boolean foreground(TabModel tab, PrivacyWebView view) {
        return resumed && !disposing && !isFinishing() && tab == activeTab() && tab.navigation.owns(view);
    }

    private boolean currentPage(TabModel tab, PrivacyWebView view, TabNavigation.Page page, boolean requireForeground) {
        return !disposing && tab.navigation.owns(view) && (!requireForeground || foreground(tab, view))
                && tab.navigation.current(view, page, view.getUrl());
    }

    private void attachTabToView(TabModel tab) {
        if (needsScriptPolicyRefresh(tab)) restartPolicyTab(tab);
        else displayTab(tab);
    }

    private void displayTab(TabModel tab) {
        if (displayedTab != tab) {
            stopMediaDiscovery();
            if (editUrl.hasFocus()) dismissKeyboard();
            tabPreviews.cancel();
            assistantForegroundEpoch++;
            dismissPageContextMenu();
            dismissComicDownload();
            cancelPicker(displayedTab);
            hideFullscreenVideo();
        }
        displayedTab = tab;
        hideOmniboxSuggestions();
        webViewContainer.removeAllViews();
        PrivacyWebView webView = tab.getWebView();
        TabSession.Row recovery = tab.recovery();
        if (webView != null) {
            tabsManager.touchView(tab);
            setupClientsForTab(tab);
            ThemeManager.applyWebTheme(webView, tab.getUrl());
            webView.applyPageZoom();
            webViewContainer.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tab.messages.activateHome();
        } else if (recovery != null) {
            showTabStatus(getString(R.string.browser_restoring_tab), 0, null);
        } else {
            showTabStatus(getString(R.string.renderer_lost_message), R.string.renderer_restore_tab, () -> restoreLostTab(tab));
        }
        if (agentBottomSheet != null) {
            agentBottomSheet.pageChanged();
            markAssistantDocument(tab);
        }
        renderChrome(tab);
        syncMediaDiscovery(tab, tab.navigation.target(), tab.navigation.finished());
        updateFloatingActionButtonsForActiveTab();
        requestSelectedRestore();
        scheduleTabPreview(tab);
    }

    private void requestSelectedRestore() {
        TabModel selected = activeTab();
        if (resumed && sessionWritable() && selected != null && selected.getWebView() == null) {
            lazyRestore.select(selected, selected.recovery());
        } else if (lazyRestore != null) {
            lazyRestore.cancel();
        }
    }

    /** Browser-owned scope. A cached URL or a restored-but-uncommitted page is not authority. */
    @Nullable public AssistantApproval.Page assistantPage() {
        TabModel tab = activeTab();
        if (tab == null || tab.isIncognito()) return null;
        PrivacyWebView view = tab.getWebView();
        TabNavigation.Page page = tab.navigation.page();
        String url = tab.getUrl();
        if (!currentPage(tab, view, page, true) || RequestContext.origin(url) == null) return null;
        return new AssistantApproval.Page(assistantActivityIdentity, tab.getId(), page.key, assistantForegroundEpoch, url);
    }

    private void markAssistantDocument(TabModel tab) {
        if (agentBottomSheet == null || tab == null || tab != activeTab() || assistantPage() == null) return;
        PrivacyWebView view = tab.getWebView();
        if (!allowsPageScripts(tab, view) || InternalHome.ownsHost(tab.getUrl())) return;
        // Defense in depth for asynchronous evaluation; native review and ownership do not trust this marker.
        view.evaluateJavascript(InternalHome.guardExternalPage(tab.getUrl(), "document.__privacyAssistantDocument="
                + JSONObject.quote(tab.navigation.page().key) + ";"), null);
    }

    public boolean evaluateApprovedAssistantScript(AssistantApproval.Page expected, String body, android.webkit.ValueCallback<String> callback) {
        if (!expected.matches(assistantPage()) || InternalHome.ownsHost(expected.url)) return false;
        TabModel tab = activeTab();
        PrivacyWebView view = tab.getWebView();
        if (!allowsPageScripts(tab, view)) return false;
        view.evaluateJavascript(InternalHome.guardExternalPage(expected.url,
                "if(document.__privacyAssistantDocument!==" + JSONObject.quote(tab.navigation.page().key) + ")return;\n" + body), callback);
        return true;
    }

    public boolean openApprovedAssistantLink(AssistantApproval.Page expected, String url, boolean newTab) {
        if (!expected.matches(assistantPage()) || RequestContext.origin(url) == null || InternalHome.ownsHost(url)) return false;
        TabModel target = newTab ? tabsManager.createTab(this, url) : activeTab();
        loadInTab(target, url);
        return true;
    }

    public boolean reloadApprovedAssistantPage(AssistantApproval.Page expected) {
        if (!expected.matches(assistantPage())) return false;
        reloadTab(activeTab());
        return true;
    }

    public String assistantTitle(AssistantApproval.Page expected) {
        if (!expected.matches(assistantPage())) return "";
        String title = activeTab().getTitle();
        return title == null ? "" : title.substring(0, Math.min(256, title.length()));
    }

    public void refreshApprovedRules() { refreshScriptPolicies(null); }

    private void restoreLostTab(TabModel tab) {
        if (tab != activeTab() || !tab.navigation.rendererLost() || disposing) return;
        loadInTab(tab, tab.getUrl() == null ? DEFAULT_HOME_URL : tab.getUrl());
    }

    private void loadInTab(TabModel tab, String url) {
        if (!startupReady || isFinishing() || !sessionWritable() || tab == null) return;
        url = navigationTarget(url);
        if (url == null) return;
        PrivacyWebView view = tab.getWebView();
        if (needsScriptPolicyRefresh(tab)) view = rebuildPolicyView(tab, view);
        if (view == null) {
            if (tab != activeTab() || (tab.recovery() == null && !tab.navigation.rendererLost())) return;
            lazyRestore.cancel();
            if (tab.recovery() != null) {
                // A direct navigation replaces dormant history instead of requesting the old page first.
                tab.discardRecovery();
                session.forgetHistory(tab);
            }
            view = allocateTabView(this);
            tab.navigation.replaceView(view);
            tabResourcesChanged();
            applyDesktopMode(tab);
            attachTabToView(tab);
        }
        setupClientsForTab(tab);
        startNavigation(tab, view, url);
        Trace.beginSection("Browser.tabs.navigate");
        try { view.loadUrl(url); } finally { Trace.endSection(); }
    }

    private void reloadTab(TabModel tab) {
        if (tab == null || !resumed || disposing || isFinishing() || refreshScriptPolicies(tab)) return;
        PrivacyWebView view = tab.getWebView();
        if (view == null) return;
        startNavigation(tab, view, tab.getUrl());
        view.reload();
    }

    private void navigateHistory(TabModel tab, int offset) {
        if (tab == null || tab != activeTab()) return;
        PrivacyWebView view = tab.getWebView();
        if (view == null || !view.canGoBackOrForward(offset)) return;
        android.webkit.WebBackForwardList history = view.copyBackForwardList();
        android.webkit.WebHistoryItem target = history.getItemAtIndex(history.getCurrentIndex() + offset);
        startNavigation(tab, view, target == null ? null : target.getUrl());
        view.goBackOrForward(offset);
    }

    private boolean allowsPageScripts(TabModel tab, PrivacyWebView view) {
        return tab.navigation.owns(view) && tab.scriptPolicy != null && tab.scriptPolicy.allowsScripts(view);
    }

    private PrivacyWebView rebuildPolicyView(TabModel tab, PrivacyWebView oldView) {
        if (fullscreenOwner == oldView) hideFullscreenVideo();
        if (tab == activeTab()) {
            dismissPageContextMenu();
            dismissComicDownload();
            cancelPicker(tab);
        }
        if (sessionWritable()) session.forgetHistory(tab);
        tabPreviews.remove(tab);
        tab.releaseView(oldView, false);
        PrivacyWebView view = allocateTabView(this);
        tab.navigation.replaceView(view);
        tabResourcesChanged();
        applyDesktopMode(tab);
        setupClientsForTab(tab);
        // A worker can publish a new rule revision here. Don't recurse through a policy
        // refresh that could destroy the very view returned to this navigation.
        if (tab == activeTab()) displayTab(tab);
        return view;
    }

    private boolean needsScriptPolicyRefresh(TabModel tab) {
        return tab.getWebView() != null && tab.scriptPolicy != null && !tab.scriptPolicy.isCurrent();
    }

    private void restartPolicyTab(TabModel tab) {
        if (tab != activeTab()) {
            parkInactiveTab(tab, false); // no network or new view until this row is selected
            return;
        }
        String url = InternalHome.migrate(tab.sessionUrl());
        rebuildPolicyView(tab, tab.getWebView());
        // Old closures, SPA state and the old back stack can retain injected code. Discard them
        // and navigate with a GET instead of potentially resubmitting a previous form.
        loadInTab(tab, RequestContext.origin(url) != null ? url : DEFAULT_HOME_URL);
    }

    private boolean refreshScriptPolicies(TabModel reloading) {
        if (!startupReady || !sessionWritable()) return false;
        long revision = WebScriptPolicy.revision();
        if (revision == checkedScriptRevision) return false;
        checkedScriptRevision = revision;
        boolean alreadyReloaded = false;
        for (TabModel tab : tabsManager.getTabs()) {
            if (!needsScriptPolicyRefresh(tab)) continue;
            restartPolicyTab(tab);
            alreadyReloaded |= tab == reloading;
        }
        return alreadyReloaded;
    }

    private void cancelPicker(TabModel tab) {
        if (tab != null && tab.messages != null) {
            tab.messages.cancelPicker();
            tab.messages.evaluate("if(window.__cleanupImageSearchPicker)window.__cleanupImageSearchPicker(true);"
                    + com.privacy.browser.adblock.picker.ElementPickerEngine.getCleanupScript());
        }
        if (pagePickerDialog != null) {
            pagePickerDialog.dismiss();
            pagePickerDialog = null;
        }
    }

    private void invalidatePage(TabModel tab) {
        if (tab == discoveryOwner) stopMediaDiscovery();
        TabNavigation.Page oldPage = tab.navigation.page();
        tab.navigation.abort(tab.getWebView());
        tab.updatePageScope(oldPage, tab.getWebView());
        clearPageEndpoints(tab);
    }

    private void clearPageEndpoints(TabModel tab) {
        if (tabPreviews.remove(tab)) refreshTabGrid();
        if (tab.messages != null) tab.messages.invalidate();
        if (tab.blobDownloads() != null) tab.blobDownloads().onNavigation();
        if (tab == activeTab()) {
            dismissVideoOptions();
            dismissPageContextMenu();
            dismissComicDownload();
            cancelPicker(tab);
            if (customVideoView != null) hideFullscreenVideo();
            if (agentBottomSheet != null) agentBottomSheet.pageChanged();
        }
    }

    private void startNavigation(TabModel tab, PrivacyWebView view, String url) {
        if (disposing || !tab.navigation.owns(view)) return;
        if (tab == discoveryOwner) stopMediaDiscovery();
        TabNavigation.Page oldPage = tab.navigation.page();
        if (!tab.navigation.start(view, url)) return;
        WebViewClient client = view.getWebViewClient();
        if (client instanceof PrivacyWebViewClient) ((PrivacyWebViewClient) client).setNavigationTarget(url);
        ThemeManager.applyWebTheme(view, url);
        view.applyPageZoom();
        tab.updatePageScope(oldPage, view);
        clearPageEndpoints(tab);
        if (tab == activeTab()) {
            if (!editUrl.hasFocus()) showTopBar(false);
            renderChrome(tab);
            syncMediaDiscovery(tab, url, false);
            updateFloatingActionButtonsForActiveTab();
        }
    }

    private void setupClientsForTab(TabModel tab) {
        PrivacyWebView webView = tab.getWebView();
        if (webView == null || tab.isInitialized()) return;
        tab.setInitialized(true);
        tab.scriptPolicy = new WebScriptPolicy(webView);

        webView.setToolbarCoordinator(new PrivacyWebView.ToolbarCoordinator() {
            @Override public boolean isToolbarLocked() {
                return !foreground(tab, webView) || isTopBarLocked();
            }
            @Override public int getToolbarHeight() { return getToolbarHeightPx(); }
            @Override public float onToolbarDrag(float deltaY) {
                return foreground(tab, webView) ? handleToolbarDrag(deltaY) : 0f;
            }
            @Override public void onToolbarRelease() {
                if (foreground(tab, webView)) handleToolbarRelease();
            }
            @Override public void cancelToolbarAnimation() {
                if (foreground(tab, webView)) cancelTopBarAnimation();
            }
        });
        webView.setOnScrollListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (foreground(tab, webView)) handlePageScroll(scrollY, oldScrollY);
        });
        tab.messages = new TabPageMessages(webView, !tab.isIncognito() && allowsPageScripts(tab, webView),
                page -> currentPage(tab, webView, page, false) && allowsPageScripts(tab, webView),
                (page, kind, payload, reply) -> receivePageMessage(tab, webView, page, kind, payload, reply));

        webView.getSettings().setSupportMultipleWindows(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (disposing || !tab.navigation.owns(webView) || view != webView) return;
                if (tab.navigation.updateProgress(webView, view.getUrl(), view.getProgress()) && tab == activeTab()) {
                    renderLoadControls(tab);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (view == webView && currentPage(tab, webView, tab.navigation.page(), false)
                        && java.util.Objects.equals(title, view.getTitle())) {
                    tab.setTitle(title);
                    sessionMetadataChanged(tab);
                    refreshTabGrid();
                }
            }

            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                if (view != webView || tab.isIncognito() || icon == null || InternalHome.ownsHost(tab.getUrl())
                        || !currentPage(tab, webView, tab.navigation.page(), false) || icon != view.getFavicon()) return;
                String domain = com.privacy.browser.favicon.FaviconManager.extractDomain(tab.getUrl());
                if (!domain.isEmpty()) com.privacy.browser.favicon.FaviconManager.getInstance(MainActivity.this).saveFavicon(domain, icon);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                TabNavigation.Page page = tab.navigation.page();
                if (!currentPage(tab, webView, page, true) || customVideoView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenOwner = webView;
                fullscreenPage = page;
                showFullscreenVideo(view, callback);
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenOwner == webView && currentPage(tab, webView, fullscreenPage, true)) hideFullscreenVideo();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                if (view != webView || !isUserGesture || !currentPage(tab, webView, tab.navigation.page(), true)) return false;
                TabModel newTab = tabsManager.createTab(MainActivity.this, "about:blank", tab.isIncognito());
                setupClientsForTab(newTab);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newTab.getWebView());
                resultMsg.sendToTarget();
                return true;
            }
        });

        BlobDownloadManager blob = tab.isIncognito() ? null : new BlobDownloadManager(webView,
                () -> foreground(tab, webView) && sessionWritable()
                        && TabViewBudget.count(tabsManager.getTabs(), TabModel::hasPageTransfer) < TabViewBudget.PAGE_TRANSFERS,
                () -> {
                    tabResourcesChanged();
                    refreshTabGrid();
                });
        tab.setBlobDownloads(blob);
        webView.setWebViewClient(new PrivacyWebViewClient(trackerBlocker, tab, this, new PrivacyWebViewClient.ClientListener() {
            @Override public void onPageStarted(String url) {
                startNavigation(tab, webView, url);
            }

            @Override public void onPageCommitted(String url) {
                if (disposing || !tab.navigation.owns(webView)) return;
                TabNavigation.Page oldPage = tab.navigation.page();
                if (!tab.navigation.commit(webView, url, webView.getUrl())) return;
                tab.updatePageScope(oldPage, webView);
                clearPageEndpoints(tab);
                tab.navigation.updateProgress(webView, url, webView.getProgress());
                tab.setTitle(webView.getTitle());
                tab.messages.bind(tab.navigation.page(), url);
                ThemeManager.applyWebTheme(webView, url);
                if (RequestContext.origin(url) != null && !InternalHome.ownsHost(url) && allowsPageScripts(tab, webView)) {
                    tab.scriptPolicy.onCommittedPage(url);
                    if (blob != null) blob.onDocumentCommitted(url);
                }
                if (!tab.isIncognito() && sessionWritable() && historyDbHelper != null
                        && RequestContext.origin(url) != null && !InternalHome.ownsHost(url)) {
                    historyDbHelper.addVisit(tab.getTitle(), url);
                }
                if (tab == activeTab()) {
                    markAssistantDocument(tab);
                    renderChrome(tab);
                    boolean routeChanged = oldPage != null && oldPage != tab.navigation.page();
                    syncMediaDiscovery(tab, url, tab.navigation.finished(), routeChanged);
                    updateFloatingActionButtonsForActiveTab();
                }
                sessionMetadataChanged(tab);
                refreshTabGrid();
                scheduleTabPreview(tab);
            }

            @Override public void onNativeHistoryChanged(String url) {
                sessionHistoryChanged(tab, webView, url);
            }

            @Override public void onPageVisible(String url) {
                if (!disposing && tab.navigation.markVisible(webView, url)) scheduleTabPreview(tab);
            }

            @Override public boolean onPageFinished(String url) {
                if (disposing || !tab.navigation.owns(webView) || !tab.navigation.finish(webView, url, webView.getUrl())) return false;
                if (tab == activeTab()) {
                    renderLoadControls(tab);
                    syncMediaDiscovery(tab, url, true);
                }
                sessionHistoryChanged(tab, webView, url);
                scheduleTabPreview(tab);
                return true;
            }

            @Override public void onPageFailed(String url) {
                if (disposing || !tab.navigation.owns(webView) || !tab.navigation.canFail(url, webView.getUrl())) return;
                invalidatePage(tab);
                if (tab == activeTab()) {
                    renderChrome(tab);
                    updateFloatingActionButtonsForActiveTab();
                }
            }

            @Override public void onTrackerBlocked(String pageKey) {
                shieldRefresh.changed(pageKey);
            }

            @Override public boolean canPrompt() { return foreground(tab, webView); }

            @Override public void onRendererLost() {
                if (fullscreenOwner == webView) {
                    customViewCallback = null;
                    hideFullscreenVideo();
                }
                boolean active = tab == activeTab();
                if (!tab.releaseView(webView, true) || disposing) return;
                tabPreviews.remove(tab);
                if (active) {
                    dismissPageContextMenu();
                    dismissComicDownload();
                    cancelPicker(tab);
                    attachTabToView(tab);
                }
                sessionMetadataChanged(tab);
                refreshTabGrid();
                tabResourcesChanged();
                // Do not query sibling WebViews here: their renderer callbacks may still be queued.
            }
        }));
        BrowserDownloadListener downloads = new BrowserDownloadListener(this, tab.isIncognito(), blob);
        webView.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            if (foreground(tab, webView)) downloads.onDownloadStart(url, userAgent, disposition, mime, length);
        });
        setupWebViewContextMenu(webView, tab);
    }

    private void receivePageMessage(TabModel tab, PrivacyWebView view, TabNavigation.Page page, String kind,
                                    JSONObject data, androidx.webkit.JavaScriptReplyProxy reply) {
        if (kind.startsWith("home:")) {
            if (!tab.isIncognito() && currentPage(tab, view, page, true)) handleHomeMessage(kind, reply);
            return;
        }
        if (tab.isIncognito()) return;
        if ("comic".equals(kind) || "comic-manual".equals(kind)) {
            if (page != discoveryPage || !canScanComic(tab)) return;
            boolean manual = "comic-manual".equals(kind);
            String proof = data.optString("picker");
            if (manual) {
                if (comicScanToken == null || !comicScanToken.equals(proof)) return;
                comicScanToken = null;
            } else if (!comicObservation.equals(proof)) return;
            JSONArray values = data.optJSONArray("value");
            List<String> urls = new ArrayList<>();
            if (values != null && values.length() <= ComicManager.MAX_PAGES) {
                for (int i = 0; i < values.length(); i++) {
                    Object raw = values.opt(i);
                    String url = raw instanceof String ? boundedWebUrl((String) raw) : null;
                    if (url == null) { urls.clear(); break; }
                    urls.add(url); // One position per detected page; even a repeated URL is a page.
                }
            }
            if (ComicManager.setDetectedComic(page.key, tab.getTitle(), urls)) {
                dismissComicDownload();
                updateFabComicDownload(urls.size());
            }
            if (manual) Toast.makeText(this, urls.isEmpty() ? R.string.comic_scan_empty : R.string.comic_scan_ready,
                    Toast.LENGTH_SHORT).show();
        } else if (currentPage(tab, view, page, true)) {
            if ("element".equals(kind)) {
                JSONArray nodes = data.optJSONArray("value");
                if (nodes == null) return;
                pagePickerDialog = com.privacy.browser.adblock.picker.ElementPickerDialog.show(this, view, tab.getUrl(), nodes,
                        () -> currentPage(tab, view, page, true), () -> reloadTab(tab));
            } else if ("image".equals(kind)) {
                String url = boundedWebUrl(data.optString("value"));
                if (url != null) showImageSearchEngineChooser(tab, url);
            }
        }
    }

    private static String boundedWebUrl(String url) {
        return url != null && url.length() <= PageMessageGate.MAX_URL && RequestContext.origin(url) != null
                && !InternalHome.ownsHost(url) ? url : null;
    }

    private void handleHomeMessage(String kind, androidx.webkit.JavaScriptReplyProxy reply) {
        switch (kind) {
            case "home:init":
                JSONArray items = new JSONArray();
                try {
                    if (bookmarkDbHelper != null) {
                        for (BookmarkModel bm : bookmarkDbHelper.getRecentBookmarks(6)) {
                            String url = boundedWebUrl(bm.getUrl());
                            if (url == null) continue;
                            String title = bm.getTitle();
                            items.put(new JSONObject().put("url", url).put("title", title == null ? "" : title.substring(0, Math.min(256, title.length()))));
                        }
                    }
                    reply.postMessage(new JSONObject().put("bookmarks", items)
                            .put("blocked", com.privacy.browser.adblock.AdBlockManager.getInstance(this).getTotalBlockedCount())
                            .put("theme", ThemeManager.isAppDarkTheme(this) ? "dark" : "light").toString());
                } catch (Exception e) {
                    Log.w("MainActivity", "Could not refresh local homepage data");
                }
                break;
            case "home:bookmarks": startActivity(new Intent(this, BookmarksActivity.class)); break;
            case "home:history": startActivity(new Intent(this, HistoryActivity.class)); break;
            case "home:downloads": startActivity(new Intent(this, com.privacy.downloader.ui.DownloadsActivity.class)); break;
            case "home:settings": startActivity(new Intent(this, SettingsActivity.class)); break;
            case "home:private": explainPrivateUnavailable(); break;
            case "home:theme":
                ThemeManager.setAppTheme(this, ThemeManager.isAppDarkTheme(this)
                        ? ThemeManager.APP_THEME_LIGHT : ThemeManager.APP_THEME_DARK);
                checkpointSession();
                recreate();
                break;
            default: break;
        }
    }

    private void explainPrivateUnavailable() {
        if (isFinishing() || disposing) return;
        new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                .setTitle("Private tabs unavailable")
                .setMessage(PrivateTabPolicy.MESSAGE + "\n\n" + BrowserDataPolicy.OLDER_PRIVATE_DATA)
                .setNegativeButton("Close", null)
                .setNeutralButton("Review cleanup options", (d, which) -> showClearDataChoices())
                .show();
    }

    private void renderLoadControls(TabModel tab) {
        int progress = tab.navigation.progress();
        btnReload.setImageResource(progress < 100 ? R.drawable.ic_close : R.drawable.ic_reload);
        progressBar.setProgress(progress);
        progressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
    }

    private void renderVisibleShieldCount(String pageKey) {
        TabModel tab = activeTab();
        if (tab != null && foreground(tab, tab.getWebView()) && pageKey.equals(tab.pageKey())) {
            renderShieldCount(pageKey);
        }
    }

    private void renderShieldCount(String pageKey) {
        int count = trackerBlocker.manager().getPageBlockedCount(pageKey);
        if (count == shieldCount) return;
        android.os.Trace.beginSection("Browser.filters.shieldCount");
        try {
            shieldCount = count;
            btnShield.setText(String.valueOf(count));
        } finally { android.os.Trace.endSection(); }
    }

    private void renderShield(TabModel tab) {
        boolean selected = resumed && !disposing && !isFinishing() && tab == activeTab();
        String pageKey = tab.isIncognito() ? null : tab.pageKey();
        shieldRefresh.show(selected && tab.getWebView() != null ? pageKey : null);
        if (!selected) return;
        int icon = tab.isIncognito() ? R.drawable.ic_incognito : R.drawable.ic_shield;
        if (icon != shieldIcon) {
            shieldIcon = icon;
            btnShield.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        }
        renderShieldCount(pageKey);
    }

    private void renderChrome(TabModel tab) {
        renderLoadControls(tab);
        renderShield(tab);
        displayFormattedUrl(tab.getUrl());
    }

    private void displayFormattedUrl(String url) {
        if (editUrl.hasFocus()) return;
        TabModel tab = activeTab();
        PrivacyWebView view = tab == null ? null : tab.getWebView();
        ImageView security = findViewById(R.id.btn_site_security);
        boolean local = url == null || isHomePageUrl(url) || "about:blank".equals(url);
        editUrl.setText(local ? "" : url);
        editUrl.setHint(R.string.search_hint);
        if (security != null) {
            boolean committedWeb = !local && view != null && currentPage(tab, view, tab.navigation.page(), false)
                    && boundedWebUrl(url) != null;
            security.setEnabled(committedWeb);
            security.setAlpha(committedWeb ? 1f : 0.45f);
            security.setColorFilter(committedWeb && url.startsWith("http://") ? Color.parseColor("#FF5252")
                    : ThemeManager.isAppDarkTheme(this) ? Color.WHITE : Color.parseColor("#111827"));
        }
    }

    private String getDefaultHomeUrl() { return DEFAULT_HOME_URL; }

    public static boolean isHomePageUrl(String url) {
        return InternalHome.isHome(InternalHome.migrate(url));
    }

    private boolean isCurrentTabOnHomePage() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        return active == null || active.isIncognito() || !active.navigation.ready() || isHomePageUrl(active.getUrl());
    }

    private void updateFloatingActionButtonsForActiveTab() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null) {
            int detectedCount = active.navigation.page() == null ? 0 : active.navigation.page().media.count();
            updateFabVideoDownload(detectedCount);
            ComicManager.ComicItem comicItem =
                    ComicManager.getDetectedComic(active.pageKey());
            updateFabComicDownload(comicItem != null ? comicItem.getPageCount() : 0);
        } else {
            updateFabVideoDownload(0);
            updateFabComicDownload(0);
        }
    }

    private void navigateToInput(String input) {
        if (TextUtils.isEmpty(input)) return;
        String query = input.trim();

        TabModel active = tabsManager.getActiveTab();
        if (active == null) return;

        hideOmniboxSuggestions();

        if (query.regionMatches(true, 0, "javascript:", 0, "javascript:".length())) {
            // Bookmarklets are a deliberate address-bar action, not a transferable link type.
            PrivacyWebView view = active.getWebView();
            if (currentPage(active, view, active.navigation.page(), true) && allowsPageScripts(active, view)
                    && RequestContext.origin(active.getUrl()) != null && !InternalHome.ownsHost(active.getUrl())) {
                view.loadUrl(query);
            } else {
                Toast.makeText(this, R.string.bookmarklet_unavailable, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String targetUrl;
        if (SearchEngineManager.isBangsEnabled(this) && SearchEngineManager.detectBang(query) != null) {
            targetUrl = SearchEngineManager.buildSearchUrl(this, query);
        } else if (query.startsWith("http://") || query.startsWith("https://") || query.startsWith("about:") || InternalHome.isLegacy(query)) {
            targetUrl = query;
        } else if (isLocalHostOrIp(query)) {
            targetUrl = "http://" + query;
        } else if (query.contains(".") && !query.contains(" ")) {
            targetUrl = "https://" + query;
        } else {
            targetUrl = SearchEngineManager.buildSearchUrl(this, query);
        }

        loadInTab(active, targetUrl);
    }

    private boolean isLocalHostOrIp(String query) {
        if (query == null) return false;
        String lower = query.toLowerCase().trim();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                Uri uri = Uri.parse(lower);
                lower = uri.getHost() != null ? uri.getHost() : lower;
            } catch (Exception ignored) {}
        }
        if (lower.equals("localhost") || lower.startsWith("localhost:") || lower.startsWith("localhost/")) {
            return true;
        }
        if (lower.startsWith("127.0.0.1") || lower.startsWith("10.0.2.2") || lower.startsWith("0.0.0.0")) {
            return true;
        }
        if (lower.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?(/.*)?$")) {
            return true;
        }
        return false;
    }

    private boolean canEditOmnibox() {
        return startupReady && resumed && !disposing && !isFinishing() && hasWindowFocus()
                && editUrl.hasFocus() && tabGrid == null && customVideoView == null && !BrowserDataCleaner.inProgress()
                && SearchEngineManager.isAutocompleteEnabled(this);
    }

    private boolean canShowOmniboxRequest(OmniboxRequests.Request request) {
        TabModel active = activeTab();
        return active != null && canEditOmnibox() && request.matches(active.getId(), active.isIncognito(), editUrl.getText());
    }

    private void updateOmniboxSuggestions(CharSequence input) {
        Trace.beginSection("Browser.omnibox.edit");
        try {
            TabModel active = activeTab();
            if (active == null || !canEditOmnibox()) {
                hideOmniboxSuggestions();
                return;
            }
            OmniboxRequests.Request request = omniboxRequests.submit(active.getId(), active.isIncognito(), input);
            if (request == null) clearOmniboxRows();
            else renderOmnibox(request, Collections.emptyList()); // immediate fallback, no local lookup or clipboard read
        } finally {
            Trace.endSection();
        }
    }

    private void publishLocalOmnibox(OmniboxRequests.Request request, List<OmniboxSuggestion> local) {
        Trace.beginSection("Browser.omnibox.localRows");
        try {
            boolean alreadyOffered = omniboxClipboard.offer(request.incognito, request.query) != null;
            omniboxClipboard.inspect(request.incognito, this::readOmniboxClipboard);
            if (!local.isEmpty() || (!alreadyOffered && omniboxClipboard.offer(request.incognito, request.query) != null)) {
                renderOmnibox(request, local);
            }
        } finally {
            Trace.endSection();
        }
    }

    private CharSequence readOmniboxClipboard() {
        Trace.beginSection("Browser.omnibox.clipboard");
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = manager == null ? null : manager.getPrimaryClip();
            return clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
        } catch (RuntimeException unavailable) {
            return null;
        } finally {
            Trace.endSection();
        }
    }

    private void renderOmnibox(OmniboxRequests.Request request, List<OmniboxSuggestion> local) {
        if (omniboxAdapter == null || !omniboxRequests.isCurrent(request)) return;
        String query = request.query;
        List<OmniboxSuggestion> suggestions = new ArrayList<>(10);
        String clip = omniboxClipboard.offer(request.incognito, query);
        if (clip != null) {
            boolean localAddress = isLocalHostOrIp(clip);
            boolean webAddress = clip.startsWith("http://") || clip.startsWith("https://");
            boolean link = webAddress || localAddress || (clip.contains(".") && !clip.contains(" "));
            String target = !link ? SearchEngineManager.buildSearchUrl(this, clip)
                    : webAddress ? clip : (localAddress ? "http://" : "https://") + clip;
            suggestions.add(new OmniboxSuggestion(OmniboxSuggestion.Type.CLIPBOARD,
                    link ? "Link you copied" : "Text you copied", clip, target, R.drawable.ic_find));
        }

        if (isLocalHostOrIp(query)) {
            String target = query.startsWith("http://") || query.startsWith("https://") ? query : "http://" + query;
            suggestions.add(new OmniboxSuggestion(OmniboxSuggestion.Type.SEARCH, query,
                    "Open " + target, target, R.drawable.ic_find));
        }
        SearchEngineManager.BangResult bang = SearchEngineManager.isBangsEnabled(this)
                ? SearchEngineManager.detectBang(query) : null;
        if (bang != null) {
            String title = bang.getServiceName() + " Search (" + bang.getTag() + ")";
            String subtitle = bang.getCleanQuery().isEmpty() ? "Search on " + bang.getServiceName() : bang.getCleanQuery();
            suggestions.add(new OmniboxSuggestion(OmniboxSuggestion.Type.BANG, title, subtitle,
                    bang.getTargetUrl(), R.drawable.ic_search));
        }

        for (OmniboxSuggestion row : local) {
            if (row.getType() == OmniboxSuggestion.Type.HISTORY) {
                if (request.incognito || suggestions.stream().anyMatch(s -> s.getTargetUrl().equalsIgnoreCase(row.getTargetUrl()))) {
                    continue;
                }
            }
            suggestions.add(row);
        }
        SearchEngineManager.SearchEngine engine = SearchEngineManager.getSelectedEngine(this);
        String searchTarget = bang != null ? bang.getTargetUrl() : SearchEngineManager.buildSearchUrl(this, query);
        suggestions.add(new OmniboxSuggestion(OmniboxSuggestion.Type.SEARCH, query,
                "Search with " + engine.getName(), searchTarget, R.drawable.ic_search));
        visibleOmniboxRequest = request;
        omniboxAdapter.updateItems(suggestions);
        omniboxContainer.setVisibility(View.VISIBLE);
    }

    private void clearOmniboxRows() {
        visibleOmniboxRequest = null;
        if (omniboxContainer != null) omniboxContainer.setVisibility(View.GONE);
        if (omniboxAdapter != null && omniboxAdapter.getCount() != 0) omniboxAdapter.clear();
    }

    private void hideOmniboxSuggestions() {
        if (omniboxRequests != null) omniboxRequests.invalidate();
        omniboxClipboard.reset();
        clearOmniboxRows();
    }

    // =========================================================================
    // Modern Browser Actions (Tabs, Reader Mode, Desktop Mode, Fire Nuke)
    // =========================================================================

    private void showTabsDialog() {
        if (!resumed || !sessionWritable() || isFinishing() || tabGrid != null) return;
        dismissVideoOptions();
        if (editUrl.hasFocus()) dismissKeyboard();
        tabPreviews.cancel(); // only existing previews or placeholders enter the grid
        Trace.beginSection("Browser.tabs.openGrid");
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
            View content = LayoutInflater.from(this).inflate(R.layout.dialog_tabs_grid, null);
            AlertDialog dialog = builder.setView(content).create();
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            RecyclerView recycler = content.findViewById(R.id.recycler_tabs);
            recycler.setLayoutManager(new GridLayoutManager(this, 2));

            class Card extends RecyclerView.ViewHolder {
                final TextView title, url, status;
                final ImageView preview;
                final View privateIcon, active;
                TabModel tab;

                Card(View item) {
                    super(item);
                    title = item.findViewById(R.id.tab_card_title);
                    url = item.findViewById(R.id.tab_card_url);
                    status = item.findViewById(R.id.tab_card_status);
                    preview = item.findViewById(R.id.tab_card_preview);
                    privateIcon = item.findViewById(R.id.tab_icon_incognito);
                    active = item.findViewById(R.id.tab_active_indicator);
                    item.setOnClickListener(v -> {
                        int index = tabsManager.getTabs().indexOf(tab);
                        if (index < 0 || tabGrid != dialog) return;
                        dismissTabGrid();
                        tabsManager.switchTab(index);
                    });
                    item.findViewById(R.id.tab_card_btn_close).setOnClickListener(v -> closeGridTab(dialog, tab));
                }
            }
            RecyclerView.Adapter<Card> adapter = new RecyclerView.Adapter<Card>() {
                @NonNull @Override public Card onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    return new Card(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tab_grid_card, parent, false));
                }

                @Override public void onBindViewHolder(@NonNull Card holder, int position) {
                    TabModel tab = tabsManager.getTabs().get(position);
                    holder.tab = tab;
                    holder.title.setText(tab.getTitle());
                    String url = tab.sessionUrl();
                    holder.url.setText(url == null ? "" : url.replaceFirst("^https?://", ""));
                    holder.privateIcon.setVisibility(tab.isIncognito() ? View.VISIBLE : View.GONE);
                    holder.active.setVisibility(tab == activeTab() ? View.VISIBLE : View.GONE);
                    int label = tab.keepLive ? R.string.tab_kept_live
                            : tab.hasPageTransfer() ? R.string.tab_live_for_download
                            : tab.navigation.rendererLost() ? R.string.tab_needs_restore
                            : tab.recovery() != null ? R.string.tab_saved : 0;
                    holder.status.setVisibility(label == 0 ? View.GONE : View.VISIBLE);
                    if (label != 0) holder.status.setText(label);
                    holder.preview.setImageDrawable(tabPreviews.drawable(tab));
                }

                @Override public void onViewRecycled(@NonNull Card holder) {
                    holder.preview.setImageDrawable(null);
                    holder.tab = null;
                }

                @Override public int getItemCount() { return tabsManager.getTabCount(); }
            };
            ItemTouchHelper swipe = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
                @Override public boolean onMove(@NonNull RecyclerView list, @NonNull RecyclerView.ViewHolder holder,
                                                @NonNull RecyclerView.ViewHolder target) { return false; }
                @Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                    closeGridTab(dialog, ((Card) holder).tab);
                }
            });
            tabGrid = dialog;
            tabGridTitle = content.findViewById(R.id.tabs_grid_title);
            tabGridAdapter = adapter;
            recycler.setAdapter(adapter);
            swipe.attachToRecyclerView(recycler);
            dialog.setOnDismissListener(ignored -> {
                clearTabGrid(dialog);
                swipe.attachToRecyclerView(null);
                recycler.setAdapter(null);
            });

            content.findViewById(R.id.tabs_btn_new_tab).setOnClickListener(v -> {
                if (tabGrid != dialog || !resumed || !sessionWritable()) return;
                dismissTabGrid();
                String home = getDefaultHomeUrl();
                loadInTab(tabsManager.createTab(this, home), home);
            });
            content.findViewById(R.id.tabs_btn_new_incognito).setOnClickListener(v -> {
                if (tabGrid != dialog || !resumed || !sessionWritable()) return;
                dismissTabGrid();
                explainPrivateUnavailable();
            });
            content.findViewById(R.id.tabs_btn_close_all).setOnClickListener(v -> {
                if (tabGrid != dialog || closeTabsConfirmation != null) return;
                AlertDialog confirm = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                        .setTitle(R.string.tabs_close_all)
                        .setMessage(getString(R.string.tabs_close_all_confirm, tabsManager.getTabCount()))
                        .setPositiveButton(R.string.tabs_close_all, (d, w) -> {
                            if (tabGrid != dialog || !resumed || !sessionWritable()) return;
                            dismissTabGrid();
                            tabsManager.closeAllTabs(this);
                        }).setNegativeButton(android.R.string.cancel, null).create();
                closeTabsConfirmation = confirm;
                confirm.setOnDismissListener(d -> {
                    if (closeTabsConfirmation == confirm) closeTabsConfirmation = null;
                });
                confirm.show();
                applyAmoledDialogTheme(confirm);
            });
            refreshTabGrid();
            dialog.show();
        } finally { Trace.endSection(); }
    }

    private void closeGridTab(AlertDialog dialog, TabModel tab) {
        if (tabGrid != dialog || tab == null) return;
        int index = tabsManager.getTabs().indexOf(tab);
        if (index < 0) return;
        if (tabsManager.getTabCount() == 1) dismissTabGrid();
        tabsManager.closeTab(index, this);
    }

    private void refreshTabGrid() {
        if (tabGridAdapter == null) return;
        tabGridTitle.setText(getString(R.string.tabs_open_count, tabsManager.getTabCount()));
        tabGridAdapter.notifyDataSetChanged();
    }

    private void clearTabGrid(AlertDialog dialog) {
        if (tabGrid != dialog) return;
        tabGrid = null;
        tabGridAdapter = null;
        tabGridTitle = null;
        if (closeTabsConfirmation != null) {
            closeTabsConfirmation.dismiss();
            closeTabsConfirmation = null;
        }
    }

    private void dismissTabGrid() {
        AlertDialog dialog = tabGrid;
        if (dialog != null) {
            clearTabGrid(dialog);
            dialog.dismiss();
        }
    }

    private void showBrowserMenu() {
        TabModel active = tabsManager.getActiveTab();
        if (active == null) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        View popupView = inflater.inflate(R.layout.popup_browser_menu, null);

        int widthPx = (int) (220 * getResources().getDisplayMetrics().density);
        PopupWindow popupWindow = new PopupWindow(popupView, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(20);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // Configure Top Navigation Bar (Back, Forward, Reload)
        ImageButton navBack = popupView.findViewById(R.id.menu_nav_back);
        ImageButton navForward = popupView.findViewById(R.id.menu_nav_forward);
        ImageButton navReload = popupView.findViewById(R.id.menu_nav_reload);

        boolean isDark = ThemeManager.isAppDarkTheme(this);
        int activeIconColor = isDark ? Color.WHITE : Color.parseColor("#111827");
        int disabledIconColor = isDark ? Color.parseColor("#444444") : Color.parseColor("#D1D5DB");

        if (active.getWebView() != null && active.getWebView().canGoBack()) {
            navBack.setEnabled(true);
            navBack.setColorFilter(activeIconColor);
            navBack.setOnClickListener(v -> {
                popupWindow.dismiss();
                navigateHistory(active, -1);
            });
        } else {
            navBack.setEnabled(false);
            navBack.setColorFilter(disabledIconColor);
        }

        if (active.getWebView() != null && active.getWebView().canGoForward()) {
            navForward.setEnabled(true);
            navForward.setColorFilter(activeIconColor);
            navForward.setOnClickListener(v -> {
                popupWindow.dismiss();
                navigateHistory(active, 1);
            });
        } else {
            navForward.setEnabled(false);
            navForward.setColorFilter(disabledIconColor);
        }

        navReload.setColorFilter(activeIconColor);
        navReload.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (active.getWebView() != null) reloadTab(active);
            else restoreLostTab(active);
        });

        // Configure Bookmark item
        boolean isCurrentBookmarked = bookmarkDbHelper != null && bookmarkDbHelper.isBookmarked(active.getUrl());
        ImageView iconBookmark = popupView.findViewById(R.id.menu_icon_bookmark);
        TextView textBookmark = popupView.findViewById(R.id.menu_text_bookmark);
        if (isCurrentBookmarked) {
            iconBookmark.setImageResource(R.drawable.ic_bookmark_filled);
            textBookmark.setText("Remove Bookmark");
        } else {
            iconBookmark.setImageResource(R.drawable.ic_bookmark);
            textBookmark.setText("Add to Bookmarks");
        }

        popupView.findViewById(R.id.menu_item_bookmark).setOnClickListener(v -> {
            popupWindow.dismiss();
            toggleBookmarkCurrentPage(active);
        });

        popupView.findViewById(R.id.menu_item_bookmarks_list).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(MainActivity.this, BookmarksActivity.class));
        });

        popupView.findViewById(R.id.menu_item_history).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(MainActivity.this, HistoryActivity.class));
        });

        popupView.findViewById(R.id.menu_item_downloads).setOnClickListener(v -> {
            popupWindow.dismiss();
            startActivity(new Intent(this, com.privacy.downloader.ui.DownloadsActivity.class));
        });

        popupView.findViewById(R.id.menu_item_grab_links).setOnClickListener(v -> {
            popupWindow.dismiss();
            extractAndGrabPageLinks(active);
        });

        View scanComic = popupView.findViewById(R.id.menu_item_scan_comic);
        scanComic.setVisibility(canScanComic(active) ? View.VISIBLE : View.GONE);
        scanComic.setOnClickListener(v -> {
            popupWindow.dismiss();
            scanComicPages(active);
        });

        // Ad Blocker Shield item
        View itemAdblock = popupView.findViewById(R.id.menu_item_adblock);
        if (itemAdblock != null) {
            TextView badgeAdblock = popupView.findViewById(R.id.menu_badge_adblock);
            ImageView iconAdblock = popupView.findViewById(R.id.menu_icon_adblock);
            com.privacy.browser.adblock.AdBlockManager abm = com.privacy.browser.adblock.AdBlockManager.getInstance(this);
            boolean isWhitelisted = abm.isSiteWhitelisted(active.getUrl());
            int blockedCount = abm.getPageBlockedCount(active.pageKey());
            if (badgeAdblock != null) {
                if (isWhitelisted || !abm.isGlobalEnabled()) {
                    badgeAdblock.setText("OFF");
                    badgeAdblock.setTextColor(Color.GRAY);
                    if (iconAdblock != null) iconAdblock.setColorFilter(Color.GRAY);
                } else {
                    badgeAdblock.setText(String.valueOf(blockedCount));
                    badgeAdblock.setTextColor(Color.parseColor("#4CAF50"));
                    if (iconAdblock != null) iconAdblock.setColorFilter(Color.parseColor("#4CAF50"));
                }
            }
            itemAdblock.setOnClickListener(v -> {
                popupWindow.dismiss();
                com.privacy.browser.adblock.AdBlockDashboardDialog.show(
                        MainActivity.this,
                        active.pageKey(),
                        active.getUrl(),
                        () -> reloadTab(active),
                        () -> startElementZapper(),
                        () -> startElementPicker(),
                        () -> showMyFilters()
                );
            });
        }

        // Agent toggle item (directly below Ad Blocker)
        View itemAgent = popupView.findViewById(R.id.menu_item_agent);
        if (itemAgent != null) {
            android.widget.Switch switchAgent = popupView.findViewById(R.id.menu_switch_agent);
            final com.privacy.browser.ai.AiConfigManager aiConfig = new com.privacy.browser.ai.AiConfigManager(this);
            boolean isAgentEnabled = aiConfig.isAgentEnabled();
            if (switchAgent != null) {
                switchAgent.setChecked(isAgentEnabled);
            }
            itemAgent.setOnClickListener(v -> {
                boolean newState = !aiConfig.isAgentEnabled();
                aiConfig.setAgentEnabled(newState);
                if (!newState && agentBottomSheet != null) agentBottomSheet.disabled();
                if (switchAgent != null) switchAgent.setChecked(newState);
                updateAiFloatingButtonVisibility();
                popupWindow.dismiss();
            });
        }

        // Search by Image Hub item (directly below Ad Blocker)
        View itemImageSearch = popupView.findViewById(R.id.menu_item_image_search);
        if (itemImageSearch != null) {
            itemImageSearch.setOnClickListener(v -> {
                popupWindow.dismiss();
                showImageSearchEngineChooser(active, null);
            });
        }

        View pin = popupView.findViewById(R.id.menu_item_keep_tab_live);
        TextView pinLabel = pin.findViewById(R.id.menu_label_keep_tab_live);
        pinLabel.setText(active.keepLive ? R.string.tab_release_pin : R.string.tab_keep_live);
        pin.setVisibility(canToggleLivePin(active) ? View.VISIBLE : View.GONE);
        pin.setOnClickListener(v -> {
            popupWindow.dismiss();
            toggleLivePin(active);
        });

        // Desktop Site mode
        TextView badgeDesktop = popupView.findViewById(R.id.menu_badge_desktop);
        if (active.isDesktopMode()) {
            badgeDesktop.setText("ON");
            badgeDesktop.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            badgeDesktop.setText("OFF");
            badgeDesktop.setTextColor(Color.GRAY);
        }
        popupView.findViewById(R.id.menu_item_desktop).setOnClickListener(v -> {
            popupWindow.dismiss();
            toggleDesktopMode(active);
        });

        popupView.findViewById(R.id.menu_item_reader).setOnClickListener(v -> {
            popupWindow.dismiss();
            if (allowsPageScripts(active, active.getWebView())) ReaderModeHelper.toggleReaderMode(active.getWebView());
        });

        popupView.findViewById(R.id.menu_item_find).setOnClickListener(v -> {
            popupWindow.dismiss();
            showTopBar(false);
            findBar.setVisibility(View.VISIBLE);
            editFind.requestFocus();
            showKeyboard(editFind);
        });

        popupView.findViewById(R.id.menu_item_share).setOnClickListener(v -> {
            popupWindow.dismiss();
            shareCurrentPage(active);
        });

        View itemSettings = popupView.findViewById(R.id.menu_item_settings);
        if (itemSettings != null) {
            itemSettings.setOnClickListener(v -> {
                popupWindow.dismiss();
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            });
        }

        popupView.findViewById(R.id.menu_item_nuke).setOnClickListener(v -> {
            popupWindow.dismiss();
            showClearDataChoices();
        });

        int xOffset = (int) (-180 * getResources().getDisplayMetrics().density);
        int yOffset = (int) (6 * getResources().getDisplayMetrics().density);
        popupWindow.showAsDropDown(btnMenu, xOffset, yOffset);
    }

    private boolean canToggleLivePin(TabModel tab) {
        PrivacyWebView view = tab.getWebView();
        return !tab.isIncognito() && foreground(tab, view) && sessionWritable() && (tab.keepLive
                || (currentPage(tab, view, tab.navigation.page(), true) && !InternalHome.ownsHost(tab.getUrl())
                && RequestContext.origin(tab.getUrl()) != null));
    }

    private void toggleLivePin(TabModel tab) {
        if (!canToggleLivePin(tab)) return;
        if (!tab.keepLive && TabViewBudget.count(tabsManager.getTabs(), saved -> saved.keepLive) >= TabViewBudget.MANUAL_PINS) {
            Toast.makeText(this, getString(R.string.tab_pin_limit, TabViewBudget.MANUAL_PINS), Toast.LENGTH_LONG).show();
            return;
        }
        tab.keepLive = !tab.keepLive;
        Toast.makeText(this, tab.keepLive ? R.string.tab_pin_enabled : R.string.tab_pin_released, Toast.LENGTH_SHORT).show();
        tabResourcesChanged();
    }

    private void extractAndGrabPageLinks(TabModel active) {
        if (active == null || active.getWebView() == null || rejectPrivateDownloads(active)) return;
        PrivacyWebView view = active.getWebView();
        TabNavigation.Page page = active.navigation.page();
        if (!currentPage(active, view, page, true) || !allowsPageScripts(active, view)) return;
        RequestContext request = downloadContext(active);
        // The link grabber is an explicit DOM snapshot, not another path to automatic network probes.
        evaluateMediaSnapshot(active, view, MediaPageScripts.LINKS_JS, value -> {
            if (!currentPage(active, view, page, true)) return;
            JSONArray result = new JSONArray();
            java.util.Set<String> seen = new java.util.HashSet<>();
            int chars = 0;
            try {
                Predicate<String> allowed = mediaOptionsPolicy(view);
                if (videoDiscoveryEnabled) {
                    for (MediaSniffer.SniffedMedia row : page.media.snapshot()) {
                        if (!allowed.test(row.getUrl()) || !seen.add(row.getUrl())) continue;
                        JSONObject item = new JSONObject().put("url", row.getUrl()).put("title", row.getTitle()).put("type", row.getType());
                        chars += item.toString().length() + 1;
                        if (chars > 120 * 1024) break;
                        result.put(item);
                    }
                }
                JSONArray dom = mediaSnapshot(value);
                for (int i = 0; dom != null && i < Math.min(160, dom.length()); i++) {
                    JSONObject raw = dom.optJSONObject(i);
                    if (raw == null) continue;
                    String url = boundedWebUrl(raw.optString("url"));
                    if (url == null || !seen.add(url)) continue;
                    JSONObject item = new JSONObject().put("url", url)
                            .put("title", clipLabel(raw.optString("title"), 100)).put("type", clipLabel(raw.optString("type"), 16));
                    chars += item.toString().length() + 1;
                    if (chars > 120 * 1024) break;
                    result.put(item);
                }
                if (result.length() != 0) LinkGrabberActivity.launch(this, result.toString(), request);
                else Toast.makeText(this, R.string.media_no_links, Toast.LENGTH_SHORT).show();
            } catch (org.json.JSONException ignored) {
                Toast.makeText(this, R.string.media_no_links, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String clipLabel(String value, int limit) { return value.substring(0, Math.min(limit, value.length())); }

    private void setupWebViewContextMenu(PrivacyWebView webView, TabModel tab) {
        webView.setOnLongClickListener(v -> {
            TabNavigation.Page page = tab.navigation.page();
            if (!currentPage(tab, webView, page, true)) return false;
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result == null) return false;
            RequestContext request = downloadContext(tab);
            int type = result.getType();
            String extra = result.getExtra();

            if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                // Image wrapped in an anchor link: retrieve both the link href and image source
                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                android.os.Message msg = handler.obtainMessage();
                msg.setTarget(new android.os.Handler(android.os.Looper.getMainLooper(), m -> {
                    if (!currentPage(tab, webView, page, true)) return true;
                    String linkUrl = m.getData().getString("url");
                    String srcUrl = m.getData().getString("src");
                    if (linkUrl == null || linkUrl.isEmpty()) linkUrl = extra;
                    if (srcUrl == null || srcUrl.isEmpty()) srcUrl = extra;
                    showTabbedContextMenu(tab, linkUrl, srcUrl, true, true, request);
                    return true;
                }));
                webView.requestFocusNodeHref(msg);
                return true;
            } else if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    showTabbedContextMenu(tab, extra, null, true, false, request);
                    return true;
                }
            } else if (type == WebView.HitTestResult.IMAGE_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    showTabbedContextMenu(tab, null, extra, false, true, request);
                    return true;
                }
            }
            return false;
        });
    }

    private void showTabbedContextMenu(TabModel tab, String linkUrl, String imageUrl, boolean hasLink, boolean hasImage, RequestContext request) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(MainActivity.this));
        dismissPageContextMenu();
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_tabbed_context_menu, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        View tabBar = view.findViewById(R.id.ctx_tab_bar);
        TextView tabLink = view.findViewById(R.id.ctx_tab_link);
        TextView tabImage = view.findViewById(R.id.ctx_tab_image);
        TextView title = view.findViewById(R.id.ctx_header_title);
        ImageView headerIcon = view.findViewById(R.id.ctx_header_icon);
        LinearLayout actionsContainer = view.findViewById(R.id.ctx_actions_container);

        final boolean[] isShowingLink = new boolean[]{hasLink};

        if (hasLink && hasImage) {
            tabBar.setVisibility(View.VISIBLE);
        } else {
            tabBar.setVisibility(View.GONE);
        }

        Runnable renderActions = () -> {
            actionsContainer.removeAllViews();
            if (isShowingLink[0] && linkUrl != null) {
                title.setText(linkUrl);
                headerIcon.setImageResource(R.drawable.ic_share);
                boolean isDark = ThemeManager.isAppDarkTheme(MainActivity.this);
                int activeTextColor = isDark ? Color.WHITE : Color.parseColor("#111827");
                int inactiveTextColor = isDark ? Color.parseColor("#8E8EA8") : Color.parseColor("#6B7280");
                int activeBgRes = isDark ? R.drawable.bg_tab_card_active : R.drawable.bg_tab_card_active_light;

                tabLink.setBackgroundResource(activeBgRes);
                tabLink.setTextColor(activeTextColor);
                tabImage.setBackground(null);
                tabImage.setTextColor(inactiveTextColor);

                addContextAction(actionsContainer, "Open Link in New Tab", R.drawable.ic_add, () -> {
                    if (isAdLink(linkUrl, tab.getUrl())) {
                        Toast.makeText(this, "Blocked ad link from opening in new tab", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        return;
                    }
                    TabModel newTab = tabsManager.createTab(this, linkUrl, tab.isIncognito());
                    loadInTab(newTab, linkUrl);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Open Link in Background", R.drawable.ic_add, () -> {
                    if (isAdLink(linkUrl, tab.getUrl())) {
                        Toast.makeText(this, "Blocked ad link from opening in background", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        return;
                    }
                    queueBackgroundTab(tab, linkUrl);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Download Link with Manager", R.drawable.ic_download, () -> {
                    DownloadPromptDialog.show(this, linkUrl, request, null, null, -1);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Copy Link Address", R.drawable.ic_find, () -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Link", linkUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Link copied to clipboard", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Share Link", R.drawable.ic_share, () -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, linkUrl);
                    startActivity(Intent.createChooser(share, "Share link via"));
                    dialog.dismiss();
                });

            } else if (imageUrl != null) {
                title.setText(imageUrl);
                headerIcon.setImageResource(R.drawable.ic_search);
                boolean isDark = ThemeManager.isAppDarkTheme(MainActivity.this);
                int activeTextColor = isDark ? Color.WHITE : Color.parseColor("#111827");
                int inactiveTextColor = isDark ? Color.parseColor("#8E8EA8") : Color.parseColor("#6B7280");
                int activeBgRes = isDark ? R.drawable.bg_tab_card_active : R.drawable.bg_tab_card_active_light;

                tabImage.setBackgroundResource(activeBgRes);
                tabImage.setTextColor(activeTextColor);
                tabLink.setBackground(null);
                tabLink.setTextColor(inactiveTextColor);

                addContextAction(actionsContainer, "Open Image in New Tab", R.drawable.ic_add, () -> {
                    TabModel newTab = tabsManager.createTab(this, imageUrl, tab.isIncognito());
                    loadInTab(newTab, imageUrl);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Open Image in Background", R.drawable.ic_add, () -> {
                    queueBackgroundTab(tab, imageUrl);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Download Image with Manager", R.drawable.ic_download, () -> {
                    DownloadPromptDialog.show(this, imageUrl, request, null, "image/*", -1);
                    dialog.dismiss();
                });

                addContextActionWithChevron(actionsContainer, "Search Image via...", R.drawable.ic_search, () -> {
                    dialog.dismiss();
                    showImageSearchEngineChooser(tab, imageUrl);
                });

                addContextAction(actionsContainer, "Copy Image Address", R.drawable.ic_find, () -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Image URL", imageUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Image address copied", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Share Image Link", R.drawable.ic_share, () -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, imageUrl);
                    startActivity(Intent.createChooser(share, "Share image link via"));
                    dialog.dismiss();
                });
            }
        };

        tabLink.setOnClickListener(v -> {
            isShowingLink[0] = true;
            renderActions.run();
        });

        tabImage.setOnClickListener(v -> {
            isShowingLink[0] = false;
            renderActions.run();
        });

        renderActions.run();

        view.findViewById(R.id.ctx_btn_dismiss).setOnClickListener(v -> dialog.dismiss());
        pageContextMenu = dialog;
        dialog.setOnDismissListener(d -> {
            if (pageContextMenu == dialog) pageContextMenu = null;
        });
        dialog.show();
    }

    private void dismissPageContextMenu() {
        if (pageContextMenu == null) return;
        AlertDialog menu = pageContextMenu;
        pageContextMenu = null;
        menu.dismiss();
    }

    private void queueBackgroundTab(TabModel source, String candidate) {
        PrivacyWebView view = source.getWebView();
        if (!currentPage(source, view, source.navigation.page(), true)) return;
        TabModel tab = tabsManager.queueBackgroundTab(navigationTarget(candidate), source.isIncognito());
        if (tab == null) Toast.makeText(this, R.string.tab_background_unavailable, Toast.LENGTH_SHORT).show();
        else Toast.makeText(this, R.string.tab_background_queued, Toast.LENGTH_SHORT).show();
    }

    private void showImageSearchEngineChooser(TabModel tab, @Nullable String imageUrl) {
        PrivacyWebView webView = tab.getWebView();
        TabNavigation.Page page = tab.navigation.page();
        if (tab.isIncognito() || !currentPage(tab, webView, page, true)) return;
        long scope = assistantForegroundEpoch;
        com.privacy.imagesearch.ui.ImageSearchHubDialog.show(
                this,
                imageUrl,
                new com.privacy.imagesearch.ui.ImageSearchHubDialog.ImageSearchCallback() {
                    @Override
                    public void onSearchRequested(@NonNull String searchUrl, boolean openInBackground) {
                        if (scope != assistantForegroundEpoch || !currentPage(tab, webView, page, true)) {
                            Toast.makeText(MainActivity.this, "Tab changed. No image URL was sent.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (openInBackground) {
                            queueBackgroundTab(tab, searchUrl);
                        } else {
                            TabModel newTab = tabsManager.createTab(MainActivity.this, searchUrl, tab.isIncognito());
                            setupClientsForTab(newTab);
                            loadInTab(newTab, searchUrl);
                            tabsManager.switchTab(tabsManager.getTabCount() - 1);
                        }
                    }

                    @Override
                    public void onSelectWebImageRequested() {
                        if (scope != assistantForegroundEpoch || !currentPage(tab, webView, page, true)) {
                            Toast.makeText(MainActivity.this, "Tab changed. No image URL was sent.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        startImageSearchPicker();
                    }

                }
        );
    }

    private void addContextAction(LinearLayout parent, String text, int iconRes, Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_context_menu_action, parent, false);
        TextView tv = row.findViewById(R.id.ctx_item_text);
        ImageView iv = row.findViewById(R.id.ctx_item_icon);
        tv.setText(text);
        if (iconRes != 0) iv.setImageResource(iconRes);
        row.setOnClickListener(v -> action.run());
        parent.addView(row);
    }

    private void addContextActionWithChevron(LinearLayout parent, String text, int iconRes, Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_context_menu_action, parent, false);
        TextView tv = row.findViewById(R.id.ctx_item_text);
        ImageView iv = row.findViewById(R.id.ctx_item_icon);
        View chevron = row.findViewById(R.id.ctx_item_chevron);
        tv.setText(text);
        if (iconRes != 0) iv.setImageResource(iconRes);
        chevron.setVisibility(View.VISIBLE);
        row.setOnClickListener(v -> action.run());
        parent.addView(row);
    }

    private void toggleBookmarkCurrentPage(TabModel active) {
        PrivacyWebView view = active.getWebView();
        if (active.isIncognito() || !currentPage(active, view, active.navigation.page(), true)) return;
        String url = active.getUrl();
        if (url == null || url.trim().isEmpty() || url.startsWith("about:")) {
            Toast.makeText(this, "Cannot bookmark this page", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bookmarkDbHelper.isBookmarked(url)) {
            bookmarkDbHelper.removeBookmark(url);
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
        } else {
            String title = active.getTitle();
            if (title == null || title.trim().isEmpty()) {
                title = active.getWebView().getTitle();
            }
            long newId = bookmarkDbHelper.addBookmarkAndGetId(title, url, 0);
            if (newId <= 0) {
                Toast.makeText(this, "Could not save bookmark", Toast.LENGTH_SHORT).show();
                return;
            }
            if (active.getWebView() != null) {
                TabNavigation.Page page = active.navigation.page();
                com.privacy.browser.bookmarks.BookmarkPreviewManager.getInstance(this).captureAndSavePreview(newId, view,
                        () -> currentPage(active, view, page, true));
            }
            Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBookmarksDialog() {
        List<BookmarkModel> bookmarks = bookmarkDbHelper.getAllBookmarks();
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(MainActivity.this));
        builder.setTitle("Bookmarks (" + bookmarks.size() + ")");

        boolean isDark = ThemeManager.isAppDarkTheme(this);
        int surfaceColor = isDark ? Color.BLACK : Color.WHITE;
        int textPrimColor = isDark ? Color.WHITE : Color.parseColor("#111827");
        int textSecColor = isDark ? Color.parseColor("#888888") : Color.parseColor("#6B7280");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(surfaceColor);
        layout.setPadding(32, 24, 32, 24);

        if (bookmarks.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("No saved bookmarks.\n\nTap 'Add to Bookmarks' in the menu to save any webpage.");
            emptyTv.setTextColor(Color.GRAY);
            emptyTv.setTextSize(14);
            emptyTv.setPadding(0, 32, 0, 32);
            layout.addView(emptyTv);
            builder.setView(layout);
            builder.setPositiveButton("Close", null);
            builder.show();
            return;
        }

        ListView listView = new ListView(this);
        listView.setBackgroundColor(surfaceColor);
        layout.addView(listView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        ArrayAdapter<BookmarkModel> adapter = new ArrayAdapter<BookmarkModel>(this, 0, bookmarks) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                LinearLayout itemLayout;
                TextView titleView;
                TextView urlView;

                if (convertView instanceof LinearLayout) {
                    itemLayout = (LinearLayout) convertView;
                    View[] views = (View[]) itemLayout.getTag();
                    titleView = (TextView) views[0];
                    urlView = (TextView) views[1];
                } else {
                    itemLayout = new LinearLayout(MainActivity.this);
                    itemLayout.setOrientation(LinearLayout.VERTICAL);
                    itemLayout.setPadding(16, 20, 16, 20);

                    titleView = new TextView(MainActivity.this);
                    titleView.setTextColor(textPrimColor);
                    titleView.setTextSize(15);
                    titleView.setMaxLines(1);
                    titleView.setEllipsize(TextUtils.TruncateAt.END);
                    itemLayout.addView(titleView);

                    urlView = new TextView(MainActivity.this);
                    urlView.setTextColor(textSecColor);
                    urlView.setTextSize(12);
                    urlView.setMaxLines(1);
                    urlView.setEllipsize(TextUtils.TruncateAt.END);
                    itemLayout.addView(urlView);

                    itemLayout.setTag(new View[]{titleView, urlView});
                }

                BookmarkModel bm = getItem(position);
                if (bm != null) {
                    titleView.setText(bm.getTitle());
                    urlView.setText(bm.getUrl());
                }
                return itemLayout;
            }
        };
        listView.setAdapter(adapter);

        AlertDialog dialog = builder.setView(layout).create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            BookmarkModel bm = bookmarks.get(position);
            TabModel active = tabsManager.getActiveTab();
            if (active != null) {

                loadInTab(active, bm.getUrl());
            }
            dialog.dismiss();
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            BookmarkModel bm = bookmarks.get(position);
            AlertDialog subDialog = new AlertDialog.Builder(MainActivity.this, ThemeManager.getDialogTheme(MainActivity.this))
                .setTitle(bm.getTitle())
                .setItems(new String[]{"Open in New Tab", "Delete Bookmark"}, (subDlg, which) -> {
                    if (which == 0) {
                        TabModel newTab = tabsManager.createTab(MainActivity.this, bm.getUrl());
                        setupClientsForTab(newTab);
                        loadInTab(newTab, bm.getUrl());
                        dialog.dismiss();
                    } else if (which == 1) {
                        bookmarkDbHelper.removeBookmark(bm.getUrl());
                        Toast.makeText(MainActivity.this, "Bookmark removed", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        showBookmarksDialog();
                    }
                })
                .create();
            subDialog.show();
            applyAmoledDialogTheme(subDialog);
            return true;
        });

        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    private void showHistoryDialog() {
        List<HistoryModel> historyList = historyDbHelper != null ? historyDbHelper.getAllHistory(200) : new java.util.ArrayList<>();
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(MainActivity.this));
        builder.setTitle("History (" + historyList.size() + ")");

        boolean isDark = ThemeManager.isAppDarkTheme(this);
        int surfaceColor = isDark ? Color.BLACK : Color.WHITE;
        int textPrimColor = isDark ? Color.WHITE : Color.parseColor("#111827");
        int textSecColor = isDark ? Color.parseColor("#888888") : Color.parseColor("#6B7280");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(surfaceColor);
        layout.setPadding(32, 24, 32, 24);

        if (historyList.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("No browsing history.\n\nPages you visit in standard tabs will appear here.");
            emptyTv.setTextColor(Color.GRAY);
            emptyTv.setTextSize(14);
            emptyTv.setPadding(0, 32, 0, 32);
            layout.addView(emptyTv);
            builder.setView(layout);
            builder.setPositiveButton("Close", null);
            AlertDialog emptyDialog = builder.create();
            emptyDialog.show();
            applyAmoledDialogTheme(emptyDialog);
            return;
        }

        ListView listView = new ListView(this);
        listView.setBackgroundColor(surfaceColor);
        layout.addView(listView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());

        ArrayAdapter<HistoryModel> adapter = new ArrayAdapter<HistoryModel>(this, 0, historyList) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                LinearLayout itemLayout;
                TextView titleView;
                TextView urlView;
                TextView dateView;

                if (convertView instanceof LinearLayout) {
                    itemLayout = (LinearLayout) convertView;
                    View[] views = (View[]) itemLayout.getTag();
                    titleView = (TextView) views[0];
                    urlView = (TextView) views[1];
                    dateView = (TextView) views[2];
                } else {
                    itemLayout = new LinearLayout(MainActivity.this);
                    itemLayout.setOrientation(LinearLayout.VERTICAL);
                    itemLayout.setPadding(16, 20, 16, 20);

                    titleView = new TextView(MainActivity.this);
                    titleView.setTextColor(textPrimColor);
                    titleView.setTextSize(15);
                    titleView.setMaxLines(1);
                    titleView.setEllipsize(TextUtils.TruncateAt.END);
                    itemLayout.addView(titleView);

                    LinearLayout subRow = new LinearLayout(MainActivity.this);
                    subRow.setOrientation(LinearLayout.HORIZONTAL);

                    urlView = new TextView(MainActivity.this);
                    urlView.setTextColor(textSecColor);
                    urlView.setTextSize(12);
                    urlView.setMaxLines(1);
                    urlView.setEllipsize(TextUtils.TruncateAt.END);
                    subRow.addView(urlView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

                    dateView = new TextView(MainActivity.this);
                    dateView.setTextColor(Color.parseColor("#666666"));
                    dateView.setTextSize(11);
                    dateView.setPadding(16, 0, 0, 0);
                    subRow.addView(dateView);

                    itemLayout.addView(subRow);
                    itemLayout.setTag(new View[]{titleView, urlView, dateView});
                }

                HistoryModel item = getItem(position);
                if (item != null) {
                    titleView.setText(item.getTitle());
                    urlView.setText(item.getUrl());
                    dateView.setText(sdf.format(new Date(item.getVisitedAt())));
                }
                return itemLayout;
            }
        };
        listView.setAdapter(adapter);

        AlertDialog dialog = builder.setView(layout).create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            HistoryModel item = historyList.get(position);
            TabModel active = tabsManager.getActiveTab();
            if (active != null) {

                loadInTab(active, item.getUrl());
            }
            dialog.dismiss();
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            HistoryModel item = historyList.get(position);
            AlertDialog subDialog = new AlertDialog.Builder(MainActivity.this, ThemeManager.getDialogTheme(MainActivity.this))
                .setTitle(item.getTitle())
                .setItems(new String[]{"Open in New Tab", "Delete Entry"}, (subDlg, which) -> {
                    if (which == 0) {
                        TabModel newTab = tabsManager.createTab(MainActivity.this, item.getUrl());
                        setupClientsForTab(newTab);
                        loadInTab(newTab, item.getUrl());
                        dialog.dismiss();
                    } else if (which == 1) {
                        historyDbHelper.deleteVisit(item.getId());
                        Toast.makeText(MainActivity.this, "Entry deleted", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        showHistoryDialog();
                    }
                })
                .create();
            subDialog.show();
            applyAmoledDialogTheme(subDialog);
            return true;
        });

        // Clear History Action Button
        TextView btnClear = new TextView(this);
        btnClear.setText("Clear All History");
        btnClear.setTextColor(Color.parseColor("#FF5555"));
        btnClear.setTextSize(15);
        btnClear.setPadding(0, 24, 0, 8);
        btnClear.setOnClickListener(v -> {
            AlertDialog clearDlg = new AlertDialog.Builder(MainActivity.this, ThemeManager.getDialogTheme(MainActivity.this))
                .setTitle("Clear History")
                .setMessage("Delete all visited pages history?")
                .setPositiveButton("Clear", (cd, which) -> {
                    historyDbHelper.clearAll();
                    Toast.makeText(MainActivity.this, "History cleared", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .create();
            clearDlg.show();
            applyAmoledDialogTheme(clearDlg);
        });
        layout.addView(btnClear);

        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    private void shareCurrentPage(TabModel active) {
        String url = active.getUrl();
        if (url == null || url.trim().isEmpty() || url.startsWith("about:")) {
            Toast.makeText(this, "No valid link to share", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, active.getTitle());
        shareIntent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(shareIntent, "Share link via"));
    }

    private void applyDesktopMode(TabModel tab) {
        if (tab.getWebView() == null) return;
        if (tab.isDesktopMode()) {
            tab.getWebView().getSettings().setUserAgentString(DESKTOP_UA);
            tab.getWebView().getSettings().setUseWideViewPort(true);
            tab.getWebView().getSettings().setLoadWithOverviewMode(true);
        } else {
            PrivacyWebView.applyCleanMobileUserAgent(tab.getWebView().getSettings());
        }
    }

    private void toggleDesktopMode(TabModel tab) {
        if (tab.getWebView() == null) return;
        tab.setDesktopMode(!tab.isDesktopMode());
        sessionMetadataChanged(tab);
        applyDesktopMode(tab);
        reloadTab(tab);
        Toast.makeText(this, tab.isDesktopMode() ? "Desktop mode enabled" : "Mobile mode restored", Toast.LENGTH_SHORT).show();
    }

    private void showClearDataChoices() {
        if (disposing || isFinishing()) return;
        boolean[] selected = new boolean[4];
        new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                .setTitle("Clear browsing data — all sites")
                .setMultiChoiceItems(new String[] {"WebView cookies", "WebView URL cache", "Legacy Web Storage request",
                        "History and saved tabs (closes tabs)"}, selected, (d, which, enabled) -> selected[which] = enabled)
                .setPositiveButton("Review", (d, which) -> requestClearBrowserData(
                        BrowserDataPolicy.allSites(selected[0], selected[1], selected[2], selected[3]), null, null))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The tool and menu both need a fresh, visible native confirmation for this exact immutable plan. */
    public void requestClearBrowserData(BrowserDataPolicy.Plan plan,
                                        java.util.function.Consumer<String> result, java.util.function.Consumer<String> error) {
        if (!resumed || disposing || isFinishing() || BrowserDataCleaner.inProgress() || plan.isEmpty()) {
            String message = plan.isEmpty() ? "No data types selected. No data was deleted."
                    : "Open the browser to confirm, and wait for any previous clear to finish.";
            if (error != null) error.accept(message);
            else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        boolean[] confirmed = {false};
        AlertDialog dialog = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                .setTitle("Confirm all-sites cleanup")
                .setMessage(plan.selectedItems() + "\n\n" + BrowserDataPolicy.LIMITS + "\n\n" + BrowserDataPolicy.OLDER_PRIVATE_DATA)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear selected data", (d, which) -> {
                    if (!resumed || disposing || isFinishing() || BrowserDataCleaner.inProgress()) return;
                    confirmed[0] = true;
                    performBrowserDataClear(plan, result);
                }).create();
        dialog.setOnDismissListener(d -> {
            if (!confirmed[0] && error != null) error.accept("Cleanup not confirmed. No data was deleted.");
        });
        dialog.show();
    }

    /** Called by the native assistant approval callback, with no app-history permission. */
    public void clearApprovedBrowserData(BrowserDataPolicy.Plan plan, java.util.function.Consumer<String> result,
                                         java.util.function.Consumer<String> error) {
        if (assistantPage() == null || plan.isEmpty() || plan.localHistoryAndTabs || BrowserDataCleaner.inProgress()) {
            error.accept("Cleanup is unavailable now. No data was deleted.");
            return;
        }
        performBrowserDataClear(plan, result);
    }

    private void performBrowserDataClear(BrowserDataPolicy.Plan plan, java.util.function.Consumer<String> result) {
        hideOmniboxSuggestions();
        dismissTabGrid();
        tabPreviews.clear();
        BrowserDataCleaner.clear(this, plan, this::clearLocalHistoryAndTabs, report -> {
            if (result != null) result.accept(report.toJson());
            if (result == null && !disposing && resumed && !isFinishing()) new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                    .setTitle("Cleanup result").setMessage(report.summary()).setPositiveButton("Close", null).show();
        });
    }

    private void clearLocalHistoryAndTabs(Consumer<Boolean> complete) {
        if (!sessionWritable()) {
            complete.accept(false);
            return;
        }
        replacingSession = true;
        cancelTabTrim();
        lazyRestore.cancel();
        session.reset();
        boolean closed = false;
        boolean historyRemoved = false;
        try {
            // Old document callbacks lose ownership before they could write anything else.
            tabsManager.closeAllTabs(this);
            closed = true;
        } catch (RuntimeException failure) {
            Log.w("MainActivity", "Could not close all tabs during cleanup");
        }
        try {
            historyDbHelper.clearAll();
            historyRemoved = true;
        } catch (RuntimeException failure) {
            Log.w("MainActivity", "Could not remove app history");
        } finally {
            replacingSession = false;
        }
        final boolean localRemoved = closed && historyRemoved;
        Handler main = new Handler(Looper.getMainLooper());
        sessionOwner = sessionWriter.wipe(sessionOwner,
                removed -> main.post(() -> complete.accept(localRemoved && removed)));
        if (closed) sessionTabsChanged(); // only the newly created home, never the pre-wipe tabs
        else sessionWriter.release(sessionOwner);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        getSharedPreferences(SettingsActivity.PREFS_BROWSER, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(discoverySettings);
        String latestTheme = ThemeManager.getAppTheme(this);
        if (currentAppTheme != null && !currentAppTheme.equals(latestTheme)) {
            checkpointSession();
            recreate();
            return;
        }
        if (startupReady) refreshScriptPolicies(null);
        refreshDiscoverySettings();
        if (!startupReady) return;
        TabModel tab = activeTab();
        if (tab != null && tab.getWebView() != null) {
            ThemeManager.applyWebTheme(tab.getWebView());
            tab.getWebView().applyPageZoom();
            markAssistantDocument(tab);
            if (tab.messages != null) tab.messages.activateHome();
        }
        if (tab != null) renderShield(tab);
        requestSelectedRestore();
        scheduleTabPreview(tab);
        scheduleResumeWork();
    }

    private void scheduleResumeWork() {
        resumeWork.removeCallbacks(deferredResumeWork);
        if (startupReady && resumed) resumeWork.postDelayed(deferredResumeWork, 1500);
    }

    @Override
    protected void onPause() {
        resumed = false;
        getSharedPreferences(SettingsActivity.PREFS_BROWSER, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(discoverySettings);
        hideOmniboxSuggestions();
        stopMediaDiscovery();
        shieldRefresh.show(null);
        if (lazyRestore != null) lazyRestore.cancel();
        tabPreviews.cancel();
        dismissTabGrid();
        resumeWork.removeCallbacks(deferredResumeWork);
        assistantForegroundEpoch++;
        if (agentBottomSheet != null) agentBottomSheet.ownerPaused();
        dismissPageContextMenu();
        dismissComicDownload();
        cancelPicker(displayedTab);
        if (customVideoView != null) {
            hideFullscreenVideo();
        }
        super.onPause();
        checkpointSession();
    }

    private void showFullscreenVideo(View view, WebChromeClient.CustomViewCallback callback) {
        if (customVideoView != null) {
            callback.onCustomViewHidden();
            return;
        }

        if (editUrl.hasFocus()) dismissKeyboard();
        tabPreviews.cancel();
        dismissVideoOptions();
        dismissComicDownload();
        customVideoView = view;
        customViewCallback = callback;
        originalOrientation = getRequestedOrientation();

        if (fullscreenVideoContainer != null) {
            fullscreenVideoContainer.removeAllViews();
            fullscreenVideoContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fullscreenVideoContainer.setVisibility(View.VISIBLE);
        }

        if (topAppBar != null) topAppBar.setVisibility(View.GONE);
        if (toolbarContainer != null) toolbarContainer.setVisibility(View.GONE);
        if (findBar != null) findBar.setVisibility(View.GONE);
        if (fabVideoDownload != null) fabVideoDownload.setVisibility(View.GONE);
        if (fabComicDownload != null) fabComicDownload.setVisibility(View.GONE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);

        View decorView = getWindow().getDecorView();
        originalSystemUiVisibility = decorView.getSystemUiVisibility();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void hideFullscreenVideo() {
        if (customVideoView == null) return;

        if (fullscreenVideoContainer != null) {
            fullscreenVideoContainer.setVisibility(View.GONE);
            fullscreenVideoContainer.removeAllViews();
        }

        if (topAppBar != null) topAppBar.setVisibility(View.VISIBLE);
        if (toolbarContainer != null) toolbarContainer.setVisibility(View.VISIBLE);
        showTopBar(false);

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(originalSystemUiVisibility);
        setRequestedOrientation(originalOrientation);

        if (customViewCallback != null) {
            try {
                customViewCallback.onCustomViewHidden();
            } catch (Throwable ignored) {}
            customViewCallback = null;
        }
        customVideoView = null;
        fullscreenOwner = null;
        fullscreenPage = null;
        updateFloatingActionButtonsForActiveTab();
    }

    @Override
    protected void onStop() {
        super.onStop();
        checkpointSession();
        if (startupReady) NativeTabSession.flushCookiesAfterStop();
    }

    private boolean isAdLink(String url, String topUrl) {
        if (url == null) return false;
        try {
            return com.privacy.browser.adblock.AdBlockManager.getInstance(this).isBlocked(android.net.Uri.parse(url), topUrl);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showShieldDetails() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null) {
            com.privacy.browser.adblock.AdBlockDashboardDialog.show(
                    this,
                    active.pageKey(),
                    active.getUrl(),
                    () -> reloadTab(active),
                    () -> startElementZapper(),
                    () -> startElementPicker(),
                    () -> showMyFilters()
            );
        }
    }

    public void startImageSearchPicker() {
        TabModel active = activeTab();
        if (active == null || active.messages == null) return;
        cancelPicker(active);
        String ticket = active.messages.armPicker("image");
        if (ticket == null) {
            Toast.makeText(this, R.string.picker_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try (java.io.InputStream in = getAssets().open("image_search_picker.js")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            active.messages.evaluate("const pickerTicket=" + JSONObject.quote(ticket) + ";" + out.toString("UTF-8"));
        } catch (java.io.IOException e) {
            active.messages.cancelPicker();
            Toast.makeText(this, R.string.picker_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    public void startElementPicker() { startElementTool(false); }
    public void startElementZapper() { startElementTool(true); }

    private void startElementTool(boolean zapper) {
        TabModel active = activeTab();
        if (active == null || active.messages == null) return;
        cancelPicker(active);
        String ticket = active.messages.armPicker("element");
        if (ticket == null) {
            Toast.makeText(this, R.string.picker_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, zapper ? "Tap any element to zap it" : "Tap any element to block it", Toast.LENGTH_SHORT).show();
        active.messages.evaluate(com.privacy.browser.adblock.picker.ElementPickerEngine.getInjectionScript(zapper, ticket));
    }

    public void showMyFilters() {
        com.privacy.browser.adblock.rules.MyFiltersDialog.show(this, () -> {
            TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
            if (active != null) reloadTab(active);
        });
    }

    private void showSiteConnectionDetails(TabModel active) {
        if (active == null || active.getUrl() == null) return;
        String rawUrl = active.getUrl();

        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(MainActivity.this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_site_info, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        ImageView iconSecurity = view.findViewById(R.id.site_info_icon_security);
        TextView textStatus = view.findViewById(R.id.site_info_text_status);
        TextView textHost = view.findViewById(R.id.site_info_text_host);

        String host = rawUrl;
        try {
            java.net.URI uri = new java.net.URI(rawUrl);
            host = uri.getHost() != null ? uri.getHost() : rawUrl;
        } catch (Exception ignored) {}

        textHost.setText(host);

        PrivacyWebView siteView = active.getWebView();
        if (siteView == null || !currentPage(active, siteView, active.navigation.page(), true)) return;
        if (rawUrl.startsWith("https://") && siteView.getCertificate() != null) {
            textStatus.setText("HTTPS connection");
            textStatus.setTextColor(Color.parseColor("#4CAF50"));
            iconSecurity.setColorFilter(Color.parseColor("#4CAF50"));
        } else if (rawUrl.startsWith("http://")) {
            textStatus.setText("Connection is Not Secure");
            textStatus.setTextColor(Color.parseColor("#FF5252"));
            iconSecurity.setColorFilter(Color.parseColor("#FF5252"));
        } else {
            textStatus.setText("Connection security not verified");
            textStatus.setTextColor(Color.WHITE);
            iconSecurity.setColorFilter(Color.WHITE);
        }

        View dataNote = view.findViewById(R.id.site_info_data_note);
        ((TextView) dataNote).setText(BrowserDataPolicy.SITE_CLEAR_UNAVAILABLE);
        view.findViewById(R.id.site_info_btn_clear_browser).setOnClickListener(v -> {
            dialog.dismiss();
            showClearDataChoices();
        });

        view.findViewById(R.id.site_info_btn_done).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    private void dismissKeyboard() {
        hideOmniboxSuggestions();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        editUrl.clearFocus();
    }

    private void showKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public void onBackPressed() {
        if (agentBottomSheet != null && agentBottomSheet.isShowing()) {
            agentBottomSheet.dismiss();
            return;
        }

        if (customVideoView != null) {
            hideFullscreenVideo();
            return;
        }

        if (editUrl.hasFocus() || visibleOmniboxRequest != null) {
            dismissKeyboard();
            return;
        }

        if (findBar.getVisibility() == View.VISIBLE) {
            findBar.setVisibility(View.GONE);
            TabModel active = tabsManager.getActiveTab();
            if (active != null && active.getWebView() != null) active.getWebView().clearMatches();
            return;
        }

        TabModel active = tabsManager.getActiveTab();
        if (active != null && active.getWebView() != null && active.getWebView().canGoBack()) {
            navigateHistory(active, -1);
            return;
        }

        if (tabsManager.getTabCount() > 1) {
            tabsManager.closeTab(tabsManager.getActiveIndex(), this);
            Toast.makeText(this, "Tab closed", Toast.LENGTH_SHORT).show();
            return;
        }

        super.onBackPressed();
    }

    private void applyAmoledDialogTheme(AlertDialog dialog) {
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    protected void onDestroy() {
        checkpointSession();
        if (session != null) {
            session.reset();
            sessionWriter.release(sessionOwner);
        }
        disposing = true;
        resumed = false;
        hideOmniboxSuggestions();
        omniboxRequests.close();
        omniboxWorker.shutdownNow();
        shieldRefresh.show(null);
        cancelTabTrim();
        dismissTabGrid();
        tabPreviews.clear();
        resumeWork.removeCallbacks(deferredResumeWork);
        if (lazyRestore != null) lazyRestore.cancel();
        pendingLaunch = null;
        assistantForegroundEpoch++;
        dismissPageContextMenu();
        dismissComicDownload();
        cancelPicker(displayedTab);
        hideFullscreenVideo();
        cancelTopBarAnimation();
        stopMediaDiscovery();
        if (agentBottomSheet != null) {
            agentBottomSheet.dispose();
            agentBottomSheet = null;
        }
        if (tabsManager != null) tabsManager.dispose();
        traceTabResources();
        displayedTab = null;
        super.onDestroy();
    }
}

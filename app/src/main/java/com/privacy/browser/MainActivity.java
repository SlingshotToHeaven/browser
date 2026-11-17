package com.privacy.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.view.WindowManager;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
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
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import com.privacy.browser.bookmarks.BookmarkDbHelper;
import com.privacy.browser.bookmarks.BookmarkModel;
import com.privacy.browser.history.HistoryDbHelper;
import com.privacy.browser.history.HistoryModel;
import com.privacy.downloader.interceptor.BrowserDownloadListener;
import com.privacy.downloader.ui.DownloadPromptDialog;
import com.privacy.downloader.ui.DownloadsActivity;
import com.privacy.downloader.ui.DownloadsDialog;
import com.privacy.downloader.ui.LinkGrabberActivity;
import com.privacy.browser.bookmarks.BookmarksActivity;
import com.privacy.browser.history.HistoryActivity;
import com.privacy.browser.search.OmniboxSuggestion;
import com.privacy.browser.search.OmniboxSuggestionsAdapter;
import com.privacy.browser.search.SearchEngineManager;
import com.privacy.browser.settings.SettingsActivity;
import com.privacy.browser.media.MediaSniffer;
import com.privacy.browser.media.PrivacyVideoSnifferBridge;
import com.privacy.browser.ui.VideoQualityDialog;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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
    private boolean isLoading = false;
    private int blockedTrackerCount = 0;
    private boolean isAddressBarFirstTap = false;

    public static final String EXTRA_LOAD_URL = "com.privacy.browser.EXTRA_LOAD_URL";
    public static final String EXTRA_OPEN_IN_NEW_TAB = "com.privacy.browser.EXTRA_OPEN_IN_NEW_TAB";
    public static final String EXTRA_OPEN_IN_INCOGNITO = "com.privacy.browser.EXTRA_OPEN_IN_INCOGNITO";

    public static final String DEFAULT_HOME_URL = "file:///android_asset/home.html";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private static final String PREFS_NAME = "browser_session";
    private static final String KEY_SAVED_TABS = "saved_tabs";
    private static final String KEY_ACTIVE_TAB = "active_tab_idx";
    private String currentAppTheme;
    private boolean isRestoringSession = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        currentAppTheme = ThemeManager.getAppTheme(this);
        ThemeManager.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bookmarkDbHelper = BookmarkDbHelper.getInstance(this);
        historyDbHelper = HistoryDbHelper.getInstance(this);
        initTrackerEngine();
        initViews();
        initServiceWorkerSniffer();
        initTabs();
        checkNotificationPermission();
    }

    private void initServiceWorkerSniffer() {
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
                androidx.webkit.ServiceWorkerControllerCompat controller = androidx.webkit.ServiceWorkerControllerCompat.getInstance();
                controller.setServiceWorkerClient(new androidx.webkit.ServiceWorkerClientCompat() {
                    @Nullable
                    @Override
                    public WebResourceResponse shouldInterceptRequest(@NonNull WebResourceRequest request) {
                        try {
                            TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                            String tabId = active != null ? active.getId() : null;
                            String pageTitle = active != null ? active.getTitle() : null;
                            if (tabId != null && request.getUrl() != null) {
                                MediaSniffer.inspectRequest(tabId, request.getUrl(), pageTitle);
                            }
                        } catch (Throwable ignored) {}
                        return null;
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void initTrackerEngine() {
        trackerBlocker = new TrackerBlocker();
        trackerBlocker.initialize(this);
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

        // Omnibox suggestions initialization
        omniboxContainer = findViewById(R.id.omnibox_suggestions_container);
        omniboxList = findViewById(R.id.omnibox_suggestions_list);
        if (omniboxList != null) {
            omniboxAdapter = new OmniboxSuggestionsAdapter(this);
            omniboxList.setAdapter(omniboxAdapter);
            omniboxList.setOnItemClickListener((parent, view, position, id) -> {
                OmniboxSuggestion item = omniboxAdapter.getItem(position);
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
                String text = editUrl.getText() != null ? editUrl.getText().toString() : "";
                if (btnClearUrl != null) {
                    btnClearUrl.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
                }
                updateOmniboxSuggestions(text);
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
                    updateOmniboxSuggestions(s != null ? s.toString() : "");
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnReload.setOnClickListener(v -> {
            TabModel active = tabsManager.getActiveTab();
            if (active != null) {
                if (isLoading) {
                    active.getWebView().stopLoading();
                } else {
                    active.getWebView().reload();
                }
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
            com.privacy.browser.ai.AgentSessionManager sessionManager =
                    com.privacy.browser.ai.AgentSessionManager.getInstance(this);
            sessionManager.setFloatingButton(aiFloatingButton);
            sessionManager.initialize();

            aiFloatingButton.setOnAiButtonClickListener(() -> {
                TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                android.webkit.WebView activeWv = active != null ? active.getWebView() : null;

                if (agentBottomSheet == null) {
                    agentBottomSheet = new com.privacy.browser.ai.AgentBottomSheet(
                            MainActivity.this, activeWv, MainActivity.this, tabsManager);
                } else {
                    agentBottomSheet.updateActiveContext(activeWv, MainActivity.this, tabsManager);
                }

                if (agentBottomSheet.isShowing()) {
                    agentBottomSheet.dismiss();
                } else {
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
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null) {
            com.privacy.browser.comic.ComicManager.ComicItem item =
                    com.privacy.browser.comic.ComicManager.getDetectedComic(active.getId());
            if (item != null) {
                String ua = active.getWebView() != null && active.getWebView().getSettings() != null ?
                        active.getWebView().getSettings().getUserAgentString() : null;
                com.privacy.browser.comic.ComicDownloadDialog.show(MainActivity.this, item, ua);
            }
        }
    }

    private void updateFabComicDownload(int count) {
        if (fabComicDownload == null) return;
        if (!com.privacy.browser.settings.SettingsActivity.isComicDownloaderEnabled(this) || isCurrentTabOnHomePage()) {
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

        MediaSniffer.setMediaDetectionListener((tabId, count) -> {
            runOnUiThread(() -> {
                TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                if (active != null && active.getId().equals(tabId)) {
                    updateFabVideoDownload(count);
                }
            });
        });
    }

    private void setupMovableFabTouchListener(View fab, Runnable onClickAction) {
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
                            if (onClickAction != null) {
                                onClickAction.run();
                            }
                        }
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    private void onFloatingDownloadFabClicked() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active == null) return;
        List<MediaSniffer.SniffedMedia> mediaList = MediaSniffer.getMediaForTab(active.getId());
        String ua = active.getWebView() != null && active.getWebView().getSettings() != null
                ? active.getWebView().getSettings().getUserAgentString()
                : null;

        if (!mediaList.isEmpty()) {
            VideoQualityDialog.show(MainActivity.this, mediaList, active.getUrl(), ua,
                    () -> extractAndGrabPageLinks(active),
                    () -> refreshMediaForActiveTab(active));
        } else {
            // Idle state tap: check for any link or prompt link grabber
            extractAndGrabPageLinks(active);
        }
    }

    private void refreshMediaForActiveTab(TabModel active) {
        if (active == null) return;
        Toast.makeText(this, "Refreshing media streams...", Toast.LENGTH_SHORT).show();
        MediaSniffer.clearTab(active.getId());
        updateFabVideoDownload(0);

        PrivacyWebView webView = active.getWebView();
        String currentUrl = (webView != null && webView.getUrl() != null && !webView.getUrl().isEmpty())
                ? webView.getUrl()
                : active.getUrl();
        String currentTitle = (webView != null && webView.getTitle() != null && !webView.getTitle().isEmpty())
                ? webView.getTitle()
                : active.getTitle();

        active.setUrl(currentUrl);
        active.setTitle(currentTitle);

        // 1. Re-evaluate DOM video sniffer
        if (webView != null) {
            webView.evaluateJavascript(PrivacyWebViewClient.VIDEO_SNIFFER_JS, null);
        }

        // 2. Re-trigger platform resolvers
        if (currentUrl != null) {
            if (MediaSniffer.isVimeoUrl(currentUrl)) {
                MediaSniffer.resolveVimeoFromUrl(active.getId(), currentUrl, currentTitle, () -> {
                    runOnUiThread(() -> {
                        int c = MediaSniffer.getMediaForTab(active.getId()).size();
                        updateFabVideoDownload(c);
                        if (c > 0) onFloatingDownloadFabClicked();
                        else Toast.makeText(MainActivity.this, "No video streams detected", Toast.LENGTH_SHORT).show();
                    });
                });
            } else if (MediaSniffer.isYouTubeUrl(currentUrl)) {
                MediaSniffer.resolveYouTubeFromUrl(active.getId(), currentUrl, currentTitle, () -> {
                    runOnUiThread(() -> {
                        int c = MediaSniffer.getMediaForTab(active.getId()).size();
                        updateFabVideoDownload(c);
                        if (c > 0) onFloatingDownloadFabClicked();
                        else Toast.makeText(MainActivity.this, "No video streams detected", Toast.LENGTH_SHORT).show();
                    });
                });
            } else {
                // Generic page re-probe
                extractAndGrabPageLinks(active);
            }
        }
    }

    private void updateFabVideoDownload(int count) {
        if (fabVideoDownload == null) return;
        boolean enabled = SettingsActivity.isFloatingDownloadButtonEnabled(this);
        if (!enabled || isCurrentTabOnHomePage()) {
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
            if (active != null) active.getWebView().findNext(false);
        });

        btnFindNext.setOnClickListener(v -> {
            TabModel active = tabsManager.getActiveTab();
            if (active != null) active.getWebView().findNext(true);
        });

        btnFindClose.setOnClickListener(v -> {
            findBar.setVisibility(View.GONE);
            showTopBar(true);
            TabModel active = tabsManager.getActiveTab();
            if (active != null) active.getWebView().clearMatches();
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

    private void initTabs() {
        tabsManager = new TabsManager();
        tabsManager.setListener(new TabsManager.TabChangeListener() {
            @Override
            public void onTabSwitched(TabModel tab) {
                if (customVideoView != null) {
                    hideFullscreenVideo();
                }
                showTopBar(false);
                attachTabToView(tab);
                saveSession();
            }

            @Override
            public void onTabCountChanged(int count) {
                btnTabs.setText(String.valueOf(count));
                saveSession();
            }
        });

        restoreSessionOrInit();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
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

        // Check for AI Automation Recipe execution
        String recipeJson = intent.getStringExtra("automation_recipe_json");
        if (recipeJson != null && !recipeJson.trim().isEmpty()) {
            String autoTitle = intent.getStringExtra("automation_title");
            android.widget.Toast.makeText(this, "Executing Automation: " + (autoTitle != null ? autoTitle : "Routine"), android.widget.Toast.LENGTH_SHORT).show();
            TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
            android.webkit.WebView wv = active != null ? active.getWebView() : null;
            com.privacy.browser.ai.ToolDispatcher dispatcher = new com.privacy.browser.ai.ToolDispatcher(this);
            dispatcher.setCurrentWebView(wv);
            dispatcher.setActivityAndTabs(this, tabsManager);
            com.privacy.browser.ai.AutomationRecipeRunner runner = new com.privacy.browser.ai.AutomationRecipeRunner(this, dispatcher, wv);
            runner.runRecipe(recipeJson, null);
            return;
        }

        String directUrl = intent.getStringExtra(EXTRA_LOAD_URL);
        if (directUrl != null && !directUrl.trim().isEmpty()) {
            boolean openInNewTab = intent.getBooleanExtra(EXTRA_OPEN_IN_NEW_TAB, false);
            boolean openInIncognito = intent.getBooleanExtra(EXTRA_OPEN_IN_INCOGNITO, false);
            if (openInIncognito) {
                TabModel tab = tabsManager.createTab(this, directUrl, true);
                setupClientsForTab(tab);
                tab.getWebView().loadUrl(directUrl);
            } else if (openInNewTab || tabsManager.getTabCount() == 0) {
                TabModel tab = tabsManager.createTab(this, directUrl);
                setupClientsForTab(tab);
                tab.getWebView().loadUrl(directUrl);
            } else {
                TabModel active = tabsManager.getActiveTab();
                if (active != null) {
                    active.setUrl(directUrl);
                    active.getWebView().loadUrl(directUrl);
                } else {
                    TabModel tab = tabsManager.createTab(this, directUrl);
                    setupClientsForTab(tab);
                    tab.getWebView().loadUrl(directUrl);
                }
            }
            saveSession();
            return;
        }

        String incomingUrl = extractUrlFromIntent(intent);
        if (incomingUrl == null) return;

        if (isDownloadableUrl(incomingUrl)) {
            String cookies = CookieManager.getInstance().getCookie(incomingUrl);
            DownloadPromptDialog.show(this, incomingUrl, null, null, null, cookies, null, null);
        } else {
            TabModel tab = tabsManager.createTab(this, incomingUrl);
            setupClientsForTab(tab);
            tab.getWebView().loadUrl(incomingUrl);
            saveSession();
        }
    }

    private void restoreSessionOrInit() {
        Intent intent = getIntent();

        // Check for AI Automation Recipe execution on cold launch
        String recipeJson = intent != null ? intent.getStringExtra("automation_recipe_json") : null;
        if (recipeJson != null && !recipeJson.trim().isEmpty()) {
            String autoTitle = intent.getStringExtra("automation_title");
            android.widget.Toast.makeText(this, "Executing Automation: " + (autoTitle != null ? autoTitle : "Routine"), android.widget.Toast.LENGTH_SHORT).show();
            TabModel tab = tabsManager.createTab(this, DEFAULT_HOME_URL);
            setupClientsForTab(tab);
            android.webkit.WebView wv = tab.getWebView();
            com.privacy.browser.ai.ToolDispatcher dispatcher = new com.privacy.browser.ai.ToolDispatcher(this);
            dispatcher.setCurrentWebView(wv);
            dispatcher.setActivityAndTabs(this, tabsManager);
            com.privacy.browser.ai.AutomationRecipeRunner runner = new com.privacy.browser.ai.AutomationRecipeRunner(this, dispatcher, wv);
            runner.runRecipe(recipeJson, null);
            saveSession();
            return;
        }

        String directUrl = intent != null ? intent.getStringExtra(EXTRA_LOAD_URL) : null;
        String incomingUrl = extractUrlFromIntent(intent);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedTabsJson = prefs.getString(KEY_SAVED_TABS, null);
        int savedActiveIdx = prefs.getInt(KEY_ACTIVE_TAB, 0);

        boolean restored = false;
        if (savedTabsJson != null) {
            try {
                JSONArray array = new JSONArray(savedTabsJson);
                if (array.length() > 0) {
                    isRestoringSession = true;
                    try {
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            String url = obj.optString("url", DEFAULT_HOME_URL);
                            if (url == null || url.trim().isEmpty() || "about:blank".equals(url)) {
                                url = getDefaultHomeUrl();
                            }
                            String title = obj.optString("title", "New Tab");
                            if (title == null || title.trim().isEmpty()) {
                                title = "New Tab";
                            }
                            boolean desktop = obj.optBoolean("desktop", false);

                            TabModel tab = tabsManager.createTab(this, url, false, true);
                            tab.setTitle(title);
                            tab.setDesktopMode(desktop);
                            if (desktop) {
                                tab.getWebView().getSettings().setUserAgentString(DESKTOP_UA);
                                tab.getWebView().getSettings().setUseWideViewPort(true);
                                tab.getWebView().getSettings().setLoadWithOverviewMode(true);
                            }
                            setupClientsForTab(tab);

                            boolean stateRestored = false;
                            String stateFileName = obj.optString("state_file", null);
                            if (stateFileName != null) {
                                File stateFile = new File(new File(getFilesDir(), "saved_tabs"), stateFileName);
                                if (stateFile.exists()) {
                                    try (FileInputStream fis = new FileInputStream(stateFile)) {
                                        byte[] bytes = new byte[(int) stateFile.length()];
                                        int read = fis.read(bytes);
                                        if (read > 0) {
                                            Parcel parcel = Parcel.obtain();
                                            parcel.unmarshall(bytes, 0, bytes.length);
                                            parcel.setDataPosition(0);
                                            Bundle stateBundle = Bundle.CREATOR.createFromParcel(parcel);
                                            parcel.recycle();

                                            WebBackForwardList restoredList = tab.getWebView().restoreState(stateBundle);
                                            if (restoredList != null && restoredList.getSize() > 0) {
                                                WebHistoryItem cur = restoredList.getCurrentItem();
                                                if (cur != null && cur.getUrl() != null && !cur.getUrl().isEmpty() && !"about:blank".equals(cur.getUrl())) {
                                                    stateRestored = true;
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        Log.w("MainActivity", "Failed restoring tab state: " + e.getMessage());
                                    }
                                }
                            }

                            if (!stateRestored) {
                                tab.getWebView().loadUrl(url);
                            }
                        }

                        if (tabsManager.getTabCount() > 0) {
                            int targetIdx = Math.max(0, Math.min(savedActiveIdx, tabsManager.getTabCount() - 1));
                            tabsManager.switchTab(targetIdx);
                            restored = true;
                        }
                    } finally {
                        isRestoringSession = false;
                    }
                }
            } catch (JSONException ignored) {}
        }

        if (directUrl != null && !directUrl.trim().isEmpty()) {
            boolean openInNewTab = intent != null && intent.getBooleanExtra(EXTRA_OPEN_IN_NEW_TAB, false);
            boolean openInIncognito = intent != null && intent.getBooleanExtra(EXTRA_OPEN_IN_INCOGNITO, false);
            if (openInIncognito) {
                TabModel tab = tabsManager.createTab(this, directUrl, true);
                setupClientsForTab(tab);
                tab.getWebView().loadUrl(directUrl);
            } else if (openInNewTab || !restored) {
                TabModel tab = tabsManager.createTab(this, directUrl);
                setupClientsForTab(tab);
                tab.getWebView().loadUrl(directUrl);
            } else {
                TabModel active = tabsManager.getActiveTab();
                if (active != null) {
                    active.setUrl(directUrl);
                    active.getWebView().loadUrl(directUrl);
                } else {
                    TabModel tab = tabsManager.createTab(this, directUrl);
                    setupClientsForTab(tab);
                    tab.getWebView().loadUrl(directUrl);
                }
            }
            saveSession();
            return;
        }

        if (incomingUrl != null) {
            if (isDownloadableUrl(incomingUrl)) {
                String cookies = CookieManager.getInstance().getCookie(incomingUrl);
                DownloadPromptDialog.show(this, incomingUrl, null, null, null, cookies, null, null);
            } else {
                TabModel tab = tabsManager.createTab(this, incomingUrl);
                setupClientsForTab(tab);
                tab.getWebView().loadUrl(incomingUrl);
            }
            saveSession();
            return;
        }

        if (!restored) {
            // Fallback to fresh initial tab
            String home = getDefaultHomeUrl();
            TabModel tab = tabsManager.createTab(this, home);
            setupClientsForTab(tab);
            tab.getWebView().loadUrl(home);
            saveSession();
        } else {
            saveSession();
        }

        if (incomingUrl != null && isDownloadableUrl(incomingUrl)) {
            String cookies = CookieManager.getInstance().getCookie(incomingUrl);
            DownloadPromptDialog.show(this, incomingUrl, null, null, null, cookies, null, null);
        }
    }

    private void attachTabToView(TabModel tab) {
        hideOmniboxSuggestions();
        webViewContainer.removeAllViews();
        PrivacyWebView webView = tab.getWebView();

        // Configure Clients & Protection Shims
        setupClientsForTab(tab);

        // Configure per-site JavaScript (NoScript)
        boolean jsEnabled = com.privacy.browser.adblock.AdBlockManager.getInstance(this).isSiteJavaScriptEnabled(tab.getUrl());
        webView.getSettings().setJavaScriptEnabled(jsEnabled);

        webViewContainer.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editUrl.clearFocus();
        displayFormattedUrl(tab.getUrl());

        int iconRes = tab.isIncognito() ? R.drawable.ic_incognito : R.drawable.ic_shield;
        blockedTrackerCount = com.privacy.browser.adblock.AdBlockManager.getInstance(this).getPageBlockedCount(tab.getId());
        btnShield.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
        btnShield.setText(String.valueOf(blockedTrackerCount));

        int detectedCount = MediaSniffer.getMediaForTab(tab.getId()).size();
        updateFabVideoDownload(detectedCount);

        com.privacy.browser.comic.ComicManager.ComicItem comicItem =
                com.privacy.browser.comic.ComicManager.getDetectedComic(tab.getId());
        updateFabComicDownload(comicItem != null ? comicItem.getPageCount() : 0);
    }

    private void setupClientsForTab(TabModel tab) {
        if (tab.isInitialized()) {
            return;
        }
        tab.setInitialized(true);

        PrivacyWebView webView = tab.getWebView();

        // 0. Attach Real-Time Video Sniffer JS Bridge
        webView.addJavascriptInterface(new PrivacyVideoSnifferBridge(new PrivacyVideoSnifferBridge.VideoCallback() {
            @Override
            public void onVideoDetected(String pageUrl, String title, String videoSrc) {
                MediaSniffer.inspectVideoElement(tab.getId(), pageUrl, title, videoSrc);
            }

            @Override
            public void onPageUrlChanged(String pageUrl, String title) {
                runOnUiThread(() -> {
                    if (pageUrl == null || pageUrl.isEmpty()) return;
                    String prevUrl = tab.getUrl();
                    tab.setUrl(pageUrl);
                    if (title != null && !title.isEmpty()) {
                        tab.setTitle(title);
                    }
                    if (!editUrl.hasFocus()) {
                        displayFormattedUrl(pageUrl);
                    }
                    saveSession();

                    boolean isSameVideo = false;
                    if (prevUrl != null) {
                        String prevVid = MediaSniffer.extractVideoId(prevUrl);
                        String newVid = MediaSniffer.extractVideoId(pageUrl);
                        if (prevVid != null && prevVid.equals(newVid)) {
                            isSameVideo = true;
                        } else if (prevUrl.equals(pageUrl)) {
                            isSameVideo = true;
                        }
                    }

                    if (!isSameVideo) {
                        MediaSniffer.clearTab(tab.getId());
                        updateFabVideoDownload(0);
                    }

                    String newVid = MediaSniffer.extractVideoId(pageUrl);
                    if (newVid != null) {
                        if (MediaSniffer.isVimeoUrl(pageUrl)) {
                            MediaSniffer.resolveVimeoFromUrl(tab.getId(), pageUrl, title, null);
                        } else if (MediaSniffer.isYouTubeUrl(pageUrl)) {
                            MediaSniffer.resolveYouTubeFromUrl(tab.getId(), pageUrl, title, null);
                        }
                    }
                });
            }

            @Override
            public void onYouTubePlayerResponse(String pageUrl, String title, String jsonResponse) {
                MediaSniffer.parseYouTubeData(tab.getId(), pageUrl, title, jsonResponse);
            }
        }), "PrivacyVideoSniffer");

        // 1. Attach Anti-Fingerprinting Suite (document_start shims & bridge)
        webView.setToolbarCoordinator(new PrivacyWebView.ToolbarCoordinator() {
            @Override
            public boolean isToolbarLocked() {
                TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                if (active == null || active.getWebView() != webView) return true;
                return isTopBarLocked();
            }

            @Override
            public int getToolbarHeight() {
                return getToolbarHeightPx();
            }

            @Override
            public float onToolbarDrag(float deltaY) {
                TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                if (active == null || active.getWebView() != webView) return 0f;
                return handleToolbarDrag(deltaY);
            }

            @Override
            public void onToolbarRelease() {
                TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                if (active == null || active.getWebView() != webView) return;
                handleToolbarRelease();
            }

            @Override
            public void cancelToolbarAnimation() {
                cancelTopBarAnimation();
            }
        });

        webView.setOnScrollListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
            if (active != null && active.getWebView() == view) {
                handlePageScroll(scrollY, oldScrollY);
            }
        });

        FingerprintDefenseManager.attach(this, webView, apiType -> {
            updateBlockedCounter(1);
        });

        // 1b. Attach Document-Start Cosmetic Ad Pruner, Anti-Adblock Defuser, YouTube Defuser & Popup Defuser
        com.privacy.browser.adblock.AntiAdblockDefuser.attachDocumentStart(this, webView);
        com.privacy.browser.adblock.YouTubeAdDefuser.attachDocumentStart(this, webView);
        com.privacy.browser.adblock.PopupClickDefuser.attachDocumentStart(this, webView);

        // 2. Attach WebChromeClient for Progress & Popup Interception
        webView.getSettings().setSupportMultipleWindows(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                tab.setTitle(title);
            }

            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                if (icon != null && tab.getUrl() != null) {
                    String domain = com.privacy.browser.favicon.FaviconManager.extractDomain(tab.getUrl());
                    if (!domain.isEmpty()) {
                        com.privacy.browser.favicon.FaviconManager.getInstance(MainActivity.this).saveFavicon(domain, icon);
                    }
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullscreenVideo(view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreenVideo();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                com.privacy.browser.adblock.AdBlockManager abm = com.privacy.browser.adblock.AdBlockManager.getInstance(MainActivity.this);

                if (abm.isAdBlockActiveForSite(tab.getUrl())) {
                    // If popup blocking is active for this site, strictly block unprompted background ad popups
                    if (abm.isSitePopupsBlocked(tab.getUrl()) && !isUserGesture && !isDialog) {
                        Log.i("AdBlock", "Blocked unprompted ad popup window on " + tab.getUrl());
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Blocked ad popup", Toast.LENGTH_SHORT).show());
                        return false;
                    }

                    WebView.HitTestResult result = view.getHitTestResult();
                    String data = result != null ? result.getExtra() : null;
                    if (data != null) {
                        try {
                            if (abm.isBlocked(android.net.Uri.parse(data), tab.getUrl())) {
                                Log.i("AdBlock", "Blocked popup to ad target: " + data);
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Blocked ad popup", Toast.LENGTH_SHORT).show());
                                return false;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                TabModel newTab = tabsManager.createTab(MainActivity.this, "about:blank", tab.isIncognito());
                setupClientsForTab(newTab);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newTab.getWebView());
                resultMsg.sendToTarget();
                return true;
            }
        });

        // 3. Attach PrivacyWebViewClient for Subresource Blocking & Cosmetic CSS
        webView.setWebViewClient(new PrivacyWebViewClient(trackerBlocker, tab.getId(), tab.getUrl(), MainActivity.this, new PrivacyWebViewClient.ClientListener() {
            @Override
            public void onPageStarted(String url, Bitmap favicon) {
                showTopBar(false);
                hideOmniboxSuggestions();
                isLoading = true;
                btnReload.setImageResource(R.drawable.ic_close);
                tab.setUrl(url);
                com.privacy.browser.media.MediaSniffer.clearTab(tab.getId());
                updateFabVideoDownload(0);
                com.privacy.browser.comic.ComicManager.clearTab(tab.getId());
                updateFabComicDownload(0);
                updateFloatingActionButtonsForActiveTab();
                com.privacy.browser.adblock.AdBlockManager.getInstance(MainActivity.this).resetPageStats(tab.getId());
                blockedTrackerCount = 0;
                btnShield.setText("0");
                if (!editUrl.hasFocus()) {
                    displayFormattedUrl(url);
                }
                saveSession();
            }

            @Override
            public void onPageFinished(String url) {
                isLoading = false;
                btnReload.setImageResource(R.drawable.ic_reload);
                tab.setUrl(url);
                if (!editUrl.hasFocus()) {
                    displayFormattedUrl(url);
                }
                updateFloatingActionButtonsForActiveTab();
                if (!tab.isIncognito() && historyDbHelper != null && url != null && !url.startsWith("about:") && !isHomePageUrl(url)) {
                    String title = tab.getTitle();
                    if (title == null || title.isEmpty() || title.equals("New Tab") || title.equals("Untitled")) {
                        title = webView.getTitle();
                    }
                    historyDbHelper.addVisit(title, url);
                }
                saveSession();
            }

            @Override
            public void onTrackerBlocked(String host) {
                updateBlockedCounter(1);
            }
        }));

        // 3.5. Attach BlobDownloadBridge for blob: object URL downloads
        webView.addJavascriptInterface(com.privacy.downloader.interceptor.BlobDownloadManager.getInstance(this).getBridge(), "BlobDownloadBridge");

        // 3.6. Attach AntiAdblockBridge for anti-adblock neutralization telemetry & state queries
        webView.addJavascriptInterface(new com.privacy.browser.adblock.AntiAdblockBridge(
                com.privacy.browser.adblock.AdBlockManager.getInstance(this),
                tab.getId(),
                () -> tab.getUrl(),
                (tabId, detector, details) -> {
                    Log.d("AntiAdblockBridge", "Defeated anti-adblock on tab " + tabId + ": " + detector + " - " + details);
                }
        ), "PrivacyAntiAdblockBridge");

        // 3.7. Attach ComicDetectorBridge for sequential comic streams & CBR archiver
        webView.addJavascriptInterface(new com.privacy.browser.comic.ComicDetectorBridge(tab.getId(), (tabId, title, imageUrls) -> {
            if (!com.privacy.browser.settings.SettingsActivity.isComicDownloaderEnabled(MainActivity.this)) return;
            com.privacy.browser.comic.ComicManager.setDetectedComic(tabId, title, imageUrls, tab.getUrl());
            runOnUiThread(() -> {
                TabModel current = tabsManager != null ? tabsManager.getActiveTab() : null;
                if (current != null && current.getId().equals(tabId)) {
                    updateFabComicDownload(imageUrls.size());
                }
            });
        }), "ComicDetectorBridge");

        // 3.8. Attach PrivacyElementPickerBridge for uBlock Origin element picker & zapper
        webView.addJavascriptInterface(new com.privacy.browser.adblock.picker.ElementPickerBridge(
                new com.privacy.browser.adblock.picker.ElementPickerBridge.ElementPickerCallback() {
                    @Override
                    public void onElementPicked(String nodesJson) {
                        runOnUiThread(() -> {
                            com.privacy.browser.adblock.picker.ElementPickerDialog.show(
                                    MainActivity.this,
                                    webView,
                                    tab.getUrl(),
                                    nodesJson,
                                    () -> Log.i("AdBlock", "Custom filter applied to tab " + tab.getId())
                            );
                        });
                    }

                    @Override
                    public void onElementZapped() {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Element zapped", Toast.LENGTH_SHORT).show());
                    }
                }
        ), "PrivacyElementPickerBridge");

        // 3.8.1. Attach ImageSearchBridge for Search by Image web element selection
        webView.addJavascriptInterface(new com.privacy.imagesearch.bridge.ImageSearchBridge(
                new com.privacy.imagesearch.bridge.ImageSearchBridge.BridgeCallback() {
                    @Override
                    public void onImageSelected(@NonNull String imageUrl) {
                        runOnUiThread(() -> showImageSearchEngineChooser(tab, imageUrl));
                    }

                    @Override
                    public void onPickerCancelled() {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Image selection cancelled", Toast.LENGTH_SHORT).show());
                    }
                }
        ), "ImageSearchBridge");

        // 3.9. Attach AndroidInterface for local homepage bridge (search focus, engine search, theme, stats, navigation)
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void focusAddressBar() {
                runOnUiThread(() -> {
                    if (editUrl != null) {
                        editUrl.requestFocus();
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(editUrl, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        }
                    }
                });
            }

            @android.webkit.JavascriptInterface
            public void searchOrNavigate(String query, String engine) {
                runOnUiThread(() -> {
                    if (query == null || query.trim().isEmpty()) return;
                    String q = query.trim();
                    String targetUrl;
                    if (q.startsWith("http://") || q.startsWith("https://")) {
                        targetUrl = q;
                    } else if (q.contains(".") && !q.contains(" ")) {
                        targetUrl = "https://" + q;
                    } else {
                        String eng = engine != null ? engine.toLowerCase() : "ddg";
                        if ("google".equals(eng)) {
                            targetUrl = "https://www.google.com/search?q=" + android.net.Uri.encode(q);
                        } else if ("brave".equals(eng)) {
                            targetUrl = "https://search.brave.com/search?q=" + android.net.Uri.encode(q);
                        } else if ("searx".equals(eng)) {
                            targetUrl = "https://searx.be/search?q=" + android.net.Uri.encode(q);
                        } else if ("startpage".equals(eng)) {
                            targetUrl = "https://www.startpage.com/sp/search?query=" + android.net.Uri.encode(q);
                        } else {
                            targetUrl = "https://duckduckgo.com/?q=" + android.net.Uri.encode(q);
                        }
                    }
                    tab.setUrl(targetUrl);
                    webView.loadUrl(targetUrl);
                    saveSession();
                });
            }

            @android.webkit.JavascriptInterface
            public void openBookmarks() {
                runOnUiThread(() -> startActivity(new Intent(MainActivity.this, BookmarksActivity.class)));
            }

            @android.webkit.JavascriptInterface
            public void openHistory() {
                runOnUiThread(() -> startActivity(new Intent(MainActivity.this, HistoryActivity.class)));
            }

            @android.webkit.JavascriptInterface
            public void openDownloads() {
                runOnUiThread(() -> startActivity(new Intent(MainActivity.this, com.privacy.downloader.ui.DownloadsActivity.class)));
            }

            @android.webkit.JavascriptInterface
            public void openSettings() {
                runOnUiThread(() -> startActivity(new Intent(MainActivity.this, com.privacy.browser.settings.SettingsActivity.class)));
            }

            @android.webkit.JavascriptInterface
            public void openIncognito() {
                runOnUiThread(() -> {
                    String home = getDefaultHomeUrl();
                    TabModel newTab = tabsManager.createTab(MainActivity.this, home, true);
                    setupClientsForTab(newTab);
                    newTab.getWebView().loadUrl(home);
                    tabsManager.switchTab(tabsManager.getTabCount() - 1);
                });
            }

            @android.webkit.JavascriptInterface
            public String getAppTheme() {
                return ThemeManager.isAppDarkTheme(MainActivity.this) ? "dark" : "light";
            }

            @android.webkit.JavascriptInterface
            public void toggleTheme() {
                runOnUiThread(() -> {
                    boolean isDark = ThemeManager.isAppDarkTheme(MainActivity.this);
                    String newTheme = isDark ? ThemeManager.APP_THEME_LIGHT : ThemeManager.APP_THEME_DARK;
                    ThemeManager.setAppTheme(MainActivity.this, newTheme);
                    saveSession();
                    recreate();
                });
            }

            @android.webkit.JavascriptInterface
            public long getTotalBlockedCount() {
                return com.privacy.browser.adblock.AdBlockManager.getInstance(MainActivity.this).getTotalBlockedCount();
            }

            @android.webkit.JavascriptInterface
            public String getRecentBookmarksJson() {
                try {
                    if (bookmarkDbHelper == null) return "[]";
                    java.util.List<BookmarkModel> list = bookmarkDbHelper.getAllBookmarks();
                    if (list == null || list.isEmpty()) return "[]";
                    org.json.JSONArray arr = new org.json.JSONArray();
                    int max = Math.min(list.size(), 6);
                    for (int i = 0; i < max; i++) {
                        BookmarkModel bm = list.get(i);
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("title", bm.getTitle());
                        o.put("url", bm.getUrl());
                        arr.put(o);
                    }
                    return arr.toString();
                } catch (Exception e) {
                    return "[]";
                }
            }
        }, "AndroidInterface");

        // 4. Attach BrowserDownloadListener for advanced segmented downloads
        webView.setDownloadListener(new BrowserDownloadListener(this, () -> tab.getUrl(), () -> tab.getWebView()));

        // 5. Attach Long-Press Context Menu for links & images
        setupWebViewContextMenu(webView, tab);
    }

    private void displayFormattedUrl(String url) {
        if (url == null || url.isEmpty() || editUrl.hasFocus()) return;

        ImageView btnSiteSecurity = findViewById(R.id.btn_site_security);

        if (url.equals(DEFAULT_HOME_URL) || url.startsWith("file:///android_asset/")) {
            editUrl.setText("");
            editUrl.setHint(R.string.search_hint);
            if (btnSiteSecurity != null) {
                btnSiteSecurity.setColorFilter(Color.parseColor("#4CAF50"));
            }
            TabModel activeTab = tabsManager != null ? tabsManager.getActiveTab() : null;
            if (activeTab != null && activeTab.getWebView() != null) {
                String themeMode = ThemeManager.isAppDarkTheme(this) ? "dark" : "light";
                long totalBlocked = com.privacy.browser.adblock.AdBlockManager.getInstance(this).getTotalBlockedCount();
                String js = "if (typeof applyTheme === 'function') applyTheme('" + themeMode + "'); if (typeof refreshHomeData === 'function') refreshHomeData(" + totalBlocked + ");";
                activeTab.getWebView().evaluateJavascript(js, null);
            }
        } else {
            boolean isDark = ThemeManager.isAppDarkTheme(this);
            int domainTextColor = isDark ? Color.WHITE : Color.parseColor("#111827");
            int neutralIconColor = isDark ? Color.WHITE : Color.parseColor("#111827");

            if (url.startsWith("https://")) {
                SpannableStringBuilder builder = new SpannableStringBuilder(url);
                builder.setSpan(new ForegroundColorSpan(Color.parseColor("#4CAF50")), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new ForegroundColorSpan(domainTextColor), 5, url.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                editUrl.setText(builder, TextView.BufferType.SPANNABLE);
                if (btnSiteSecurity != null) {
                    btnSiteSecurity.setColorFilter(Color.parseColor("#4CAF50"));
                }
            } else if (url.startsWith("http://")) {
                SpannableStringBuilder builder = new SpannableStringBuilder(url);
                builder.setSpan(new ForegroundColorSpan(Color.parseColor("#FF5252")), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new ForegroundColorSpan(domainTextColor), 4, url.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                editUrl.setText(builder, TextView.BufferType.SPANNABLE);
                if (btnSiteSecurity != null) {
                    btnSiteSecurity.setColorFilter(Color.parseColor("#FF5252"));
                }
            } else {
                editUrl.setText(url);
                if (btnSiteSecurity != null) {
                    btnSiteSecurity.setColorFilter(neutralIconColor);
                }
            }
        }
    }

    private String getDefaultHomeUrl() {
        return DEFAULT_HOME_URL;
    }

    public static boolean isHomePageUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        return trimmed.equals(DEFAULT_HOME_URL)
                || trimmed.startsWith(DEFAULT_HOME_URL)
                || trimmed.startsWith("file:///android_asset/");
    }

    private boolean isCurrentTabOnHomePage() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active == null) return true;
        String url = active.getUrl();
        if (url == null && active.getWebView() != null) {
            url = active.getWebView().getUrl();
        }
        return isHomePageUrl(url);
    }

    private void updateFloatingActionButtonsForActiveTab() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null) {
            int detectedCount = MediaSniffer.getMediaForTab(active.getId()).size();
            updateFabVideoDownload(detectedCount);
            com.privacy.browser.comic.ComicManager.ComicItem comicItem =
                    com.privacy.browser.comic.ComicManager.getDetectedComic(active.getId());
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

        String targetUrl;
        if (SearchEngineManager.isBangsEnabled(this) && SearchEngineManager.detectBang(query) != null) {
            targetUrl = SearchEngineManager.buildSearchUrl(this, query);
        } else if (query.startsWith("http://") || query.startsWith("https://") || query.startsWith("about:") || query.startsWith("file://") || query.startsWith("javascript:")) {
            targetUrl = query;
        } else if (isLocalHostOrIp(query)) {
            targetUrl = "http://" + query;
        } else if (query.contains(".") && !query.contains(" ")) {
            targetUrl = "https://" + query;
        } else {
            targetUrl = SearchEngineManager.buildSearchUrl(this, query);
        }
        active.setUrl(targetUrl);
        active.getWebView().loadUrl(targetUrl);
        saveSession();
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

    private void updateOmniboxSuggestions(String input) {
        if (!SearchEngineManager.isAutocompleteEnabled(this)) {
            hideOmniboxSuggestions();
            return;
        }

        if (input == null || input.trim().isEmpty()) {
            hideOmniboxSuggestions();
            return;
        }

        String query = input.trim();
        List<OmniboxSuggestion> suggestions = new ArrayList<>();

        // 0. Check Clipboard for URL or text to paste
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clipText = cm.getPrimaryClip().getItemAt(0).getText();
                if (clipText != null) {
                    String clipStr = clipText.toString().trim();
                    if (!clipStr.isEmpty() && !clipStr.equalsIgnoreCase(query)) {
                        boolean isUrl = clipStr.startsWith("http://") || clipStr.startsWith("https://") || (clipStr.contains(".") && !clipStr.contains(" ")) || isLocalHostOrIp(clipStr);
                        String target = isUrl ? (clipStr.startsWith("http") ? clipStr : (isLocalHostOrIp(clipStr) ? "http://" + clipStr : "https://" + clipStr)) : SearchEngineManager.buildSearchUrl(this, clipStr);
                        suggestions.add(new OmniboxSuggestion(
                                OmniboxSuggestion.Type.CLIPBOARD,
                                isUrl ? "Link you copied" : "Text you copied",
                                clipStr,
                                target,
                                R.drawable.ic_find
                        ));
                    }
                }
            }
        } catch (Exception ignored) {}

        // 0.5. Check direct Localhost or IP navigation
        if (isLocalHostOrIp(query)) {
            String target = query.startsWith("http://") || query.startsWith("https://") ? query : "http://" + query;
            suggestions.add(new OmniboxSuggestion(
                    OmniboxSuggestion.Type.SEARCH,
                    query,
                    "Open " + target,
                    target,
                    R.drawable.ic_find
            ));
        }

        // 1. Check Bang Command match
        if (SearchEngineManager.isBangsEnabled(this)) {
            SearchEngineManager.BangResult bang = SearchEngineManager.detectBang(query);
            if (bang != null) {
                String bangTitle = bang.getServiceName() + " Search (" + bang.getTag() + ")";
                String bangSub = bang.getCleanQuery().isEmpty() ? "Search on " + bang.getServiceName() : bang.getCleanQuery();
                suggestions.add(new OmniboxSuggestion(
                        OmniboxSuggestion.Type.BANG,
                        bangTitle,
                        bangSub,
                        bang.getTargetUrl(),
                        R.drawable.ic_search
                ));
            }
        }

        // 2. Query Bookmarks
        if (bookmarkDbHelper != null) {
            List<BookmarkModel> bookmarks = bookmarkDbHelper.searchBookmarks(query, 3);
            for (BookmarkModel bm : bookmarks) {
                suggestions.add(new OmniboxSuggestion(
                        OmniboxSuggestion.Type.BOOKMARK,
                        bm.getTitle(),
                        bm.getUrl(),
                        bm.getUrl(),
                        R.drawable.ic_bookmark
                ));
            }
        }

        // 3. Query History (suppressed on incognito tabs)
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        boolean isIncognito = active != null && active.isIncognito();
        if (!isIncognito && historyDbHelper != null) {
            List<HistoryModel> historyItems = historyDbHelper.searchHistory(query, 3);
            for (HistoryModel hm : historyItems) {
                boolean duplicate = false;
                for (OmniboxSuggestion s : suggestions) {
                    if (s.getTargetUrl().equalsIgnoreCase(hm.getUrl())) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    suggestions.add(new OmniboxSuggestion(
                            OmniboxSuggestion.Type.HISTORY,
                            hm.getTitle(),
                            hm.getUrl(),
                            hm.getUrl(),
                            R.drawable.ic_history
                    ));
                }
            }
        }

        // 4. Search Engine fallback suggestion
        SearchEngineManager.SearchEngine engine = SearchEngineManager.getSelectedEngine(this);
        String searchTargetUrl = SearchEngineManager.buildSearchUrl(this, query);
        suggestions.add(new OmniboxSuggestion(
                OmniboxSuggestion.Type.SEARCH,
                query,
                "Search with " + engine.getName(),
                searchTargetUrl,
                R.drawable.ic_search
        ));

        if (suggestions.isEmpty()) {
            hideOmniboxSuggestions();
        } else {
            if (omniboxAdapter != null) {
                omniboxAdapter.updateItems(suggestions);
            }
            if (omniboxContainer != null && omniboxContainer.getVisibility() != View.VISIBLE) {
                omniboxContainer.setVisibility(View.VISIBLE);
            }
        }
    }

    private void hideOmniboxSuggestions() {
        if (omniboxContainer != null && omniboxContainer.getVisibility() == View.VISIBLE) {
            omniboxContainer.setVisibility(View.GONE);
            if (omniboxAdapter != null) {
                omniboxAdapter.clear();
            }
        }
    }

    // =========================================================================
    // Modern Browser Actions (Tabs, Reader Mode, Desktop Mode, Fire Nuke)
    // =========================================================================

    private void showTabsDialog() {
        TabModel currentActive = tabsManager.getActiveTab();
        if (currentActive != null) {
            currentActive.captureThumbnail();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_tabs_grid, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }

        TextView title = view.findViewById(R.id.tabs_grid_title);
        title.setText("Open Tabs (" + tabsManager.getTabCount() + ")");

        androidx.recyclerview.widget.RecyclerView recyclerView = view.findViewById(R.id.recycler_tabs);
        recyclerView.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 2));

        final androidx.recyclerview.widget.RecyclerView.Adapter[] adapterHolder = new androidx.recyclerview.widget.RecyclerView.Adapter[1];

        class TabCardViewHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            TextView textTitle;
            TextView textUrl;
            ImageView iconIncognito;
            ImageView imgPreview;
            View layoutPlaceholder;
            View indicatorActive;
            ImageView btnClose;

            public TabCardViewHolder(View itemView) {
                super(itemView);
                textTitle = itemView.findViewById(R.id.tab_card_title);
                textUrl = itemView.findViewById(R.id.tab_card_url);
                iconIncognito = itemView.findViewById(R.id.tab_icon_incognito);
                imgPreview = itemView.findViewById(R.id.tab_card_preview);
                layoutPlaceholder = itemView.findViewById(R.id.tab_card_placeholder);
                indicatorActive = itemView.findViewById(R.id.tab_active_indicator);
                btnClose = itemView.findViewById(R.id.tab_card_btn_close);
            }
        }

        adapterHolder[0] = new androidx.recyclerview.widget.RecyclerView.Adapter<TabCardViewHolder>() {
            @NonNull
            @Override
            public TabCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View card = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_tab_grid_card, parent, false);
                return new TabCardViewHolder(card);
            }

            @Override
            public void onBindViewHolder(@NonNull TabCardViewHolder holder, int position) {
                final int pos = holder.getAdapterPosition();
                if (pos < 0 || pos >= tabsManager.getTabCount()) return;

                TabModel tab = tabsManager.getTabs().get(pos);
                holder.textTitle.setText(tab.getTitle());
                holder.textUrl.setText(tab.getUrl() != null ? tab.getUrl().replace("https://", "").replace("http://", "") : "");
                holder.iconIncognito.setVisibility(tab.isIncognito() ? View.VISIBLE : View.GONE);

                boolean isActive = (pos == tabsManager.getActiveIndex());
                holder.indicatorActive.setVisibility(isActive ? View.VISIBLE : View.GONE);

                Bitmap thumb = tab.getThumbnail();
                if (thumb != null && !thumb.isRecycled()) {
                    holder.imgPreview.setImageBitmap(thumb);
                    holder.imgPreview.setVisibility(View.VISIBLE);
                    holder.layoutPlaceholder.setVisibility(View.GONE);
                } else {
                    holder.imgPreview.setImageDrawable(null);
                    holder.imgPreview.setVisibility(View.GONE);
                    holder.layoutPlaceholder.setVisibility(View.VISIBLE);
                }

                holder.itemView.setOnClickListener(v -> {
                    int clickedPos = holder.getAdapterPosition();
                    if (clickedPos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        tabsManager.switchTab(clickedPos);
                        dialog.dismiss();
                    }
                });

                holder.btnClose.setOnClickListener(v -> {
                    int closePos = holder.getAdapterPosition();
                    if (closePos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        if (tabsManager.getTabCount() <= 1) {
                            // User closed the last remaining tab
                            tabsManager.closeTab(closePos, MainActivity.this);
                            TabModel active = tabsManager.getActiveTab();
                            if (active != null) {
                                active.getWebView().loadUrl(getDefaultHomeUrl());
                            }
                            dialog.dismiss();
                        } else {
                            tabsManager.closeTab(closePos, MainActivity.this);
                            title.setText("Open Tabs (" + tabsManager.getTabCount() + ")");
                            notifyItemRemoved(closePos);
                            notifyItemRangeChanged(closePos, tabsManager.getTabCount());
                        }
                    }
                });
            }

            @Override
            public int getItemCount() {
                return tabsManager.getTabCount();
            }
        };

        recyclerView.setAdapter(adapterHolder[0]);

        // Attach Swipe-to-Dismiss (Left and Right)
        androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback swipeCallback =
                new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.LEFT | androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull androidx.recyclerview.widget.RecyclerView rView,
                                  @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder vh,
                                  @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder, int direction) {
                int swipedPos = viewHolder.getAdapterPosition();
                if (swipedPos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && swipedPos < tabsManager.getTabCount()) {
                    if (tabsManager.getTabCount() <= 1) {
                        // User swiped away the final tab
                        tabsManager.closeTab(swipedPos, MainActivity.this);
                        TabModel active = tabsManager.getActiveTab();
                        if (active != null) {
                            active.getWebView().loadUrl(getDefaultHomeUrl());
                        }
                        dialog.dismiss();
                    } else {
                        tabsManager.closeTab(swipedPos, MainActivity.this);
                        title.setText("Open Tabs (" + tabsManager.getTabCount() + ")");
                        adapterHolder[0].notifyItemRemoved(swipedPos);
                        adapterHolder[0].notifyItemRangeChanged(swipedPos, tabsManager.getTabCount());
                    }
                }
            }
        };

        new androidx.recyclerview.widget.ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView);

        view.findViewById(R.id.tabs_btn_new_tab).setOnClickListener(v -> {
            String home = getDefaultHomeUrl();
            TabModel newTab = tabsManager.createTab(this, home, false);
            newTab.getWebView().loadUrl(home);
            dialog.dismiss();
        });

        view.findViewById(R.id.tabs_btn_new_incognito).setOnClickListener(v -> {
            String home = getDefaultHomeUrl();
            TabModel newTab = tabsManager.createTab(this, home, true);
            newTab.getWebView().loadUrl(home);
            dialog.dismiss();
        });

        view.findViewById(R.id.tabs_btn_close_all).setOnClickListener(v -> {
            AlertDialog.Builder closeConfirmBuilder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(MainActivity.this));
            closeConfirmBuilder.setTitle("Close All Tabs");
            closeConfirmBuilder.setMessage("Close all " + tabsManager.getTabCount() + " open tabs?");
            closeConfirmBuilder.setPositiveButton("Close All", (d, w) -> {
                tabsManager.closeAllTabs(this);
                dialog.dismiss();
            });
            closeConfirmBuilder.setNegativeButton("Cancel", null);
            AlertDialog closeConfirmDlg = closeConfirmBuilder.create();
            closeConfirmDlg.show();
            applyAmoledDialogTheme(closeConfirmDlg);
        });

        dialog.show();
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
                active.getWebView().goBack();
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
                active.getWebView().goForward();
            });
        } else {
            navForward.setEnabled(false);
            navForward.setColorFilter(disabledIconColor);
        }

        navReload.setColorFilter(activeIconColor);
        navReload.setOnClickListener(v -> {
            popupWindow.dismiss();
            active.getWebView().reload();
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

        // Ad Blocker Shield item
        View itemAdblock = popupView.findViewById(R.id.menu_item_adblock);
        if (itemAdblock != null) {
            TextView badgeAdblock = popupView.findViewById(R.id.menu_badge_adblock);
            ImageView iconAdblock = popupView.findViewById(R.id.menu_icon_adblock);
            com.privacy.browser.adblock.AdBlockManager abm = com.privacy.browser.adblock.AdBlockManager.getInstance(this);
            boolean isWhitelisted = abm.isSiteWhitelisted(active.getUrl());
            int blockedCount = abm.getPageBlockedCount(active.getId());
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
                        active.getId(),
                        active.getUrl(),
                        () -> active.getWebView().reload(),
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
                com.privacy.imagesearch.ui.ImageSearchHubDialog.show(
                        MainActivity.this,
                        null,
                        new com.privacy.imagesearch.ui.ImageSearchHubDialog.ImageSearchCallback() {
                            @Override
                            public void onSearchRequested(@NonNull String searchUrl, boolean openInBackground) {
                                if (openInBackground) {
                                    TabModel bgTab = tabsManager.createTab(MainActivity.this, searchUrl, active.isIncognito(), true);
                                    setupClientsForTab(bgTab);
                                    bgTab.getWebView().loadUrl(searchUrl);
                                    showBackgroundTabNotice(bgTab);
                                } else {
                                    TabModel newTab = tabsManager.createTab(MainActivity.this, searchUrl, active.isIncognito());
                                    setupClientsForTab(newTab);
                                    newTab.getWebView().loadUrl(searchUrl);
                                    tabsManager.switchTab(tabsManager.getTabCount() - 1);
                                }
                            }

                            @Override
                            public void onSelectWebImageRequested() {
                                startImageSearchPicker();
                            }

                            @Override
                            public void onCaptureRegionRequested() {
                                startImageSearchAreaCrop();
                            }

                            @Override
                            public void onUploadLocalImageRequested() {
                                startImageSearchFilePicker();
                            }
                        }
                );
            });
        }

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
            ReaderModeHelper.toggleReaderMode(active.getWebView());
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
            nukeAllData();
        });

        int xOffset = (int) (-180 * getResources().getDisplayMetrics().density);
        int yOffset = (int) (6 * getResources().getDisplayMetrics().density);
        popupWindow.showAsDropDown(btnMenu, xOffset, yOffset);
    }

    private void extractAndGrabPageLinks(TabModel active) {
        if (active == null || active.getWebView() == null) return;
        PrivacyWebView webView = active.getWebView();
        String js = "(function() {" +
                "  var list = [];" +
                "  var seen = new Set();" +
                "  function categorize(url, fallbackType) {" +
                "    var u = (url || '').toLowerCase().split('?')[0];" +
                "    if (u.match(/\\.(mp4|mkv|webm|avi|mov|flv|m3u8|ts|mpd)$/)) return 'video';" +
                "    if (u.match(/\\.(mp3|m4a|aac|flac|wav|ogg|opus)$/)) return 'audio';" +
                "    if (u.match(/\\.(zip|rar|7z|tar|gz|bz2|xz|apk|iso)$/)) return 'archive';" +
                "    if (u.match(/\\.(pdf|epub|mobi|doc|docx|xls|xlsx|ppt|pptx)$/)) return 'document';" +
                "    if (u.match(/\\.(jpg|jpeg|png|webp|gif|svg|bmp)$/)) return 'image';" +
                "    return fallbackType || 'link';" +
                "  }" +
                "  function add(url, text, type) {" +
                "    if (!url || url.indexOf('javascript:') === 0 || url.indexOf('about:') === 0 || url.indexOf('blob:') === 0 || url.indexOf('proxy.html') !== -1) return;" +
                "    if (seen.has(url)) return;" +
                "    seen.add(url);" +
                "    list.push({url: url, title: (text || '').trim().substring(0, 100), type: categorize(url, type)});" +
                "  }" +
                "  var links = document.querySelectorAll('a[href]');" +
                "  for (var i = 0; i < links.length; i++) {" +
                "    add(links[i].href, links[i].innerText || links[i].getAttribute('title'), 'link');" +
                "  }" +
                "  var media = document.querySelectorAll('img[src], video[src], audio[src], source[src], track[src]');" +
                "  for (var j = 0; j < media.length; j++) {" +
                "    var tag = media[j].tagName.toLowerCase();" +
                "    var t = tag === 'img' ? 'image' : (tag === 'video' ? 'video' : (tag === 'audio' ? 'audio' : 'link'));" +
                "    add(media[j].src, media[j].getAttribute('alt') || media[j].getAttribute('title') || media[j].getAttribute('label') || '', t);" +
                "  }" +
                "  var iframes = document.querySelectorAll('iframe[src]');" +
                "  for (var k = 0; k < iframes.length; k++) {" +
                "    var isrc = iframes[k].src;" +
                "    if (!isrc || isrc.indexOf('proxy.html') !== -1 || isrc.indexOf('about:') === 0 || isrc.indexOf('javascript:') === 0) continue;" +
                "    if (isrc.match(/(?:player\\.vimeo\\.com\\/video\\/|vimeo\\.com\\/)(\\d+)/i)) {" +
                "      list.push({url: isrc, title: iframes[k].getAttribute('title') || 'Vimeo Video', type: 'vimeo'});" +
                "      continue;" +
                "    }" +
                "    if (isrc.match(/(youtube\\.com|dailymotion\\.com)/i)) {" +
                "      add(isrc, iframes[k].getAttribute('title') || 'Embedded Video Player', 'link');" +
                "    }" +
                "  }" +
                "  return JSON.stringify(list);" +
                "})();";

        webView.evaluateJavascript(js, value -> {
            try {
                String currentUrl = active.getUrl();
                String pageTitle = active.getTitle();
                java.util.List<String> vimeoTargets = new java.util.ArrayList<>();
                if (com.privacy.browser.media.MediaSniffer.isVimeoUrl(currentUrl)) {
                    vimeoTargets.add(currentUrl);
                }

                java.util.List<org.json.JSONObject> domItems = new java.util.ArrayList<>();
                if (value != null && !value.equals("null") && !value.isEmpty()) {
                    String jsonStr = value;
                    if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                        org.json.JSONTokener tokener = new org.json.JSONTokener(jsonStr);
                        Object obj = tokener.nextValue();
                        if (obj instanceof String) {
                            jsonStr = (String) obj;
                        }
                    }
                    try {
                        org.json.JSONArray domArr = new org.json.JSONArray(jsonStr);
                        for (int i = 0; i < domArr.length(); i++) {
                            org.json.JSONObject item = domArr.getJSONObject(i);
                            String u = item.optString("url");
                            String t = item.optString("type");
                            if ("vimeo".equalsIgnoreCase(t)) {
                                if (u != null && !vimeoTargets.contains(u)) {
                                    vimeoTargets.add(u);
                                }
                            } else {
                                domItems.add(item);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Check if Vimeo targets need proactive resolution before displaying grabber
                java.util.List<com.privacy.browser.media.MediaSniffer.SniffedMedia> existingSniffed =
                        com.privacy.browser.media.MediaSniffer.getMediaForTab(active.getId());
                boolean hasVideoStreams = false;
                for (com.privacy.browser.media.MediaSniffer.SniffedMedia sm : existingSniffed) {
                    if ("video".equalsIgnoreCase(sm.getType())) {
                        hasVideoStreams = true;
                        break;
                    }
                }

                Runnable proceedToGrabber = () -> {
                    try {
                        org.json.JSONArray finalArr = new org.json.JSONArray();
                        java.util.Set<String> seenUrls = new java.util.HashSet<>();

                        // 1. Network & Platform Sniffed Media (Vimeo streams, HLS, DASH, direct MP4)
                        java.util.List<com.privacy.browser.media.MediaSniffer.SniffedMedia> sniffedList =
                                com.privacy.browser.media.MediaSniffer.getMediaForTab(active.getId());
                        for (com.privacy.browser.media.MediaSniffer.SniffedMedia sm : sniffedList) {
                            if (seenUrls.add(sm.getUrl())) {
                                org.json.JSONObject obj = new org.json.JSONObject();
                                obj.put("url", sm.getUrl());
                                obj.put("title", sm.getTitle());
                                obj.put("type", sm.getType());
                                if (sm.getReferrer() != null) {
                                    obj.put("referrer", sm.getReferrer());
                                }
                                finalArr.put(obj);
                            }
                        }

                        // 2. DOM Extracted items
                        for (org.json.JSONObject item : domItems) {
                            String u = item.optString("url");
                            if (u != null && seenUrls.add(u)) {
                                finalArr.put(item);
                            }
                        }

                        if (finalArr.length() == 0) {
                            Toast.makeText(MainActivity.this, "No media links found on this page", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        Intent intent = new Intent(MainActivity.this, LinkGrabberActivity.class);
                        intent.putExtra(LinkGrabberActivity.EXTRA_LINKS_JSON, finalArr.toString());
                        intent.putExtra(LinkGrabberActivity.EXTRA_COOKIES, CookieManager.getInstance().getCookie(active.getUrl()));
                        intent.putExtra(LinkGrabberActivity.EXTRA_USER_AGENT, webView.getSettings().getUserAgentString());
                        intent.putExtra(LinkGrabberActivity.EXTRA_REFERRER, active.getUrl());
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Failed to build links: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                };

                if (!hasVideoStreams && !vimeoTargets.isEmpty()) {
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(vimeoTargets.size());
                    for (String vt : vimeoTargets) {
                        com.privacy.browser.media.MediaSniffer.resolveVimeoFromUrl(active.getId(), vt, pageTitle, latch::countDown);
                    }
                    new Thread(() -> {
                        try {
                            latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (Exception ignored) {}
                        runOnUiThread(proceedToGrabber);
                    }).start();
                } else {
                    proceedToGrabber.run();
                }

            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Failed to parse page links: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupWebViewContextMenu(PrivacyWebView webView, TabModel tab) {
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result == null) return false;
            int type = result.getType();
            String extra = result.getExtra();

            if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                // Image wrapped in an anchor link: retrieve both the link href and image source
                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                android.os.Message msg = handler.obtainMessage();
                msg.setTarget(new android.os.Handler(android.os.Looper.getMainLooper(), m -> {
                    String linkUrl = m.getData().getString("url");
                    String srcUrl = m.getData().getString("src");
                    if (linkUrl == null || linkUrl.isEmpty()) linkUrl = extra;
                    if (srcUrl == null || srcUrl.isEmpty()) srcUrl = extra;
                    showTabbedContextMenu(tab, linkUrl, srcUrl, true, true);
                    return true;
                }));
                webView.requestFocusNodeHref(msg);
                return true;
            } else if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    showTabbedContextMenu(tab, extra, null, true, false);
                    return true;
                }
            } else if (type == WebView.HitTestResult.IMAGE_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    showTabbedContextMenu(tab, null, extra, false, true);
                    return true;
                }
            }
            return false;
        });
    }

    private void showTabbedContextMenu(TabModel tab, String linkUrl, String imageUrl, boolean hasLink, boolean hasImage) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(MainActivity.this));
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
                    newTab.getWebView().loadUrl(linkUrl);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Open Link in Background", R.drawable.ic_add, () -> {
                    if (isAdLink(linkUrl, tab.getUrl())) {
                        Toast.makeText(this, "Blocked ad link from opening in background", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        return;
                    }
                    TabModel bgTab = tabsManager.createTab(this, linkUrl, tab.isIncognito(), true);
                    setupClientsForTab(bgTab);
                    bgTab.getWebView().loadUrl(linkUrl);
                    showBackgroundTabNotice(bgTab);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Download Link with Manager", R.drawable.ic_download, () -> {
                    String cookies = CookieManager.getInstance().getCookie(linkUrl);
                    String userAgent = tab.getWebView().getSettings().getUserAgentString();
                    DownloadPromptDialog.show(this, linkUrl, userAgent, null, null, cookies, tab.getUrl(), null);
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
                    newTab.getWebView().loadUrl(imageUrl);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Open Image in Background", R.drawable.ic_add, () -> {
                    TabModel bgTab = tabsManager.createTab(this, imageUrl, tab.isIncognito(), true);
                    setupClientsForTab(bgTab);
                    bgTab.getWebView().loadUrl(imageUrl);
                    showBackgroundTabNotice(bgTab);
                    dialog.dismiss();
                });

                addContextAction(actionsContainer, "Download Image with Manager", R.drawable.ic_download, () -> {
                    String cookies = CookieManager.getInstance().getCookie(imageUrl);
                    String userAgent = tab.getWebView().getSettings().getUserAgentString();
                    DownloadPromptDialog.show(this, imageUrl, userAgent, null, "image/*", cookies, tab.getUrl(), null);
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
        dialog.show();
    }

    private void showBackgroundTabNotice(TabModel bgTab) {
        int tabCount = tabsManager.getTabCount();
        Toast.makeText(this, "Opened in background (Tab " + tabCount + ")", Toast.LENGTH_SHORT).show();
    }

    private void showImageSearchEngineChooser(TabModel tab, String imageUrl) {
        com.privacy.imagesearch.ui.ImageSearchHubDialog.show(
                this,
                imageUrl,
                new com.privacy.imagesearch.ui.ImageSearchHubDialog.ImageSearchCallback() {
                    @Override
                    public void onSearchRequested(@NonNull String searchUrl, boolean openInBackground) {
                        if (openInBackground) {
                            TabModel bgTab = tabsManager.createTab(MainActivity.this, searchUrl, tab.isIncognito(), true);
                            setupClientsForTab(bgTab);
                            bgTab.getWebView().loadUrl(searchUrl);
                            showBackgroundTabNotice(bgTab);
                        } else {
                            TabModel newTab = tabsManager.createTab(MainActivity.this, searchUrl, tab.isIncognito());
                            setupClientsForTab(newTab);
                            newTab.getWebView().loadUrl(searchUrl);
                            tabsManager.switchTab(tabsManager.getTabCount() - 1);
                        }
                    }

                    @Override
                    public void onSelectWebImageRequested() {
                        startImageSearchPicker();
                    }

                    @Override
                    public void onCaptureRegionRequested() {
                        startImageSearchAreaCrop();
                    }

                    @Override
                    public void onUploadLocalImageRequested() {
                        startImageSearchFilePicker();
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
            if (newId > 0 && active.getWebView() != null) {
                com.privacy.browser.bookmarks.BookmarkPreviewManager.getInstance(this).captureAndSavePreview(newId, active.getWebView());
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
                active.setUrl(bm.getUrl());
                active.getWebView().loadUrl(bm.getUrl());
                saveSession();
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
                        newTab.getWebView().loadUrl(bm.getUrl());
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
                active.setUrl(item.getUrl());
                active.getWebView().loadUrl(item.getUrl());
                saveSession();
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
                        newTab.getWebView().loadUrl(item.getUrl());
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

    private void toggleDesktopMode(TabModel tab) {
        boolean nextState = !tab.isDesktopMode();
        tab.setDesktopMode(nextState);
        if (nextState) {
            tab.getWebView().getSettings().setUserAgentString(DESKTOP_UA);
            tab.getWebView().getSettings().setUseWideViewPort(true);
            tab.getWebView().getSettings().setLoadWithOverviewMode(true);
        } else {
            PrivacyWebView.applyCleanMobileUserAgent(tab.getWebView().getSettings());
        }
        tab.getWebView().reload();
        Toast.makeText(this, nextState ? "Desktop mode enabled" : "Mobile mode restored", Toast.LENGTH_SHORT).show();
    }

    private void nukeAllData() {
        AlertDialog dialog = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(MainActivity.this))
            .setTitle("Fire / Nuke Data")
            .setMessage("Wipe all cookies, cache, web storage, history, and close all tabs?")
            .setPositiveButton("Nuke Everything", (d, which) -> {
                // Clear Cookies
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();

                // Clear Web Storage & Databases
                WebStorage.getInstance().deleteAllData();

                // Clear History Database
                if (historyDbHelper != null) {
                    historyDbHelper.clearAll();
                }

                // Clear Saved Session in SharedPreferences
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().commit();

                // Reset Tabs & Cache
                tabsManager.closeAllTabs(this);
                blockedTrackerCount = 0;
                btnShield.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_shield, 0, 0, 0);
                btnShield.setText("0");

                Toast.makeText(this, "Session incinerated. All traces cleared.", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .create();
        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    private void saveSession() {
        if (isRestoringSession || tabsManager == null) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(this::saveSession);
            return;
        }

        CookieManager.getInstance().flush();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        File sessionDir = new File(getFilesDir(), "saved_tabs");
        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }

        JSONArray array = new JSONArray();
        List<TabModel> tabs = tabsManager.getTabs();
        int activeIdx = tabsManager.getActiveIndex();
        int savedActiveIdx = 0;
        int nonIncognitoCount = 0;
        java.util.Set<String> activeStateFiles = new java.util.HashSet<>();

        for (int i = 0; i < tabs.size(); i++) {
            TabModel tab = tabs.get(i);
            if (tab == null || tab.isIncognito()) continue;

            if (i == activeIdx) {
                savedActiveIdx = nonIncognitoCount;
            }

            JSONObject obj = new JSONObject();
            try {
                String liveUrl = null;
                String liveTitle = null;
                if (tab.getWebView() != null) {
                    liveUrl = tab.getWebView().getUrl();
                    liveTitle = tab.getWebView().getTitle();
                }
                if (liveUrl == null || liveUrl.isEmpty() || "about:blank".equals(liveUrl)) {
                    liveUrl = tab.getUrl();
                }
                if (liveUrl == null || liveUrl.isEmpty() || "about:blank".equals(liveUrl)) {
                    liveUrl = getDefaultHomeUrl();
                }

                if (liveTitle == null || liveTitle.isEmpty()) {
                    liveTitle = tab.getTitle();
                }
                if (liveTitle == null || liveTitle.isEmpty()) {
                    liveTitle = "Tab";
                }

                obj.put("url", liveUrl);
                obj.put("title", liveTitle);
                obj.put("desktop", tab.isDesktopMode());

                // Save full WebView state bundle (including WebBackForwardList history)
                if (tab.getWebView() != null) {
                    Bundle stateBundle = new Bundle();
                    WebBackForwardList history = tab.getWebView().saveState(stateBundle);
                    if (history != null && history.getSize() > 0) {
                        String stateFileName = "tab_" + nonIncognitoCount + ".bin";
                        File stateFile = new File(sessionDir, stateFileName);
                        Parcel parcel = Parcel.obtain();
                        stateBundle.writeToParcel(parcel, 0);
                        byte[] bytes = parcel.marshall();
                        parcel.recycle();

                        try (FileOutputStream fos = new FileOutputStream(stateFile)) {
                            fos.write(bytes);
                            obj.put("state_file", stateFileName);
                            activeStateFiles.add(stateFileName);
                        } catch (Exception e) {
                            Log.w("MainActivity", "Failed to write tab state: " + e.getMessage());
                        }
                    }
                }
                array.put(obj);
                nonIncognitoCount++;
            } catch (JSONException ignored) {}
        }

        // Clean orphaned state files not in active session
        File[] existingFiles = sessionDir.listFiles();
        if (existingFiles != null) {
            for (File f : existingFiles) {
                if (!activeStateFiles.contains(f.getName())) {
                    f.delete();
                }
            }
        }

        editor.putString(KEY_SAVED_TABS, array.toString());
        editor.putInt(KEY_ACTIVE_TAB, savedActiveIdx);
        editor.commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String latestTheme = ThemeManager.getAppTheme(this);
        if (currentAppTheme != null && !currentAppTheme.equals(latestTheme)) {
            currentAppTheme = latestTheme;
            saveSession();
            recreate();
            return;
        }
        if (tabsManager != null && tabsManager.getTabs() != null) {
            for (TabModel tab : tabsManager.getTabs()) {
                if (tab != null && tab.getWebView() != null) {
                    ThemeManager.applyWebTheme(tab.getWebView());
                    tab.getWebView().applyPageZoom();
                }
            }
        }
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null) {
            int detectedCount = MediaSniffer.getMediaForTab(active.getId()).size();
            updateFabVideoDownload(detectedCount);

            com.privacy.browser.comic.ComicManager.ComicItem comicItem =
                    com.privacy.browser.comic.ComicManager.getDetectedComic(active.getId());
            updateFabComicDownload(comicItem != null ? comicItem.getPageCount() : 0);
        } else {
            updateFabComicDownload(0);
        }
    }

    @Override
    protected void onPause() {
        if (customVideoView != null) {
            hideFullscreenVideo();
        }
        super.onPause();
        saveSession();
    }

    private void showFullscreenVideo(View view, WebChromeClient.CustomViewCallback callback) {
        if (customVideoView != null) {
            callback.onCustomViewHidden();
            return;
        }

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

        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null) {
            int detectedCount = MediaSniffer.getMediaForTab(active.getId()).size();
            updateFabVideoDownload(detectedCount);
            com.privacy.browser.comic.ComicManager.ComicItem comicItem =
                    com.privacy.browser.comic.ComicManager.getDetectedComic(active.getId());
            updateFabComicDownload(comicItem != null ? comicItem.getPageCount() : 0);
        }

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
    }

    @Override
    protected void onStop() {
        super.onStop();
        CookieManager.getInstance().flush();
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
                    active.getId(),
                    active.getUrl(),
                    () -> active.getWebView().reload(),
                    () -> startElementZapper(),
                    () -> startElementPicker(),
                    () -> showMyFilters()
            );
        }
    }

    public void startImageSearchPicker() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null && active.getWebView() != null) {
            try {
                java.io.InputStream is = getAssets().open("image_search_picker.js");
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                is.close();
                String js = baos.toString("UTF-8");
                active.getWebView().evaluateJavascript(js, result -> {
                    Log.d("ImageSearch", "Picker script evaluated: " + result);
                });
            } catch (Exception e) {
                Log.e("ImageSearch", "Failed to load image_search_picker.js from assets", e);
                // Inline fallback if asset read fails
                String fallbackJs = "(function(){" +
                        "if(window.__imageSearchPickerActive){if(window.__cleanupImageSearchPicker)window.__cleanupImageSearchPicker(false);}" +
                        "window.__imageSearchPickerActive=true;" +
                        "var b=document.createElement('div');b.id='__isp_b';" +
                        "b.style='position:fixed;top:14px;left:16px;right:16px;z-index:2147483647;background:#1E1E2E;color:#FFF;padding:12px 16px;border-radius:12px;display:flex;justify-content:space-between;align-items:center;box-shadow:0 8px 24px rgba(0,0,0,0.6);font-size:14px;font-family:sans-serif;';" +
                        "b.innerHTML='<span>Tap any image on the page to search</span><button id=\"__isp_c\" style=\"background:#45475A;color:#CDD6F4;border:none;padding:6px 14px;border-radius:6px;font-weight:bold;\">Cancel</button>';" +
                        "(document.body||document.documentElement).appendChild(b);" +
                        "function cleanup(c){window.__imageSearchPickerActive=false;var el=document.getElementById('__isp_b');if(el)el.remove();document.removeEventListener('click',h,true);document.removeEventListener('touchend',h,true);if(c&&window.ImageSearchBridge&&window.ImageSearchBridge.onPickerCancelled)window.ImageSearchBridge.onPickerCancelled();}" +
                        "window.__cleanupImageSearchPicker=cleanup;" +
                        "document.getElementById('__isp_c').onclick=function(e){e.preventDefault();e.stopPropagation();cleanup(true);};" +
                        "function ext(node){if(!node)return null;var tag=(node.nodeName||'').toLowerCase();" +
                        "if(tag==='img'){var s=node.currentSrc||node.src||node.getAttribute('src')||node.getAttribute('data-src')||node.getAttribute('data-original')||node.getAttribute('data-url');if(s&&!s.startsWith('data:image/svg'))return s;}" +
                        "if(tag==='picture'){var ic=node.querySelector('img');if(ic)return ext(ic);}" +
                        "if(tag==='canvas'){try{return node.toDataURL('image/png');}catch(e){}}" +
                        "var bg=window.getComputedStyle(node).backgroundImage;if(bg&&bg!=='none'){var m=/url\\(['\"]?(.*?)['\"]?\\)/i.exec(bg);if(m&&m[1])return m[1];}" +
                        "var ch=node.querySelector('img, canvas');if(ch)return ext(ch);return null;}" +
                        "function h(e){if(b.contains(e.target))return;var x=e.clientX,y=e.clientY;if(e.changedTouches&&e.changedTouches.length>0){x=e.changedTouches[0].clientX;y=e.changedTouches[0].clientY;}" +
                        "var s=ext(e.target);if(!s&&document.elementsFromPoint&&typeof x==='number'){var els=document.elementsFromPoint(x,y);for(var i=0;i<els.length;i++){if(!b.contains(els[i])){s=ext(els[i]);if(s)break;}}}" +
                        "if(s){e.preventDefault();e.stopPropagation();cleanup(false);if(window.ImageSearchBridge)window.ImageSearchBridge.onImageSelected(s);}}" +
                        "document.addEventListener('click',h,true);document.addEventListener('touchend',h,true);" +
                        "})();";
                active.getWebView().evaluateJavascript(fallbackJs, null);
            }
        }
    }

    public void startImageSearchAreaCrop() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active == null || active.getWebView() == null) return;

        WebView webView = active.getWebView();
        // Wait 200ms for any dismissing menus or dialogs to completely clear from screen
        webView.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            int width = webView.getWidth();
            int height = webView.getHeight();
            if (width <= 0 || height <= 0) return;

            // Capture hardware bitmap via PixelCopy on Android O+ or fallback to decorView draw
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && getWindow() != null) {
                View decorView = getWindow().getDecorView();
                int winW = decorView.getWidth();
                int winH = decorView.getHeight();
                if (winW > 0 && winH > 0) {
                    Bitmap windowBitmap = Bitmap.createBitmap(winW, winH, Bitmap.Config.ARGB_8888);
                    try {
                        PixelCopy.request(getWindow(), null, windowBitmap, copyResult -> {
                            if (copyResult == PixelCopy.SUCCESS) {
                                int[] loc = new int[2];
                                webView.getLocationInWindow(loc);
                                int x = Math.max(0, Math.min(loc[0], winW - 1));
                                int y = Math.max(0, Math.min(loc[1], winH - 1));
                                int w = Math.max(1, Math.min(width, winW - x));
                                int h = Math.max(1, Math.min(height, winH - y));
                                Bitmap webViewSnapshot = Bitmap.createBitmap(windowBitmap, x, y, w, h);
                                windowBitmap.recycle();
                                launchCropOverlay(webViewSnapshot, active);
                            } else {
                                windowBitmap.recycle();
                                fallbackCanvasCaptureAndCrop(webView, width, height, active);
                            }
                        }, new Handler(Looper.getMainLooper()));
                        return;
                    } catch (Throwable t) {
                        windowBitmap.recycle();
                    }
                }
            }

            fallbackCanvasCaptureAndCrop(webView, width, height, active);
        }, 220);
    }

    private void fallbackCanvasCaptureAndCrop(WebView webView, int width, int height, TabModel active) {
        try {
            View decorView = getWindow().getDecorView();
            int winW = decorView.getWidth();
            int winH = decorView.getHeight();
            if (winW <= 0 || winH <= 0) return;

            Bitmap snapshot = Bitmap.createBitmap(winW, winH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(snapshot);
            decorView.draw(canvas);

            int[] loc = new int[2];
            webView.getLocationInWindow(loc);
            int x = Math.max(0, Math.min(loc[0], winW - 1));
            int y = Math.max(0, Math.min(loc[1], winH - 1));
            int w = Math.max(1, Math.min(width, winW - x));
            int h = Math.max(1, Math.min(height, winH - y));
            Bitmap webViewSnapshot = Bitmap.createBitmap(snapshot, x, y, w, h);
            snapshot.recycle();
            launchCropOverlay(webViewSnapshot, active);
        } catch (Throwable ignored) {
            Toast.makeText(this, "Failed to capture screen area", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchCropOverlay(Bitmap snapshot, TabModel active) {
        FrameLayout container = findViewById(R.id.webview_container);
        if (container == null) return;

        com.privacy.imagesearch.ui.RegionCropOverlayView cropView = new com.privacy.imagesearch.ui.RegionCropOverlayView(this);
        container.addView(cropView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cropView.setup(snapshot, new com.privacy.imagesearch.ui.RegionCropOverlayView.CropCallback() {
            @Override
            public void onAreaCropped(@NonNull Bitmap croppedBitmap) {
                Toast.makeText(MainActivity.this, "Searching captured region...", Toast.LENGTH_SHORT).show();
                com.privacy.imagesearch.uploader.ImageSearchUploader.uploadAndSearch(croppedBitmap, new com.privacy.imagesearch.uploader.ImageSearchUploader.UploadCallback() {
                    @Override
                    public void onSuccess(@NonNull String searchResultUrl) {
                        TabModel newTab = tabsManager.createTab(MainActivity.this, searchResultUrl, active.isIncognito());
                        setupClientsForTab(newTab);
                        newTab.getWebView().loadUrl(searchResultUrl);
                        tabsManager.switchTab(tabsManager.getTabCount() - 1);
                    }

                    @Override
                    public void onError(@NonNull String errorMessage) {
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onCancelled() {
                Toast.makeText(MainActivity.this, "Capture cancelled", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static final int REQUEST_CODE_PICK_IMAGE_SEARCH = 8842;

    public void startImageSearchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Image to Search"), REQUEST_CODE_PICK_IMAGE_SEARCH);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_IMAGE_SEARCH && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri imageUri = data.getData();
            try (java.io.InputStream is = getContentResolver().openInputStream(imageUri)) {
                if (is != null) {
                    Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(is);
                    if (bitmap != null) {
                        Toast.makeText(this, "Uploading selected image...", Toast.LENGTH_SHORT).show();
                        com.privacy.imagesearch.uploader.ImageSearchUploader.uploadAndSearch(bitmap, new com.privacy.imagesearch.uploader.ImageSearchUploader.UploadCallback() {
                            @Override
                            public void onSuccess(@NonNull String searchResultUrl) {
                                TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
                                boolean incognito = active != null && active.isIncognito();
                                TabModel newTab = tabsManager.createTab(MainActivity.this, searchResultUrl, incognito);
                                setupClientsForTab(newTab);
                                newTab.getWebView().loadUrl(searchResultUrl);
                                tabsManager.switchTab(tabsManager.getTabCount() - 1);
                            }

                            @Override
                            public void onError(@NonNull String errorMessage) {
                                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed to load selected image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void startElementPicker() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null) {
            Toast.makeText(this, "Tap any element to block it", Toast.LENGTH_SHORT).show();
            active.getWebView().evaluateJavascript(com.privacy.browser.adblock.picker.ElementPickerEngine.getInjectionScript(false), null);
        }
    }

    public void startElementZapper() {
        TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
        if (active != null) {
            Toast.makeText(this, "Tap any element to zap it", Toast.LENGTH_SHORT).show();
            active.getWebView().evaluateJavascript(com.privacy.browser.adblock.picker.ElementPickerEngine.getInjectionScript(true), null);
        }
    }

    public void showMyFilters() {
        com.privacy.browser.adblock.rules.MyFiltersDialog.show(this, () -> {
            TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
            if (active != null) active.getWebView().reload();
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

        if (rawUrl.startsWith("https://")) {
            textStatus.setText("Connection is Secure");
            textStatus.setTextColor(Color.parseColor("#4CAF50"));
            iconSecurity.setColorFilter(Color.parseColor("#4CAF50"));
        } else if (rawUrl.startsWith("http://")) {
            textStatus.setText("Connection is Not Secure");
            textStatus.setTextColor(Color.parseColor("#FF5252"));
            iconSecurity.setColorFilter(Color.parseColor("#FF5252"));
        } else {
            textStatus.setText("Internal Page");
            textStatus.setTextColor(Color.WHITE);
            iconSecurity.setColorFilter(Color.WHITE);
        }

        final String finalHost = host;
        view.findViewById(R.id.site_info_btn_clear_cookies).setOnClickListener(v -> {
            CookieManager cookieManager = CookieManager.getInstance();
            String cookies = cookieManager.getCookie(rawUrl);
            if (cookies != null) {
                String[] cookieList = cookies.split(";");
                for (String cookie : cookieList) {
                    String[] parts = cookie.split("=");
                    if (parts.length > 0) {
                        String name = parts[0].trim();
                        cookieManager.setCookie(rawUrl, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                    }
                }
                cookieManager.flush();
            }
            Toast.makeText(this, "Cookies cleared for " + finalHost, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        view.findViewById(R.id.site_info_btn_clear_storage).setOnClickListener(v -> {
            try {
                java.net.URI uri = new java.net.URI(rawUrl);
                String origin = uri.getScheme() + "://" + uri.getAuthority();
                WebStorage.getInstance().deleteOrigin(origin);
                Toast.makeText(this, "Cache and storage wiped for " + finalHost, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Cleared local storage", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        view.findViewById(R.id.site_info_btn_done).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    public void updateBlockedCounter(int delta) {
        blockedTrackerCount += delta;
        runOnUiThread(() -> {
            TabModel active = tabsManager != null ? tabsManager.getActiveTab() : null;
            int iconRes = (active != null && active.isIncognito()) ? R.drawable.ic_incognito : R.drawable.ic_shield;
            btnShield.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
            btnShield.setText(String.valueOf(blockedTrackerCount));
        });
    }

    private void dismissKeyboard() {
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

        if (omniboxContainer != null && omniboxContainer.getVisibility() == View.VISIBLE) {
            hideOmniboxSuggestions();
            dismissKeyboard();
            return;
        }

        if (findBar.getVisibility() == View.VISIBLE) {
            findBar.setVisibility(View.GONE);
            TabModel active = tabsManager.getActiveTab();
            if (active != null) active.getWebView().clearMatches();
            return;
        }

        TabModel active = tabsManager.getActiveTab();
        if (active != null && active.getWebView().canGoBack()) {
            active.getWebView().goBack();
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
        if (customVideoView != null) {
            hideFullscreenVideo();
        }
        saveSession();
        super.onDestroy();
    }
}

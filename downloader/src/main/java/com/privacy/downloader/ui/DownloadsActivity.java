package com.privacy.downloader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.os.Environment;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.privacy.downloader.R;
import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.db.DownloadHistory;
import com.privacy.downloader.db.DownloadHistory.Row;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.ActiveDownloads;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.provider.DownloadFileIntents;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.drawable.ColorDrawable;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.util.ArchiveExtractor;
import com.privacy.downloader.util.ChecksumVerifier;

public class DownloadsActivity extends Activity implements DownloadEngine.DownloadListener {

    private static final String PREF_SORT_ORDER = "pref_download_sort_order";
    private static final long SEARCH_DELAY_MS = 160;
    private static final Object PROGRESS_PAYLOAD = new Object();
    private int currentSortOrder;

    private DownloadEngine engine;
    private DownloadDbHelper db;
    private RecyclerView listView;
    private LinearLayoutManager layout;
    private View layoutEmptyState;
    private View loading;
    private TextView tvAggregateSpeed;
    private LinearLayout searchBarContainer;
    private EditText editSearch;
    private AlertDialog diagnosticsDialog;
    private Runnable refreshDiagnostics;

    // Selection mode members
    private View toolbarStandard;
    private View toolbarSelection;
    private View btnCloseSelection;
    private TextView textSelectedCount;
    private TextView btnSelectAll;
    private View btnShareSelected;
    private View btnDeleteSelected;
    private boolean isSelectionMode = false;
    private Set<String> selectedTaskIds = new HashSet<>();
    private boolean selectedAll;
    private boolean selectAllRequested;
    private boolean selectingAll;

    private TextView pillAll, pillActive, pillCompleted, pillVideos, pillMusic, pillDocs, pillArchives;
    private int currentFilterIndex = 0; // 0=All, 1=Active, 2=Completed, 3=Videos, 4=Music, 5=Docs, 6=Archives
    private String currentSearchQuery = "";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> new Thread(r, "DownloadHistory"));
    private final Set<String> dirtyRows = new HashSet<>();
    private DownloadHistory.Query query;
    private DownloadPages pages;
    private CancellationSignal queryOwner;
    private boolean resumed;
    private boolean resetting;
    private boolean firstPage;
    private boolean needsReset;
    private boolean wantMore;
    private boolean readFailed;
    private long itemAction;
    private final Runnable settledSearch = () -> {
        resetting = false;
        pumpHistory();
    };
    private DownloadsCardAdapter adapter;
    private ActiveDownloads active;
    private final DownloadPages.Edits pageEdits = new DownloadPages.Edits() {
        @Override public void inserted(int at, int count) { adapter.notifyItemRangeInserted(at, count); }
        @Override public void removed(int at) { adapter.notifyItemRemoved(at); }
        @Override public void moved(int from, int to) { adapter.notifyItemMoved(from, to); }
        @Override public void changed(int at) { adapter.notifyItemChanged(at); }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DownloaderThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        currentSortOrder = getSharedPreferences("download_prefs", MODE_PRIVATE).getInt(PREF_SORT_ORDER, 0);
        if (currentSortOrder < 0 || currentSortOrder >= DownloadHistory.Sort.values().length) currentSortOrder = 0;
        engine = DownloadEngine.getInstance(this);
        db = DownloadDbHelper.getInstance(this);
        active = new ActiveDownloads(false, 0, Collections.emptyList());
        pages = new DownloadPages(DownloadHistory.Sort.values()[currentSortOrder], pageEdits);

        initViews();
        setupFilterPills();
        updatePillsUI();
        engine.addListener(this);
        checkNotificationPermission();

        if (getIntent() != null && getIntent().getBooleanExtra("extra_show_settings", false)) {
            showSettingsDialog();
        }
    }

    private void checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        onActiveDownloadsChanged(engine.activeDownloads());
        if (refreshDiagnostics != null) refreshDiagnostics.run();
        engine.reconcile();
        if (query == null || readFailed || needsReset) refreshDownloadsList();
        else pumpHistory();
    }

    @Override
    protected void onPause() {
        resumed = false;
        itemAction++;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        if (diagnosticsDialog != null) diagnosticsDialog.dismiss();
        engine.removeListener(this);
        cancelRead();
        ui.removeCallbacksAndMessages(null);
        reader.shutdownNow();
        super.onDestroy();
    }

    private void initViews() {
        toolbarStandard = findViewById(R.id.downloads_toolbar);
        toolbarSelection = findViewById(R.id.downloads_selection_toolbar);
        btnCloseSelection = findViewById(R.id.downloads_btn_close_selection);
        textSelectedCount = findViewById(R.id.downloads_text_selected_count);
        btnSelectAll = findViewById(R.id.downloads_btn_select_all);
        btnShareSelected = findViewById(R.id.downloads_btn_share_selected);
        btnDeleteSelected = findViewById(R.id.downloads_btn_delete_selected);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        if (btnCloseSelection != null) {
            btnCloseSelection.setOnClickListener(v -> exitSelectionMode());
        }
        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        }
        if (btnShareSelected != null) {
            btnShareSelected.setOnClickListener(v -> shareSelectedItems());
        }
        if (btnDeleteSelected != null) {
            btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        }

        tvAggregateSpeed = findViewById(R.id.tv_aggregate_speed);
        layoutEmptyState = findViewById(R.id.layout_empty_state);
        loading = findViewById(R.id.downloads_loading);
        listView = findViewById(R.id.list_downloads);

        searchBarContainer = findViewById(R.id.search_bar_container);
        editSearch = findViewById(R.id.edit_search);

        findViewById(R.id.btn_toggle_search).setOnClickListener(v -> {
            if (searchBarContainer.getVisibility() == View.VISIBLE) {
                searchBarContainer.setVisibility(View.GONE);
                editSearch.setText("");
            } else {
                searchBarContainer.setVisibility(View.VISIBLE);
                editSearch.requestFocus();
            }
        });

        findViewById(R.id.btn_clear_search).setOnClickListener(v -> editSearch.setText(""));

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String search = s.toString().trim().toLowerCase(Locale.US);
                if (search.equals(currentSearchQuery)) return;
                currentSearchQuery = search;
                resetHistory(SEARCH_DELAY_MS);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.btn_add_download).setOnClickListener(v -> showAddUrlPrompt());
        findViewById(R.id.btn_overflow_menu).setOnClickListener(v -> showOverflowMenu());

        layout = new LinearLayoutManager(this);
        listView.setLayoutManager(layout);
        listView.setItemAnimator(null); // Byte progress should not animate the whole card or steal scroll focus.
        adapter = new DownloadsCardAdapter();
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) { requestNextPage(); }
        });
    }

    private void setupFilterPills() {
        pillAll = findViewById(R.id.pill_all);
        pillActive = findViewById(R.id.pill_active);
        pillCompleted = findViewById(R.id.pill_completed);
        pillVideos = findViewById(R.id.pill_videos);
        pillMusic = findViewById(R.id.pill_music);
        pillDocs = findViewById(R.id.pill_docs);
        pillArchives = findViewById(R.id.pill_archives);

        pillAll.setOnClickListener(v -> selectFilter(0));
        pillActive.setOnClickListener(v -> selectFilter(1));
        pillCompleted.setOnClickListener(v -> selectFilter(2));
        pillVideos.setOnClickListener(v -> selectFilter(3));
        pillMusic.setOnClickListener(v -> selectFilter(4));
        pillDocs.setOnClickListener(v -> selectFilter(5));
        pillArchives.setOnClickListener(v -> selectFilter(6));
    }

    private void selectFilter(int index) {
        currentFilterIndex = index;
        updatePillsUI();
        refreshDownloadsList();
    }

    private void updatePillsUI() {
        TextView[] pills = new TextView[]{pillAll, pillActive, pillCompleted, pillVideos, pillMusic, pillDocs, pillArchives};
        for (int i = 0; i < pills.length; i++) {
            if (i == currentFilterIndex) {
            boolean isDark = DownloaderThemeHelper.isAppDarkTheme(this);
            pills[i].setBackgroundResource(isDark ? R.drawable.filter_pill_selected : R.drawable.filter_pill_selected_light);
            pills[i].setTextColor(DownloaderThemeHelper.resolveColor(this, R.attr.textPrimary, isDark ? Color.WHITE : Color.parseColor("#111827")));
            } else {
            boolean isDarkU = DownloaderThemeHelper.isAppDarkTheme(this);
            pills[i].setBackgroundResource(isDarkU ? R.drawable.filter_pill_unselected : R.drawable.filter_pill_unselected_light);
            pills[i].setTextColor(DownloaderThemeHelper.resolveColor(this, R.attr.textSecondary, isDarkU ? Color.parseColor("#A0A0A0") : Color.parseColor("#6B7280")));
            }
        }
    }

    private void refreshDownloadsList() { resetHistory(0); }

    private void resetHistory(long delay) {
        itemAction++;
        ui.removeCallbacks(settledSearch);
        cancelRead();
        query = new DownloadHistory.Query(DownloadHistory.Filter.values()[currentFilterIndex],
                DownloadHistory.Sort.values()[currentSortOrder], currentSearchQuery);
        int old = pages.size();
        pages = new DownloadPages(query.sort, pageEdits);
        if (old != 0) adapter.notifyItemRangeRemoved(0, old);
        listView.scrollToPosition(0);
        firstPage = true;
        wantMore = needsReset = readFailed = false;
        selectAllRequested = selectingAll = selectedAll = false;
        dirtyRows.clear(); // The new read will include these commits.
        updateHistoryViews();
        resetting = delay != 0;
        if (resetting) ui.postDelayed(settledSearch, delay);
        else pumpHistory();
    }

    private void cancelRead() {
        CancellationSignal old = queryOwner;
        queryOwner = null;
        if (old != null) old.cancel();
    }

    @Override public void onDownloadRowsChanged(Set<String> ids, boolean reset) {
        if (reset || (!needsReset && dirtyRows.addAll(ids) && dirtyRows.size() > DownloadHistory.ID_BATCH)) {
            dirtyRows.clear();
            needsReset = true;
        }
        pumpHistory();
    }

    private void requestNextPage() {
        if (!resumed || needsReset || !pages.more() || layout.findLastVisibleItemPosition() < pages.size() - 8) return;
        wantMore = true;
        pumpHistory();
    }

    private void pumpHistory() {
        if (!resumed || query == null || resetting || isFinishing()) return;
        if (needsReset) {
            refreshDownloadsList();
            return;
        }
        if (queryOwner != null || readFailed) return;
        DownloadHistory.Query request = query;
        if (firstPage) {
            // Until this settles, selection controls are blocked, or Back replaces the set entirely.
            Set<String> selection = selectedTaskIds;
            readHistory(cancel -> new InitialPage(db.readPage(request, null, cancel), selection.isEmpty()
                    ? selection : db.readMatchingIds(request, selection, cancel)), result -> {
                firstPage = false;
                if (selection == selectedTaskIds) selectedTaskIds = result.selected;
                applyPage(result.page);
                finishSelectionUpdate();
            });
        } else if (!dirtyRows.isEmpty()) {
            List<String> ids = new ArrayList<>(dirtyRows);
            dirtyRows.clear();
            readHistory(cancel -> db.readChangedRows(request, ids, cancel), rows -> applyChangedRows(ids, rows));
        } else if (selectAllRequested) {
            selectAllRequested = false;
            selectingAll = true;
            Set<String> before = selectedTaskIds;
            readHistory(cancel -> db.readMatchingIds(request, null, cancel), selected -> {
                selectingAll = false;
                if (before != selectedTaskIds) return;
                selectedTaskIds = selected;
                selectedAll = !selected.isEmpty();
                finishSelectionUpdate();
                rebindSelection();
            });
        } else if (wantMore && pages.more()) {
            wantMore = false;
            Row after = pages.boundary();
            readHistory(cancel -> db.readPage(request, after, cancel), this::applyPage);
        }
    }

    private <T> void readHistory(Function<CancellationSignal, T> read, Consumer<T> deliver) {
        CancellationSignal owner = new CancellationSignal();
        queryOwner = owner;
        reader.execute(() -> {
            if (owner.isCanceled()) return;
            try {
                T result = read.apply(owner);
                ui.post(() -> {
                    if (queryOwner != owner || isFinishing() || isDestroyed()) return;
                    queryOwner = null;
                    deliver.accept(result);
                    updateHistoryViews();
                    pumpHistory();
                });
            } catch (RuntimeException failure) {
                ui.post(() -> {
                    if (queryOwner != owner || isFinishing() || isDestroyed()) return;
                    queryOwner = null;
                    readFailed = true;
                    selectingAll = selectAllRequested = false;
                    updateHistoryViews();
                    Toast.makeText(this, "Could not load downloads. Select a filter to retry.", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void applyPage(DownloadHistory.Page page) {
        Trace.beginSection("Downloads.applyPage");
        try {
            pages.append(page.rows, page.more);
        } finally {
            Trace.endSection();
        }
        listView.post(this::requestNextPage);
    }

    private void applyChangedRows(List<String> ids, List<Row> rows) {
        Trace.beginSection("Downloads.applyRows");
        try {
            Map<String, Row> byId = new HashMap<>();
            for (Row row : rows) byId.put(row.id, row);
            for (String id : ids) {
                Row match = byId.get(id);
                if (match == null) selectedTaskIds.remove(id);
                else if (!selectedTaskIds.contains(id)) selectedAll = false;
                pages.replace(id, match);
            }
            finishSelectionUpdate();
        } finally {
            Trace.endSection();
        }
        listView.post(this::requestNextPage);
    }

    private void updateHistoryViews() {
        loading.setVisibility(firstPage && !readFailed ? View.VISIBLE : View.GONE);
        layoutEmptyState.setVisibility(!firstPage && !readFailed && pages.size() == 0 ? View.VISIBLE : View.GONE);
        if (isSelectionMode) updateSelectionToolbar();
    }

    private static final class InitialPage {
        final DownloadHistory.Page page;
        final Set<String> selected;
        InitialPage(DownloadHistory.Page page, Set<String> selected) { this.page = page; this.selected = selected; }
    }

    private void rebindSelection() {
        if (pages.size() != 0) adapter.notifyItemRangeChanged(0, pages.size());
    }

    private void enterSelectionMode(String initialTaskId) {
        if (pages.position(initialTaskId) < 0) return;
        isSelectionMode = true;
        selectedAll = false;
        selectedTaskIds = new HashSet<>(Collections.singleton(initialTaskId));
        updateSelectionToolbar();
        rebindSelection();
    }

    private void exitSelectionMode() {
        if (!isSelectionMode) return;
        isSelectionMode = selectedAll = selectAllRequested = selectingAll = false;
        selectedTaskIds = new HashSet<>();
        toolbarSelection.setVisibility(View.GONE);
        toolbarStandard.setVisibility(View.VISIBLE);
        rebindSelection();
    }

    private boolean selectionBusy() { return firstPage || selectingAll || selectAllRequested; }

    private void toggleItemSelection(String taskId) {
        if (selectionBusy()) return;
        if (!selectedTaskIds.remove(taskId)) selectedTaskIds.add(taskId);
        selectedAll = false;
        finishSelectionUpdate();
        int position = pages.position(taskId);
        if (isSelectionMode && position >= 0) adapter.notifyItemChanged(position);
    }

    private void finishSelectionUpdate() {
        if (!isSelectionMode) return;
        if (selectedTaskIds.isEmpty()) exitSelectionMode();
        else updateSelectionToolbar();
    }

    private boolean allSelected() {
        return selectedAll || (!pages.more() && pages.size() != 0 && selectedTaskIds.size() == pages.size());
    }

    private void toggleSelectAll() {
        if (selectionBusy() || readFailed) return;
        if (allSelected()) exitSelectionMode();
        else {
            selectAllRequested = true;
            updateSelectionToolbar();
            pumpHistory();
        }
    }

    private void updateSelectionToolbar() {
        toolbarStandard.setVisibility(View.GONE);
        toolbarSelection.setVisibility(View.VISIBLE);
        textSelectedCount.setText(selectedTaskIds.size() + " selected");
        btnSelectAll.setText(allSelected() ? "Deselect" : "Select All");
        btnSelectAll.setEnabled(!selectionBusy() && !readFailed);
        btnShareSelected.setEnabled(!selectionBusy());
        btnDeleteSelected.setEnabled(!selectionBusy());
    }

    private void confirmDeleteSelected() {
        if (selectionBusy() || selectedTaskIds.isEmpty()) return;
        List<String> selection = new ArrayList<>(selectedTaskIds);
        int count = selection.size();
        new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this))
                .setTitle("Delete Downloads")
                .setMessage("Are you sure you want to delete " + count + " selected " + (count == 1 ? "download" : "downloads") + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    engine.deleteSelected(selection, removed -> Toast.makeText(this,
                            "Removed " + removed + " of " + count + " downloads from queue", Toast.LENGTH_SHORT).show());
                    exitSelectionMode();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareSelectedItems() {
        if (selectionBusy() || selectedTaskIds.isEmpty()) return;
        List<String> selection = new ArrayList<>(selectedTaskIds);
        runFileAction(() -> {
            Set<String> completed = db.readMatchingIds(new DownloadHistory.Query(DownloadHistory.Filter.COMPLETED,
                    DownloadHistory.Sort.NEWEST, ""), selection, null);
            selection.retainAll(completed);
            return DownloadFileIntents.share(this, selection);
        }, "Cannot share files");
    }

    private void runFileAction(Callable<Intent> action, String failureMessage) {
        long request = ++itemAction;
        reader.execute(() -> {
            try {
                Intent intent = action.call();
                ui.post(() -> {
                    if (!canDeliverAction(request)) return;
                    try { startActivity(intent); } catch (RuntimeException e) { fileActionError(failureMessage, e); }
                });
            } catch (Exception e) {
                ui.post(() -> { if (canDeliverAction(request)) fileActionError(failureMessage, e); });
            }
        });
    }

    private boolean canDeliverAction(long request) {
        return request == itemAction && resumed && !isFinishing() && !isDestroyed();
    }

    private void fileActionError(String message, Exception e) {
        Toast.makeText(this, message + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
    }

    private void onCurrentItem(String id, Consumer<DownloadItem> action) {
        long request = ++itemAction;
        engine.readDownload(id, false, item -> {
            if (!canDeliverAction(request)) return;
            if (item != null) action.accept(item);
            else Toast.makeText(this, "Download no longer available", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }



    private void applyDialogTheme(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showAddUrlPrompt() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_url, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        EditText input = view.findViewById(R.id.add_url_edit_text);
        TextView btnPaste = view.findViewById(R.id.add_url_btn_paste);
        Button btnCancel = view.findViewById(R.id.add_url_btn_cancel);
        Button btnContinue = view.findViewById(R.id.add_url_btn_continue);

        btnPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (text != null) {
                    input.setText(text.toString().trim());
                }
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        View btnSwitchBatch = view.findViewById(R.id.add_url_btn_switch_batch);
        if (btnSwitchBatch != null) {
            btnSwitchBatch.setOnClickListener(v -> {
                dialog.dismiss();
                showBatchImportDialog();
            });
        }

        btnContinue.setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"))) {
                dialog.dismiss();
                DownloadPromptDialog.show(DownloadsActivity.this, url, RequestContext.anonymous(), null, null, -1);
            } else {
                Toast.makeText(DownloadsActivity.this, "Please enter a valid HTTP/HTTPS URL", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.show();
        applyDialogTheme(dialog);
    }

    private void showBatchImportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_batch_import, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        EditText editUrls = view.findViewById(R.id.batch_edit_urls);
        TextView btnPaste = view.findViewById(R.id.batch_btn_paste);
        TextView btnClear = view.findViewById(R.id.batch_btn_clear);
        TextView textCountBadge = view.findViewById(R.id.batch_text_count_badge);

        TextView pill1 = view.findViewById(R.id.batch_pill_thread_1);
        TextView pill2 = view.findViewById(R.id.batch_pill_thread_2);
        TextView pill4 = view.findViewById(R.id.batch_pill_thread_4);
        TextView pill8 = view.findViewById(R.id.batch_pill_thread_8);

        CheckBox checkWifiOnly = view.findViewById(R.id.batch_check_wifi_only);
        CheckBox checkSubfolders = view.findViewById(R.id.batch_check_subfolders);

        Button btnCancel = view.findViewById(R.id.batch_btn_cancel);
        Button btnEnqueue = view.findViewById(R.id.batch_btn_enqueue);

        checkWifiOnly.setChecked(false);
        checkSubfolders.setChecked(engine.isCategorySubfoldersEnabled());

        final int[] selectedThreads = new int[]{4};
        TextView[] pills = new TextView[]{pill1, pill2, pill4, pill8};
        int[] vals = new int[]{1, 2, 4, 8};

        Runnable updatePills = () -> {
            for (int i = 0; i < pills.length; i++) {
                if (vals[i] == selectedThreads[0]) {
                    pills[i].setBackgroundResource(R.drawable.bg_pill_thread_selected);
                    pills[i].setTextColor(Color.WHITE);
                } else {
                    pills[i].setBackgroundResource(R.drawable.bg_pill_thread_unselected);
                    pills[i].setTextColor(Color.parseColor("#A0A0B0"));
                }
            }
        };

        for (int i = 0; i < pills.length; i++) {
            final int threads = vals[i];
            pills[i].setOnClickListener(v -> {
                selectedThreads[0] = threads;
                updatePills.run();
            });
        }
        updatePills.run();

        final List<String> extractedUrls = new ArrayList<>();
        Runnable parseUrls = () -> {
            extractedUrls.clear();
            String text = editUrls.getText().toString();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://[^\\s\"',;<>]+");
            java.util.regex.Matcher m = p.matcher(text);
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            while (m.find()) {
                String u = m.group();
                if (u.length() > 8) {
                    seen.add(u);
                }
            }
            extractedUrls.addAll(seen);
            int count = extractedUrls.size();
            textCountBadge.setText(count + " unique valid links detected");
            textCountBadge.setTextColor(count > 0 ? Color.parseColor("#4CAF50") : Color.parseColor("#8E8EA8"));
            btnEnqueue.setText("Queue All (" + count + ")");
            btnEnqueue.setEnabled(count > 0);
            btnEnqueue.setAlpha(count > 0 ? 1.0f : 0.5f);
        };

        editUrls.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                parseUrls.run();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (text != null) {
                    String existing = editUrls.getText().toString().trim();
                    if (existing.isEmpty()) {
                        editUrls.setText(text);
                    } else {
                        editUrls.setText(existing + "\n" + text);
                    }
                }
            }
        });

        btnClear.setOnClickListener(v -> editUrls.setText(""));

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnEnqueue.setOnClickListener(v -> {
            if (extractedUrls.isEmpty()) return;
            File baseDir = DownloadPromptDialog.getSafeDownloadsDir(this);
            boolean useSubfolders = checkSubfolders.isChecked();
            boolean wifiOnly = checkWifiOnly.isChecked();

            int queued = 0;
            String queueError = null;
            for (String rawUrl : extractedUrls) {
                String fileName = "download_" + System.currentTimeMillis() + "_" + queued;
                try {
                    String path = new java.net.URL(rawUrl).getPath();
                    int slash = path.lastIndexOf('/');
                    if (slash != -1 && slash < path.length() - 1) {
                        fileName = path.substring(slash + 1);
                    }
                } catch (Exception ignored) {}

                File destDir = com.privacy.downloader.util.CategoryOrganizer.getDestinationDirectory(baseDir, fileName, useSubfolders);
                File destFile = new File(destDir, fileName);

                DownloadItem item = new DownloadItem();
                item.setId(java.util.UUID.randomUUID().toString());
                item.setUrl(rawUrl);
                item.setOriginalUrl(rawUrl);
                item.setFileName(fileName);
                item.setDestinationPath(destFile.getAbsolutePath());
                item.setNumSegments(selectedThreads[0]);
                item.setWifiOnly(wifiOnly);
                item.setStatus(DownloadStatus.PENDING);

                try {
                    engine.enqueue(item);
                    queued++;
                } catch (IOException e) {
                    if (queueError == null) queueError = e.getMessage();
                }
            }

            String result = "Queued " + queued + " of " + extractedUrls.size() + " downloads";
            Toast.makeText(this, queueError == null ? result : result + ". " + queueError, Toast.LENGTH_LONG).show();
            if (queued == 0) return;
            dialog.dismiss();
        });

        parseUrls.run();
        dialog.show();
        applyDialogTheme(dialog);
    }

    private void showUpdateLinkDialog(DownloadItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_update_link, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView textFilename = view.findViewById(R.id.update_link_text_filename);
        EditText editUrl = view.findViewById(R.id.update_link_edit_url);
        TextView btnPaste = view.findViewById(R.id.update_link_btn_paste);
        Button btnCancel = view.findViewById(R.id.update_link_btn_cancel);
        Button btnUpdate = view.findViewById(R.id.update_link_btn_update);

        textFilename.setText(item.getFileName());
        editUrl.setText(item.getUrl());
        editUrl.selectAll();

        btnPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (text != null) {
                    editUrl.setText(text.toString().trim());
                }
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnUpdate.setOnClickListener(v -> {
            String newUrl = editUrl.getText().toString().trim();
            if (!TextUtils.isEmpty(newUrl) && (newUrl.startsWith("http://") || newUrl.startsWith("https://"))) {
                btnUpdate.setEnabled(false);
                engine.updateUrl(item.getId(), newUrl, updated -> {
                    btnUpdate.setEnabled(true);
                    if (updated) {
                        Toast.makeText(this, "Link updated. Retrying download...", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else {
                        Toast.makeText(this, "Link not updated. Stop the transfer first and use the same type of link.", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                Toast.makeText(DownloadsActivity.this, "Invalid URL", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.show();
        applyDialogTheme(dialog);
    }

    private void showOverflowMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_overflow_options, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        view.findViewById(R.id.menu_option_pause_all).setOnClickListener(v -> {
            engine.pauseAll();
            dialog.dismiss();
        });

        view.findViewById(R.id.menu_option_resume_all).setOnClickListener(v -> {
            engine.resumeAll();
            dialog.dismiss();
        });

        view.findViewById(R.id.menu_option_clear_completed).setOnClickListener(v -> {
            engine.clearCompleted();
            dialog.dismiss();
        });

        View optBatch = view.findViewById(R.id.menu_option_batch_import);
        if (optBatch != null) {
            optBatch.setOnClickListener(v -> {
                dialog.dismiss();
                showBatchImportDialog();
            });
        }

        View optSort = view.findViewById(R.id.menu_option_sort);
        if (optSort != null) {
            optSort.setOnClickListener(v -> {
                dialog.dismiss();
                showSortDialog();
            });
        }

        view.findViewById(R.id.menu_option_settings).setOnClickListener(v -> {
            dialog.dismiss();
            showSettingsDialog();
        });

        view.findViewById(R.id.menu_btn_close).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        applyDialogTheme(dialog);
    }

    public void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_download_settings, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        EditText editConcurrency = view.findViewById(R.id.settings_edit_concurrency);
        EditText editSpeed = view.findViewById(R.id.settings_edit_speed);
        EditText editRetries = view.findViewById(R.id.settings_edit_retries);
        CheckBox checkWifiOnly = view.findViewById(R.id.settings_check_wifi_only);
        CheckBox checkBatterySaver = view.findViewById(R.id.settings_check_battery_saver);
        CheckBox checkCategoryFolders = view.findViewById(R.id.settings_check_category_folders);
        CheckBox checkSchedule = view.findViewById(R.id.settings_check_schedule);
        EditText editScheduleStart = view.findViewById(R.id.settings_edit_schedule_start);
        EditText editScheduleEnd = view.findViewById(R.id.settings_edit_schedule_end);
        CheckBox checkAutoExtract = view.findViewById(R.id.settings_check_auto_extract);

        editConcurrency.setText(String.valueOf(engine.getMaxConcurrentDownloads()));
        long currentSpeedKb = engine.getGlobalSpeedLimit() > 0 ? engine.getGlobalSpeedLimit() / 1024 : 0;
        editSpeed.setText(String.valueOf(currentSpeedKb));
        if (editRetries != null) {
            editRetries.setText(String.valueOf(engine.getMaxRetries()));
        }

        checkWifiOnly.setChecked(engine.isWifiOnlyGlobal());
        checkBatterySaver.setChecked(engine.isBatterySaverEnabled());
        checkCategoryFolders.setChecked(engine.isCategorySubfoldersEnabled());

        checkSchedule.setChecked(engine.isScheduleEnabled());
        editScheduleStart.setText(String.format(Locale.US, "%02d:%02d", engine.getScheduleStartHour(), engine.getScheduleStartMinute()));
        editScheduleEnd.setText(String.format(Locale.US, "%02d:%02d", engine.getScheduleEndHour(), engine.getScheduleEndMinute()));

        final int[] startH = new int[]{engine.getScheduleStartHour()};
        final int[] startM = new int[]{engine.getScheduleStartMinute()};
        final int[] endH = new int[]{engine.getScheduleEndHour()};
        final int[] endM = new int[]{engine.getScheduleEndMinute()};

        editScheduleStart.setFocusable(false);
        editScheduleStart.setClickable(true);
        editScheduleStart.setOnClickListener(v -> {
            new android.app.TimePickerDialog(this, DownloaderThemeHelper.getDialogTheme(this), (tp, h, m) -> {
                startH[0] = h;
                startM[0] = m;
                editScheduleStart.setText(String.format(Locale.US, "%02d:%02d", h, m));
            }, startH[0], startM[0], true).show();
        });

        editScheduleEnd.setFocusable(false);
        editScheduleEnd.setClickable(true);
        editScheduleEnd.setOnClickListener(v -> {
            new android.app.TimePickerDialog(this, DownloaderThemeHelper.getDialogTheme(this), (tp, h, m) -> {
                endH[0] = h;
                endM[0] = m;
                editScheduleEnd.setText(String.format(Locale.US, "%02d:%02d", h, m));
            }, endH[0], endM[0], true).show();
        });

        SharedPreferences prefs = getSharedPreferences("downloader_settings", Context.MODE_PRIVATE);
        if (checkAutoExtract != null) {
            checkAutoExtract.setChecked(prefs.getBoolean(DownloadEngine.KEY_AUTO_EXTRACT_ZIP, false));
        }

        // Default Download Folder Setup
        View btnChangeDefaultDir = view.findViewById(R.id.settings_btn_change_default_dir);
        TextView textDefaultFolderPath = view.findViewById(R.id.settings_text_default_folder_path);
        TextView textDefaultFreeSpace = view.findViewById(R.id.settings_text_default_free_space);
        final File[] selectedDefaultDir = new File[1];
        String savedDefaultDir = engine.getDefaultDownloadDirectory();
        if (savedDefaultDir != null && new File(savedDefaultDir).exists()) {
            selectedDefaultDir[0] = new File(savedDefaultDir);
        } else {
            selectedDefaultDir[0] = DownloadPromptDialog.getSafeDownloadsDir(this);
        }
        if (textDefaultFolderPath != null && textDefaultFreeSpace != null) {
            DownloadPromptDialog.updateFolderDisplay(selectedDefaultDir[0], textDefaultFolderPath, textDefaultFreeSpace);
        }

        final Runnable autoSave = () -> {
            try {
                int slots = engine.getMaxConcurrentDownloads();
                try {
                    slots = Integer.parseInt(editConcurrency.getText().toString().trim());
                } catch (NumberFormatException ignored) {}
                // Apply the schedule window and constraint toggles as one settings snapshot.
                engine.setQueueConstraints(checkWifiOnly.isChecked(), checkBatterySaver.isChecked(), slots,
                        checkSchedule.isChecked(), startH[0], startM[0], endH[0], endM[0]);

                // 2. Speed Limit
                try {
                    long speedKb = Long.parseLong(editSpeed.getText().toString().trim());
                    engine.setGlobalSpeedLimit(Math.multiplyExact(Math.max(0L, speedKb), 1024L));
                } catch (Exception ignored) {}

                // 2b. Retry Attempts
                if (editRetries != null) {
                    try {
                        int retries = Integer.parseInt(editRetries.getText().toString().trim());
                        if (retries > 0) {
                            engine.setMaxRetries(retries);
                        }
                    } catch (Exception ignored) {}
                }

                engine.setCategorySubfoldersEnabled(checkCategoryFolders.isChecked());
                if (selectedDefaultDir[0] != null) engine.setDefaultDownloadDirectory(selectedDefaultDir[0].getAbsolutePath());
                if (checkAutoExtract != null) {
                    prefs.edit().putBoolean(DownloadEngine.KEY_AUTO_EXTRACT_ZIP, checkAutoExtract.isChecked()).apply();
                }
            } catch (Exception ignored) {}
        };

        // Wire immediate auto-save on every toggle
        checkWifiOnly.setOnCheckedChangeListener((b, isChecked) -> autoSave.run());
        checkBatterySaver.setOnCheckedChangeListener((b, isChecked) -> autoSave.run());
        checkCategoryFolders.setOnCheckedChangeListener((b, isChecked) -> autoSave.run());
        checkSchedule.setOnCheckedChangeListener((b, isChecked) -> autoSave.run());
        if (checkAutoExtract != null) {
            checkAutoExtract.setOnCheckedChangeListener((b, isChecked) -> autoSave.run());
        }

        editConcurrency.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { autoSave.run(); }
        });

        editSpeed.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { autoSave.run(); }
        });

        if (editRetries != null) {
            editRetries.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                public void afterTextChanged(Editable s) { autoSave.run(); }
            });
        }

        if (btnChangeDefaultDir != null) {
            btnChangeDefaultDir.setOnClickListener(v -> {
                DownloadPromptDialog.showFolderChooserDialog(this, selectedDefaultDir, textDefaultFolderPath, textDefaultFreeSpace);
                autoSave.run();
            });
        }

        view.findViewById(R.id.settings_preset_tasks_1).setOnClickListener(v -> { editConcurrency.setText("1"); autoSave.run(); });
        view.findViewById(R.id.settings_preset_tasks_2).setOnClickListener(v -> { editConcurrency.setText("2"); autoSave.run(); });
        view.findViewById(R.id.settings_preset_tasks_3).setOnClickListener(v -> { editConcurrency.setText("3"); autoSave.run(); });
        view.findViewById(R.id.settings_preset_tasks_5).setOnClickListener(v -> { editConcurrency.setText("5"); autoSave.run(); });
        view.findViewById(R.id.settings_preset_tasks_8).setOnClickListener(v -> { editConcurrency.setText("8"); autoSave.run(); });

        view.findViewById(R.id.settings_preset_unlimited).setOnClickListener(v -> { editSpeed.setText("0"); autoSave.run(); });
        view.findViewById(R.id.settings_preset_1mb).setOnClickListener(v -> { editSpeed.setText("1024"); autoSave.run(); });
        view.findViewById(R.id.settings_preset_5mb).setOnClickListener(v -> { editSpeed.setText("5120"); autoSave.run(); });
        view.findViewById(R.id.settings_preset_20mb).setOnClickListener(v -> { editSpeed.setText("20480"); autoSave.run(); });

        View presetR3 = view.findViewById(R.id.settings_preset_retries_3);
        View presetR5 = view.findViewById(R.id.settings_preset_retries_5);
        View presetR8 = view.findViewById(R.id.settings_preset_retries_8);
        View presetR12 = view.findViewById(R.id.settings_preset_retries_12);
        if (presetR3 != null) presetR3.setOnClickListener(v -> { if (editRetries != null) editRetries.setText("3"); autoSave.run(); });
        if (presetR5 != null) presetR5.setOnClickListener(v -> { if (editRetries != null) editRetries.setText("5"); autoSave.run(); });
        if (presetR8 != null) presetR8.setOnClickListener(v -> { if (editRetries != null) editRetries.setText("8"); autoSave.run(); });
        if (presetR12 != null) presetR12.setOnClickListener(v -> { if (editRetries != null) editRetries.setText("12"); autoSave.run(); });

        View btnHeaderClose = view.findViewById(R.id.settings_btn_header_close);
        if (btnHeaderClose != null) {
            btnHeaderClose.setOnClickListener(v -> {
                autoSave.run();
                dialog.dismiss();
            });
        }

        Button btnCancel = view.findViewById(R.id.settings_btn_cancel);
        Button btnSave = view.findViewById(R.id.settings_btn_save);

        btnCancel.setOnClickListener(v -> {
            autoSave.run();
            dialog.dismiss();
        });

        btnSave.setOnClickListener(v -> {
            autoSave.run();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> autoSave.run());

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.85);
            dialog.getWindow().setLayout(width, maxHeight);
        }
    }

    private void showSortDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_sort_options, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView optDateDesc = view.findViewById(R.id.sort_by_date_desc);
        TextView optDateAsc = view.findViewById(R.id.sort_by_date_asc);
        TextView optSizeDesc = view.findViewById(R.id.sort_by_size_desc);
        TextView optSizeAsc = view.findViewById(R.id.sort_by_size_asc);
        TextView optNameAsc = view.findViewById(R.id.sort_by_name_asc);
        TextView optNameDesc = view.findViewById(R.id.sort_by_name_desc);

        TextView[] views = new TextView[]{optDateDesc, optDateAsc, optSizeDesc, optSizeAsc, optNameAsc, optNameDesc};
        for (int i = 0; i < views.length; i++) {
            if (i == currentSortOrder) {
                views[i].setTextColor(DownloaderThemeHelper.resolveColor(this, R.attr.accentColor, Color.parseColor("#00E5FF")));
                views[i].setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                views[i].setTextColor(DownloaderThemeHelper.resolveColor(this, R.attr.textPrimary, Color.parseColor("#111827")));
                views[i].setTypeface(null, android.graphics.Typeface.NORMAL);
            }
            final int sortIndex = i;
            views[i].setOnClickListener(v -> {
                currentSortOrder = sortIndex;
                getSharedPreferences("download_prefs", MODE_PRIVATE).edit().putInt(PREF_SORT_ORDER, currentSortOrder).apply();
                refreshDownloadsList();
                dialog.dismiss();
            });
        }

        dialog.show();
        applyDialogTheme(dialog);
    }

    private void showTaskDiagnosticsDialog(String taskId) {
        itemAction++;
        if (diagnosticsDialog != null) diagnosticsDialog.dismiss();

        AlertDialog.Builder builder = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_task_diagnostics, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView textStatusBadge = view.findViewById(R.id.diag_text_status_badge);
        TextView textFileName = view.findViewById(R.id.diag_text_file_name);
        TextView textStreamMode = view.findViewById(R.id.diag_text_stream_mode);
        View layoutPhase = view.findViewById(R.id.diag_layout_phase);
        TextView textCurrentPhase = view.findViewById(R.id.diag_text_current_phase);
        TextView textSpeedEta = view.findViewById(R.id.diag_text_speed_eta);
        TextView textTotalProgress = view.findViewById(R.id.diag_text_total_progress);
        TextView textRangeSupport = view.findViewById(R.id.diag_text_range_support);

        View cardStreamTracks = view.findViewById(R.id.diag_card_stream_tracks);
        TextView textVideoTrackInfo = view.findViewById(R.id.diag_text_video_track_info);
        TextView textVideoUrl = view.findViewById(R.id.diag_text_video_url);
        View layoutAudioTrack = view.findViewById(R.id.diag_layout_audio_track);
        TextView textAudioTrackInfo = view.findViewById(R.id.diag_text_audio_track_info);
        TextView textAudioUrl = view.findViewById(R.id.diag_text_audio_url);

        TextView textDestinationPath = view.findViewById(R.id.diag_text_destination_path);
        View labelSegments = view.findViewById(R.id.diag_label_segments);
        LinearLayout containerSegments = view.findViewById(R.id.diag_container_segments);
        Button btnClose = view.findViewById(R.id.diag_btn_close);

        btnClose.setOnClickListener(v -> dialog.dismiss());

        Consumer<DownloadItem> updateUI = liveItem -> {
            if (liveItem == null) {
                Toast.makeText(this, "Download no longer available", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                return;
            }

            textFileName.setText(liveItem.getFileName());
            if (textDestinationPath != null) {
                textDestinationPath.setText(liveItem.getDestinationPath());
            }

            DownloadStatus status = liveItem.getStatus();
            if (status == DownloadStatus.COMPLETED) {
                textStatusBadge.setText("COMPLETED");
                textStatusBadge.setTextColor(Color.parseColor("#4CAF50"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_completed);
            } else if (status.isActive()) {
                textStatusBadge.setText(status == DownloadStatus.PENDING ? "QUEUED" : status.name());
                textStatusBadge.setTextColor(Color.parseColor("#BB86FC"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
            } else if (status == DownloadStatus.PAUSED) {
                textStatusBadge.setText("PAUSED");
                textStatusBadge.setTextColor(Color.parseColor("#FFB74D"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_paused);
            } else if (status.isWaiting()) {
                textStatusBadge.setText(waitingBadge(status));
                textStatusBadge.setTextColor(Color.parseColor("#00E5FF"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
            } else {
                textStatusBadge.setText(status == DownloadStatus.CANCELLED ? "CANCELLED" : "FAILED");
                textStatusBadge.setTextColor(Color.parseColor("#FF5252"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_failed);
            }

            // Determine Transfer Mode
            boolean hasAudioTrack = liveItem.getAudioUrl() != null && !liveItem.getAudioUrl().trim().isEmpty();
            if (textStreamMode != null) {
                if (hasAudioTrack) {
                    textStreamMode.setText("Dual-Stream Mux (Video + Audio)");
                } else if (liveItem.getUrl() != null && (liveItem.getUrl().contains(".m3u8") || liveItem.getUrl().contains("hls"))) {
                    textStreamMode.setText("HLS Adaptive Stream (.m3u8)");
                } else if (liveItem.getNumSegments() > 1 && liveItem.isSupportsRange()) {
                    textStreamMode.setText("Multi-Threaded (" + liveItem.getNumSegments() + " Parallel Chunks)");
                } else {
                    textStreamMode.setText("Single Connection Direct Stream");
                }
            }

            // Determine Active Phase
            if (textCurrentPhase != null) {
                if (status == DownloadStatus.COMPLETED) {
                    textCurrentPhase.setText("Download & muxing complete");
                    textCurrentPhase.setTextColor(Color.parseColor("#4CAF50"));
                } else if (status == DownloadStatus.FAILED) {
                    textCurrentPhase.setText(liveItem.getErrorMessage() != null ? liveItem.getErrorMessage() : "Download error");
                    textCurrentPhase.setTextColor(Color.parseColor("#FF5252"));
                } else if (status == DownloadStatus.PAUSED) {
                    textCurrentPhase.setText("Paused by user");
                    textCurrentPhase.setTextColor(Color.parseColor("#FFB74D"));
                } else {
                    String msg = liveItem.getErrorMessage();
                    if (msg != null && !msg.trim().isEmpty()) {
                        textCurrentPhase.setText(msg);
                    } else if (hasAudioTrack) {
                        textCurrentPhase.setText("Downloading streams...");
                    } else {
                        textCurrentPhase.setText("Transfer in progress");
                    }
                    textCurrentPhase.setTextColor(Color.parseColor("#BB86FC"));
                }
            }

            if (liveItem.isSupportsRange()) {
                textRangeSupport.setText("Supported (HTTP 206 Multi-threading)");
                textRangeSupport.setTextColor(Color.parseColor("#4CAF50"));
            } else {
                textRangeSupport.setText("Single Connection (Range not supported)");
                textRangeSupport.setTextColor(Color.parseColor("#FFB74D"));
            }

            textSpeedEta.setText(liveItem.getFormattedSpeed() + " • ETA: " + liveItem.getFormattedEta());
            textTotalProgress.setText(liveItem.getFormattedDownloadedSize() + " / " + liveItem.getFormattedTotalSize()
                    + " (" + liveItem.getProgressPercentage() + "%)");

            // Populate Track Breakdown Card
            if (cardStreamTracks != null) {
                if (hasAudioTrack) {
                    cardStreamTracks.setVisibility(View.VISIBLE);
                    long audioBytes = liveItem.getAudioBytes();
                    long totalBytes = liveItem.getTotalBytes();
                    long videoBytes = (totalBytes > audioBytes && audioBytes > 0) ? (totalBytes - audioBytes) : -1;

                    if (textVideoTrackInfo != null) {
                        textVideoTrackInfo.setText(videoBytes > 0 ? DownloadItem.formatBytes(videoBytes) : "Video Track");
                    }
                    if (textVideoUrl != null) {
                        textVideoUrl.setText(liveItem.getUrl());
                    }
                    if (layoutAudioTrack != null) {
                        layoutAudioTrack.setVisibility(View.VISIBLE);
                    }
                    if (textAudioTrackInfo != null) {
                        textAudioTrackInfo.setText(audioBytes > 0 ? DownloadItem.formatBytes(audioBytes) : "Audio Track");
                    }
                    if (textAudioUrl != null) {
                        textAudioUrl.setText(liveItem.getAudioUrl());
                    }
                } else {
                    cardStreamTracks.setVisibility(View.GONE);
                }
            }

            List<DownloadSegment> segments = liveItem.getSegments();
            containerSegments.removeAllViews();

            if (segments == null || segments.isEmpty()) {
                if (labelSegments != null) labelSegments.setVisibility(View.GONE);
                TextView noSeg = new TextView(this);
                noSeg.setText(hasAudioTrack ? "Sequential audio/video direct connection." : "Single stream connection in progress.");
                noSeg.setTextColor(Color.parseColor("#707088"));
                noSeg.setTextSize(11f);
                noSeg.setGravity(android.view.Gravity.CENTER);
                noSeg.setPadding(0, 16, 0, 16);
                containerSegments.addView(noSeg);
            } else {
                if (labelSegments != null) labelSegments.setVisibility(View.VISIBLE);
                LayoutInflater inflater = LayoutInflater.from(this);
                for (DownloadSegment seg : segments) {
                    View segView = inflater.inflate(R.layout.item_segment_chunk, containerSegments, false);
                    TextView segTitle = segView.findViewById(R.id.seg_text_title);
                    TextView segBadge = segView.findViewById(R.id.seg_text_badge);
                    TextView segRange = segView.findViewById(R.id.seg_text_range);
                    ProgressBar segBar = segView.findViewById(R.id.seg_progress_bar);
                    TextView segTransferred = segView.findViewById(R.id.seg_text_transferred);
                    TextView segPercent = segView.findViewById(R.id.seg_text_percent);

                    segTitle.setText("Segment #" + (seg.getIndex() + 1) + " (Thread " + (seg.getIndex() + 1) + ")");

                    long segTotal = seg.getTotalBytes();
                    long segDownloaded = seg.getDownloadedBytes();
                    int percent = (segTotal > 0) ? (int) ((segDownloaded * 100) / segTotal) : 0;
                    if (percent > 100) percent = 100;

                    if (seg.isComplete() || (status == DownloadStatus.COMPLETED)) {
                        segBadge.setText("COMPLETED");
                        segBadge.setTextColor(Color.parseColor("#4CAF50"));
                        segBadge.setBackgroundResource(R.drawable.bg_badge_completed);
                        segBar.setProgress(100);
                        segBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")));
                        percent = 100;
                    } else if (status.isActive()) {
                        segBadge.setText("ACTIVE");
                        segBadge.setTextColor(Color.parseColor("#BB86FC"));
                        segBadge.setBackgroundResource(R.drawable.bg_badge_running);
                        segBar.setProgress(percent);
                        segBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#BB86FC")));
                    } else {
                        segBadge.setText("PENDING");
                        segBadge.setTextColor(Color.parseColor("#8E8EA8"));
                        segBadge.setBackgroundResource(R.drawable.bg_pill_thread_unselected);
                        segBar.setProgress(percent);
                        segBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#8E8EA8")));
                    }

                    segRange.setText("Byte Range: " + seg.getStartByte() + " - " + (seg.getEndByte() > 0 ? seg.getEndByte() : "End")
                            + " (" + DownloadItem.formatBytes(segTotal) + ")");
                    segTransferred.setText(DownloadItem.formatBytes(segDownloaded) + " / " + DownloadItem.formatBytes(segTotal));
                    segPercent.setText(percent + "%");

                    containerSegments.addView(segView);
                }
            }
        };

        class Diagnostics implements DownloadEngine.DownloadListener {
            boolean pending;
            boolean dirty;
            ActiveDownloads.Transfer last = active.find(taskId);

            void refresh() {
                if (!dialog.isShowing() || !resumed) return;
                if (pending) {
                    dirty = true;
                    return;
                }
                pending = true;
                engine.readDownload(taskId, true, detail -> {
                    pending = false;
                    if (!dialog.isShowing() || isDestroyed()) return;
                    updateUI.accept(detail);
                    if (dirty) {
                        dirty = false;
                        refresh();
                    }
                });
            }

            @Override public void onActiveDownloadsChanged(ActiveDownloads snapshot) {
                ActiveDownloads.Transfer next = snapshot.find(taskId);
                if (Objects.equals(last, next)) return;
                last = next;
                refresh();
            }

            @Override public void onDownloadRowsChanged(Set<String> ids, boolean reset) {
                if (reset || ids.contains(taskId)) refresh();
            }
        }
        Diagnostics diagnostics = new Diagnostics();
        dialog.setOnDismissListener(d -> {
            engine.removeListener(diagnostics);
            if (diagnosticsDialog == dialog) {
                diagnosticsDialog = null;
                refreshDiagnostics = null;
            }
        });
        diagnosticsDialog = dialog;
        refreshDiagnostics = diagnostics::refresh;
        textStatusBadge.setText("Loading…");
        dialog.show();
        engine.addListener(diagnostics);
        diagnostics.refresh();
        applyDialogTheme(dialog);
        if (dialog.getWindow() != null) {
            int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.85);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, maxHeight);
        }
    }

    private void showChecksumDialog(DownloadItem item) {
        if (item.getDestinationPath() == null) return;
        File file = new File(item.getDestinationPath());
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_checksum_verifier, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView textFilename = view.findViewById(R.id.checksum_text_filename);
        TextView textComputed = view.findViewById(R.id.checksum_text_computed_hash);
        ImageView btnCopy = view.findViewById(R.id.checksum_btn_copy);
        EditText editExpected = view.findViewById(R.id.checksum_edit_expected);
        TextView textStatusBadge = view.findViewById(R.id.checksum_text_status_badge);
        Button btnClose = view.findViewById(R.id.checksum_btn_close);

        textFilename.setText(item.getFileName());

        final String[] computedHash = new String[]{""};

        btnCopy.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(computedHash[0])) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("SHA-256", computedHash[0]);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Hash copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        applyDialogTheme(dialog);

        ChecksumVerifier.computeSha256Async(file, new ChecksumVerifier.ChecksumCallback() {
            @Override
            public void onCalculated(String sha256Hex) {
                computedHash[0] = sha256Hex;
                textComputed.setText(sha256Hex);

                editExpected.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        String expected = s.toString().trim();
                        if (expected.isEmpty()) {
                            textStatusBadge.setText("Enter expected hash above to verify match");
                            textStatusBadge.setTextColor(Color.parseColor("#8E8EA8"));
                            textStatusBadge.setBackgroundResource(R.drawable.bg_pill_thread_unselected);
                        } else if (expected.equalsIgnoreCase(sha256Hex)) {
                            textStatusBadge.setText("Integrity Verified: SHA-256 Hash Match");
                            textStatusBadge.setTextColor(Color.parseColor("#4CAF50"));
                            textStatusBadge.setBackgroundResource(R.drawable.bg_badge_completed);
                        } else {
                            textStatusBadge.setText("Checksum Mismatch: File Corrupted or Modified");
                            textStatusBadge.setTextColor(Color.parseColor("#FF5252"));
                            textStatusBadge.setBackgroundResource(R.drawable.bg_badge_failed);
                        }
                    }

                    @Override
                    public void afterTextChanged(Editable s) {}
                });
            }

            @Override
            public void onError(String message) {
                textComputed.setText("Error computing hash: " + message);
                textComputed.setTextColor(Color.parseColor("#FF5252"));
                textStatusBadge.setText("Hash computation failed");
                textStatusBadge.setTextColor(Color.parseColor("#FF5252"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_failed);
            }
        });
    }

    private void extractZip(DownloadItem item) {
        if (item.getDestinationPath() == null) return;
        File zipFile = new File(item.getDestinationPath());
        if (!zipFile.exists()) {
            Toast.makeText(this, "ZIP file not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String folderName = item.getFileName().replaceAll("(?i)\\.zip$", "");
        File destDir = new File(zipFile.getParentFile(), folderName);

        AlertDialog progressDialog = new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this))
                .setTitle("Extracting Archive")
                .setMessage("Extracting to " + folderName + "...")
                .setCancelable(false)
                .show();

        ArchiveExtractor.extractZipAsync(zipFile, destDir, new ArchiveExtractor.ExtractionCallback() {
            @Override
            public void onProgress(String currentFile, int extractedCount) {
                runOnUiThread(() -> progressDialog.setMessage("Extracting: " + currentFile + " (" + extractedCount + " files)"));
            }

            @Override
            public void onCompleted(File outputDir, int totalFiles) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(DownloadsActivity.this, "Extracted " + totalFiles + " files to " + outputDir.getName(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(DownloadsActivity.this, "Extraction failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private boolean isVideo(String name) { return DownloadHistory.Filter.VIDEOS.hasExtension(name); }
    private boolean isAudio(String name) { return DownloadHistory.Filter.MUSIC.hasExtension(name); }
    private boolean isDocument(String name) { return DownloadHistory.Filter.DOCS.hasExtension(name); }
    private boolean isArchive(String name) { return DownloadHistory.Filter.ARCHIVES.hasExtension(name); }

    private int getFileIconRes(String name) {
        if (isVideo(name)) return R.drawable.ic_file_video;
        if (isAudio(name)) return R.drawable.ic_file_audio;
        if (isArchive(name)) return R.drawable.ic_file_archive;
        if (isDocument(name)) return R.drawable.ic_file_doc;
        return R.drawable.ic_file_generic;
    }

    @Override public void onActiveDownloadsChanged(ActiveDownloads snapshot) {
        if (!resumed) return;
        Trace.beginSection("Downloads.applyProgress");
        try {
            ActiveDownloads previous = active;
            active = snapshot;
            if (snapshot.speed > 0) {
                tvAggregateSpeed.setVisibility(View.VISIBLE);
                if (previous.speed != snapshot.speed || tvAggregateSpeed.length() == 0) {
                    tvAggregateSpeed.setText("Speed: " + DownloadItem.formatSpeed(snapshot.speed));
                }
            } else tvAggregateSpeed.setVisibility(View.GONE);
            for (ActiveDownloads.Transfer old : previous.transfers) {
                ActiveDownloads.Transfer replacement = snapshot.find(old.id);
                if (replacement == null || !Objects.equals(old.token, replacement.token)) rebindProgress(old.id, old.token);
            }
            for (ActiveDownloads.Transfer next : snapshot.transfers) {
                if (!next.equals(previous.find(next.id))) rebindProgress(next.id, next.token);
            }
        } finally {
            Trace.endSection();
        }
    }

    private void rebindProgress(String id, String token) {
        int position = pages.position(id);
        if (position < 0) return;
        Row row = pages.get(position);
        if (row.status.isTransferring() && Objects.equals(token, row.token)) {
            adapter.notifyItemChanged(position, PROGRESS_PAYLOAD);
        }
    }

    private static String waitingBadge(DownloadStatus status) {
        switch (status) {
            case WAITING_FOR_WIFI: return "WAITING FOR WI-FI";
            case WAITING_FOR_BATTERY: return "WAITING FOR BATTERY";
            case WAITING_FOR_SCHEDULE: return "SCHEDULED";
            default: return "WAITING";
        }
    }

    private String formatCardErrorMessage(String rawError) {
        if (rawError == null || rawError.trim().isEmpty()) {
            return "Download error";
        }
        String lower = rawError.toLowerCase(Locale.US);
        if (lower.startsWith("required audio track failed")) {
            return "Required audio track failed";
        } else if (lower.contains("403")) {
            return "HTTP 403: Forbidden (Access Denied)";
        } else if (lower.contains("404")) {
            return "HTTP 404: File Not Found";
        } else if (lower.contains("incomplete transfer")) {
            return "Incomplete transfer (Connection lost)";
        } else if (lower.contains("stream muxing failed") || lower.contains("remux")) {
            return "Stream muxing failed";
        } else if (lower.contains("timeout") || lower.contains("timed out")) {
            return "Connection timed out";
        } else if (lower.contains("unknownhost") || lower.contains("unable to resolve host")) {
            return "No Internet / Host unreachable";
        } else if (lower.contains("space")) {
            return "Insufficient disk storage";
        }
        if (rawError.contains("http://") || rawError.contains("https://")) {
            int httpIdx = rawError.indexOf("http");
            String prefix = rawError.substring(0, httpIdx).trim();
            if (!prefix.isEmpty()) {
                return prefix;
            }
        }
        if (rawError.length() > 50) {
            return rawError.substring(0, 47) + "...";
        }
        return rawError;
    }

    private static final class CardHolder extends RecyclerView.ViewHolder {
        final View cardRoot;
        final FrameLayout iconContainer;
        final ImageView iconFile;
        final CheckBox checkBox;
        final TextView textFilename;
        final TextView textStatusBadge;
        final ProgressBar progressBar;
        final TextView textStats;
        final TextView textEta;
        final View actionsContainer;
        final ImageView btnInfo;
        final ImageView btnLink;
        final ImageView btnPauseResume;
        final ImageView btnDelete;
        final TextView btnChecksum;
        final TextView btnExtract;

        CardHolder(View view) {
            super(view);
            cardRoot = view.findViewById(R.id.card_root);
            iconContainer = view.findViewById(R.id.card_icon_container);
            iconFile = view.findViewById(R.id.card_icon_file);
            checkBox = view.findViewById(R.id.card_checkbox);
            textFilename = view.findViewById(R.id.card_text_filename);
            textStatusBadge = view.findViewById(R.id.card_text_status_badge);
            progressBar = view.findViewById(R.id.card_progress_bar);
            textStats = view.findViewById(R.id.card_text_stats);
            textEta = view.findViewById(R.id.card_text_eta);
            actionsContainer = view.findViewById(R.id.card_actions_container);
            btnInfo = view.findViewById(R.id.card_btn_info);
            btnLink = view.findViewById(R.id.card_btn_link);
            btnPauseResume = view.findViewById(R.id.card_btn_pause_resume);
            btnDelete = view.findViewById(R.id.card_btn_delete);
            btnChecksum = view.findViewById(R.id.card_btn_checksum);
            btnExtract = view.findViewById(R.id.card_btn_extract);
        }
    }

    private final class DownloadsCardAdapter extends RecyclerView.Adapter<CardHolder> {
        @Override public int getItemCount() { return pages.size(); }

        @NonNull @Override public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_card, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull CardHolder holder, int position, @NonNull List<Object> payloads) {
            Row row = pages.get(position);
            if (!payloads.isEmpty() && row.status.isTransferring()) {
                ActiveDownloads.Transfer transfer = liveTransfer(row);
                bindTransfer(holder, transfer == null ? row.status : transfer.status,
                        transfer == null ? row.downloaded : transfer.downloadedBytes,
                        transfer == null ? row.total : transfer.totalBytes, transfer == null ? 0 : transfer.speed,
                        transfer == null ? row.threads : transfer.threads);
            } else onBindViewHolder(holder, position);
        }

        private ActiveDownloads.Transfer liveTransfer(Row row) {
            ActiveDownloads.Transfer transfer = row.status.isTransferring() ? active.find(row.id) : null;
            return transfer != null && Objects.equals(row.token, transfer.token) ? transfer : null;
        }

        @Override public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            Row row = pages.get(position);
            String taskId = row.id;
            ActiveDownloads.Transfer transfer = liveTransfer(row);
            DownloadStatus status = transfer == null ? row.status : transfer.status;
            long total = transfer == null ? row.total : transfer.totalBytes;
            long downloaded = transfer == null ? row.downloaded : transfer.downloadedBytes;
            long speed = transfer == null ? 0 : transfer.speed;
            int threads = transfer == null ? row.threads : transfer.threads;
            int progress = DownloadItem.progressPercentage(downloaded, total);
            boolean isSelected = selectedTaskIds.contains(taskId);

            holder.iconFile.setImageResource(getFileIconRes(row.name));
            holder.textFilename.setText(row.name);

            // Reset ETA color to default cyan
            holder.textEta.setTextColor(Color.parseColor("#00E5FF"));

            // Configure status & badge
            if (status == DownloadStatus.COMPLETED) {
                holder.textStatusBadge.setText("COMPLETED");
                holder.textStatusBadge.setTextColor(Color.parseColor("#4CAF50"));
                holder.textStatusBadge.setBackgroundResource(R.drawable.bg_badge_completed);
                holder.progressBar.setProgress(100);
                holder.progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")));
                holder.textStats.setText(DownloadItem.formatBytes(total) + " • Verified");
                holder.textEta.setText("Done");
                holder.textEta.setTextColor(Color.parseColor("#4CAF50"));

                holder.btnPauseResume.setVisibility(View.GONE);
                holder.btnLink.setVisibility(View.GONE);

                if (holder.btnChecksum != null) {
                    holder.btnChecksum.setVisibility(View.VISIBLE);
                    holder.btnChecksum.setOnClickListener(v -> onCurrentItem(taskId, DownloadsActivity.this::showChecksumDialog));
                }
                if (holder.btnExtract != null) {
                    if (isArchive(row.name) && row.name.toLowerCase(Locale.US).endsWith(".zip")) {
                        holder.btnExtract.setVisibility(View.VISIBLE);
                        holder.btnExtract.setOnClickListener(v -> onCurrentItem(taskId, DownloadsActivity.this::extractZip));
                    } else {
                        holder.btnExtract.setVisibility(View.GONE);
                    }
                }
            } else {
                if (holder.btnChecksum != null) holder.btnChecksum.setVisibility(View.GONE);
                if (holder.btnExtract != null) holder.btnExtract.setVisibility(View.GONE);
            }

            if (status.isActive()) {
                bindTransfer(holder, status, downloaded, total, speed, threads);
                holder.btnPauseResume.setVisibility(View.VISIBLE);
                holder.btnPauseResume.setImageResource(R.drawable.ic_pause);
                holder.btnPauseResume.setOnClickListener(v -> engine.pause(taskId));
                holder.btnLink.setVisibility(View.GONE);
            } else if (status == DownloadStatus.PAUSED) {
                holder.textStatusBadge.setText("PAUSED");
                holder.textStatusBadge.setTextColor(Color.parseColor("#FFB74D")); // Amber
                holder.textStatusBadge.setBackgroundResource(R.drawable.bg_badge_paused);
                holder.progressBar.setProgress(progress);
                holder.progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB74D")));
                holder.textStats.setText(DownloadItem.formatBytes(downloaded) + " / " + DownloadItem.formatBytes(total) + " (" + progress + "%)");
                holder.textEta.setText("Paused");
                holder.textEta.setTextColor(Color.parseColor("#FFB74D"));
                holder.btnPauseResume.setVisibility(View.VISIBLE);
                holder.btnPauseResume.setImageResource(R.drawable.ic_play);
                holder.btnPauseResume.setOnClickListener(v -> engine.resume(taskId));
                holder.btnLink.setVisibility(View.VISIBLE);
            } else if (status.isWaiting()) {
                holder.textStatusBadge.setText(waitingBadge(status));
                holder.textStatusBadge.setTextColor(Color.parseColor("#00E5FF"));
                holder.textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
                holder.progressBar.setProgress(progress);
                holder.progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
                holder.textStats.setText(row.reason == null ? "Waiting for download conditions" : row.reason);
                holder.textEta.setText("Auto-resume");
                holder.btnPauseResume.setVisibility(View.VISIBLE);
                holder.btnPauseResume.setImageResource(R.drawable.ic_pause);
                holder.btnPauseResume.setOnClickListener(v -> engine.pause(taskId));
                holder.btnLink.setVisibility(View.VISIBLE);
            } else if (status == DownloadStatus.CANCELLED) {
                holder.textStatusBadge.setText("CANCELLED");
                holder.textStatusBadge.setTextColor(Color.parseColor("#FFB74D"));
                holder.textStatusBadge.setBackgroundResource(R.drawable.bg_badge_paused);
                holder.progressBar.setProgress(progress);
                holder.progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB74D")));
                holder.textStats.setText("Cancelled");
                holder.textEta.setText("");
                holder.btnPauseResume.setVisibility(View.GONE);
                holder.btnLink.setVisibility(View.GONE);
            } else if (status == DownloadStatus.FAILED) {
                holder.textStatusBadge.setText("FAILED");
                holder.textStatusBadge.setTextColor(Color.parseColor("#FF5252")); // Red
                holder.textStatusBadge.setBackgroundResource(R.drawable.bg_badge_failed);
                holder.progressBar.setProgress(progress);
                holder.progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")));
                holder.textStats.setText(formatCardErrorMessage(row.reason));
                holder.textEta.setText("Error");
                holder.textEta.setTextColor(Color.parseColor("#FF5252"));
                holder.btnPauseResume.setVisibility(View.VISIBLE);
                holder.btnPauseResume.setImageResource(R.drawable.ic_play);
                holder.btnPauseResume.setOnClickListener(v -> onCurrentItem(taskId, item -> DownloadRecoveryDialog.retry(DownloadsActivity.this, engine, item)));
                holder.btnLink.setVisibility(View.VISIBLE);
            }

            holder.btnInfo.setOnClickListener(v -> showTaskDiagnosticsDialog(taskId));
            holder.btnLink.setOnClickListener(v -> onCurrentItem(taskId, DownloadsActivity.this::showUpdateLinkDialog));
            holder.btnDelete.setOnClickListener(v -> {
                engine.delete(taskId, true, removed -> Toast.makeText(DownloadsActivity.this,
                        removed ? "Download removed from queue" : "Could not remove download", Toast.LENGTH_SHORT).show());
            });

            boolean isDark = DownloaderThemeHelper.isAppDarkTheme(DownloadsActivity.this);

            if (isSelectionMode) {
                holder.iconContainer.setVisibility(View.GONE);
                holder.checkBox.setVisibility(View.VISIBLE);
                holder.checkBox.setChecked(isSelected);
                holder.actionsContainer.setVisibility(View.GONE);

                holder.cardRoot.setBackgroundResource(isSelected
                        ? (isDark ? R.drawable.bg_history_selected_row : R.drawable.bg_history_selected_row_light)
                        : (isDark ? R.drawable.card_download_bg : R.drawable.card_download_bg_light));

                holder.itemView.setOnClickListener(v -> toggleItemSelection(taskId));
                holder.itemView.setOnLongClickListener(v -> {
                    toggleItemSelection(taskId);
                    return true;
                });
            } else {
                holder.iconContainer.setVisibility(View.VISIBLE);
                holder.checkBox.setVisibility(View.GONE);
                holder.actionsContainer.setVisibility(View.VISIBLE);

                holder.cardRoot.setBackgroundResource(isDark ? R.drawable.card_download_bg : R.drawable.card_download_bg_light);

                // Tap card to open completed file or inspect diagnostics
                holder.itemView.setOnClickListener(v -> {
                    if (status == DownloadStatus.COMPLETED) {
                        openFile(taskId);
                    } else {
                        showTaskDiagnosticsDialog(taskId);
                    }
                });

                // Long press opens dedicated actions menu
                holder.itemView.setOnLongClickListener(v -> {
                    onCurrentItem(taskId, this::showDownloadItemContextMenu);
                    return true;
                });
            }

        }

        private void bindTransfer(CardHolder holder, DownloadStatus status, long downloaded, long total, long speed, int threads) {
            int progress = DownloadItem.progressPercentage(downloaded, total);
            holder.textStatusBadge.setText(status == DownloadStatus.PENDING ? "QUEUED" : status == DownloadStatus.CONNECTING
                    ? "CONNECTING" : (threads > 1 ? "RUNNING • " + threads + " THREADS" : "RUNNING"));
            holder.textStatusBadge.setTextColor(Color.parseColor("#BB86FC"));
            holder.textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
            holder.progressBar.setProgress(progress);
            holder.progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#BB86FC")));
            holder.textStats.setText(DownloadItem.formatBytes(downloaded) + " / " + DownloadItem.formatBytes(total)
                    + " (" + progress + "%) • " + DownloadItem.formatSpeed(speed));
            holder.textEta.setText(status == DownloadStatus.PENDING ? "Queued" : "ETA: " + DownloadItem.formatEta(downloaded, total, speed));
        }

        private void openFile(String id) {
            runFileAction(() -> DownloadFileIntents.view(DownloadsActivity.this, id), "Cannot open file");
        }

        private void shareFile(DownloadItem item) {
            runFileAction(() -> DownloadFileIntents.share(DownloadsActivity.this, Collections.singletonList(item.getId())),
                    "Cannot share file");
        }

        private void showDownloadItemContextMenu(DownloadItem item) {
            AlertDialog.Builder builder = new AlertDialog.Builder(DownloadsActivity.this, DownloaderThemeHelper.getDialogTheme(DownloadsActivity.this));
            View view = LayoutInflater.from(DownloadsActivity.this).inflate(R.layout.dialog_download_actions, null);
            builder.setView(view);

            AlertDialog dialog = builder.create();
            applyDialogTheme(dialog);

            ImageView headerIcon = view.findViewById(R.id.ctx_header_icon);
            TextView headerTitle = view.findViewById(R.id.ctx_header_title);
            TextView headerSubtitle = view.findViewById(R.id.ctx_header_subtitle);
            LinearLayout actionsContainer = view.findViewById(R.id.ctx_actions_container);

            if (headerIcon != null) {
                headerIcon.setImageResource(getFileIconRes(item.getFileName()));
            }
            if (headerTitle != null) {
                headerTitle.setText(item.getFileName());
            }
            if (headerSubtitle != null) {
                headerSubtitle.setText(item.getFormattedTotalSize() + " • " + item.getStatus().name());
            }

            final boolean isCompleted = item.getStatus() == DownloadStatus.COMPLETED;

            // 1. Select option (enters selection mode)
            addMenuAction(actionsContainer, "Select", R.drawable.ic_check_circle, () -> {
                dialog.dismiss();
                enterSelectionMode(item.getId());
            });

            // 2. Open File (if completed)
            if (isCompleted) {
                addMenuAction(actionsContainer, "Open File", R.drawable.ic_play, () -> {
                    dialog.dismiss();
                    openFile(item.getId());
                });
            }

            // 3. Share File (if completed)
            if (isCompleted) {
                addMenuAction(actionsContainer, "Share File", R.drawable.ic_share, () -> {
                    dialog.dismiss();
                    shareFile(item);
                });
            }

            // 4. File Diagnostics & Details
            addMenuAction(actionsContainer, "File Details & Diagnostics", R.drawable.ic_info, () -> {
                dialog.dismiss();
                showTaskDiagnosticsDialog(item.getId());
            });

            if ((item.getStatus() == DownloadStatus.PAUSED || item.getStatus() == DownloadStatus.FAILED)
                    && DownloadEngine.canStartOver(item)) {
                addMenuAction(actionsContainer, "Retry or start over", R.drawable.ic_play, () -> {
                    dialog.dismiss();
                    DownloadRecoveryDialog.retry(DownloadsActivity.this, engine, item);
                });
            }

            // 5. Copy Download Link
            if (item.getUrl() != null && !item.getUrl().isEmpty()) {
                addMenuAction(actionsContainer, "Copy Download Link", R.drawable.ic_link, () -> {
                    dialog.dismiss();
                    ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("URL", item.getUrl()));
                        Toast.makeText(DownloadsActivity.this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            // 6. Checksum (if completed)
            if (isCompleted) {
                addMenuAction(actionsContainer, "Verify SHA-256 Hash", R.drawable.ic_check, () -> {
                    dialog.dismiss();
                    showChecksumDialog(item);
                });
            }

            // 7. Extract (if zip)
            if (isCompleted && isArchive(item.getFileName()) && item.getFileName().toLowerCase(Locale.US).endsWith(".zip")) {
                addMenuAction(actionsContainer, "Extract Archive", R.drawable.ic_file_archive, () -> {
                    dialog.dismiss();
                    extractZip(item);
                });
            }

            // 8. Delete Download
            addMenuAction(actionsContainer, "Delete Download", R.drawable.ic_delete, () -> {
                dialog.dismiss();
                engine.delete(item.getId(), true, removed -> Toast.makeText(DownloadsActivity.this,
                        removed ? "Download removed from queue" : "Could not remove download", Toast.LENGTH_SHORT).show());
            });

            dialog.show();
        }

        private void addMenuAction(LinearLayout container, String title, int iconRes, Runnable onClick) {
            View row = LayoutInflater.from(DownloadsActivity.this).inflate(R.layout.item_download_action_row, container, false);
            ImageView icon = row.findViewById(R.id.action_item_icon);
            TextView text = row.findViewById(R.id.action_item_text);

            icon.setImageResource(iconRes);
            text.setText(title);

            if (iconRes == R.drawable.ic_delete) {
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")));
                text.setTextColor(Color.parseColor("#FF5252"));
            }

            row.setOnClickListener(v -> onClick.run());
            container.addView(row);
        }
    }
}

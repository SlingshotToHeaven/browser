package com.privacy.downloader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.privacy.downloader.R;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static final int SORT_DATE_DESC = 0;
    private static final int SORT_DATE_ASC = 1;
    private static final int SORT_SIZE_DESC = 2;
    private static final int SORT_SIZE_ASC = 3;
    private static final int SORT_NAME_ASC = 4;
    private static final int SORT_NAME_DESC = 5;

    private int currentSortOrder = SORT_DATE_DESC;

    private DownloadEngine engine;
    private ListView listView;
    private View layoutEmptyState;
    private TextView tvAggregateSpeed;
    private LinearLayout searchBarContainer;
    private EditText editSearch;

    // Selection mode members
    private View toolbarStandard;
    private View toolbarSelection;
    private View btnCloseSelection;
    private TextView textSelectedCount;
    private TextView btnSelectAll;
    private View btnShareSelected;
    private View btnDeleteSelected;
    private boolean isSelectionMode = false;
    private final Set<String> selectedTaskIds = new HashSet<>();

    private TextView pillAll, pillActive, pillCompleted, pillVideos, pillMusic, pillDocs, pillArchives;
    private int currentFilterIndex = 0; // 0=All, 1=Active, 2=Completed, 3=Videos, 4=Music, 5=Docs, 6=Archives
    private String currentSearchQuery = "";

    private final List<DownloadItem> filteredItems = new ArrayList<>();
    private DownloadsCardAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DownloaderThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        currentSortOrder = getSharedPreferences("download_prefs", MODE_PRIVATE).getInt(PREF_SORT_ORDER, SORT_DATE_DESC);
        engine = DownloadEngine.getInstance(this);

        initViews();
        setupFilterPills();
        updatePillsUI();
        refreshDownloadsList();
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
        engine.addListener(this);
        refreshDownloadsList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        engine.removeListener(this);
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
        listView = findViewById(R.id.list_downloads);

        searchBarContainer = findViewById(R.id.search_bar_container);
        editSearch = findViewById(R.id.edit_search);

        findViewById(R.id.btn_toggle_search).setOnClickListener(v -> {
            if (searchBarContainer.getVisibility() == View.VISIBLE) {
                searchBarContainer.setVisibility(View.GONE);
                editSearch.setText("");
                currentSearchQuery = "";
                refreshDownloadsList();
            } else {
                searchBarContainer.setVisibility(View.VISIBLE);
                editSearch.requestFocus();
            }
        });

        findViewById(R.id.btn_clear_search).setOnClickListener(v -> {
            editSearch.setText("");
            currentSearchQuery = "";
            refreshDownloadsList();
        });

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s.toString().trim().toLowerCase(Locale.US);
                refreshDownloadsList();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.btn_add_download).setOnClickListener(v -> showAddUrlPrompt());
        findViewById(R.id.btn_overflow_menu).setOnClickListener(v -> showOverflowMenu());

        adapter = new DownloadsCardAdapter();
        listView.setAdapter(adapter);
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

    private void refreshDownloadsList() {
        List<DownloadItem> all = engine.getAllDownloads();
        filteredItems.clear();

        long totalSpeed = 0;
        for (DownloadItem item : all) {
            if (item.getStatus().isActive()) {
                totalSpeed += item.getSpeedBytesPerSec();
            }

            // Search filter
            if (!currentSearchQuery.isEmpty()) {
                String name = item.getFileName() != null ? item.getFileName().toLowerCase(Locale.US) : "";
                if (!name.contains(currentSearchQuery)) {
                    continue;
                }
            }

            // Category filter
            if (currentFilterIndex == 0) {
                filteredItems.add(item);
            } else if (currentFilterIndex == 1 && item.getStatus().isActive()) {
                filteredItems.add(item);
            } else if (currentFilterIndex == 2 && item.getStatus() == DownloadStatus.COMPLETED) {
                filteredItems.add(item);
            } else if (currentFilterIndex == 3 && isVideo(item.getFileName())) {
                filteredItems.add(item);
            } else if (currentFilterIndex == 4 && isAudio(item.getFileName())) {
                filteredItems.add(item);
            } else if (currentFilterIndex == 5 && isDocument(item.getFileName())) {
                filteredItems.add(item);
            } else if (currentFilterIndex == 6 && isArchive(item.getFileName())) {
                filteredItems.add(item);
            }
        }

        Collections.sort(filteredItems, (a, b) -> {
            switch (currentSortOrder) {
                case SORT_DATE_ASC:
                    return Long.compare(a.getCreatedTime(), b.getCreatedTime());
                case SORT_SIZE_DESC: {
                    long sizeA = a.getTotalBytes() > 0 ? a.getTotalBytes() : a.getDownloadedBytes();
                    long sizeB = b.getTotalBytes() > 0 ? b.getTotalBytes() : b.getDownloadedBytes();
                    return Long.compare(sizeB, sizeA);
                }
                case SORT_SIZE_ASC: {
                    long sizeA = a.getTotalBytes() > 0 ? a.getTotalBytes() : a.getDownloadedBytes();
                    long sizeB = b.getTotalBytes() > 0 ? b.getTotalBytes() : b.getDownloadedBytes();
                    return Long.compare(sizeA, sizeB);
                }
                case SORT_NAME_ASC: {
                    String nameA = a.getFileName() != null ? a.getFileName() : "";
                    String nameB = b.getFileName() != null ? b.getFileName() : "";
                    return nameA.compareToIgnoreCase(nameB);
                }
                case SORT_NAME_DESC: {
                    String nameA = a.getFileName() != null ? a.getFileName() : "";
                    String nameB = b.getFileName() != null ? b.getFileName() : "";
                    return nameB.compareToIgnoreCase(nameA);
                }
                case SORT_DATE_DESC:
                default:
                    return Long.compare(b.getCreatedTime(), a.getCreatedTime());
            }
        });

        if (tvAggregateSpeed != null) {
            if (totalSpeed > 0) {
                tvAggregateSpeed.setVisibility(View.VISIBLE);
                tvAggregateSpeed.setText("Speed: " + DownloadItem.formatBytes(totalSpeed) + "/s");
            } else {
                tvAggregateSpeed.setVisibility(View.GONE);
            }
        }

        if (layoutEmptyState != null) {
            layoutEmptyState.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
        }

        if (isSelectionMode) {
            // Prune any deleted items
            Set<String> validIds = new HashSet<>();
            for (DownloadItem di : filteredItems) {
                validIds.add(di.getId());
            }
            selectedTaskIds.retainAll(validIds);
            if (selectedTaskIds.isEmpty()) {
                exitSelectionMode();
            } else {
                updateSelectionToolbar();
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void enterSelectionMode(String initialTaskId) {
        isSelectionMode = true;
        selectedTaskIds.clear();
        if (initialTaskId != null) {
            selectedTaskIds.add(initialTaskId);
        }
        updateSelectionToolbar();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        selectedTaskIds.clear();
        if (toolbarSelection != null) toolbarSelection.setVisibility(View.GONE);
        if (toolbarStandard != null) toolbarStandard.setVisibility(View.VISIBLE);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void toggleItemSelection(String taskId) {
        if (selectedTaskIds.contains(taskId)) {
            selectedTaskIds.remove(taskId);
        } else {
            selectedTaskIds.add(taskId);
        }
        if (selectedTaskIds.isEmpty()) {
            exitSelectionMode();
        } else {
            updateSelectionToolbar();
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    private void toggleSelectAll() {
        if (selectedTaskIds.size() == filteredItems.size()) {
            selectedTaskIds.clear();
            exitSelectionMode();
        } else {
            selectedTaskIds.clear();
            for (DownloadItem item : filteredItems) {
                selectedTaskIds.add(item.getId());
            }
            updateSelectionToolbar();
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    private void updateSelectionToolbar() {
        if (toolbarStandard != null) toolbarStandard.setVisibility(View.GONE);
        if (toolbarSelection != null) toolbarSelection.setVisibility(View.VISIBLE);

        int count = selectedTaskIds.size();
        if (textSelectedCount != null) {
            textSelectedCount.setText(count + " selected");
        }
        if (btnSelectAll != null) {
            btnSelectAll.setText(count == filteredItems.size() && !filteredItems.isEmpty() ? "Deselect" : "Select All");
        }
    }

    private void confirmDeleteSelected() {
        if (selectedTaskIds.isEmpty()) return;
        final int count = selectedTaskIds.size();

        new AlertDialog.Builder(this, DownloaderThemeHelper.getDialogTheme(this))
                .setTitle("Delete Downloads")
                .setMessage("Are you sure you want to delete " + count + " selected " + (count == 1 ? "download" : "downloads") + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    for (String id : new ArrayList<>(selectedTaskIds)) {
                        engine.delete(id, true);
                    }
                    Toast.makeText(this, count + " downloads deleted", Toast.LENGTH_SHORT).show();
                    exitSelectionMode();
                    refreshDownloadsList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareSelectedItems() {
        if (selectedTaskIds.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        for (DownloadItem item : filteredItems) {
            if (selectedTaskIds.contains(item.getId()) && item.getStatus() == DownloadStatus.COMPLETED) {
                File f = new File(item.getDestinationPath());
                if (f.exists()) {
                    uris.add(Uri.fromFile(f));
                }
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No completed files available to share", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (uris.size() == 1) {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("*/*");
                share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                startActivity(Intent.createChooser(share, "Share file"));
            } else {
                Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
                share.setType("*/*");
                share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                startActivity(Intent.createChooser(share, "Share " + uris.size() + " files"));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Share error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
                DownloadPromptDialog.show(DownloadsActivity.this, url, null, null, null, null, null, null);
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

        checkWifiOnly.setChecked(engine.isWifiOnlyGlobal());
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
                if (!destDir.exists()) destDir.mkdirs();

                File destFile = new File(destDir, fileName);
                int count = 1;
                while (destFile.exists()) {
                    int dot = fileName.lastIndexOf('.');
                    String base = (dot != -1) ? fileName.substring(0, dot) : fileName;
                    String ext = (dot != -1) ? fileName.substring(dot) : "";
                    destFile = new File(destDir, base + "_" + count + ext);
                    count++;
                }

                DownloadItem item = new DownloadItem();
                item.setId(java.util.UUID.randomUUID().toString());
                item.setUrl(rawUrl);
                item.setOriginalUrl(rawUrl);
                item.setFileName(destFile.getName());
                item.setDestinationPath(destFile.getAbsolutePath());
                item.setTempPath(destFile.getAbsolutePath() + ".part");
                item.setNumSegments(selectedThreads[0]);
                item.setUserAgent(com.privacy.downloader.engine.DownloadTask.DEFAULT_USER_AGENT);
                item.setWifiOnly(wifiOnly);
                item.setStatus(DownloadStatus.PENDING);

                engine.enqueue(item);
                queued++;
            }

            Toast.makeText(this, "Queued " + queued + " batch downloads", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            refreshDownloadsList();
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
                engine.updateUrl(item.getId(), newUrl);
                Toast.makeText(DownloadsActivity.this, "Link updated! Resuming transfer...", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
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
            Toast.makeText(this, "All downloads paused", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        view.findViewById(R.id.menu_option_resume_all).setOnClickListener(v -> {
            engine.resumeAll();
            Toast.makeText(this, "Resuming pending downloads", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        view.findViewById(R.id.menu_option_clear_completed).setOnClickListener(v -> {
            engine.clearCompleted();
            Toast.makeText(this, "Cleared completed downloads from history", Toast.LENGTH_SHORT).show();
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
                // 1. Concurrency
                try {
                    int maxTasks = Integer.parseInt(editConcurrency.getText().toString().trim());
                    if (maxTasks > 0) {
                        engine.setMaxConcurrentDownloads(maxTasks);
                    }
                } catch (Exception ignored) {}

                // 2. Speed Limit
                try {
                    long speedKb = Long.parseLong(editSpeed.getText().toString().trim());
                    engine.setGlobalSpeedLimit(speedKb > 0 ? speedKb * 1024 : 0);
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

                // 3. Toggles
                engine.setWifiOnlyGlobal(checkWifiOnly.isChecked());
                engine.setBatterySaverEnabled(checkBatterySaver.isChecked());
                engine.setCategorySubfoldersEnabled(checkCategoryFolders.isChecked());
                engine.setScheduleEnabled(checkSchedule.isChecked());

                // 4. Default Download Directory
                if (selectedDefaultDir[0] != null) {
                    engine.setDefaultDownloadDirectory(selectedDefaultDir[0].getAbsolutePath());
                }

                // 5. Schedule Window
                try {
                    String[] sParts = editScheduleStart.getText().toString().trim().split(":");
                    int sh = Integer.parseInt(sParts[0].trim());
                    int sm = sParts.length > 1 ? Integer.parseInt(sParts[1].trim()) : 0;

                    String[] eParts = editScheduleEnd.getText().toString().trim().split(":");
                    int eh = Integer.parseInt(eParts[0].trim());
                    int em = eParts.length > 1 ? Integer.parseInt(eParts[1].trim()) : 0;

                    engine.setScheduleWindow(sh, sm, eh, em);
                } catch (Exception ignored) {
                    engine.setScheduleWindow(startH[0], startM[0], endH[0], endM[0]);
                }

                // 6. Auto Extract
                if (checkAutoExtract != null) {
                    prefs.edit().putBoolean(DownloadEngine.KEY_AUTO_EXTRACT_ZIP, checkAutoExtract.isChecked()).commit();
                }

                engine.reloadPreferences();
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

    private void showTaskDiagnosticsDialog(DownloadItem initialItem) {
        if (initialItem == null) return;
        final String taskId = initialItem.getId();

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

        Runnable updateUI = () -> {
            DownloadItem liveItem = engine.getDownloadItem(taskId);
            if (liveItem == null) {
                liveItem = initialItem;
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
                textStatusBadge.setText("RUNNING");
                textStatusBadge.setTextColor(Color.parseColor("#BB86FC"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
            } else if (status == DownloadStatus.PAUSED) {
                textStatusBadge.setText("PAUSED");
                textStatusBadge.setTextColor(Color.parseColor("#FFB74D"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_paused);
            } else if (status == DownloadStatus.WAITING_FOR_WIFI) {
                textStatusBadge.setText("WAITING FOR WI-FI");
                textStatusBadge.setTextColor(Color.parseColor("#00E5FF"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
            } else if (status == DownloadStatus.WAITING_FOR_SCHEDULE) {
                textStatusBadge.setText("SCHEDULED");
                textStatusBadge.setTextColor(Color.parseColor("#00E5FF"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
            } else {
                textStatusBadge.setText("FAILED");
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

            List<DownloadSegment> segments = engine.getTaskSegments(taskId);
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

        DownloadEngine.DownloadListener diagListener = new DownloadEngine.DownloadListener() {
            @Override
            public void onDownloadProgress(DownloadItem item) {
                if (item.getId().equals(taskId)) {
                    runOnUiThread(updateUI);
                }
            }

            @Override
            public void onDownloadCompleted(DownloadItem item) {
                if (item.getId().equals(taskId)) {
                    runOnUiThread(updateUI);
                }
            }

            @Override
            public void onDownloadFailed(DownloadItem item, String error) {
                if (item.getId().equals(taskId)) {
                    runOnUiThread(updateUI);
                }
            }

            @Override
            public void onQueueChanged() {
                runOnUiThread(updateUI);
            }
        };

        engine.addListener(diagListener);
        dialog.setOnDismissListener(d -> engine.removeListener(diagListener));

        updateUI.run();
        dialog.show();
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

    private boolean isVideo(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".mov");
    }

    private boolean isAudio(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg");
    }

    private boolean isDocument(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".epub");
    }

    private boolean isArchive(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".apk");
    }

    private int getFileIconRes(String name) {
        if (isVideo(name)) return R.drawable.ic_file_video;
        if (isAudio(name)) return R.drawable.ic_file_audio;
        if (isArchive(name)) return R.drawable.ic_file_archive;
        if (isDocument(name)) return R.drawable.ic_file_doc;
        return R.drawable.ic_file_generic;
    }

    private long lastUiProgressUpdateTime = 0;

    @Override
    public void onDownloadProgress(DownloadItem item) {
        if (item == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUiProgressUpdateTime < 600) {
            return;
        }
        lastUiProgressUpdateTime = now;

        int targetIndex = -1;
        long totalSpeed = 0;
        for (int i = 0; i < filteredItems.size(); i++) {
            DownloadItem cur = filteredItems.get(i);
            if (cur.getId().equals(item.getId())) {
                filteredItems.set(i, item);
                targetIndex = i;
                cur = item;
            }
            if (cur.getStatus().isActive()) {
                totalSpeed += cur.getSpeedBytesPerSec();
            }
        }

        if (tvAggregateSpeed != null) {
            if (totalSpeed > 0) {
                tvAggregateSpeed.setVisibility(View.VISIBLE);
                tvAggregateSpeed.setText("Speed: " + DownloadItem.formatBytes(totalSpeed) + "/s");
            } else {
                tvAggregateSpeed.setVisibility(View.GONE);
            }
        }

        if (targetIndex != -1 && listView != null) {
            int first = listView.getFirstVisiblePosition();
            int last = listView.getLastVisiblePosition();
            if (targetIndex >= first && targetIndex <= last) {
                View view = listView.getChildAt(targetIndex - first);
                if (view != null && adapter != null) {
                    adapter.getView(targetIndex, view, listView);
                }
            }
        } else {
            refreshDownloadsList();
        }
    }

    @Override
    public void onDownloadCompleted(DownloadItem item) {
        refreshDownloadsList();
    }

    @Override
    public void onDownloadFailed(DownloadItem item, String error) {
        refreshDownloadsList();
    }

    @Override
    public void onQueueChanged() {
        refreshDownloadsList();
    }

    private String formatCardErrorMessage(String rawError) {
        if (rawError == null || rawError.trim().isEmpty()) {
            return "Download error";
        }
        String lower = rawError.toLowerCase(Locale.US);
        if (lower.contains("403")) {
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

    private class DownloadsCardAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return filteredItems.size();
        }

        @Override
        public DownloadItem getItem(int position) {
            return filteredItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(DownloadsActivity.this).inflate(R.layout.item_download_card, parent, false);
            }

            DownloadItem item = getItem(position);
            final String taskId = item.getId();
            final boolean isSelected = selectedTaskIds.contains(taskId);

            View cardRoot = convertView.findViewById(R.id.card_root);
            FrameLayout iconContainer = convertView.findViewById(R.id.card_icon_container);
            ImageView iconFile = convertView.findViewById(R.id.card_icon_file);
            CheckBox checkBox = convertView.findViewById(R.id.card_checkbox);

            TextView textFilename = convertView.findViewById(R.id.card_text_filename);
            TextView textStatusBadge = convertView.findViewById(R.id.card_text_status_badge);
            ProgressBar progressBar = convertView.findViewById(R.id.card_progress_bar);
            TextView textStats = convertView.findViewById(R.id.card_text_stats);
            TextView textEta = convertView.findViewById(R.id.card_text_eta);

            View actionsContainer = convertView.findViewById(R.id.card_actions_container);
            ImageView btnInfo = convertView.findViewById(R.id.card_btn_info);
            ImageView btnLink = convertView.findViewById(R.id.card_btn_link);
            ImageView btnPauseResume = convertView.findViewById(R.id.card_btn_pause_resume);
            ImageView btnDelete = convertView.findViewById(R.id.card_btn_delete);

            TextView btnChecksum = convertView.findViewById(R.id.card_btn_checksum);
            TextView btnExtract = convertView.findViewById(R.id.card_btn_extract);

            iconFile.setImageResource(getFileIconRes(item.getFileName()));
            textFilename.setText(item.getFileName());

            // Reset ETA color to default cyan
            textEta.setTextColor(Color.parseColor("#00E5FF"));

            // Configure status & badge
            if (item.getStatus() == DownloadStatus.COMPLETED) {
                textStatusBadge.setText("COMPLETED");
                textStatusBadge.setTextColor(Color.parseColor("#4CAF50"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_completed);
                progressBar.setProgress(100);
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")));
                textStats.setText(item.getFormattedTotalSize() + " • Verified");
                textEta.setText("Done");
                textEta.setTextColor(Color.parseColor("#4CAF50"));

                btnPauseResume.setVisibility(View.GONE);
                btnLink.setVisibility(View.GONE);

                if (btnChecksum != null) {
                    btnChecksum.setVisibility(View.VISIBLE);
                    btnChecksum.setOnClickListener(v -> showChecksumDialog(item));
                }
                if (btnExtract != null) {
                    if (isArchive(item.getFileName()) && item.getFileName().toLowerCase(Locale.US).endsWith(".zip")) {
                        btnExtract.setVisibility(View.VISIBLE);
                        btnExtract.setOnClickListener(v -> extractZip(item));
                    } else {
                        btnExtract.setVisibility(View.GONE);
                    }
                }
            } else {
                if (btnChecksum != null) btnChecksum.setVisibility(View.GONE);
                if (btnExtract != null) btnExtract.setVisibility(View.GONE);
            }

            if (item.getStatus().isActive()) {
                int threads = item.getNumSegments();
                textStatusBadge.setText(threads > 1 ? "RUNNING • " + threads + " THREADS" : "RUNNING");
                textStatusBadge.setTextColor(Color.parseColor("#BB86FC"));
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
                progressBar.setProgress(item.getProgressPercentage());
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#BB86FC")));
                textStats.setText(item.getFormattedDownloadedSize() + " / " + item.getFormattedTotalSize()
                        + " (" + item.getProgressPercentage() + "%) • " + item.getFormattedSpeed());
                textEta.setText("ETA: " + item.getFormattedEta());
                btnPauseResume.setVisibility(View.VISIBLE);
                btnPauseResume.setImageResource(R.drawable.ic_pause);
                btnPauseResume.setOnClickListener(v -> engine.pause(item.getId()));
                btnLink.setVisibility(View.GONE);
            } else if (item.getStatus() == DownloadStatus.PAUSED) {
                textStatusBadge.setText("PAUSED");
                textStatusBadge.setTextColor(Color.parseColor("#FFB74D")); // Amber
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_paused);
                progressBar.setProgress(item.getProgressPercentage());
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB74D")));
                textStats.setText(item.getFormattedDownloadedSize() + " / " + item.getFormattedTotalSize() + " (" + item.getProgressPercentage() + "%)");
                textEta.setText("Paused");
                textEta.setTextColor(Color.parseColor("#FFB74D"));
                btnPauseResume.setVisibility(View.VISIBLE);
                btnPauseResume.setImageResource(R.drawable.ic_play);
                btnPauseResume.setOnClickListener(v -> engine.resume(item.getId()));
                btnLink.setVisibility(View.VISIBLE);
            } else if (item.getStatus() == DownloadStatus.WAITING_FOR_WIFI) {
                textStatusBadge.setText("WAITING FOR WI-FI");
                textStatusBadge.setTextColor(Color.parseColor("#00E5FF")); // Cyan
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
                progressBar.setProgress(item.getProgressPercentage());
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
                textStats.setText(item.getFormattedDownloadedSize() + " / " + item.getFormattedTotalSize() + " (Waiting for Wi-Fi)");
                textEta.setText("Paused");
                btnPauseResume.setVisibility(View.VISIBLE);
                btnPauseResume.setImageResource(R.drawable.ic_play);
                btnPauseResume.setOnClickListener(v -> engine.resume(item.getId()));
                btnLink.setVisibility(View.VISIBLE);
            } else if (item.getStatus() == DownloadStatus.WAITING_FOR_SCHEDULE) {
                textStatusBadge.setText("SCHEDULED");
                textStatusBadge.setTextColor(Color.parseColor("#00E5FF")); // Cyan
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
                progressBar.setProgress(item.getProgressPercentage());
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
                textStats.setText(item.getFormattedDownloadedSize() + " / " + item.getFormattedTotalSize() + " (" + (item.getErrorMessage() != null ? item.getErrorMessage() : "Scheduled") + ")");
                textEta.setText("Night");
                btnPauseResume.setVisibility(View.VISIBLE);
                btnPauseResume.setImageResource(R.drawable.ic_play);
                btnPauseResume.setOnClickListener(v -> engine.resume(item.getId()));
                btnLink.setVisibility(View.VISIBLE);
            } else if (item.getStatus() == DownloadStatus.FAILED) {
                textStatusBadge.setText("FAILED");
                textStatusBadge.setTextColor(Color.parseColor("#FF5252")); // Red
                textStatusBadge.setBackgroundResource(R.drawable.bg_badge_failed);
                progressBar.setProgress(item.getProgressPercentage());
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")));
                textStats.setText(formatCardErrorMessage(item.getErrorMessage()));
                textEta.setText("Error");
                textEta.setTextColor(Color.parseColor("#FF5252"));
                btnPauseResume.setVisibility(View.VISIBLE);
                btnPauseResume.setImageResource(R.drawable.ic_play);
                btnPauseResume.setOnClickListener(v -> engine.resume(item.getId()));
                btnLink.setVisibility(View.VISIBLE);
            }

            btnInfo.setOnClickListener(v -> showTaskDiagnosticsDialog(item));
            btnLink.setOnClickListener(v -> showUpdateLinkDialog(item));
            btnDelete.setOnClickListener(v -> {
                engine.delete(item.getId(), true);
                Toast.makeText(DownloadsActivity.this, "Download deleted", Toast.LENGTH_SHORT).show();
            });

            boolean isDark = DownloaderThemeHelper.isAppDarkTheme(DownloadsActivity.this);

            if (isSelectionMode) {
                iconContainer.setVisibility(View.GONE);
                checkBox.setVisibility(View.VISIBLE);
                checkBox.setChecked(isSelected);
                actionsContainer.setVisibility(View.GONE);

                cardRoot.setBackgroundResource(isSelected
                        ? (isDark ? R.drawable.bg_history_selected_row : R.drawable.bg_history_selected_row_light)
                        : (isDark ? R.drawable.card_download_bg : R.drawable.card_download_bg_light));

                convertView.setOnClickListener(v -> toggleItemSelection(taskId));
                convertView.setOnLongClickListener(v -> {
                    toggleItemSelection(taskId);
                    return true;
                });
            } else {
                iconContainer.setVisibility(View.VISIBLE);
                checkBox.setVisibility(View.GONE);
                actionsContainer.setVisibility(View.VISIBLE);

                cardRoot.setBackgroundResource(isDark ? R.drawable.card_download_bg : R.drawable.card_download_bg_light);

                // Tap card to open completed file or inspect diagnostics
                convertView.setOnClickListener(v -> {
                    if (item.getStatus() == DownloadStatus.COMPLETED) {
                        openFile(item);
                    } else {
                        showTaskDiagnosticsDialog(item);
                    }
                });

                // Long press opens dedicated actions menu
                convertView.setOnLongClickListener(v -> {
                    showDownloadItemContextMenu(item);
                    return true;
                });
            }

            return convertView;
        }

        private void openFile(DownloadItem item) {
            File f = new File(item.getDestinationPath());
            if (!f.exists()) {
                Toast.makeText(DownloadsActivity.this, "File not found on storage", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri = Uri.fromFile(f);
                intent.setDataAndType(uri, item.getMimeType() != null ? item.getMimeType() : "*/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(DownloadsActivity.this, "Cannot open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        private void shareFile(DownloadItem item) {
            File f = new File(item.getDestinationPath());
            if (!f.exists()) return;

            try {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType(item.getMimeType() != null ? item.getMimeType() : "*/*");
                share.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                share.putExtra(Intent.EXTRA_SUBJECT, item.getFileName());
                startActivity(Intent.createChooser(share, "Share " + item.getFileName()));
            } catch (Exception e) {
                Toast.makeText(DownloadsActivity.this, "Share error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
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
                    openFile(item);
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
                showTaskDiagnosticsDialog(item);
            });

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
                engine.delete(item.getId(), true);
                Toast.makeText(DownloadsActivity.this, "Download deleted", Toast.LENGTH_SHORT).show();
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

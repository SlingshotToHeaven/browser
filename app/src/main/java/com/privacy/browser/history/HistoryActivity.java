package com.privacy.browser.history;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.privacy.browser.MainActivity;
import com.privacy.browser.R;
import com.privacy.browser.ThemeManager;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import com.privacy.browser.ThemeManager;
import java.util.concurrent.Executors;

public class HistoryActivity extends Activity {

    public static class SubVisit {
        final long id;
        final long visitedAt;

        SubVisit(long id, long visitedAt) {
            this.id = id;
            this.visitedAt = visitedAt;
        }
    }

    public static class HistoryCluster {
        final List<Long> ids = new ArrayList<>();
        final List<SubVisit> subVisits = new ArrayList<>();
        final String title;
        final String url;
        long latestVisitedAt;
        long earliestVisitedAt;

        HistoryCluster(HistoryModel first) {
            this.title = first.getTitle();
            this.url = first.getUrl();
            this.ids.add(first.getId());
            this.subVisits.add(new SubVisit(first.getId(), first.getVisitedAt()));
            this.latestVisitedAt = first.getVisitedAt();
            this.earliestVisitedAt = first.getVisitedAt();
        }

        void addVisit(HistoryModel next) {
            this.ids.add(next.getId());
            this.subVisits.add(new SubVisit(next.getId(), next.getVisitedAt()));
            if (next.getVisitedAt() > latestVisitedAt) {
                latestVisitedAt = next.getVisitedAt();
            }
            if (next.getVisitedAt() < earliestVisitedAt) {
                earliestVisitedAt = next.getVisitedAt();
            }
        }

        public int getVisitCount() {
            return ids.size();
        }

        public long getClusterKey() {
            return ids.get(0);
        }
    }

    private static class ListItem {
        static final int TYPE_HEADER = 0;
        static final int TYPE_CLUSTER = 1;
        static final int TYPE_SUB_VISIT = 2;

        final int type;
        final String headerTitle;
        final HistoryCluster cluster;
        final SubVisit subVisit;

        ListItem(String headerTitle) {
            this.type = TYPE_HEADER;
            this.headerTitle = headerTitle;
            this.cluster = null;
            this.subVisit = null;
        }

        ListItem(HistoryCluster cluster) {
            this.type = TYPE_CLUSTER;
            this.headerTitle = null;
            this.cluster = cluster;
            this.subVisit = null;
        }

        ListItem(HistoryCluster parentCluster, SubVisit subVisit) {
            this.type = TYPE_SUB_VISIT;
            this.headerTitle = null;
            this.cluster = parentCluster;
            this.subVisit = subVisit;
        }
    }

    private static final int[] MONOGRAM_COLORS = {
        Color.parseColor("#00E5FF"), // Cyan
        Color.parseColor("#BB86FC"), // Purple
        Color.parseColor("#00E676"), // Green
        Color.parseColor("#FF9100"), // Orange
        Color.parseColor("#2979FF"), // Blue
        Color.parseColor("#FF4081"), // Pink
        Color.parseColor("#FFD600"), // Yellow
        Color.parseColor("#1DE9B6")  // Teal
    };

    private static final long MAX_CONSECUTIVE_GAP_MS = 5 * 60 * 1000L; // 5 minutes

    private HistoryDbHelper dbHelper;
    private ListView listView;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private TextView textCount;
    private View btnClearAll;
    private EditText editSearch;
    private ImageView btnClearSearch;

    // Multi-Selection Toolbar UI & State
    private View toolbarStandard;
    private View toolbarSelection;
    private ImageView btnCloseSelection;
    private TextView textSelectedCount;
    private TextView btnSelectAll;
    private ImageView btnDeleteSelected;

    private boolean isSelectionMode = false;
    private final Set<Long> selectedClusterKeys = new HashSet<>();
    private final Set<Long> expandedClusterKeys = new HashSet<>();

    private final List<HistoryModel> allHistory = new ArrayList<>();
    private final List<HistoryCluster> filteredClusters = new ArrayList<>();
    private final List<ListItem> displayItems = new ArrayList<>();
    private HistoryAdapter adapter;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat timeSecFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayOnlyFormat = new SimpleDateFormat("MMM d", Locale.getDefault());

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        dbHelper = HistoryDbHelper.getInstance(this);

        initViews();
        loadHistory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dbExecutor.shutdown();
    }

    private void initViews() {
        toolbarStandard = findViewById(R.id.history_toolbar);
        toolbarSelection = findViewById(R.id.history_selection_toolbar);
        btnCloseSelection = findViewById(R.id.history_btn_close_selection);
        textSelectedCount = findViewById(R.id.history_text_selected_count);
        btnSelectAll = findViewById(R.id.history_btn_select_all);
        btnDeleteSelected = findViewById(R.id.history_btn_delete_selected);

        findViewById(R.id.history_btn_back).setOnClickListener(v -> finish());

        if (btnCloseSelection != null) {
            btnCloseSelection.setOnClickListener(v -> exitSelectionMode());
        }

        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        }

        if (btnDeleteSelected != null) {
            btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        }

        textCount = findViewById(R.id.history_text_count);
        btnClearAll = findViewById(R.id.history_btn_clear_all);
        editSearch = findViewById(R.id.history_edit_search);
        btnClearSearch = findViewById(R.id.history_btn_clear_search);

        listView = findViewById(R.id.history_list);
        emptyView = findViewById(R.id.history_empty_view);
        emptyTitle = findViewById(R.id.history_empty_title);
        emptyDesc = findViewById(R.id.history_empty_desc);

        adapter = new HistoryAdapter();
        listView.setAdapter(adapter);

        btnClearAll.setOnClickListener(v -> showClearHistoryScopeDialog());

        btnClearSearch.setOnClickListener(v -> {
            editSearch.setText("");
        });

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s != null ? s.toString().trim() : "";
                btnClearSearch.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                filterHistory(q);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void loadHistory() {
        dbExecutor.execute(() -> {
            List<HistoryModel> loaded = dbHelper.getAllHistory(1000);
            mainHandler.post(() -> {
                allHistory.clear();
                allHistory.addAll(loaded);
                filterHistory(editSearch.getText() != null ? editSearch.getText().toString().trim() : "");
            });
        });
    }

    private void filterHistory(String query) {
        filteredClusters.clear();

        List<HistoryModel> matchingModels = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            matchingModels.addAll(allHistory);
        } else {
            String lower = query.toLowerCase(Locale.US);
            for (HistoryModel hm : allHistory) {
                if (hm.getTitle().toLowerCase(Locale.US).contains(lower) ||
                    hm.getUrl().toLowerCase(Locale.US).contains(lower)) {
                    matchingModels.add(hm);
                }
            }
        }

        // Deduplication: Collapse consecutive visits to identical URLs IF gap <= 5 minutes
        HistoryCluster currentCluster = null;
        for (HistoryModel hm : matchingModels) {
            if (currentCluster != null &&
                currentCluster.url.equals(hm.getUrl()) &&
                Math.abs(currentCluster.earliestVisitedAt - hm.getVisitedAt()) <= MAX_CONSECUTIVE_GAP_MS) {
                currentCluster.addVisit(hm);
            } else {
                currentCluster = new HistoryCluster(hm);
                filteredClusters.add(currentCluster);
            }
        }

        buildDisplayItems(query != null && !query.isEmpty());
        adapter.notifyDataSetChanged();
        updateUiState(query);
    }

    private void buildDisplayItems(boolean isSearching) {
        displayItems.clear();
        if (filteredClusters.isEmpty()) {
            return;
        }

        if (isSearching) {
            for (HistoryCluster cluster : filteredClusters) {
                displayItems.add(new ListItem(cluster));
                if (expandedClusterKeys.contains(cluster.getClusterKey())) {
                    for (SubVisit sv : cluster.subVisits) {
                        displayItems.add(new ListItem(cluster, sv));
                    }
                }
            }
            return;
        }

        Calendar now = Calendar.getInstance();
        int nowYear = now.get(Calendar.YEAR);
        int nowDay = now.get(Calendar.DAY_OF_YEAR);

        Calendar cal = Calendar.getInstance();
        String currentSection = null;

        for (HistoryCluster cluster : filteredClusters) {
            cal.setTimeInMillis(cluster.latestVisitedAt);
            int itemYear = cal.get(Calendar.YEAR);
            int itemDay = cal.get(Calendar.DAY_OF_YEAR);

            String section;
            if (nowYear == itemYear && nowDay == itemDay) {
                section = "TODAY";
            } else if (nowYear == itemYear && nowDay - itemDay == 1) {
                section = "YESTERDAY";
            } else if (nowYear == itemYear && nowDay - itemDay < 7 && nowDay > itemDay) {
                section = "EARLIER THIS WEEK";
            } else {
                section = "OLDER";
            }

            if (!section.equals(currentSection)) {
                currentSection = section;
                displayItems.add(new ListItem(section));
            }
            displayItems.add(new ListItem(cluster));
            if (expandedClusterKeys.contains(cluster.getClusterKey())) {
                for (SubVisit sv : cluster.subVisits) {
                    displayItems.add(new ListItem(cluster, sv));
                }
            }
        }
    }

    private void updateUiState(String currentQuery) {
        int totalVisits = allHistory.size();
        int clusterCount = filteredClusters.size();

        if (currentQuery != null && !currentQuery.isEmpty()) {
            textCount.setText(clusterCount + " matches (" + totalVisits + " total)");
        } else {
            textCount.setText(totalVisits + " visits across " + clusterCount + " clusters");
        }

        btnClearAll.setVisibility(totalVisits > 0 ? View.VISIBLE : View.GONE);

        if (clusterCount == 0) {
            listView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            if (totalVisits == 0) {
                emptyTitle.setText("No browsing history");
                emptyDesc.setText("Pages you visit in standard tabs will appear here.");
            } else {
                emptyTitle.setText("No matching history");
                emptyDesc.setText("Try searching with different keywords.");
            }
        } else {
            listView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }
    }

    private void toggleClusterExpansion(long clusterKey) {
        if (expandedClusterKeys.contains(clusterKey)) {
            expandedClusterKeys.remove(clusterKey);
        } else {
            expandedClusterKeys.add(clusterKey);
        }
        buildDisplayItems(editSearch.getText() != null && !editSearch.getText().toString().trim().isEmpty());
        adapter.notifyDataSetChanged();
    }

    private void enterSelectionMode(long initialClusterKey) {
        isSelectionMode = true;
        selectedClusterKeys.clear();
        selectedClusterKeys.add(initialClusterKey);
        updateSelectionToolbar();
        adapter.notifyDataSetChanged();
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        selectedClusterKeys.clear();
        if (toolbarSelection != null) toolbarSelection.setVisibility(View.GONE);
        if (toolbarStandard != null) toolbarStandard.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void toggleItemSelection(long clusterKey) {
        if (selectedClusterKeys.contains(clusterKey)) {
            selectedClusterKeys.remove(clusterKey);
        } else {
            selectedClusterKeys.add(clusterKey);
        }
        if (selectedClusterKeys.isEmpty()) {
            exitSelectionMode();
        } else {
            updateSelectionToolbar();
            adapter.notifyDataSetChanged();
        }
    }

    private void toggleSelectAll() {
        if (selectedClusterKeys.size() == filteredClusters.size()) {
            selectedClusterKeys.clear();
            exitSelectionMode();
        } else {
            selectedClusterKeys.clear();
            for (HistoryCluster hc : filteredClusters) {
                selectedClusterKeys.add(hc.getClusterKey());
            }
            updateSelectionToolbar();
            adapter.notifyDataSetChanged();
        }
    }

    private void updateSelectionToolbar() {
        if (toolbarStandard != null) toolbarStandard.setVisibility(View.GONE);
        if (toolbarSelection != null) toolbarSelection.setVisibility(View.VISIBLE);

        int count = selectedClusterKeys.size();
        if (textSelectedCount != null) {
            textSelectedCount.setText(count + " selected");
        }

        if (btnSelectAll != null) {
            boolean allSelected = !filteredClusters.isEmpty() && count == filteredClusters.size();
            btnSelectAll.setText(allSelected ? "Deselect All" : "Select All");
        }
    }

    private void confirmDeleteSelected() {
        if (selectedClusterKeys.isEmpty()) return;

        List<Long> idsToDelete = new ArrayList<>();
        for (HistoryCluster hc : filteredClusters) {
            if (selectedClusterKeys.contains(hc.getClusterKey())) {
                idsToDelete.addAll(hc.ids);
            }
        }

        int count = selectedClusterKeys.size();
        int totalVisits = idsToDelete.size();

        new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                .setTitle("Delete Selected History")
                .setMessage("Delete " + count + " selected " + (count == 1 ? "entry" : "entries") +
                        (totalVisits > count ? " (" + totalVisits + " total visits)" : "") + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    dbExecutor.execute(() -> {
                        dbHelper.deleteVisits(idsToDelete);
                        mainHandler.post(() -> {
                            Toast.makeText(this, count + " entries deleted", Toast.LENGTH_SHORT).show();
                            exitSelectionMode();
                            loadHistory();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    private void openUrl(String url, boolean openInNewTab, boolean openInIncognito) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_LOAD_URL, url);
        if (openInNewTab) {
            intent.putExtra(MainActivity.EXTRA_OPEN_IN_NEW_TAB, true);
        }
        if (openInIncognito) {
            intent.putExtra(MainActivity.EXTRA_OPEN_IN_INCOGNITO, true);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void showSelectedItemsOptions() {
        if (selectedClusterKeys.isEmpty()) return;

        List<HistoryCluster> selectedList = new ArrayList<>();
        for (HistoryCluster hc : filteredClusters) {
            if (selectedClusterKeys.contains(hc.getClusterKey())) {
                selectedList.add(hc);
            }
        }
        if (selectedList.isEmpty()) return;

        if (selectedList.size() == 1) {
            showItemOptions(selectedList.get(0));
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_context_menu_sheet, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        ImageView headerIcon = view.findViewById(R.id.ctx_header_icon);
        if (headerIcon != null) {
            headerIcon.setImageResource(R.drawable.ic_history);
        }

        TextView title = view.findViewById(R.id.ctx_header_title);
        if (title != null) {
            title.setText(selectedList.size() + " Selected Items");
        }

        LinearLayout container = view.findViewById(R.id.ctx_actions_container);
        if (container != null) {
            addContextAction(container, "Open First in Active Tab", R.drawable.ic_search, () -> {
                dialog.dismiss();
                exitSelectionMode();
                openUrl(selectedList.get(0).url, false, false);
            });

            addContextAction(container, "Open All in New Tabs (" + selectedList.size() + ")", R.drawable.ic_add, () -> {
                dialog.dismiss();
                for (int i = 0; i < selectedList.size(); i++) {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.putExtra(MainActivity.EXTRA_LOAD_URL, selectedList.get(i).url);
                    intent.putExtra(MainActivity.EXTRA_OPEN_IN_NEW_TAB, true);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }
                exitSelectionMode();
                finish();
            });

            addContextAction(container, "Open All in Incognito Tabs (" + selectedList.size() + ")", R.drawable.ic_incognito, () -> {
                dialog.dismiss();
                for (int i = 0; i < selectedList.size(); i++) {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.putExtra(MainActivity.EXTRA_LOAD_URL, selectedList.get(i).url);
                    intent.putExtra(MainActivity.EXTRA_OPEN_IN_INCOGNITO, true);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }
                exitSelectionMode();
                finish();
            });

            addContextAction(container, "Copy All URLs", R.drawable.ic_find, () -> {
                dialog.dismiss();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < selectedList.size(); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(selectedList.get(i).url);
                }
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    cb.setPrimaryClip(ClipData.newPlainText("URLs", sb.toString()));
                    Toast.makeText(this, selectedList.size() + " URLs copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            });

            addContextAction(container, "Share All Links", R.drawable.ic_share, () -> {
                dialog.dismiss();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < selectedList.size(); i++) {
                    HistoryCluster hc = selectedList.get(i);
                    if (i > 0) sb.append("\n\n");
                    sb.append(hc.title).append("\n").append(hc.url);
                }
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, selectedList.size() + " Links from History");
                shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());
                startActivity(Intent.createChooser(shareIntent, "Share Links"));
            });

            addContextAction(container, "Delete Selected (" + selectedList.size() + ")", R.drawable.ic_trash, () -> {
                dialog.dismiss();
                confirmDeleteSelected();
            });
        }

        Button btnDismiss = view.findViewById(R.id.ctx_btn_dismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void showItemOptions(HistoryCluster cluster) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_context_menu_sheet, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        ImageView headerIcon = view.findViewById(R.id.ctx_header_icon);
        if (headerIcon != null) {
            headerIcon.setImageResource(R.drawable.ic_history);
        }

        TextView title = view.findViewById(R.id.ctx_header_title);
        if (title != null) {
            String titleText = cluster.title;
            if (titleText == null || titleText.trim().isEmpty()) {
                titleText = cluster.url;
            }
            if (cluster.getVisitCount() > 1) {
                titleText = titleText + " (" + cluster.getVisitCount() + " visits)";
            }
            title.setText(titleText);
        }

        final String domain = extractHost(cluster.url);

        LinearLayout container = view.findViewById(R.id.ctx_actions_container);
        if (container != null) {
            addContextAction(container, "Open in Active Tab", R.drawable.ic_search, () -> {
                dialog.dismiss();
                openUrl(cluster.url, false, false);
            });

            addContextAction(container, "Open in New Tab", R.drawable.ic_add, () -> {
                dialog.dismiss();
                openUrl(cluster.url, true, false);
            });

            addContextAction(container, "Open in Incognito Tab", R.drawable.ic_incognito, () -> {
                dialog.dismiss();
                openUrl(cluster.url, true, true);
            });

            addContextAction(container, "Copy Link Address", R.drawable.ic_find, () -> {
                dialog.dismiss();
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    cb.setPrimaryClip(ClipData.newPlainText("URL", cluster.url));
                    Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            });

            addContextAction(container, "Share Link", R.drawable.ic_share, () -> {
                dialog.dismiss();
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, cluster.title);
                shareIntent.putExtra(Intent.EXTRA_TEXT, cluster.url);
                startActivity(Intent.createChooser(shareIntent, "Share Link"));
            });

            if (domain != null && !domain.isEmpty() && !domain.startsWith("android_asset")) {
                addContextAction(container, "Delete All from " + domain, R.drawable.ic_trash, () -> {
                    dialog.dismiss();
                    dbExecutor.execute(() -> {
                        int deleted = dbHelper.deleteVisitsByDomain(domain);
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Deleted " + deleted + " visits from " + domain, Toast.LENGTH_SHORT).show();
                            loadHistory();
                        });
                    });
                });
            }

            addContextAction(container, cluster.getVisitCount() > 1 ? "Delete " + cluster.getVisitCount() + " Visits" : "Delete Entry", R.drawable.ic_trash, () -> {
                dialog.dismiss();
                dbExecutor.execute(() -> {
                    dbHelper.deleteVisits(cluster.ids);
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Entry deleted", Toast.LENGTH_SHORT).show();
                        loadHistory();
                    });
                });
            });
        }

        Button btnDismiss = view.findViewById(R.id.ctx_btn_dismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void addContextAction(LinearLayout parent, String text, int iconRes, Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_context_menu_action, parent, false);
        TextView tv = row.findViewById(R.id.ctx_item_text);
        ImageView iv = row.findViewById(R.id.ctx_item_icon);
        tv.setText(text);
        if (iconRes != 0) {
            iv.setImageResource(iconRes);
            if (iconRes == R.drawable.ic_trash) {
                iv.setColorFilter(Color.parseColor("#FF5555"));
                tv.setTextColor(Color.parseColor("#FF5555"));
            }
        }
        row.setOnClickListener(v -> action.run());
        parent.addView(row);
    }

    private void showClearHistoryScopeDialog() {
        String[] options = {
            "Last Hour",
            "Today (Last 24 Hours)",
            "Last 7 Days",
            "All Time"
        };

        new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                .setTitle("Clear Browsing History")
                .setItems(options, (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    long targetTimestamp;
                    switch (which) {
                        case 0: // Last hour
                            targetTimestamp = now - (60L * 60 * 1000);
                            clearHistorySince(targetTimestamp, "Last hour history cleared");
                            break;
                        case 1: // Today / 24 hours
                            targetTimestamp = now - (24L * 60 * 60 * 1000);
                            clearHistorySince(targetTimestamp, "Last 24 hours history cleared");
                            break;
                        case 2: // Last 7 days
                            targetTimestamp = now - (7L * 24 * 60 * 60 * 1000);
                            clearHistorySince(targetTimestamp, "Last 7 days history cleared");
                            break;
                        case 3: // All time
                        default:
                            dbExecutor.execute(() -> {
                                dbHelper.clearAll();
                                mainHandler.post(() -> {
                                    Toast.makeText(this, "Browsing history cleared", Toast.LENGTH_SHORT).show();
                                    loadHistory();
                                });
                            });
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearHistorySince(long timestamp, String message) {
        dbExecutor.execute(() -> {
            dbHelper.clearHistorySince(timestamp);
            mainHandler.post(() -> {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                loadHistory();
            });
        });
    }

    private static String extractHost(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                return host;
            }
        } catch (Exception ignored) {}

        String clean = url.replaceFirst("^https?://(www\\.)?", "");
        int slash = clean.indexOf('/');
        if (slash != -1) {
            clean = clean.substring(0, slash);
        }
        return clean;
    }

    private String formatClusterTime(HistoryCluster cluster, boolean isSearching) {
        if (cluster.getVisitCount() <= 1 || cluster.latestVisitedAt == cluster.earliestVisitedAt) {
            return isSearching ? dateFormat.format(new Date(cluster.latestVisitedAt)) : timeFormat.format(new Date(cluster.latestVisitedAt));
        }

        Calendar calLatest = Calendar.getInstance();
        calLatest.setTimeInMillis(cluster.latestVisitedAt);

        Calendar calEarliest = Calendar.getInstance();
        calEarliest.setTimeInMillis(cluster.earliestVisitedAt);

        boolean sameDay = calLatest.get(Calendar.YEAR) == calEarliest.get(Calendar.YEAR) &&
                          calLatest.get(Calendar.DAY_OF_YEAR) == calEarliest.get(Calendar.DAY_OF_YEAR);

        String earTime = timeFormat.format(new Date(cluster.earliestVisitedAt));
        String latTime = timeFormat.format(new Date(cluster.latestVisitedAt));

        if (sameDay) {
            return earTime + " – " + latTime;
        } else {
            return dayOnlyFormat.format(new Date(cluster.earliestVisitedAt)) + " " + earTime + " – " +
                   dayOnlyFormat.format(new Date(cluster.latestVisitedAt)) + " " + latTime;
        }
    }

    private class HistoryAdapter extends BaseAdapter {

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            return displayItems.get(position).type;
        }

        @Override
        public boolean isEnabled(int position) {
            return displayItems.get(position).type != ListItem.TYPE_HEADER;
        }

        @Override
        public int getCount() {
            return displayItems.size();
        }

        @Override
        public ListItem getItem(int position) {
            return displayItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            ListItem item = getItem(position);
            if (item.type == ListItem.TYPE_HEADER) {
                return -position;
            } else if (item.type == ListItem.TYPE_CLUSTER) {
                return item.cluster.getClusterKey();
            } else {
                return item.subVisit.id;
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ListItem listItem = getItem(position);

            if (listItem.type == ListItem.TYPE_HEADER) {
                HeaderViewHolder headerHolder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(HistoryActivity.this).inflate(R.layout.item_history_header, parent, false);
                    headerHolder = new HeaderViewHolder();
                    headerHolder.title = convertView.findViewById(R.id.history_header_title);
                    convertView.setTag(headerHolder);
                } else {
                    headerHolder = (HeaderViewHolder) convertView.getTag();
                }
                headerHolder.title.setText(listItem.headerTitle);
                return convertView;
            }

            if (listItem.type == ListItem.TYPE_SUB_VISIT) {
                SubVisitViewHolder subHolder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(HistoryActivity.this).inflate(R.layout.item_history_sub_visit, parent, false);
                    subHolder = new SubVisitViewHolder();
                    subHolder.tvTime = convertView.findViewById(R.id.history_sub_visit_time);
                    convertView.setTag(subHolder);
                } else {
                    subHolder = (SubVisitViewHolder) convertView.getTag();
                }

                subHolder.tvTime.setText(timeSecFormat.format(new Date(listItem.subVisit.visitedAt)));
                convertView.setOnClickListener(v -> openUrl(listItem.cluster.url, false, false));
                return convertView;
            }

            EntryViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(HistoryActivity.this).inflate(R.layout.item_history_entry, parent, false);
                holder = new EntryViewHolder();
                holder.root = convertView;
                holder.leadingContainer = convertView.findViewById(R.id.history_leading_container);
                holder.icon = convertView.findViewById(R.id.history_icon);
                holder.favicon = convertView.findViewById(R.id.history_favicon);
                holder.monogram = convertView.findViewById(R.id.history_monogram);
                holder.checkBox = convertView.findViewById(R.id.history_checkbox);
                holder.title = convertView.findViewById(R.id.history_title);
                holder.badgeRepeat = convertView.findViewById(R.id.history_badge_repeat);
                holder.url = convertView.findViewById(R.id.history_url);
                holder.date = convertView.findViewById(R.id.history_date);
                holder.btnDelete = convertView.findViewById(R.id.history_btn_delete);
                convertView.setTag(holder);
            } else {
                holder = (EntryViewHolder) convertView.getTag();
            }

            HistoryCluster cluster = listItem.cluster;
            final long clusterKey = cluster.getClusterKey();
            final boolean isSelected = selectedClusterKeys.contains(clusterKey);
            final boolean isExpanded = expandedClusterKeys.contains(clusterKey);

            String title = cluster.title;
            if (title == null || title.trim().isEmpty()) {
                title = cluster.url;
            }
            holder.title.setText(title);

            // Repeat badge: shows count and state indicator when expanded
            if (cluster.getVisitCount() > 1) {
                holder.badgeRepeat.setVisibility(View.VISIBLE);
                holder.badgeRepeat.setText(cluster.getVisitCount() + (isExpanded ? " visits ▲" : " visits ▼"));
            } else {
                holder.badgeRepeat.setVisibility(View.GONE);
            }

            String host = extractHost(cluster.url);
            String cleanUrl = cluster.url;
            if (cleanUrl != null) {
                cleanUrl = cleanUrl.replaceFirst("^https?://(www\\.)?", "");
                if (cleanUrl.endsWith("/")) {
                    cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
                }
            }
            holder.url.setText(cleanUrl);

            boolean isSearching = editSearch.getText() != null && !editSearch.getText().toString().trim().isEmpty();
            holder.date.setText(formatClusterTime(cluster, isSearching));

            // Smart Favicon with Monogram Fallback
            final String domainKey = com.privacy.browser.favicon.FaviconManager.extractDomain(cluster.url);
            holder.favicon.setTag(domainKey);

            android.graphics.Bitmap cachedFavicon = com.privacy.browser.favicon.FaviconManager.getInstance(HistoryActivity.this).getCachedFavicon(domainKey);
            if (cachedFavicon != null) {
                holder.favicon.setImageBitmap(cachedFavicon);
                holder.favicon.setVisibility(View.VISIBLE);
                holder.monogram.setVisibility(View.GONE);
            } else {
                holder.favicon.setVisibility(View.GONE);
                holder.monogram.setVisibility(View.VISIBLE);

                String initial = "?";
                if (!host.isEmpty()) {
                    initial = host.substring(0, 1).toUpperCase(Locale.US);
                } else if (title != null && !title.isEmpty()) {
                    initial = title.substring(0, 1).toUpperCase(Locale.US);
                }
                holder.monogram.setText(initial);
                int colorIndex = Math.abs(host.hashCode()) % MONOGRAM_COLORS.length;
                holder.monogram.setTextColor(MONOGRAM_COLORS[colorIndex]);

                // Asynchronously fetch icon for domain in background
                com.privacy.browser.favicon.FaviconManager.getInstance(HistoryActivity.this).loadFaviconAsync(cluster.url, (loadedDomain, bmp) -> {
                    if (domainKey.equals(holder.favicon.getTag())) {
                        holder.favicon.setImageBitmap(bmp);
                        holder.favicon.setVisibility(View.VISIBLE);
                        holder.monogram.setVisibility(View.GONE);
                    }
                });
            }

            if (isSelectionMode) {
                if (holder.icon != null) holder.icon.setVisibility(View.GONE);
                if (holder.favicon != null) holder.favicon.setVisibility(View.GONE);
                if (holder.monogram != null) holder.monogram.setVisibility(View.GONE);
                if (holder.checkBox != null) {
                    holder.checkBox.setVisibility(View.VISIBLE);
                    holder.checkBox.setChecked(isSelected);
                    holder.checkBox.setOnClickListener(v -> toggleItemSelection(clusterKey));
                }
                if (holder.leadingContainer != null) {
                    holder.leadingContainer.setOnClickListener(v -> toggleItemSelection(clusterKey));
                }
                if (isSelected) {
                    boolean isDarkTheme = ThemeManager.isAppDarkTheme(HistoryActivity.this);
holder.root.setBackgroundResource(isDarkTheme ? R.drawable.bg_history_selected_row : R.drawable.bg_history_selected_row_light);
                } else {
                    holder.root.setBackgroundColor(Color.TRANSPARENT);
                }

                convertView.setOnClickListener(v -> showSelectedItemsOptions());
                convertView.setOnLongClickListener(v -> {
                    showSelectedItemsOptions();
                    return true;
                });

                if (holder.btnDelete != null) {
                    holder.btnDelete.setVisibility(View.GONE);
                }
            } else {
                if (holder.icon != null) holder.icon.setVisibility(View.GONE);
                if (cachedFavicon != null) {
                    holder.favicon.setVisibility(View.VISIBLE);
                    holder.monogram.setVisibility(View.GONE);
                } else {
                    holder.favicon.setVisibility(View.GONE);
                    holder.monogram.setVisibility(View.VISIBLE);
                }
                if (holder.checkBox != null) holder.checkBox.setVisibility(View.GONE);
                if (holder.leadingContainer != null) holder.leadingContainer.setOnClickListener(null);

                android.util.TypedValue outValue = new android.util.TypedValue();
                HistoryActivity.this.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                holder.root.setBackgroundResource(outValue.resourceId);

                // Normal mode: if cluster has multiple visits, tap toggles expansion; otherwise opens URL
                convertView.setOnClickListener(v -> {
                    if (cluster.getVisitCount() > 1) {
                        toggleClusterExpansion(clusterKey);
                    } else {
                        openUrl(cluster.url, false, false);
                    }
                });

                convertView.setOnLongClickListener(v -> {
                    enterSelectionMode(clusterKey);
                    return true;
                });

                if (holder.btnDelete != null) {
                    holder.btnDelete.setVisibility(View.VISIBLE);
                    holder.btnDelete.setOnClickListener(v -> {
                        dbExecutor.execute(() -> {
                            dbHelper.deleteVisits(cluster.ids);
                            mainHandler.post(() -> {
                                Toast.makeText(HistoryActivity.this, "Entry deleted", Toast.LENGTH_SHORT).show();
                                loadHistory();
                            });
                        });
                    });
                }
            }

            return convertView;
        }

        private class HeaderViewHolder {
            TextView title;
        }

        private class SubVisitViewHolder {
            TextView tvTime;
        }

        private class EntryViewHolder {
            View root;
            FrameLayout leadingContainer;
            ImageView icon;
            ImageView favicon;
            TextView monogram;
            CheckBox checkBox;
            TextView title;
            TextView badgeRepeat;
            TextView url;
            TextView date;
            ImageView btnDelete;
        }
    }
}

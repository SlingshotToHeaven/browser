package com.privacy.downloader.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.privacy.downloader.R;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadsDialog implements DownloadEngine.DownloadListener {

    private final Context context;
    private final DownloadEngine engine;
    private AlertDialog dialog;
    private ListView listView;
    private DownloadsAdapter adapter;
    private final List<DownloadItem> displayItems = new ArrayList<>();

    private int currentFilter = 0; // 0 = All, 1 = Active, 2 = Completed
    private TextView tabAll, tabActive, tabCompleted, tvSpeedHeader;

    public static void show(Context context) {
        new DownloadsDialog(context).display();
    }

    private DownloadsDialog(Context context) {
        this.context = context;
        this.engine = DownloadEngine.getInstance(context);
    }

    private void display() {
        engine.addListener(this);

        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("Downloads Manager");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101010"));
        root.setPadding(24, 16, 24, 16);

        // Header info bar
        LinearLayout headerBar = new LinearLayout(context);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setPadding(0, 0, 0, 12);

        tvSpeedHeader = new TextView(context);
        tvSpeedHeader.setText("Speed: 0 B/s");
        tvSpeedHeader.setTextColor(Color.parseColor("#4CAF50")); // Green
        tvSpeedHeader.setTextSize(12);
        headerBar.addView(tvSpeedHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView btnAddUrl = new TextView(context);
        btnAddUrl.setText("+ Add URL");
        btnAddUrl.setTextColor(Color.parseColor("#BB86FC"));
        btnAddUrl.setTextSize(13);
        btnAddUrl.setOnClickListener(v -> showAddUrlPrompt());
        headerBar.addView(btnAddUrl);

        root.addView(headerBar);

        // Filter Tabs (All / Active / Completed)
        LinearLayout tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setPadding(0, 4, 0, 14);

        tabAll = createTabButton("All", 0);
        tabActive = createTabButton("Active", 1);
        tabCompleted = createTabButton("Completed", 2);

        tabsRow.addView(tabAll);
        tabsRow.addView(tabActive);
        tabsRow.addView(tabCompleted);
        root.addView(tabsRow);

        updateTabStyles();

        // List View
        listView = new ListView(context);
        listView.setBackgroundColor(Color.parseColor("#101010"));
        listView.setDivider(null);
        listView.setDividerHeight(12);

        adapter = new DownloadsAdapter(context, displayItems);
        listView.setAdapter(adapter);

        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        builder.setView(root);
        builder.setPositiveButton("Close", (d, w) -> dismiss());

        dialog = builder.create();
        dialog.setOnDismissListener(d -> engine.removeListener(this));
        dialog.show();

        refreshItems();
    }

    private TextView createTabButton(String text, int filter) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(24, 10, 24, 10);
        tv.setOnClickListener(v -> {
            currentFilter = filter;
            updateTabStyles();
            refreshItems();
        });
        return tv;
    }

    private void updateTabStyles() {
        tabAll.setTextColor(currentFilter == 0 ? Color.WHITE : Color.GRAY);
        tabActive.setTextColor(currentFilter == 1 ? Color.WHITE : Color.GRAY);
        tabCompleted.setTextColor(currentFilter == 2 ? Color.WHITE : Color.GRAY);
    }

    private void refreshItems() {
        List<DownloadItem> all = engine.getAllDownloads();
        displayItems.clear();

        long totalSpeed = 0;
        for (DownloadItem item : all) {
            if (item.getStatus().isActive()) {
                totalSpeed += item.getSpeedBytesPerSec();
            }

            if (currentFilter == 0) {
                displayItems.add(item);
            } else if (currentFilter == 1 && item.getStatus().isActive()) {
                displayItems.add(item);
            } else if (currentFilter == 2 && item.getStatus() == DownloadStatus.COMPLETED) {
                displayItems.add(item);
            }
        }

        if (tvSpeedHeader != null) {
            tvSpeedHeader.setText("Speed: " + DownloadItem.formatBytes(totalSpeed) + "/s");
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void showAddUrlPrompt() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("Add Download URL");

        EditText input = new EditText(context);
        input.setHint("https://example.com/file.zip");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setSingleLine(true);
        builder.setView(input);

        builder.setPositiveButton("Start", (d, w) -> {
            String url = input.getText().toString().trim();
            if (!TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"))) {
                DownloadPromptDialog.show(context, url, null, null, null, null, null, null);
            } else {
                Toast.makeText(context, "Invalid HTTP/HTTPS URL", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showUpdateLinkDialog(DownloadItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("Update Download Link");
        builder.setMessage("If the download link has expired, paste the refreshed URL below to resume without losing partial progress:");

        EditText input = new EditText(context);
        input.setText(item.getUrl());
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        input.selectAll();
        builder.setView(input);

        builder.setPositiveButton("Update & Resume", (d, w) -> {
            String newUrl = input.getText().toString().trim();
            if (!TextUtils.isEmpty(newUrl) && (newUrl.startsWith("http://") || newUrl.startsWith("https://"))) {
                engine.updateUrl(item.getId(), newUrl);
                Toast.makeText(context, "Link updated! Resuming...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Invalid URL", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    @Override
    public void onDownloadProgress(DownloadItem item) {
        refreshItems();
    }

    @Override
    public void onDownloadCompleted(DownloadItem item) {
        refreshItems();
    }

    @Override
    public void onDownloadFailed(DownloadItem item, String error) {
        refreshItems();
    }

    @Override
    public void onQueueChanged() {
        refreshItems();
    }

    private class DownloadsAdapter extends ArrayAdapter<DownloadItem> {

        public DownloadsAdapter(Context context, List<DownloadItem> items) {
            super(context, 0, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            DownloadItem item = getItem(position);
            if (item == null) return new View(context);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(Color.parseColor("#181818"));
            row.setPadding(24, 20, 24, 20);

            // Top row: Status icon, filename, and action buttons
            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            ImageView iconStatus = new ImageView(context);
            if (item.getStatus() == DownloadStatus.COMPLETED) {
                iconStatus.setImageResource(R.drawable.ic_check_circle);
            } else if (item.getStatus() == DownloadStatus.FAILED) {
                iconStatus.setImageResource(R.drawable.ic_error);
            } else {
                iconStatus.setImageResource(R.drawable.ic_download);
            }
            topRow.addView(iconStatus, new LinearLayout.LayoutParams(48, 48));

            LinearLayout titleCol = new LinearLayout(context);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            titleCol.setPadding(20, 0, 16, 0);

            TextView tvName = new TextView(context);
            tvName.setText(item.getFileName());
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(14);
            tvName.setSingleLine(true);
            tvName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            titleCol.addView(tvName);

            topRow.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            // Link update button
            ImageView btnLink = new ImageView(context);
            btnLink.setImageResource(R.drawable.ic_link);
            btnLink.setPadding(12, 12, 12, 12);
            btnLink.setOnClickListener(v -> showUpdateLinkDialog(item));
            topRow.addView(btnLink, new LinearLayout.LayoutParams(64, 64));

            // Pause / Resume button
            ImageView btnPauseResume = new ImageView(context);
            if (item.getStatus().isActive()) {
                btnPauseResume.setImageResource(R.drawable.ic_pause);
                btnPauseResume.setOnClickListener(v -> engine.pause(item.getId()));
            } else if (item.getStatus() == DownloadStatus.COMPLETED) {
                btnPauseResume.setVisibility(View.GONE);
            } else {
                btnPauseResume.setImageResource(R.drawable.ic_play);
                btnPauseResume.setOnClickListener(v -> engine.resume(item.getId()));
            }
            btnPauseResume.setPadding(12, 12, 12, 12);
            topRow.addView(btnPauseResume, new LinearLayout.LayoutParams(64, 64));

            // Delete button
            ImageView btnDelete = new ImageView(context);
            btnDelete.setImageResource(R.drawable.ic_delete);
            btnDelete.setPadding(12, 12, 12, 12);
            btnDelete.setOnClickListener(v -> {
                engine.delete(item.getId(), true);
                Toast.makeText(context, "Download removed", Toast.LENGTH_SHORT).show();
            });
            topRow.addView(btnDelete, new LinearLayout.LayoutParams(64, 64));

            row.addView(topRow);

            // Progress bar
            ProgressBar pb = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(item.getProgressPercentage());
            pb.setPadding(0, 12, 0, 10);
            if (item.getStatus() == DownloadStatus.COMPLETED) {
                pb.setProgress(100);
            }
            row.addView(pb);

            // Bottom Subtitle (sizes, percentage, speed, ETA)
            TextView tvSub = new TextView(context);
            tvSub.setTextSize(12);
            tvSub.setTextColor(Color.GRAY);

            if (item.getStatus() == DownloadStatus.COMPLETED) {
                tvSub.setText(item.getFormattedTotalSize() + " • Completed");
                tvSub.setTextColor(Color.parseColor("#4CAF50"));
            } else if (item.getStatus().isActive()) {
                String speed = item.getFormattedSpeed();
                String eta = item.getFormattedEta();
                tvSub.setText(item.getFormattedDownloadedSize() + " / " + item.getFormattedTotalSize()
                        + " (" + item.getProgressPercentage() + "%) • " + speed + " • ETA: " + eta);
            } else if (item.getStatus() == DownloadStatus.PAUSED) {
                tvSub.setText(item.getFormattedDownloadedSize() + " / " + item.getFormattedTotalSize() + " • Paused");
                tvSub.setTextColor(Color.parseColor("#FFB74D"));
            } else if (item.getStatus() == DownloadStatus.FAILED) {
                tvSub.setText("Failed: " + (item.getErrorMessage() != null ? item.getErrorMessage() : "Unknown error"));
                tvSub.setTextColor(Color.parseColor("#FF5252"));
            }

            row.addView(tvSub);

            // Tap row to open completed file
            if (item.getStatus() == DownloadStatus.COMPLETED) {
                row.setOnClickListener(v -> openFile(item));
            }

            return row;
        }

        private void openFile(DownloadItem item) {
            File f = new File(item.getDestinationPath());
            if (!f.exists()) {
                Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri = Uri.fromFile(f);
                intent.setDataAndType(uri, item.getMimeType() != null ? item.getMimeType() : "*/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(context, "Cannot open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }
}

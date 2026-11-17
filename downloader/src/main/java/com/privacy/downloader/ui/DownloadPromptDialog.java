package com.privacy.downloader.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.privacy.downloader.R;
import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class DownloadPromptDialog {

    public interface OnDownloadConfirmedListener {
        void onConfirmed(DownloadItem item);
    }

    public static void show(Context context, String url, String userAgent, String contentDisposition, String mimeType, String cookies, String referrer, OnDownloadConfirmedListener listener) {
        show(context, url, userAgent, contentDisposition, mimeType, cookies, referrer, null, -1, listener);
    }

    public static void show(Context context, String url, String userAgent, String contentDisposition, String mimeType, String cookies, String referrer, String customFileName, long knownSize, OnDownloadConfirmedListener listener) {
        show(context, url, userAgent, contentDisposition, mimeType, cookies, referrer, customFileName, knownSize, null, -1, listener);
    }

    public static void show(Context context, String url, String userAgent, String contentDisposition, String mimeType, String cookies, String referrer, String customFileName, long knownSize, String audioUrl, long audioBytes, OnDownloadConfirmedListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, DownloaderThemeHelper.getDialogTheme(context));
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_download_prompt, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        EditText editFileName = view.findViewById(R.id.prompt_edit_filename);
        ImageView btnClearName = view.findViewById(R.id.prompt_btn_clear_name);
        TextView textFolderPath = view.findViewById(R.id.prompt_text_folder_path);
        TextView textFreeSpace = view.findViewById(R.id.prompt_text_free_space);
        TextView btnSwitchFolder = view.findViewById(R.id.prompt_btn_switch_folder);

        TextView pillThread1 = view.findViewById(R.id.prompt_pill_thread_1);
        TextView pillThread2 = view.findViewById(R.id.prompt_pill_thread_2);
        TextView pillThread4 = view.findViewById(R.id.prompt_pill_thread_4);
        TextView pillThread8 = view.findViewById(R.id.prompt_pill_thread_8);
        TextView pillThread16 = view.findViewById(R.id.prompt_pill_thread_16);
        TextView textThreadDesc = view.findViewById(R.id.prompt_text_thread_description);

        TextView textUrl = view.findViewById(R.id.prompt_text_url);
        ImageView btnCopyUrl = view.findViewById(R.id.prompt_btn_copy_url);

        LinearLayout layoutDuplicate = view.findViewById(R.id.prompt_layout_duplicate);
        TextView textDuplicateMsg = view.findViewById(R.id.prompt_text_duplicate_msg);
        TextView btnResumeExisting = view.findViewById(R.id.prompt_btn_resume_existing);

        Button btnCancel = view.findViewById(R.id.prompt_btn_cancel);
        Button btnDownload = view.findViewById(R.id.prompt_btn_download);

        // 1. Initial File Name
        String initialFileName = (customFileName != null && !customFileName.trim().isEmpty())
                ? customFileName.trim()
                : extractFileName(contentDisposition, url, mimeType);
        editFileName.setText(initialFileName);
        btnClearName.setOnClickListener(v -> editFileName.setText(""));

        // 2. URL and Copy
        textUrl.setText(url != null ? url : "");
        btnCopyUrl.setOnClickListener(v -> {
            if (url != null) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Download URL", url);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        // 3. Folder & Storage Management
        final File[] targetDir = new File[]{getSafeDownloadsDir(context)};
        updateFolderDisplay(targetDir[0], textFolderPath, textFreeSpace);

        btnSwitchFolder.setText("Browse");
        btnSwitchFolder.setOnClickListener(v -> {
            showFolderChooserDialog(context, targetDir, textFolderPath, textFreeSpace);
        });

        View layoutThreadWarning = view.findViewById(R.id.prompt_layout_thread_warning);
        TextView textThreadWarning = view.findViewById(R.id.prompt_text_thread_warning);

        boolean isVideoOrStream = (url != null && (url.contains("googlevideo.com") || url.contains("vimeocdn.com") || url.contains("vimeo.com") || url.contains("/videoplayback")))
                || com.privacy.downloader.engine.StreamDetector.isStream(url, mimeType)
                || (audioUrl != null && !audioUrl.isEmpty())
                || (mimeType != null && mimeType.contains("video/"));

        // 4. Thread / Segment Selection (Default to 1 for video streams to prevent CDN disconnects)
        final int[] selectedSegments = new int[]{isVideoOrStream ? 1 : 4};
        TextView[] threadPills = new TextView[]{pillThread1, pillThread2, pillThread4, pillThread8, pillThread16};
        int[] threadValues = new int[]{1, 2, 4, 8, 16};

        Runnable updateThreadUI = () -> {
            for (int i = 0; i < threadPills.length; i++) {
                if (threadValues[i] == selectedSegments[0]) {
            boolean isDark = DownloaderThemeHelper.isAppDarkTheme(context);
            threadPills[i].setBackgroundResource(isDark ? R.drawable.bg_pill_thread_selected : R.drawable.bg_pill_thread_selected_light);
            threadPills[i].setTextColor(DownloaderThemeHelper.resolveColor(context, R.attr.textPrimary, isDark ? Color.WHITE : Color.parseColor("#111827")));
                } else {
            boolean isDarkU = DownloaderThemeHelper.isAppDarkTheme(context);
            threadPills[i].setBackgroundResource(isDarkU ? R.drawable.bg_pill_thread_unselected : R.drawable.bg_pill_thread_unselected_light);
            threadPills[i].setTextColor(DownloaderThemeHelper.resolveColor(context, R.attr.textSecondary, isDarkU ? Color.parseColor("#A0A0B0") : Color.parseColor("#6B7280")));
                }
            }

            switch (selectedSegments[0]) {
                case 1:
                    textThreadDesc.setText(isVideoOrStream
                            ? "1 Segment: Single stream (Recommended for Video CDNs to prevent drops)"
                            : "1 Segment: Single HTTP connection (safe for strict hosts)");
                    break;
                case 2:
                    textThreadDesc.setText("2 Segments: Dual streams for light parallel acceleration");
                    break;
                case 4:
                    textThreadDesc.setText("4 Segments: Standard multi-threaded acceleration (Recommended)");
                    break;
                case 8:
                    textThreadDesc.setText("8 Segments: Turbo multi-range parallel downloading");
                    break;
                case 16:
                    textThreadDesc.setText("16 Segments: Extreme acceleration for high-speed fiber links");
                    break;
            }

            if (layoutThreadWarning != null) {
                if (isVideoOrStream && selectedSegments[0] > 1) {
                    layoutThreadWarning.setVisibility(View.VISIBLE);
                } else {
                    layoutThreadWarning.setVisibility(View.GONE);
                }
            }
        };

        for (int i = 0; i < threadPills.length; i++) {
            final int seg = threadValues[i];
            threadPills[i].setOnClickListener(v -> {
                selectedSegments[0] = seg;
                updateThreadUI.run();
            });
        }
        updateThreadUI.run();

        // 5. Incomplete / Duplicate Detection
        DownloadDbHelper db = DownloadDbHelper.getInstance(context);
        List<DownloadItem> existing = db.getAllDownloads();
        DownloadItem matchedIncomplete = null;
        for (DownloadItem it : existing) {
            if (it.getFileName() != null && it.getFileName().equalsIgnoreCase(initialFileName) && it.getStatus() != DownloadStatus.COMPLETED) {
                matchedIncomplete = it;
                break;
            }
        }

        final DownloadItem duplicateItem = matchedIncomplete;
        if (duplicateItem != null) {
            layoutDuplicate.setVisibility(View.VISIBLE);
            textDuplicateMsg.setText("Notice: An incomplete download for this file already exists (" + duplicateItem.getFormattedDownloadedSize() + " saved). You can update the link and resume directly.");
            btnResumeExisting.setOnClickListener(v -> {
                DownloadEngine.getInstance(context).updateUrl(duplicateItem.getId(), url);
                Toast.makeText(context, "Link updated! Resuming partial download...", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        } else {
            layoutDuplicate.setVisibility(View.GONE);
        }

        CheckBox checkWifiOnly = view.findViewById(R.id.prompt_check_wifi_only);
        CheckBox checkSubfolders = view.findViewById(R.id.prompt_check_subfolders);

        DownloadEngine engine = DownloadEngine.getInstance(context);
        checkWifiOnly.setChecked(engine.isWifiOnlyGlobal());
        checkSubfolders.setChecked(engine.isCategorySubfoldersEnabled());

        checkSubfolders.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String currentName = editFileName.getText().toString().trim();
            if (currentName.isEmpty()) currentName = initialFileName;
            File resolved = com.privacy.downloader.util.CategoryOrganizer.getDestinationDirectory(targetDir[0], currentName, isChecked);
            updateFolderDisplay(resolved, textFolderPath, textFreeSpace);
        });

        // 6. Action Listeners
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        String finalUserAgent = (userAgent == null || userAgent.trim().isEmpty()) ?
                com.privacy.downloader.engine.DownloadTask.DEFAULT_USER_AGENT : userAgent;

        btnDownload.setOnClickListener(v -> {
            String fileName = editFileName.getText().toString().trim();
            if (fileName.isEmpty()) {
                fileName = initialFileName;
            }
            if (com.privacy.downloader.engine.StreamDetector.isStream(url, mimeType)) {
                fileName = com.privacy.downloader.engine.StreamDetector.ensureMp4Extension(fileName);
            }

            File baseDir = targetDir[0];
            boolean useSubfolders = checkSubfolders.isChecked();
            File downloadsDir = com.privacy.downloader.util.CategoryOrganizer.getDestinationDirectory(baseDir, fileName, useSubfolders);
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs();
            }

            File destFile = new File(downloadsDir, fileName);
            int count = 1;
            while (destFile.exists()) {
                int dot = fileName.lastIndexOf('.');
                String base = (dot != -1) ? fileName.substring(0, dot) : fileName;
                String ext = (dot != -1) ? fileName.substring(dot) : "";
                destFile = new File(downloadsDir, base + "_" + count + ext);
                count++;
            }

            DownloadItem item = new DownloadItem();
            item.setId(UUID.randomUUID().toString());
            item.setUrl(url);
            item.setOriginalUrl(url);
            item.setFileName(destFile.getName());
            item.setDestinationPath(destFile.getAbsolutePath());
            item.setTempPath(destFile.getAbsolutePath() + ".part");
            item.setMimeType(mimeType);
            item.setNumSegments(selectedSegments[0]);
            if (knownSize > 0) {
                item.setTotalBytes(knownSize);
                item.setSupportsRange(true);
            }
            if (audioUrl != null && !audioUrl.isEmpty()) {
                item.setAudioUrl(audioUrl);
                item.setAudioBytes(audioBytes);
            }
            item.setCookies(cookies);
            item.setUserAgent(finalUserAgent);
            item.setReferrer(referrer);
            item.setWifiOnly(checkWifiOnly.isChecked());
            item.setStatus(DownloadStatus.PENDING);

            DownloadEngine.getInstance(context).enqueue(item);
            Toast.makeText(context, "Download queued: " + item.getFileName(), Toast.LENGTH_SHORT).show();

            if (listener != null) {
                listener.onConfirmed(item);
            }

            dialog.dismiss();
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    public static void updateFolderDisplay(File folder, TextView pathView, TextView spaceView) {
        if (folder == null) return;
        pathView.setText(folder.getAbsolutePath());

        try {
            StatFs stat = new StatFs(folder.getPath());
            long availableBytes = stat.getAvailableBytes();
            spaceView.setText("Free Storage: " + formatBytes(availableBytes) + " Available");
            spaceView.setTextColor(Color.parseColor("#4CAF50"));
        } catch (Exception e) {
            spaceView.setText("Storage: Accessible");
            spaceView.setTextColor(Color.parseColor("#A0A0B0"));
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private static String extractFileName(String contentDisposition, String urlStr, String mimeType) {
        if (com.privacy.downloader.engine.StreamDetector.isStream(urlStr, null)) {
            String baseCandidate = null;
            if (contentDisposition != null) {
                int idx = contentDisposition.toLowerCase().indexOf("filename=");
                if (idx != -1) {
                    baseCandidate = contentDisposition.substring(idx + 9).trim();
                    if (baseCandidate.startsWith("\"") && baseCandidate.endsWith("\"") && baseCandidate.length() > 1) {
                        baseCandidate = baseCandidate.substring(1, baseCandidate.length() - 1);
                    }
                }
            }
            if (baseCandidate == null) {
                try {
                    String path = new java.net.URL(urlStr).getPath();
                    int slash = path.lastIndexOf('/');
                    if (slash != -1 && slash < path.length() - 1) {
                        baseCandidate = path.substring(slash + 1);
                    }
                } catch (Exception ignored) {}
            }
            return com.privacy.downloader.engine.StreamDetector.ensureMp4Extension(baseCandidate);
        }

        if (contentDisposition != null) {
            int idx = contentDisposition.toLowerCase().indexOf("filename=");
            if (idx != -1) {
                String name = contentDisposition.substring(idx + 9).trim();
                if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                    name = name.substring(1, name.length() - 1);
                }
                return name;
            }
        }

        String ext = null;
        if (mimeType != null && !mimeType.isEmpty() && !mimeType.equalsIgnoreCase("application/octet-stream")) {
            ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        }

        if (urlStr != null) {
            if (urlStr.startsWith("blob:") || urlStr.startsWith("data:")) {
                String prefix = urlStr.startsWith("blob:") ? "blob_file_" : "data_file_";
                return prefix + System.currentTimeMillis() + (ext != null ? "." + ext : "");
            }
            try {
                String path = new java.net.URL(urlStr).getPath();
                int slash = path.lastIndexOf('/');
                if (slash != -1 && slash < path.length() - 1) {
                    String extracted = path.substring(slash + 1);
                    if (!extracted.isEmpty()) {
                        if (ext != null && !extracted.contains(".")) {
                            extracted = extracted + "." + ext;
                        }
                        return extracted;
                    }
                }
            } catch (Exception ignored) {}
        }

        return "download_" + System.currentTimeMillis() + (ext != null ? "." + ext : "");
    }

    public static File getSafeDownloadsDir(Context context) {
        String configured = DownloadEngine.getInstance(context).getDefaultDownloadDirectory();
        if (configured != null) {
            File custom = new File(configured);
            if (custom.exists() && custom.canWrite()) {
                return custom;
            }
        }

        File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (publicDir != null && (publicDir.exists() || publicDir.mkdirs())) {
            try {
                File test = new File(publicDir, ".test_perm_" + System.currentTimeMillis());
                if (test.createNewFile()) {
                    test.delete();
                    return publicDir;
                }
            } catch (Throwable ignored) {
            }
        }
        File appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (appDir != null && (appDir.exists() || appDir.mkdirs())) {
            return appDir;
        }
        File internal = new File(context.getFilesDir(), "downloads");
        if (!internal.exists()) internal.mkdirs();
        return internal;
    }

    public static void showFolderChooserDialog(Context context, File[] targetDir, TextView pathView, TextView spaceView) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, DownloaderThemeHelper.getDialogTheme(context));
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_folder_picker, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView textCurrentPath = dialogView.findViewById(R.id.picker_text_current_path);
        ListView listFolders = dialogView.findViewById(R.id.picker_list_folders);
        ImageView btnNewFolder = dialogView.findViewById(R.id.picker_btn_new_folder);
        Button btnCancel = dialogView.findViewById(R.id.picker_btn_cancel);
        Button btnSelect = dialogView.findViewById(R.id.picker_btn_select);

        final File[] currentDir = new File[]{targetDir[0] != null ? targetDir[0] : getSafeDownloadsDir(context)};
        final java.util.List<File> dirEntries = new java.util.ArrayList<>();

        Runnable refreshList = new Runnable() {
            @Override
            public void run() {
                dirEntries.clear();
                File curr = currentDir[0];
                textCurrentPath.setText(curr.getAbsolutePath());

                // Check if can go up
                File parent = curr.getParentFile();
                if (parent != null && parent.canRead() && !curr.getAbsolutePath().equals("/")) {
                    dirEntries.add(parent); // Index 0 represents up ("..")
                }

                File[] children = curr.listFiles();
                if (children != null) {
                    java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File c : children) {
                        if (c.isDirectory() && !c.isHidden() && c.canRead()) {
                            dirEntries.add(c);
                        }
                    }
                }

                android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
                    @Override
                    public int getCount() { return dirEntries.size(); }
                    @Override
                    public Object getItem(int position) { return dirEntries.get(position); }
                    @Override
                    public long getItemId(int position) { return position; }

                    @Override
                    public View getView(int position, View convertView, ViewGroup parentView) {
                        if (convertView == null) {
                            convertView = LayoutInflater.from(context).inflate(R.layout.item_folder_picker, parentView, false);
                        }
                        File file = dirEntries.get(position);
                        ImageView iv = convertView.findViewById(R.id.item_folder_icon);
                        TextView tv = convertView.findViewById(R.id.item_folder_name);

                        if (position == 0 && parent != null && file.equals(parent)) {
                            tv.setText(".. (Parent Folder)");
                            tv.setTextColor(Color.parseColor("#BB86FC"));
                            iv.setImageResource(R.drawable.ic_arrow_back);
                            iv.setColorFilter(Color.parseColor("#BB86FC"));
                        } else {
                            tv.setText(file.getName());
                            boolean isDarkFP = DownloaderThemeHelper.isAppDarkTheme(context);
                            tv.setTextColor(DownloaderThemeHelper.resolveColor(context, R.attr.textPrimary, isDarkFP ? Color.WHITE : Color.parseColor("#111827")));
                            iv.setImageResource(R.drawable.ic_folder);
                            iv.setColorFilter(Color.parseColor("#00E5FF"));
                        }
                        return convertView;
                    }
                };
                listFolders.setAdapter(adapter);
            }
        };

        listFolders.setOnItemClickListener((parentView, itemView, position, id) -> {
            if (position >= 0 && position < dirEntries.size()) {
                File target = dirEntries.get(position);
                if (target.exists() && target.isDirectory()) {
                    currentDir[0] = target;
                    refreshList.run();
                }
            }
        });

        btnNewFolder.setOnClickListener(v -> {
            AlertDialog.Builder newFolderBuilder = new AlertDialog.Builder(context, DownloaderThemeHelper.getDialogTheme(context));
            newFolderBuilder.setTitle("New Folder");
            final EditText input = new EditText(context);
            input.setHint("Folder name");
            boolean isDarkInp = DownloaderThemeHelper.isAppDarkTheme(context);
            input.setTextColor(DownloaderThemeHelper.resolveColor(context, R.attr.textPrimary, isDarkInp ? Color.WHITE : Color.parseColor("#111827")));
            input.setHintTextColor(Color.GRAY);
            boolean isDarkBox = DownloaderThemeHelper.isAppDarkTheme(context);
            input.setBackgroundResource(isDarkBox ? R.drawable.bg_input_box : R.drawable.bg_input_box_light);
            input.setPadding(30, 25, 30, 25);

            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(40, 20, 40, 20);
            container.addView(input);
            newFolderBuilder.setView(container);

            newFolderBuilder.setPositiveButton("Create", (subDlg, which) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    File newDir = new File(currentDir[0], name);
                    if (newDir.mkdir() || newDir.exists()) {
                        currentDir[0] = newDir;
                        refreshList.run();
                    } else {
                        Toast.makeText(context, "Could not create folder", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            newFolderBuilder.setNegativeButton("Cancel", null);
            AlertDialog newFolderDlg = newFolderBuilder.create();
            newFolderDlg.show();
            if (newFolderDlg.getWindow() != null) {
                newFolderDlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSelect.setOnClickListener(v -> {
            targetDir[0] = currentDir[0];
            updateFolderDisplay(targetDir[0], pathView, spaceView);
            dialog.dismiss();
        });

        refreshList.run();
        dialog.show();

        if (dialog.getWindow() != null) {
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}

package com.privacy.downloader.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.privacy.downloader.R;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.engine.DownloadTask;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.model.GrabbedLinkItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class LinkGrabberActivity extends Activity {

    public static final String EXTRA_LINKS_JSON = "extra_links_json";
    public static final String EXTRA_COOKIES = "extra_cookies";
    public static final String EXTRA_USER_AGENT = "extra_user_agent";
    public static final String EXTRA_REFERRER = "extra_referrer";

    private final List<GrabbedLinkItem> allItems = new ArrayList<>();
    private final List<GrabbedLinkItem> filteredItems = new ArrayList<>();

    private ListView listView;
    private GrabbedLinksAdapter adapter;
    private TextView tvTotalCount, tvSelectedCount, btnToggleSelect, emptyState;
    private EditText editSearch;
    private Button btnDownload;

    private TextView pillAll, pillArchives, pillVideos, pillImages, pillDocs, pillAudio, pillOther;
    private String currentCategoryFilter = "All";
    private String currentSearchQuery = "";

    private String pageCookies;
    private String pageUserAgent;
    private String pageReferrer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DownloaderThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_link_grabber);

        pageCookies = getIntent().getStringExtra(EXTRA_COOKIES);
        pageUserAgent = getIntent().getStringExtra(EXTRA_USER_AGENT);
        pageReferrer = getIntent().getStringExtra(EXTRA_REFERRER);

        parseIncomingLinks();
        initViews();
        setupFilterPills();
        applyFilters();
    }

    private void parseIncomingLinks() {
        String jsonStr = getIntent().getStringExtra(EXTRA_LINKS_JSON);
        if (jsonStr == null || jsonStr.isEmpty()) return;

        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String url = obj.optString("url");
                String title = obj.optString("title");
                String type = obj.optString("type");
                String referrer = obj.optString("referrer", null);

                if (url != null && !url.trim().isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    allItems.add(new GrabbedLinkItem(url, title, type, referrer));
                }
            }
        } catch (Exception ignored) {}
    }

    private void initViews() {
        findViewById(R.id.grabber_btn_back).setOnClickListener(v -> finish());

        tvTotalCount = findViewById(R.id.grabber_tv_count);
        tvTotalCount.setText(allItems.size() + " links discovered");

        tvSelectedCount = findViewById(R.id.grabber_tv_selected_count);
        btnToggleSelect = findViewById(R.id.grabber_btn_toggle_select);
        emptyState = findViewById(R.id.grabber_empty_state);
        listView = findViewById(R.id.grabber_list_links);
        btnDownload = findViewById(R.id.grabber_btn_download);

        editSearch = findViewById(R.id.grabber_edit_search);
        findViewById(R.id.grabber_btn_clear_search).setOnClickListener(v -> {
            editSearch.setText("");
            currentSearchQuery = "";
            applyFilters();
        });

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s.toString().trim().toLowerCase(Locale.US);
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnToggleSelect.setOnClickListener(v -> {
            boolean anyUnselected = false;
            for (GrabbedLinkItem item : filteredItems) {
                if (!item.isSelected()) {
                    anyUnselected = true;
                    break;
                }
            }
            for (GrabbedLinkItem item : filteredItems) {
                item.setSelected(anyUnselected);
            }
            adapter.notifyDataSetChanged();
            updateSelectedSummary();
        });

        btnDownload.setOnClickListener(v -> queueSelectedDownloads());

        View btnVerify = findViewById(R.id.grabber_btn_verify);
        if (btnVerify != null) {
            btnVerify.setOnClickListener(v -> verifyLinksAsync());
        }

        adapter = new GrabbedLinksAdapter();
        listView.setAdapter(adapter);
    }

    private void setupFilterPills() {
        pillAll = findViewById(R.id.grabber_pill_all);
        pillArchives = findViewById(R.id.grabber_pill_archives);
        pillVideos = findViewById(R.id.grabber_pill_videos);
        pillImages = findViewById(R.id.grabber_pill_images);
        pillDocs = findViewById(R.id.grabber_pill_docs);
        pillAudio = findViewById(R.id.grabber_pill_audio);
        pillOther = findViewById(R.id.grabber_pill_other);

        pillAll.setOnClickListener(v -> selectCategory("All"));
        pillArchives.setOnClickListener(v -> selectCategory("Archives"));
        pillVideos.setOnClickListener(v -> selectCategory("Videos"));
        pillImages.setOnClickListener(v -> selectCategory("Images"));
        pillDocs.setOnClickListener(v -> selectCategory("Documents"));
        pillAudio.setOnClickListener(v -> selectCategory("Audio"));
        pillOther.setOnClickListener(v -> selectCategory("Links"));
    }

    private void selectCategory(String cat) {
        currentCategoryFilter = cat;
        // When changing tabs, clear selection on any items outside the newly selected tab
        if (!"All".equalsIgnoreCase(currentCategoryFilter)) {
            for (GrabbedLinkItem item : allItems) {
                if (!item.getCategory().equalsIgnoreCase(currentCategoryFilter)) {
                    item.setSelected(false);
                }
            }
        }
        updatePillsUI();
        applyFilters();
    }

    private void updatePillsUI() {
        TextView[] pills = new TextView[]{pillAll, pillArchives, pillVideos, pillImages, pillDocs, pillAudio, pillOther};
        String[] cats = new String[]{"All", "Archives", "Videos", "Images", "Documents", "Audio", "Links"};

        for (int i = 0; i < pills.length; i++) {
            if (cats[i].equalsIgnoreCase(currentCategoryFilter)) {
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

    private void applyFilters() {
        filteredItems.clear();
        for (GrabbedLinkItem item : allItems) {
            boolean matchesCat = "All".equalsIgnoreCase(currentCategoryFilter) ||
                    item.getCategory().equalsIgnoreCase(currentCategoryFilter);
            if (!matchesCat) {
                // Ensure items not matching this category tab are not selected
                if (!"All".equalsIgnoreCase(currentCategoryFilter)) {
                    item.setSelected(false);
                }
                continue;
            }

            if (!currentSearchQuery.isEmpty()) {
                boolean matchesName = item.getFileName().toLowerCase(Locale.US).contains(currentSearchQuery);
                boolean matchesUrl = item.getUrl().toLowerCase(Locale.US).contains(currentSearchQuery);
                if (!matchesName && !matchesUrl) {
                    item.setSelected(false);
                    continue;
                }
            }

            filteredItems.add(item);
        }

        if (filteredItems.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            listView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }

        adapter.notifyDataSetChanged();
        updateSelectedSummary();
    }

    private void updateSelectedSummary() {
        // Selection count is strictly scoped to items displayed in the current tab/filter
        int count = 0;
        for (GrabbedLinkItem item : filteredItems) {
            if (item.isSelected()) count++;
        }
        tvSelectedCount.setText("Selected: " + count + " / " + filteredItems.size());
        btnDownload.setEnabled(count > 0);
        btnDownload.setAlpha(count > 0 ? 1.0f : 0.5f);

        boolean allFilteredSelected = !filteredItems.isEmpty();
        for (GrabbedLinkItem item : filteredItems) {
            if (!item.isSelected()) {
                allFilteredSelected = false;
                break;
            }
        }
        btnToggleSelect.setText(allFilteredSelected ? "Deselect All" : "Select All");
    }

    private void queueSelectedDownloads() {
        // Only download selected items in the currently active tab/filter
        List<GrabbedLinkItem> toQueue = new ArrayList<>();
        for (GrabbedLinkItem item : filteredItems) {
            if (item.isSelected()) {
                toQueue.add(item);
            }
        }

        if (toQueue.isEmpty()) {
            Toast.makeText(this, "No links selected in this tab", Toast.LENGTH_SHORT).show();
            return;
        }

        DownloadEngine engine = DownloadEngine.getInstance(this);
        File downloadsDir = DownloadPromptDialog.getSafeDownloadsDir(this);
        String ua = (pageUserAgent != null && !pageUserAgent.trim().isEmpty())
                ? pageUserAgent
                : DownloadTask.DEFAULT_USER_AGENT;

        int queuedCount = 0;
        for (GrabbedLinkItem grabbed : toQueue) {
            String fileName = grabbed.getFileName();
            if (com.privacy.downloader.engine.StreamDetector.isStream(grabbed.getUrl(), null)) {
                fileName = com.privacy.downloader.engine.StreamDetector.ensureMp4Extension(fileName);
            }
            File destFile = new File(downloadsDir, fileName);

            int collision = 1;
            while (destFile.exists()) {
                int dot = fileName.lastIndexOf('.');
                String base = (dot != -1) ? fileName.substring(0, dot) : fileName;
                String ext = (dot != -1) ? fileName.substring(dot) : "";
                destFile = new File(downloadsDir, base + "_" + collision + ext);
                collision++;
            }

            DownloadItem item = new DownloadItem();
            item.setId(UUID.randomUUID().toString());
            item.setUrl(grabbed.getUrl());
            item.setOriginalUrl(grabbed.getUrl());
            item.setFileName(destFile.getName());
            item.setDestinationPath(destFile.getAbsolutePath());
            item.setTempPath(destFile.getAbsolutePath() + ".part");
            item.setNumSegments(4);
            item.setCookies(pageCookies);
            item.setUserAgent(ua);
            String ref = (grabbed.getReferrer() != null && !grabbed.getReferrer().trim().isEmpty())
                    ? grabbed.getReferrer()
                    : pageReferrer;
            item.setReferrer(ref);
            item.setStatus(DownloadStatus.PENDING);

            engine.enqueue(item);
            queuedCount++;
        }

        Toast.makeText(this, "Queued " + queuedCount + " downloads in background", Toast.LENGTH_SHORT).show();
        finish();
    }

    private class GrabbedLinksAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredItems.size();
        }

        @Override
        public GrabbedLinkItem getItem(int position) {
            return filteredItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(LinkGrabberActivity.this).inflate(R.layout.item_grabbed_link, parent, false);
                holder = new ViewHolder();
                holder.checkBox = convertView.findViewById(R.id.grabbed_checkbox);
                holder.icon = convertView.findViewById(R.id.grabbed_icon);
                holder.title = convertView.findViewById(R.id.grabbed_title);
                holder.url = convertView.findViewById(R.id.grabbed_url);
                holder.badge = convertView.findViewById(R.id.grabbed_badge);
                holder.status = convertView.findViewById(R.id.grabbed_status);
                holder.size = convertView.findViewById(R.id.grabbed_size);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            GrabbedLinkItem item = getItem(position);
            holder.title.setText((item.getTitle() != null && !item.getTitle().trim().isEmpty()) ? item.getTitle() : item.getFileName());
            holder.url.setText(item.getUrl());
            holder.badge.setText(item.getCategory());

            // Set icon by category
            switch (item.getCategory()) {
                case "Videos":
                    holder.icon.setImageResource(R.drawable.ic_file_video);
                    break;
                case "Audio":
                    holder.icon.setImageResource(R.drawable.ic_file_audio);
                    break;
                case "Images":
                    holder.icon.setImageResource(R.drawable.ic_file_image);
                    break;
                case "Archives":
                    holder.icon.setImageResource(R.drawable.ic_file_archive);
                    break;
                case "Documents":
                    holder.icon.setImageResource(R.drawable.ic_file_doc);
                    break;
                default:
                    holder.icon.setImageResource(R.drawable.ic_link);
                    break;
            }

            holder.checkBox.setOnCheckedChangeListener(null);
            holder.checkBox.setChecked(item.isSelected());
            holder.checkBox.setOnCheckedChangeListener((btn, isChecked) -> {
                item.setSelected(isChecked);
                updateSelectedSummary();
            });

            convertView.setOnClickListener(v -> {
                item.setSelected(!item.isSelected());
                holder.checkBox.setChecked(item.isSelected());
                updateSelectedSummary();
            });

            String st = item.getStatus();
            if (holder.status != null) {
                if ("ONLINE".equals(st)) {
                    holder.status.setVisibility(View.VISIBLE);
                    holder.status.setText("ONLINE");
                    holder.status.setTextColor(Color.parseColor("#4CAF50"));
                } else if ("OFFLINE".equals(st)) {
                    holder.status.setVisibility(View.VISIBLE);
                    holder.status.setText("OFFLINE");
                    holder.status.setTextColor(Color.parseColor("#FF5252"));
                } else if ("CHECKING".equals(st)) {
                    holder.status.setVisibility(View.VISIBLE);
                    holder.status.setText("CHECKING...");
                    holder.status.setTextColor(Color.parseColor("#FFB74D"));
                } else {
                    holder.status.setVisibility(View.GONE);
                }
            }

            if (holder.size != null) {
                if (item.getFileSize() > 0) {
                    holder.size.setVisibility(View.VISIBLE);
                    holder.size.setText(formatFileSize(item.getFileSize()));
                } else {
                    holder.size.setVisibility(View.GONE);
                }
            }

            return convertView;
        }
    }

    private boolean isProbing = false;

    private void verifyLinksAsync() {
        if (isProbing) {
            Toast.makeText(this, "Verification already in progress", Toast.LENGTH_SHORT).show();
            return;
        }
        if (allItems.isEmpty()) {
            Toast.makeText(this, "No links to verify", Toast.LENGTH_SHORT).show();
            return;
        }

        isProbing = true;
        TextView btnVerify = findViewById(R.id.grabber_btn_verify);
        if (btnVerify != null) {
            btnVerify.setEnabled(false);
            btnVerify.setText("Checking...");
        }

        for (GrabbedLinkItem item : allItems) {
            item.setStatus("CHECKING");
        }
        adapter.notifyDataSetChanged();

        new Thread(() -> {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            java.util.concurrent.atomic.AtomicInteger onlineCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger offlineCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(allItems.size());

            for (GrabbedLinkItem item : allItems) {
                pool.execute(() -> {
                    try {
                        java.net.URL url = new java.net.URL(item.getUrl());
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("HEAD");
                        conn.setConnectTimeout(6000);
                        conn.setReadTimeout(6000);
                        conn.setInstanceFollowRedirects(true);
                        if (pageUserAgent != null && !pageUserAgent.trim().isEmpty()) {
                            conn.setRequestProperty("User-Agent", pageUserAgent);
                        }
                        if (pageCookies != null && !pageCookies.trim().isEmpty()) {
                            conn.setRequestProperty("Cookie", pageCookies);
                        }
                        if (pageReferrer != null && !pageReferrer.trim().isEmpty()) {
                            conn.setRequestProperty("Referer", pageReferrer);
                        }

                        int code = conn.getResponseCode();
                        long len = conn.getContentLengthLong();
                        conn.disconnect();

                        if (code >= 200 && code < 400) {
                            item.setStatus("ONLINE");
                            if (len > 0) item.setFileSize(len);
                            onlineCount.incrementAndGet();
                        } else {
                            item.setStatus("OFFLINE");
                            offlineCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        item.setStatus("OFFLINE");
                        offlineCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                        runOnUiThread(() -> adapter.notifyDataSetChanged());
                    }
                });
            }

            try {
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            pool.shutdown();

            runOnUiThread(() -> {
                isProbing = false;
                if (btnVerify != null) {
                    btnVerify.setEnabled(true);
                    btnVerify.setText("Verify");
                }
                adapter.notifyDataSetChanged();
                Toast.makeText(LinkGrabberActivity.this,
                        "Verification complete: " + onlineCount.get() + " online, " + offlineCount.get() + " offline",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static class ViewHolder {
        CheckBox checkBox;
        ImageView icon;
        TextView title;
        TextView url;
        TextView badge;
        TextView status;
        TextView size;
    }
}

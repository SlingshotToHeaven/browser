package com.privacy.browser.bookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
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
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.privacy.browser.MainActivity;
import com.privacy.browser.R;
import com.privacy.browser.ThemeManager;
import com.privacy.browser.favicon.FaviconManager;
import com.privacy.browser.search.SearchEngineManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import com.privacy.browser.ThemeManager;
import java.util.concurrent.Executors;

public class BookmarksActivity extends Activity {

    public static class FolderCrumb {
        final long id;
        final String name;

        FolderCrumb(long id, String name) {
            this.id = id;
            this.name = name;
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

    private BookmarkDbHelper dbHelper;
    private ListView listView;
    private GridView gridView;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private TextView textCount;
    private TextView headerTitle;
    private View btnNewFolder;
    private ImageView btnToggleView;
    private EditText editSearch;
    private ImageView btnClearSearch;
    private LinearLayout breadcrumbLayout;

    // Multi-Selection Toolbar UI & State
    private View toolbarStandard;
    private View toolbarSelection;
    private ImageView btnCloseSelection;
    private TextView textSelectedCount;
    private TextView btnSelectAll;
    private TextView btnMoveSelected;
    private ImageView btnDeleteSelected;

    private boolean isSelectionMode = false;
    private boolean isGridView = true; // Default to modern 2-column Card Grid
    private final Set<Long> selectedIds = new HashSet<>();

    private final Stack<FolderCrumb> folderStack = new Stack<>();
    private long currentFolderId = 0;

    private final List<BookmarkModel> allItems = new ArrayList<>();
    private final List<BookmarkModel> filteredItems = new ArrayList<>();
    private BookmarksAdapter adapter;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bookmarks);

        dbHelper = BookmarkDbHelper.getInstance(this);

        folderStack.push(new FolderCrumb(0, "Bookmarks"));

        initViews();
        loadItems();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadItems();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dbExecutor.shutdown();
    }

    private void initViews() {
        toolbarStandard = findViewById(R.id.bookmarks_toolbar);
        toolbarSelection = findViewById(R.id.bookmarks_selection_toolbar);
        btnCloseSelection = findViewById(R.id.bookmarks_btn_close_selection);
        textSelectedCount = findViewById(R.id.bookmarks_text_selected_count);
        btnSelectAll = findViewById(R.id.bookmarks_btn_select_all);
        btnMoveSelected = findViewById(R.id.bookmarks_btn_move_selected);
        btnDeleteSelected = findViewById(R.id.bookmarks_btn_delete_selected);

        findViewById(R.id.bookmarks_btn_back).setOnClickListener(v -> handleBackNavigation());

        if (btnCloseSelection != null) {
            btnCloseSelection.setOnClickListener(v -> exitSelectionMode());
        }

        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        }

        if (btnMoveSelected != null) {
            btnMoveSelected.setOnClickListener(v -> showMoveDestinationPicker(new ArrayList<>(selectedIds)));
        }

        if (btnDeleteSelected != null) {
            btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        }

        headerTitle = findViewById(R.id.bookmarks_header_title);
        textCount = findViewById(R.id.bookmarks_text_count);
        btnNewFolder = findViewById(R.id.bookmarks_btn_new_folder);
        btnToggleView = findViewById(R.id.bookmarks_btn_toggle_view);
        editSearch = findViewById(R.id.bookmarks_edit_search);
        btnClearSearch = findViewById(R.id.bookmarks_btn_clear_search);
        breadcrumbLayout = findViewById(R.id.bookmarks_breadcrumb_layout);

        listView = findViewById(R.id.bookmarks_list);
        gridView = findViewById(R.id.bookmarks_grid);
        emptyView = findViewById(R.id.bookmarks_empty_view);
        emptyTitle = findViewById(R.id.bookmarks_empty_title);
        emptyDesc = findViewById(R.id.bookmarks_empty_desc);

        adapter = new BookmarksAdapter();
        listView.setAdapter(adapter);
        gridView.setAdapter(adapter);

        updateViewModeLayout();

        btnToggleView.setOnClickListener(v -> {
            isGridView = !isGridView;
            updateViewModeLayout();
            adapter.notifyDataSetChanged();
        });

        btnNewFolder.setOnClickListener(v -> showNewFolderDialog());

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
                filterItems(q);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        updateBreadcrumbView();
    }

    private void updateViewModeLayout() {
        if (isGridView) {
            gridView.setVisibility(filteredItems.isEmpty() ? View.GONE : View.VISIBLE);
            listView.setVisibility(View.GONE);
            btnToggleView.setImageResource(R.drawable.ic_view_list);
        } else {
            listView.setVisibility(filteredItems.isEmpty() ? View.GONE : View.VISIBLE);
            gridView.setVisibility(View.GONE);
            btnToggleView.setImageResource(R.drawable.ic_view_grid);
        }
    }

    private void handleBackNavigation() {
        if (isSelectionMode) {
            exitSelectionMode();
            return;
        }

        if (folderStack.size() > 1) {
            folderStack.pop();
            currentFolderId = folderStack.peek().id;
            updateBreadcrumbView();
            loadItems();
            return;
        }

        finish();
    }

    @Override
    public void onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode();
            return;
        }

        if (folderStack.size() > 1) {
            folderStack.pop();
            currentFolderId = folderStack.peek().id;
            updateBreadcrumbView();
            loadItems();
            return;
        }

        super.onBackPressed();
    }

    private void navigateToFolder(long folderId, String folderName) {
        if (isSelectionMode) exitSelectionMode();
        folderStack.push(new FolderCrumb(folderId, folderName));
        currentFolderId = folderId;
        updateBreadcrumbView();
        loadItems();
    }

    private void updateBreadcrumbView() {
        if (breadcrumbLayout == null) return;
        breadcrumbLayout.removeAllViews();

        FolderCrumb current = folderStack.peek();
        if (headerTitle != null) {
            headerTitle.setText(current.name);
        }

        for (int i = 0; i < folderStack.size(); i++) {
            final int index = i;
            FolderCrumb crumb = folderStack.get(i);

            boolean isDark = ThemeManager.isAppDarkTheme(this);
            int activeColor = isDark ? Color.parseColor("#00E5FF") : Color.parseColor("#007AFF");
            int inactiveColor = isDark ? Color.parseColor("#8E8EA8") : Color.parseColor("#6B7280");

            TextView tv = new TextView(this);
            tv.setText(crumb.name);
            tv.setTextSize(11.5f);
            tv.setTextColor(i == folderStack.size() - 1 ? activeColor : inactiveColor);
            tv.setPadding(8, 0, 8, 0);

            if (i < folderStack.size() - 1) {
                tv.setOnClickListener(v -> {
                    while (folderStack.size() > index + 1) {
                        folderStack.pop();
                    }
                    currentFolderId = folderStack.peek().id;
                    updateBreadcrumbView();
                    loadItems();
                });
            }

            breadcrumbLayout.addView(tv);

            if (i < folderStack.size() - 1) {
                TextView sep = new TextView(this);
                sep.setText(">");
                sep.setTextSize(10f);
                sep.setTextColor(isDark ? Color.parseColor("#44465E") : Color.parseColor("#D1D5DB"));
                breadcrumbLayout.addView(sep);
            }
        }
    }

    private void loadItems() {
        final long folderId = currentFolderId;
        dbExecutor.execute(() -> {
            List<BookmarkModel> loaded = dbHelper.getItemsInFolder(folderId);
            mainHandler.post(() -> {
                allItems.clear();
                allItems.addAll(loaded);
                filterItems(editSearch.getText() != null ? editSearch.getText().toString().trim() : "");
            });
        });
    }

    private void filterItems(String query) {
        filteredItems.clear();
        if (query == null || query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String lower = query.toLowerCase(Locale.US);
            for (BookmarkModel bm : allItems) {
                if (bm.getTitle().toLowerCase(Locale.US).contains(lower) ||
                    (bm.getUrl() != null && bm.getUrl().toLowerCase(Locale.US).contains(lower))) {
                    filteredItems.add(bm);
                }
            }
        }

        adapter.notifyDataSetChanged();
        updateUiState(query);
    }

    private void updateUiState(String currentQuery) {
        int total = allItems.size();
        int shown = filteredItems.size();

        if (currentQuery != null && !currentQuery.isEmpty()) {
            textCount.setText(shown + " of " + total + " matching");
        } else {
            textCount.setText(total + (total == 1 ? " item" : " items"));
        }

        if (shown == 0) {
            listView.setVisibility(View.GONE);
            gridView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            if (total == 0) {
                emptyTitle.setText(currentFolderId == 0 ? "No saved bookmarks" : "This folder is empty");
                emptyDesc.setText(currentFolderId == 0 ? "Webpages you bookmark will appear here." : "Tap '+ Folder' above to create subfolders or move items here.");
            } else {
                emptyTitle.setText("No matching bookmarks");
                emptyDesc.setText("Try searching with different keywords.");
            }
        } else {
            emptyView.setVisibility(View.GONE);
            updateViewModeLayout();
        }
    }

    private void enterSelectionMode(long initialId) {
        isSelectionMode = true;
        selectedIds.clear();
        selectedIds.add(initialId);
        updateSelectionToolbar();
        adapter.notifyDataSetChanged();
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        selectedIds.clear();
        if (toolbarSelection != null) toolbarSelection.setVisibility(View.GONE);
        if (toolbarStandard != null) toolbarStandard.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void toggleItemSelection(long id) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id);
        } else {
            selectedIds.add(id);
        }
        if (selectedIds.isEmpty()) {
            exitSelectionMode();
        } else {
            updateSelectionToolbar();
            adapter.notifyDataSetChanged();
        }
    }

    private void toggleSelectAll() {
        if (selectedIds.size() == filteredItems.size()) {
            selectedIds.clear();
            exitSelectionMode();
        } else {
            selectedIds.clear();
            for (BookmarkModel bm : filteredItems) {
                selectedIds.add(bm.getId());
            }
            updateSelectionToolbar();
            adapter.notifyDataSetChanged();
        }
    }

    private void updateSelectionToolbar() {
        if (toolbarStandard != null) toolbarStandard.setVisibility(View.GONE);
        if (toolbarSelection != null) toolbarSelection.setVisibility(View.VISIBLE);

        int count = selectedIds.size();
        if (textSelectedCount != null) {
            textSelectedCount.setText(count + " selected");
        }

        if (btnSelectAll != null) {
            boolean allSelected = !filteredItems.isEmpty() && count == filteredItems.size();
            btnSelectAll.setText(allSelected ? "Deselect All" : "Select All");
        }
    }

    private void confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return;
        int count = selectedIds.size();

        new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this))
                .setTitle("Delete Selected Items")
                .setMessage("Are you sure you want to delete " + count + " selected " + (count == 1 ? "item" : "items") + "? Folders will be removed along with their contents.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    dbExecutor.execute(() -> {
                        for (Long id : selectedIds) {
                            BookmarkPreviewManager.getInstance(this).deletePreview(id);
                        }
                        dbHelper.deleteItems(new ArrayList<>(selectedIds));
                        mainHandler.post(() -> {
                            Toast.makeText(this, count + " items deleted", Toast.LENGTH_SHORT).show();
                            exitSelectionMode();
                            loadItems();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openBookmark(String url, boolean openInNewTab, boolean openInIncognito) {
        if (url == null || url.isEmpty()) return;
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

    private void showNewFolderDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
        builder.setTitle("New Folder");

        final EditText input = new EditText(this);
        input.setHint("Folder name");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#66687A"));
        input.setSingleLine(true);
        input.setPadding(40, 30, 40, 30);
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText() != null ? input.getText().toString().trim() : "";
            if (folderName.isEmpty()) folderName = "New Folder";
            final String finalName = folderName;
            dbExecutor.execute(() -> {
                dbHelper.createFolder(finalName, currentFolderId);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Folder created", Toast.LENGTH_SHORT).show();
                    loadItems();
                });
            });
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showEditBookmarkDialog(BookmarkModel item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_bookmark, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        ImageView iconView = view.findViewById(R.id.edit_dlg_icon);
        TextView titleView = view.findViewById(R.id.edit_dlg_title);
        View chipFolder = view.findViewById(R.id.edit_dlg_chip_folder);
        TextView textFolder = view.findViewById(R.id.edit_dlg_text_folder);
        View previewContainer = view.findViewById(R.id.edit_dlg_preview_container);
        View previewFrame = view.findViewById(R.id.edit_dlg_preview_frame);
        ImageView previewImg = view.findViewById(R.id.edit_dlg_preview_img);
        ImageView previewFallback = view.findViewById(R.id.edit_dlg_preview_fallback);
        ImageView btnRemovePreview = view.findViewById(R.id.edit_dlg_btn_remove_preview);
        ImageView btnChangePreview = view.findViewById(R.id.edit_dlg_btn_change_preview);
        EditText editTitle = view.findViewById(R.id.edit_dlg_input_title);
        ImageView searchTitle = view.findViewById(R.id.edit_dlg_search_title);
        ImageView clearTitle = view.findViewById(R.id.edit_dlg_clear_title);
        View urlWrapper = view.findViewById(R.id.edit_dlg_url_wrapper);
        EditText editUrl = view.findViewById(R.id.edit_dlg_input_url);
        ImageView copyUrl = view.findViewById(R.id.edit_dlg_copy_url);
        ImageView clearUrl = view.findViewById(R.id.edit_dlg_clear_url);
        Button btnCancel = view.findViewById(R.id.edit_dlg_btn_cancel);
        Button btnSave = view.findViewById(R.id.edit_dlg_btn_save);

        if (editTitle != null) {
            editTitle.setText(item.getTitle() != null ? item.getTitle() : "");
            if (clearTitle != null) {
                clearTitle.setVisibility(editTitle.getText().length() > 0 ? View.VISIBLE : View.GONE);
                clearTitle.setOnClickListener(v -> editTitle.setText(""));
                editTitle.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        clearTitle.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                    }
                    @Override public void afterTextChanged(Editable s) {}
                });
            }
        }

        if (searchTitle != null) {
            searchTitle.setOnClickListener(v -> {
                String currentTitleText = editTitle != null ? editTitle.getText().toString().trim() : "";
                if (!currentTitleText.isEmpty()) {
                    dialog.dismiss();
                    String searchUrl = SearchEngineManager.buildSearchUrl(this, currentTitleText);
                    openBookmark(searchUrl, false, false);
                } else {
                    Toast.makeText(this, "Enter title to search", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Folder destination chip
        if (chipFolder != null && textFolder != null) {
            dbExecutor.execute(() -> {
                String folderName = dbHelper.getFolderName(item.getParentFolderId());
                mainHandler.post(() -> textFolder.setText(folderName));
            });
            chipFolder.setOnClickListener(v -> {
                dialog.dismiss();
                showMoveDestinationPicker(Collections.singletonList(item.getId()));
            });
        }

        if (item.isFolder()) {
            if (iconView != null) {
                iconView.setImageResource(R.drawable.ic_bookmarks_folder);
                iconView.setImageTintList(ColorStateList.valueOf(Color.parseColor("#FFB300")));
            }
            if (titleView != null) {
                titleView.setText("Rename Folder");
            }
            if (previewContainer != null) {
                previewContainer.setVisibility(View.GONE);
            }
            if (urlWrapper != null) {
                urlWrapper.setVisibility(View.GONE);
            }
        } else {
            if (iconView != null) {
                iconView.setImageResource(R.drawable.ic_bookmark);
                iconView.setImageTintList(ColorStateList.valueOf(Color.parseColor("#00E5FF")));
            }
            if (titleView != null) {
                titleView.setText("Edit Bookmark");
            }
            if (previewContainer != null) {
                previewContainer.setVisibility(View.VISIBLE);
            }
            if (urlWrapper != null) {
                urlWrapper.setVisibility(View.VISIBLE);
            }
            if (editUrl != null) {
                editUrl.setText(item.getUrl() != null ? item.getUrl() : "");
                if (clearUrl != null) {
                    clearUrl.setVisibility(editUrl.getText().length() > 0 ? View.VISIBLE : View.GONE);
                    clearUrl.setOnClickListener(v -> editUrl.setText(""));
                    editUrl.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                            clearUrl.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                        }
                        @Override public void afterTextChanged(Editable s) {}
                    });
                }
            }

            if (copyUrl != null) {
                copyUrl.setOnClickListener(v -> {
                    String currentUrlText = editUrl != null ? editUrl.getText().toString().trim() : "";
                    if (!currentUrlText.isEmpty()) {
                        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null) {
                            cb.setPrimaryClip(ClipData.newPlainText("URL", currentUrlText));
                            Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            // Size preview container to full available width
            if (previewFrame != null) {
                previewFrame.post(() -> {
                    int availableWidth = previewFrame.getWidth();
                    if (availableWidth > 0) {
                        ViewGroup.LayoutParams lp = previewFrame.getLayoutParams();
                        if (lp != null && lp.height != availableWidth) {
                            lp.height = availableWidth;
                            previewFrame.setLayoutParams(lp);
                        }
                    }
                });
            }

            // Load Preview Snapshot
            if (previewImg != null) {
                Bitmap preview = BookmarkPreviewManager.getInstance(this).getCachedPreview(item.getId());
                if (preview != null) {
                    previewImg.setImageBitmap(preview);
                    previewImg.setVisibility(View.VISIBLE);
                    if (previewFallback != null) previewFallback.setVisibility(View.GONE);
                    if (btnRemovePreview != null) btnRemovePreview.setVisibility(View.VISIBLE);
                } else {
                    previewImg.setVisibility(View.GONE);
                    if (previewFallback != null) previewFallback.setVisibility(View.VISIBLE);
                    if (btnRemovePreview != null) btnRemovePreview.setVisibility(View.GONE);
                }
            }

            if (btnRemovePreview != null) {
                btnRemovePreview.setOnClickListener(v -> {
                    BookmarkPreviewManager.getInstance(this).deletePreview(item.getId());
                    if (previewImg != null) previewImg.setVisibility(View.GONE);
                    if (previewFallback != null) previewFallback.setVisibility(View.VISIBLE);
                    btnRemovePreview.setVisibility(View.GONE);
                    Toast.makeText(this, "Cover reset", Toast.LENGTH_SHORT).show();
                    loadItems();
                });
            }

            if (btnChangePreview != null) {
                btnChangePreview.setOnClickListener(v -> {
                    dialog.dismiss();
                    launchPreviewRecapture(item);
                });
            }
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String title = editTitle != null && editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
                String url = editUrl != null && editUrl.getText() != null ? editUrl.getText().toString().trim() : "";

                if (title.isEmpty()) {
                    title = item.isFolder() ? "Untitled Folder" : "Untitled Bookmark";
                }

                final String finalTitle = title;
                final String finalUrl = url;

                dbExecutor.execute(() -> {
                    if (item.isFolder()) {
                        dbHelper.updateFolderName(item.getId(), finalTitle);
                    } else {
                        dbHelper.updateBookmark(item.getId(), finalTitle, finalUrl);
                    }
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadItems();
                    });
                });
            });
        }

        dialog.show();
    }

    private void showMoveDestinationPicker(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return;
        dbExecutor.execute(() -> {
            List<BookmarkModel> folders = dbHelper.getAllFolders();
            mainHandler.post(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
                View sheetView = LayoutInflater.from(this).inflate(R.layout.dialog_context_menu_sheet, null);
                builder.setView(sheetView);

                AlertDialog dialog = builder.create();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
                    dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                }

                ImageView headerIcon = sheetView.findViewById(R.id.ctx_header_icon);
                if (headerIcon != null) {
                    headerIcon.setImageResource(R.drawable.ic_bookmarks_folder);
                    headerIcon.setColorFilter(Color.parseColor("#FFB300"));
                }

                TextView title = sheetView.findViewById(R.id.ctx_header_title);
                if (title != null) {
                    title.setText("Move to Folder (" + itemIds.size() + (itemIds.size() == 1 ? " item)" : " items)"));
                }

                LinearLayout container = sheetView.findViewById(R.id.ctx_actions_container);
                if (container != null) {
                    // 1. Root Folder destination option
                    addFolderDestinationRow(container, "Bookmarks (Root)", 0L, () -> {
                        dialog.dismiss();
                        executeMoveItems(itemIds, 0L);
                    });

                    // 2. Subfolders (excluding any items currently being moved)
                    for (BookmarkModel f : folders) {
                        if (!itemIds.contains(f.getId())) {
                            addFolderDestinationRow(container, f.getTitle(), f.getId(), () -> {
                                dialog.dismiss();
                                executeMoveItems(itemIds, f.getId());
                            });
                        }
                    }
                }

                Button btnDismiss = sheetView.findViewById(R.id.ctx_btn_dismiss);
                if (btnDismiss != null) {
                    btnDismiss.setOnClickListener(v -> dialog.dismiss());
                }

                dialog.show();
            });
        });
    }

    private void addFolderDestinationRow(LinearLayout parent, String folderTitle, long folderId, Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_context_menu_action, parent, false);
        TextView tv = row.findViewById(R.id.ctx_item_text);
        ImageView iv = row.findViewById(R.id.ctx_item_icon);
        ImageView chevron = row.findViewById(R.id.ctx_item_chevron);

        tv.setText(folderTitle);
        if (iv != null) {
            iv.setImageResource(R.drawable.ic_bookmarks_folder);
            iv.setColorFilter(Color.parseColor("#FFB300"));
        }
        if (chevron != null) {
            chevron.setVisibility(View.VISIBLE);
        }

        row.setOnClickListener(v -> action.run());
        parent.addView(row);
    }

    private void executeMoveItems(List<Long> itemIds, long targetFolderId) {
        dbExecutor.execute(() -> {
            dbHelper.moveToFolder(itemIds, targetFolderId);
            mainHandler.post(() -> {
                Toast.makeText(this, itemIds.size() + (itemIds.size() == 1 ? " item moved" : " items moved"), Toast.LENGTH_SHORT).show();
                exitSelectionMode();
                loadItems();
            });
        });
    }

    private void launchPreviewRecapture(BookmarkModel item) {
        Intent intent = new Intent(this, BookmarkPreviewEditorActivity.class);
        intent.putExtra(BookmarkPreviewEditorActivity.EXTRA_BOOKMARK_ID, item.getId());
        intent.putExtra(BookmarkPreviewEditorActivity.EXTRA_BOOKMARK_URL, item.getUrl());
        intent.putExtra(BookmarkPreviewEditorActivity.EXTRA_BOOKMARK_TITLE, item.getTitle());
        startActivity(intent);
    }

    private void showSelectedItemsOptions() {
        if (selectedIds.isEmpty()) return;

        List<BookmarkModel> selectedList = new ArrayList<>();
        for (BookmarkModel bm : allItems) {
            if (selectedIds.contains(bm.getId())) {
                selectedList.add(bm);
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
            headerIcon.setImageResource(R.drawable.ic_bookmark);
        }

        TextView title = view.findViewById(R.id.ctx_header_title);
        if (title != null) {
            title.setText(selectedList.size() + " Selected Items");
        }

        LinearLayout container = view.findViewById(R.id.ctx_actions_container);
        if (container != null) {
            List<String> validUrls = new ArrayList<>();
            for (BookmarkModel bm : selectedList) {
                if (!bm.isFolder() && bm.getUrl() != null && !bm.getUrl().isEmpty()) {
                    validUrls.add(bm.getUrl());
                }
            }

            if (!validUrls.isEmpty()) {
                addContextAction(container, "Open All in New Tabs (" + validUrls.size() + ")", R.drawable.ic_add, () -> {
                    dialog.dismiss();
                    for (int i = 0; i < validUrls.size(); i++) {
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.putExtra(MainActivity.EXTRA_LOAD_URL, validUrls.get(i));
                        intent.putExtra(MainActivity.EXTRA_OPEN_IN_NEW_TAB, true);
                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(intent);
                    }
                    exitSelectionMode();
                    finish();
                });

                addContextAction(container, "Open All in Incognito Tabs (" + validUrls.size() + ")", R.drawable.ic_incognito, () -> {
                    dialog.dismiss();
                    for (int i = 0; i < validUrls.size(); i++) {
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.putExtra(MainActivity.EXTRA_LOAD_URL, validUrls.get(i));
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
                    for (int i = 0; i < validUrls.size(); i++) {
                        if (i > 0) sb.append("\n");
                        sb.append(validUrls.get(i));
                    }
                    ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("URLs", sb.toString()));
                        Toast.makeText(this, validUrls.size() + " URLs copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            addContextAction(container, "Move Selected to Folder...", R.drawable.ic_bookmarks_folder, () -> {
                dialog.dismiss();
                showMoveDestinationPicker(new ArrayList<>(selectedIds));
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

    private void showItemOptions(BookmarkModel item) {
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
            headerIcon.setImageResource(item.isFolder() ? R.drawable.ic_bookmarks_folder : R.drawable.ic_bookmark);
        }

        TextView title = view.findViewById(R.id.ctx_header_title);
        if (title != null) {
            title.setText(item.getTitle());
        }

        LinearLayout container = view.findViewById(R.id.ctx_actions_container);
        if (container != null) {
            if (!item.isFolder()) {
                addContextAction(container, "Open in Active Tab", R.drawable.ic_search, () -> {
                    dialog.dismiss();
                    openBookmark(item.getUrl(), false, false);
                });

                addContextAction(container, "Open in New Tab", R.drawable.ic_add, () -> {
                    dialog.dismiss();
                    openBookmark(item.getUrl(), true, false);
                });

                addContextAction(container, "Open in Incognito Tab", R.drawable.ic_incognito, () -> {
                    dialog.dismiss();
                    openBookmark(item.getUrl(), true, true);
                });

                addContextAction(container, "Copy Link Address", R.drawable.ic_find, () -> {
                    dialog.dismiss();
                    ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("URL", item.getUrl()));
                        Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                });

                addContextAction(container, "Share Bookmark", R.drawable.ic_share, () -> {
                    dialog.dismiss();
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, item.getTitle());
                    shareIntent.putExtra(Intent.EXTRA_TEXT, item.getUrl());
                    startActivity(Intent.createChooser(shareIntent, "Share Bookmark"));
                });
            }

            addContextAction(container, item.isFolder() ? "Rename Folder" : "Edit Bookmark", R.drawable.ic_bookmark, () -> {
                dialog.dismiss();
                showEditBookmarkDialog(item);
            });

            addContextAction(container, "Move to Folder...", R.drawable.ic_bookmarks_folder, () -> {
                dialog.dismiss();
                showMoveDestinationPicker(Collections.singletonList(item.getId()));
            });

            addContextAction(container, item.isFolder() ? "Delete Folder" : "Delete Bookmark", R.drawable.ic_trash, () -> {
                dialog.dismiss();
                dbExecutor.execute(() -> {
                    BookmarkPreviewManager.getInstance(this).deletePreview(item.getId());
                    dbHelper.deleteItems(Collections.singletonList(item.getId()));
                    mainHandler.post(() -> {
                        Toast.makeText(this, item.isFolder() ? "Folder deleted" : "Bookmark deleted", Toast.LENGTH_SHORT).show();
                        loadItems();
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

    private class BookmarksAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return filteredItems.size();
        }

        @Override
        public BookmarkModel getItem(int position) {
            return filteredItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).getId();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (isGridView) {
                return getCardView(position, convertView, parent);
            } else {
                return getListView(position, convertView, parent);
            }
        }

        private View getCardView(int position, View convertView, ViewGroup parent) {
            CardViewHolder holder;
            if (convertView == null || !(convertView.getTag() instanceof CardViewHolder)) {
                convertView = LayoutInflater.from(BookmarksActivity.this).inflate(R.layout.item_bookmark_card, parent, false);
                holder = new CardViewHolder();
                holder.root = convertView;
                holder.preview = convertView.findViewById(R.id.card_preview);
                holder.fallbackContainer = convertView.findViewById(R.id.card_fallback_container);
                holder.iconFolder = convertView.findViewById(R.id.card_icon_folder);
                holder.favicon = convertView.findViewById(R.id.card_favicon);
                holder.monogram = convertView.findViewById(R.id.card_monogram);
                holder.checkBox = convertView.findViewById(R.id.card_checkbox);
                holder.btnMore = convertView.findViewById(R.id.card_btn_more);
                holder.title = convertView.findViewById(R.id.card_title);
                holder.subtitle = convertView.findViewById(R.id.card_subtitle);
                convertView.setTag(holder);
            } else {
                holder = (CardViewHolder) convertView.getTag();
            }

            BookmarkModel item = getItem(position);
            final long itemId = item.getId();
            final boolean isSelected = selectedIds.contains(itemId);

            holder.title.setText(item.getTitle());

            if (item.isFolder()) {
                holder.subtitle.setText(item.getItemCount() + (item.getItemCount() == 1 ? " item" : " items"));
                holder.preview.setVisibility(View.GONE);
                holder.fallbackContainer.setVisibility(View.VISIBLE);
                holder.iconFolder.setVisibility(View.VISIBLE);
                holder.favicon.setVisibility(View.GONE);
                holder.monogram.setVisibility(View.GONE);
            } else {
                holder.iconFolder.setVisibility(View.GONE);
                String domainKey = FaviconManager.extractDomain(item.getUrl());
                holder.subtitle.setText(domainKey.isEmpty() ? item.getUrl() : domainKey);

                // 480p Screenshot Preview with Favicon Fallback
                Bitmap preview = BookmarkPreviewManager.getInstance(BookmarksActivity.this).getCachedPreview(itemId);
                if (preview != null) {
                    holder.preview.setImageBitmap(preview);
                    holder.preview.setVisibility(View.VISIBLE);
                    holder.fallbackContainer.setVisibility(View.GONE);
                } else {
                    holder.preview.setVisibility(View.GONE);
                    holder.fallbackContainer.setVisibility(View.VISIBLE);

                    Bitmap cachedFavicon = FaviconManager.getInstance(BookmarksActivity.this).getCachedFavicon(domainKey);
                    if (cachedFavicon != null) {
                        holder.favicon.setImageBitmap(cachedFavicon);
                        holder.favicon.setVisibility(View.VISIBLE);
                        holder.monogram.setVisibility(View.GONE);
                    } else {
                        holder.favicon.setVisibility(View.GONE);
                        holder.monogram.setVisibility(View.VISIBLE);
                        String initial = domainKey.isEmpty() ? "?" : domainKey.substring(0, 1).toUpperCase(Locale.US);
                        holder.monogram.setText(initial);
                        int colorIndex = Math.abs(domainKey.hashCode()) % MONOGRAM_COLORS.length;
                        holder.monogram.setTextColor(MONOGRAM_COLORS[colorIndex]);

                        FaviconManager.getInstance(BookmarksActivity.this).loadFaviconAsync(item.getUrl(), (d, bmp) -> {
                            if (domainKey.equals(d) && holder.preview.getVisibility() != View.VISIBLE) {
                                holder.favicon.setImageBitmap(bmp);
                                holder.favicon.setVisibility(View.VISIBLE);
                                holder.monogram.setVisibility(View.GONE);
                            }
                        });
                    }

                    BookmarkPreviewManager.getInstance(BookmarksActivity.this).loadPreviewAsync(itemId, (id, bmp) -> {
                        if (itemId == id) {
                            holder.preview.setImageBitmap(bmp);
                            holder.preview.setVisibility(View.VISIBLE);
                            holder.fallbackContainer.setVisibility(View.GONE);
                        }
                    });
                }
            }

            if (isSelectionMode) {
                holder.checkBox.setVisibility(View.VISIBLE);
                holder.checkBox.setChecked(isSelected);
                holder.checkBox.setOnClickListener(v -> toggleItemSelection(itemId));
                holder.btnMore.setVisibility(View.GONE);
                boolean isDarkTheme = ThemeManager.isAppDarkTheme(BookmarksActivity.this);
holder.root.setBackgroundResource(isSelected ? (isDarkTheme ? R.drawable.bg_bookmark_card_selected : R.drawable.bg_bookmark_card_selected_light) : (isDarkTheme ? R.drawable.bg_bookmark_card : R.drawable.bg_bookmark_card_light));

                convertView.setOnClickListener(v -> toggleItemSelection(itemId));
                convertView.setOnLongClickListener(v -> {
                    showSelectedItemsOptions();
                    return true;
                });
            } else {
                holder.checkBox.setVisibility(View.GONE);
                holder.btnMore.setVisibility(View.VISIBLE);
                holder.btnMore.setOnClickListener(v -> showItemOptions(item));
                boolean isDarkTheme = ThemeManager.isAppDarkTheme(BookmarksActivity.this);
holder.root.setBackgroundResource(isDarkTheme ? R.drawable.bg_bookmark_card : R.drawable.bg_bookmark_card_light);

                convertView.setOnClickListener(v -> {
                    if (item.isFolder()) {
                        navigateToFolder(item.getId(), item.getTitle());
                    } else {
                        openBookmark(item.getUrl(), false, false);
                    }
                });

                convertView.setOnLongClickListener(v -> {
                    enterSelectionMode(itemId);
                    return true;
                });
            }

            return convertView;
        }

        private View getListView(int position, View convertView, ViewGroup parent) {
            ListViewHolder holder;
            if (convertView == null || !(convertView.getTag() instanceof ListViewHolder)) {
                convertView = LayoutInflater.from(BookmarksActivity.this).inflate(R.layout.item_bookmark_entry, parent, false);
                holder = new ListViewHolder();
                holder.root = convertView;
                holder.leadingContainer = convertView.findViewById(R.id.bookmark_leading_container);
                holder.iconFolder = convertView.findViewById(R.id.bookmark_icon_folder);
                holder.favicon = convertView.findViewById(R.id.bookmark_favicon);
                holder.monogram = convertView.findViewById(R.id.bookmark_monogram);
                holder.checkBox = convertView.findViewById(R.id.bookmark_checkbox);
                holder.title = convertView.findViewById(R.id.bookmark_title);
                holder.url = convertView.findViewById(R.id.bookmark_url);
                holder.date = convertView.findViewById(R.id.bookmark_date);
                holder.btnMore = convertView.findViewById(R.id.bookmark_btn_more);
                convertView.setTag(holder);
            } else {
                holder = (ListViewHolder) convertView.getTag();
            }

            BookmarkModel item = getItem(position);
            final long itemId = item.getId();
            final boolean isSelected = selectedIds.contains(itemId);

            holder.title.setText(item.getTitle());

            if (item.isFolder()) {
                holder.url.setText(item.getItemCount() + (item.getItemCount() == 1 ? " item" : " items"));
                holder.date.setText("");
                holder.iconFolder.setVisibility(View.VISIBLE);
                holder.favicon.setVisibility(View.GONE);
                holder.monogram.setVisibility(View.GONE);
            } else {
                holder.iconFolder.setVisibility(View.GONE);
                String cleanUrl = item.getUrl();
                if (cleanUrl != null) {
                    cleanUrl = cleanUrl.replaceFirst("^https?://(www\\.)?", "");
                    if (cleanUrl.endsWith("/")) {
                        cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
                    }
                }
                holder.url.setText(cleanUrl);
                holder.date.setText(dateFormat.format(new Date(item.getCreatedAt())));

                String domainKey = FaviconManager.extractDomain(item.getUrl());
                holder.favicon.setTag(domainKey);
                Bitmap cachedFavicon = FaviconManager.getInstance(BookmarksActivity.this).getCachedFavicon(domainKey);

                if (cachedFavicon != null) {
                    holder.favicon.setImageBitmap(cachedFavicon);
                    holder.favicon.setVisibility(View.VISIBLE);
                    holder.monogram.setVisibility(View.GONE);
                } else {
                    holder.favicon.setVisibility(View.GONE);
                    holder.monogram.setVisibility(View.VISIBLE);

                    String initial = "?";
                    if (!domainKey.isEmpty()) {
                        initial = domainKey.substring(0, 1).toUpperCase(Locale.US);
                    } else if (item.getTitle() != null && !item.getTitle().isEmpty()) {
                        initial = item.getTitle().substring(0, 1).toUpperCase(Locale.US);
                    }
                    holder.monogram.setText(initial);
                    int colorIndex = Math.abs(domainKey.hashCode()) % MONOGRAM_COLORS.length;
                    holder.monogram.setTextColor(MONOGRAM_COLORS[colorIndex]);

                    FaviconManager.getInstance(BookmarksActivity.this).loadFaviconAsync(item.getUrl(), (domain, bmp) -> {
                        if (domainKey.equals(holder.favicon.getTag())) {
                            holder.favicon.setImageBitmap(bmp);
                            holder.favicon.setVisibility(View.VISIBLE);
                            holder.monogram.setVisibility(View.GONE);
                        }
                    });
                }
            }

            if (isSelectionMode) {
                holder.iconFolder.setVisibility(View.GONE);
                holder.favicon.setVisibility(View.GONE);
                holder.monogram.setVisibility(View.GONE);
                holder.checkBox.setVisibility(View.VISIBLE);
                holder.checkBox.setChecked(isSelected);
                holder.checkBox.setOnClickListener(v -> toggleItemSelection(itemId));
                holder.leadingContainer.setOnClickListener(v -> toggleItemSelection(itemId));

                if (isSelected) {
                    boolean isDarkTheme = ThemeManager.isAppDarkTheme(BookmarksActivity.this);
holder.root.setBackgroundResource(isDarkTheme ? R.drawable.bg_history_selected_row : R.drawable.bg_history_selected_row_light);
                } else {
                    holder.root.setBackgroundColor(Color.TRANSPARENT);
                }

                convertView.setOnClickListener(v -> toggleItemSelection(itemId));
                convertView.setOnLongClickListener(v -> {
                    showSelectedItemsOptions();
                    return true;
                });
                holder.btnMore.setVisibility(View.GONE);
            } else {
                holder.checkBox.setVisibility(View.GONE);
                holder.leadingContainer.setOnClickListener(null);

                android.util.TypedValue outValue = new android.util.TypedValue();
                BookmarksActivity.this.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                holder.root.setBackgroundResource(outValue.resourceId);

                convertView.setOnClickListener(v -> {
                    if (item.isFolder()) {
                        navigateToFolder(item.getId(), item.getTitle());
                    } else {
                        openBookmark(item.getUrl(), false, false);
                    }
                });

                convertView.setOnLongClickListener(v -> {
                    enterSelectionMode(itemId);
                    return true;
                });

                holder.btnMore.setVisibility(View.VISIBLE);
                holder.btnMore.setOnClickListener(v -> showItemOptions(item));
            }

            return convertView;
        }

        private class CardViewHolder {
            View root;
            ImageView preview;
            View fallbackContainer;
            ImageView iconFolder;
            ImageView favicon;
            TextView monogram;
            CheckBox checkBox;
            ImageView btnMore;
            TextView title;
            TextView subtitle;
        }

        private class ListViewHolder {
            View root;
            FrameLayout leadingContainer;
            ImageView iconFolder;
            ImageView favicon;
            TextView monogram;
            CheckBox checkBox;
            TextView title;
            TextView url;
            TextView date;
            ImageView btnMore;
        }
    }
}

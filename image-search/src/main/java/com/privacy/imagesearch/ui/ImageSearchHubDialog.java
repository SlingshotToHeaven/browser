package com.privacy.imagesearch.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.imagesearch.R;
import com.privacy.imagesearch.model.ImageSearchEngine;

import java.util.List;

public final class ImageSearchHubDialog {

    public interface ImageSearchCallback {
        void onSearchRequested(@NonNull String searchUrl, boolean openInBackground);
        void onSelectWebImageRequested();
        void onCaptureRegionRequested();
        void onUploadLocalImageRequested();
    }

    public static void show(@NonNull Activity activity,
                            @Nullable String candidateImageUrl,
                            @NonNull ImageSearchCallback callback) {
        int themeResId = ImageSearchThemeHelper.getDialogThemeResId(activity);
        ContextThemeWrapper themedContext = new ContextThemeWrapper(activity, themeResId);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        View view = LayoutInflater.from(themedContext).inflate(R.layout.dialog_image_search_hub, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView textTitle = view.findViewById(R.id.dialog_title);
        TextView textSubtitle = view.findViewById(R.id.dialog_subtitle);
        View previewContainer = view.findViewById(R.id.container_selected_image_preview);
        TextView textPreviewUrl = view.findViewById(R.id.text_selected_image_url);
        View actionsStrip = view.findViewById(R.id.container_actions_strip);
        View dividerBelowActions = view.findViewById(R.id.divider_below_actions);

        if (!TextUtils.isEmpty(candidateImageUrl)) {
            if (textTitle != null) textTitle.setText("Search Selected Image");
            if (textSubtitle != null) textSubtitle.setText("Choose a search engine below");
            if (previewContainer != null) {
                previewContainer.setVisibility(View.VISIBLE);
                if (textPreviewUrl != null) textPreviewUrl.setText(candidateImageUrl);
            }
            if (actionsStrip != null) actionsStrip.setVisibility(View.GONE);
            if (dividerBelowActions != null) dividerBelowActions.setVisibility(View.GONE);
        }

        view.findViewById(R.id.btn_dialog_close).setOnClickListener(v -> dialog.dismiss());

        // Mode 1: Select Web Image
        View btnSelect = view.findViewById(R.id.btn_select_image);
        if (btnSelect != null) {
            btnSelect.setOnClickListener(v -> {
                dialog.dismiss();
                callback.onSelectWebImageRequested();
            });
        }

        // Mode 2: Capture Area
        View btnCapture = view.findViewById(R.id.btn_capture_region);
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                dialog.dismiss();
                callback.onCaptureRegionRequested();
            });
        }

        // Mode 3: Upload Local File (Browse)
        View btnUpload = view.findViewById(R.id.btn_upload_file);
        if (btnUpload != null) {
            btnUpload.setOnClickListener(v -> {
                dialog.dismiss();
                callback.onUploadLocalImageRequested();
            });
        }

        // Mode 4: Paste Image URL
        View btnUrl = view.findViewById(R.id.btn_enter_url);
        if (btnUrl != null) {
            btnUrl.setOnClickListener(v -> {
                dialog.dismiss();
                promptImageUrl(activity, candidateImageUrl, callback);
            });
        }

        // Mode 5: Search All Engines
        View btnSearchAll = view.findViewById(R.id.btn_search_all_engines);
        if (btnSearchAll != null) {
            btnSearchAll.setOnClickListener(v -> {
                if (!TextUtils.isEmpty(candidateImageUrl)) {
                    searchAllEngines(activity, candidateImageUrl, callback);
                    dialog.dismiss();
                } else {
                    dialog.dismiss();
                    promptImageUrl(activity, null, new ImageSearchCallback() {
                        @Override
                        public void onSearchRequested(@NonNull String searchUrl, boolean openInBackground) {
                            searchAllEngines(activity, searchUrl, callback);
                        }

                        @Override
                        public void onSelectWebImageRequested() {
                            callback.onSelectWebImageRequested();
                        }

                        @Override
                        public void onCaptureRegionRequested() {
                            callback.onCaptureRegionRequested();
                        }

                        @Override
                        public void onUploadLocalImageRequested() {
                            callback.onUploadLocalImageRequested();
                        }
                    });
                }
            });
        }

        LinearLayout enginesContainer = view.findViewById(R.id.container_engine_items);
        LinearLayout categoryTabsContainer = view.findViewById(R.id.container_category_filters);

        // Populate Category Filter Pills
        ImageSearchEngine.Category[] categories = ImageSearchEngine.Category.values();
        for (ImageSearchEngine.Category cat : categories) {
            TextView catPill = new TextView(themedContext);
            catPill.setText(cat.name());
            catPill.setTextSize(10.5f);
            catPill.setPadding(28, 14, 28, 14);
            catPill.setTypeface(null, android.graphics.Typeface.BOLD);
            catPill.setLetterSpacing(0.06f);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 16, 0);
            catPill.setLayoutParams(lp);

            updateCategoryPillStyle(catPill, cat == ImageSearchEngine.Category.ALL, themedContext);

            catPill.setOnClickListener(v -> {
                for (int i = 0; i < categoryTabsContainer.getChildCount(); i++) {
                    View c = categoryTabsContainer.getChildAt(i);
                    if (c instanceof TextView) {
                        updateCategoryPillStyle((TextView) c, c == catPill, themedContext);
                    }
                }
                renderEnginesList(activity, themedContext, enginesContainer, cat, candidateImageUrl, dialog, callback);
            });

            categoryTabsContainer.addView(catPill);
        }

        // Initial engine list render (All categories)
        renderEnginesList(activity, themedContext, enginesContainer, ImageSearchEngine.Category.ALL, candidateImageUrl, dialog, callback);

        dialog.show();
    }

    private static void updateCategoryPillStyle(@NonNull TextView pill, boolean isSelected, @NonNull Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(30);
        if (isSelected) {
            shape.setColor(Color.parseColor("#388E3C"));
            pill.setTextColor(Color.WHITE);
        } else {
            shape.setColor(Color.parseColor("#33888888"));
            pill.setTextColor(Color.parseColor("#AAAAAA"));
        }
        pill.setBackground(shape);
    }

    private static void renderEnginesList(@NonNull Activity activity,
                                          @NonNull Context themedContext,
                                          @NonNull LinearLayout container,
                                          @NonNull ImageSearchEngine.Category category,
                                          @Nullable String candidateImageUrl,
                                          @NonNull AlertDialog dialog,
                                          @NonNull ImageSearchCallback callback) {
        container.removeAllViews();
        List<ImageSearchEngine> engines = ImageSearchEngine.getEnginesByCategory(category);

        for (ImageSearchEngine engine : engines) {
            View itemRow = LayoutInflater.from(themedContext).inflate(R.layout.item_image_search_engine, container, false);
            TextView textName = itemRow.findViewById(R.id.engine_name);
            TextView textDesc = itemRow.findViewById(R.id.engine_description);
            TextView textTag = itemRow.findViewById(R.id.engine_category_tag);

            textName.setText(engine.getDisplayName());
            textDesc.setText(engine.getDescription());
            textTag.setText(engine.getCategory().name());

            itemRow.setOnClickListener(v -> {
                dialog.dismiss();
                if (!TextUtils.isEmpty(candidateImageUrl)) {
                    callback.onSearchRequested(engine.buildSearchUrl(candidateImageUrl), false);
                } else {
                    promptUrlForEngine(activity, engine, callback);
                }
            });

            // Long-click opens in background tab
            itemRow.setOnLongClickListener(v -> {
                dialog.dismiss();
                if (!TextUtils.isEmpty(candidateImageUrl)) {
                    callback.onSearchRequested(engine.buildSearchUrl(candidateImageUrl), true);
                    Toast.makeText(activity, "Searching in background tab...", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            });

            container.addView(itemRow);
        }
    }

    private static void promptUrlForEngine(@NonNull Activity activity,
                                           @NonNull ImageSearchEngine engine,
                                           @NonNull ImageSearchCallback callback) {
        int themeResId = ImageSearchThemeHelper.getDialogThemeResId(activity);
        ContextThemeWrapper themedContext = new ContextThemeWrapper(activity, themeResId);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        builder.setTitle("Search with " + engine.getDisplayName());

        final EditText input = new EditText(themedContext);
        input.setHint("https://example.com/photo.jpg");
        input.setSingleLine(true);
        ClipboardManager cb = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
            if (text != null && (text.toString().startsWith("http://") || text.toString().startsWith("https://"))) {
                input.setText(text);
            }
        }
        input.setPadding(40, 30, 40, 30);
        builder.setView(input);

        builder.setPositiveButton("Search", (d, w) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                callback.onSearchRequested(engine.buildSearchUrl(url), false);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private static void promptImageUrl(@NonNull Activity activity,
                                       @Nullable String initialUrl,
                                       @NonNull ImageSearchCallback callback) {
        int themeResId = ImageSearchThemeHelper.getDialogThemeResId(activity);
        ContextThemeWrapper themedContext = new ContextThemeWrapper(activity, themeResId);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        builder.setTitle("Enter Image URL");

        final EditText input = new EditText(themedContext);
        input.setHint("https://example.com/photo.jpg");
        input.setSingleLine(true);
        if (initialUrl != null) {
            input.setText(initialUrl);
        } else {
            ClipboardManager cb = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
                if (text != null && (text.toString().startsWith("http://") || text.toString().startsWith("https://"))) {
                    input.setText(text);
                }
            }
        }
        input.setPadding(40, 30, 40, 30);
        builder.setView(input);

        builder.setPositiveButton("Search (Google)", (d, w) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                ImageSearchEngine def = ImageSearchEngine.getById("googleLens");
                if (def != null) {
                    callback.onSearchRequested(def.buildSearchUrl(url), false);
                }
            }
        });

        builder.setNeutralButton("Choose Engine", (d, w) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                show(activity, url, callback);
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private static void searchAllEngines(@NonNull Activity activity,
                                         @NonNull String imageUrl,
                                         @NonNull ImageSearchCallback callback) {
        String[] defaultBatch = new String[]{"googleLens", "bing", "yandex", "tineye"};
        for (int i = 0; i < defaultBatch.length; i++) {
            ImageSearchEngine e = ImageSearchEngine.getById(defaultBatch[i]);
            if (e != null) {
                callback.onSearchRequested(e.buildSearchUrl(imageUrl), i > 0);
            }
        }
        Toast.makeText(activity, "Searching across Google, Bing, Yandex, and TinEye", Toast.LENGTH_SHORT).show();
    }
}

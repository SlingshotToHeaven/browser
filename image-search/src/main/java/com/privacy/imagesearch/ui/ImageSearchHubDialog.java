package com.privacy.imagesearch.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Button;
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
    }

    public static void show(@NonNull Activity activity,
                            @Nullable String candidateImageUrl,
                            @NonNull ImageSearchCallback callback) {
        final String imageUrl = ImageSearchUrl.normalize(candidateImageUrl);
        if (candidateImageUrl != null && imageUrl == null) {
            Toast.makeText(activity, "Image search needs an HTTP(S) image URL. Local and inline images cannot be sent.", Toast.LENGTH_LONG).show();
            return;
        }
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

        if (imageUrl != null) {
            if (textTitle != null) textTitle.setText("Search Selected Image");
            if (textSubtitle != null) textSubtitle.setText("Share the URL with one search provider");
            if (previewContainer != null) {
                previewContainer.setVisibility(View.VISIBLE);
                if (textPreviewUrl != null) textPreviewUrl.setText(imageUrl);
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

        View btnUrl = view.findViewById(R.id.btn_enter_url);
        if (btnUrl != null) btnUrl.setOnClickListener(v -> {
            dialog.dismiss();
            promptImageUrl(activity, null, callback);
        });

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
                renderEnginesList(activity, themedContext, enginesContainer, cat, imageUrl, dialog, callback);
            });

            categoryTabsContainer.addView(catPill);
        }

        // Initial engine list render (All categories)
        renderEnginesList(activity, themedContext, enginesContainer, ImageSearchEngine.Category.ALL, imageUrl, dialog, callback);

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
            if (!engine.acceptsImageUrl()) continue;
            View itemRow = LayoutInflater.from(themedContext).inflate(R.layout.item_image_search_engine, container, false);
            TextView textName = itemRow.findViewById(R.id.engine_name);
            TextView textDesc = itemRow.findViewById(R.id.engine_description);
            TextView textTag = itemRow.findViewById(R.id.engine_category_tag);

            textName.setText(engine.getDisplayName());
            textDesc.setText(engine.getDescription());
            textTag.setText(engine.getCategory().name());

            itemRow.setOnClickListener(v -> {
                dialog.dismiss();
                if (candidateImageUrl == null) promptImageUrl(activity, engine, callback);
                else confirmSearch(activity, engine, candidateImageUrl, false, callback);
            });

            itemRow.setOnLongClickListener(v -> {
                if (candidateImageUrl == null) return false;
                dialog.dismiss();
                confirmSearch(activity, engine, candidateImageUrl, true, callback);
                return true;
            });

            container.addView(itemRow);
        }
        if (container.getChildCount() == 0) {
            TextView empty = new TextView(themedContext);
            empty.setText("No URL search providers in this category.");
            container.addView(empty);
        }
    }

    private static void promptImageUrl(Activity activity, @Nullable ImageSearchEngine engine, ImageSearchCallback callback) {
        Context theme = new ContextThemeWrapper(activity, ImageSearchThemeHelper.getDialogThemeResId(activity));
        EditText input = new EditText(theme);
        input.setHint("https://example.com/photo.jpg");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ImageSearchUrl.MAX_LENGTH)});
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            android.content.ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null && text.length() <= ImageSearchUrl.MAX_LENGTH) input.setText(ImageSearchUrl.normalize(text.toString()));
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(theme).setTitle("Enter image URL").setView(input)
                .setNegativeButton("Cancel", null).setPositiveButton(engine == null ? "Choose provider" : "Review", null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = ImageSearchUrl.normalize(input.getText().toString());
            if (url == null) { input.setError("Enter an HTTP(S) image URL without login credentials."); return; }
            dialog.dismiss();
            if (engine == null) show(activity, url, callback);
            else confirmSearch(activity, engine, url, false, callback);
        }));
        dialog.show();
    }

    private static void confirmSearch(Activity activity, ImageSearchEngine engine, String imageUrl, boolean background, ImageSearchCallback callback) {
        String target = engine.buildSearchUrl(imageUrl);
        String host = java.net.URI.create(target).getHost();
        AlertDialog dialog = new AlertDialog.Builder(activity, ImageSearchThemeHelper.getDialogThemeResId(activity))
                .setTitle("Share image URL with " + engine.getDisplayName() + "?")
                .setMessage("Destination: " + host + "\n\n" + imageUrl
                        + "\n\nThe provider receives this complete address, including any query. No file or screenshot is uploaded by the app.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(background ? "Open in background" : "Open URL search", (d, button) -> callback.onSearchRequested(target, background))
                .create();
        dialog.show();
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setFilterTouchesWhenObscured(true);
    }
}

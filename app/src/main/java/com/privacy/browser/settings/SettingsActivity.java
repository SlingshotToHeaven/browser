package com.privacy.browser.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.privacy.browser.R;
import com.privacy.browser.bookmarks.BookmarksActivity;
import com.privacy.browser.history.HistoryActivity;
import com.privacy.browser.search.SearchEngineManager;
import com.privacy.downloader.ui.DownloadsActivity;

import com.privacy.browser.ThemeManager;

public class SettingsActivity extends Activity {

    public static final String PREFS_BROWSER = "browser_core_settings";
    public static final String KEY_JAVASCRIPT_ENABLED = "pref_js_enabled";
    public static final String KEY_COOKIES_ENABLED = "pref_cookies_enabled";
    public static final String KEY_FLOATING_DOWNLOAD_BUTTON = "pref_floating_download_button";
    public static final String KEY_FORCE_LONG_PRESS_IMAGES = "pref_force_long_press_images";
    public static final String KEY_COMIC_DOWNLOADER_ENABLED = "pref_comic_downloader_enabled";
    public static final String KEY_PAGE_ZOOM = "pref_page_zoom";

    public static int getPageZoom(Context context) {
        if (context == null) return 100;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_PAGE_ZOOM, 100);
    }

    public static void setPageZoom(Context context, int zoom) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PAGE_ZOOM, zoom)
                .apply();
    }

    public static boolean isComicDownloaderEnabled(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_COMIC_DOWNLOADER_ENABLED, true);
    }

    public static void setComicDownloaderEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMIC_DOWNLOADER_ENABLED, enabled)
                .apply();
    }

    public static boolean isForceLongPressImagesEnabled(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_FORCE_LONG_PRESS_IMAGES, true);
    }

    public static void setForceLongPressImagesEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FORCE_LONG_PRESS_IMAGES, enabled)
                .apply();
    }

    public static boolean isFloatingDownloadButtonEnabled(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_FLOATING_DOWNLOAD_BUTTON, true);
    }

    public static void setFloatingDownloadButtonEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FLOATING_DOWNLOAD_BUTTON, enabled)
                .apply();
    }

    private TextView textCurrentAppTheme;
    private TextView textCurrentWebTheme;
    private TextView textCurrentPageZoom;
    private TextView textCurrentEngine;
    private CheckBox checkBangs;
    private CheckBox checkAutocomplete;
    private CheckBox checkJavascript;
    private CheckBox checkCookies;
    private CheckBox checkFloatingDownloadButton;
    private CheckBox checkForceLongPressImages;
    private CheckBox checkComicDownloader;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        initViews();
        loadSettings();
    }

    private void initViews() {
        findViewById(R.id.settings_btn_back).setOnClickListener(v -> finish());

        textCurrentAppTheme = findViewById(R.id.settings_text_current_app_theme);
        textCurrentWebTheme = findViewById(R.id.settings_text_current_web_theme);
        textCurrentPageZoom = findViewById(R.id.settings_text_current_page_zoom);
        findViewById(R.id.settings_item_app_theme).setOnClickListener(v -> showAppThemeDialog());
        findViewById(R.id.settings_item_web_theme).setOnClickListener(v -> showWebThemeDialog());
        findViewById(R.id.settings_item_page_zoom).setOnClickListener(v -> showPageZoomDialog());

        textCurrentEngine = findViewById(R.id.settings_text_current_engine);
        checkBangs = findViewById(R.id.settings_check_bangs);
        checkAutocomplete = findViewById(R.id.settings_check_autocomplete);
        checkJavascript = findViewById(R.id.settings_check_javascript);
        checkCookies = findViewById(R.id.settings_check_cookies);
        checkFloatingDownloadButton = findViewById(R.id.settings_check_floating_download_button);
        checkForceLongPressImages = findViewById(R.id.settings_check_force_long_press_images);
        checkComicDownloader = findViewById(R.id.settings_check_comic_downloader);

        View itemFloatingBtn = findViewById(R.id.settings_item_floating_download_button);
        if (itemFloatingBtn != null && checkFloatingDownloadButton != null) {
            itemFloatingBtn.setOnClickListener(v -> {
                checkFloatingDownloadButton.setChecked(!checkFloatingDownloadButton.isChecked());
            });
        }

        View itemComicDownloader = findViewById(R.id.settings_item_comic_downloader);
        if (itemComicDownloader != null && checkComicDownloader != null) {
            itemComicDownloader.setOnClickListener(v -> {
                checkComicDownloader.setChecked(!checkComicDownloader.isChecked());
            });
        }

        View itemForceLongPress = findViewById(R.id.settings_item_force_long_press_images);
        if (itemForceLongPress != null && checkForceLongPressImages != null) {
            itemForceLongPress.setOnClickListener(v -> {
                checkForceLongPressImages.setChecked(!checkForceLongPressImages.isChecked());
            });
        }

        findViewById(R.id.settings_item_search_engine).setOnClickListener(v -> showSearchEngineDialog());

        findViewById(R.id.settings_item_downloads).setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, DownloadsActivity.class));
        });

        View itemDownloadConfig = findViewById(R.id.settings_item_download_config);
        if (itemDownloadConfig != null) {
            itemDownloadConfig.setOnClickListener(v -> {
                Intent intent = new Intent(SettingsActivity.this, DownloadsActivity.class);
                intent.putExtra("extra_show_settings", true);
                startActivity(intent);
            });
        }

        findViewById(R.id.settings_item_bookmarks).setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, BookmarksActivity.class));
        });

        findViewById(R.id.settings_item_history).setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, HistoryActivity.class));
        });
    }

    private void loadSettings() {
        updateCurrentEngineText();

        checkBangs.setChecked(SearchEngineManager.isBangsEnabled(this));
        checkBangs.setOnCheckedChangeListener((btn, isChecked) -> {
            SearchEngineManager.setBangsEnabled(SettingsActivity.this, isChecked);
        });

        checkAutocomplete.setChecked(SearchEngineManager.isAutocompleteEnabled(this));
        checkAutocomplete.setOnCheckedChangeListener((btn, isChecked) -> {
            SearchEngineManager.setAutocompleteEnabled(SettingsActivity.this, isChecked);
        });

        if (checkFloatingDownloadButton != null) {
            checkFloatingDownloadButton.setChecked(isFloatingDownloadButtonEnabled(this));
            checkFloatingDownloadButton.setOnCheckedChangeListener((btn, isChecked) -> {
                setFloatingDownloadButtonEnabled(SettingsActivity.this, isChecked);
            });
        }

        if (checkComicDownloader != null) {
            checkComicDownloader.setChecked(isComicDownloaderEnabled(this));
            checkComicDownloader.setOnCheckedChangeListener((btn, isChecked) -> {
                setComicDownloaderEnabled(SettingsActivity.this, isChecked);
            });
        }

        if (checkForceLongPressImages != null) {
            checkForceLongPressImages.setChecked(isForceLongPressImagesEnabled(this));
            checkForceLongPressImages.setOnCheckedChangeListener((btn, isChecked) -> {
                setForceLongPressImagesEnabled(SettingsActivity.this, isChecked);
            });
        }

        updateAppThemeText();
        updateWebThemeText();
        updatePageZoomText();

        SharedPreferences prefs = getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE);
        checkJavascript.setChecked(prefs.getBoolean(KEY_JAVASCRIPT_ENABLED, true));
        checkJavascript.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(KEY_JAVASCRIPT_ENABLED, isChecked).apply();
        });

        checkCookies.setChecked(prefs.getBoolean(KEY_COOKIES_ENABLED, true));
        checkCookies.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(KEY_COOKIES_ENABLED, isChecked).apply();
        });
    }

    private void updateAppThemeText() {
        if (textCurrentAppTheme == null) return;
        String theme = ThemeManager.getAppTheme(this);
        if (ThemeManager.APP_THEME_LIGHT.equals(theme)) {
            textCurrentAppTheme.setText("Light");
        } else if (ThemeManager.APP_THEME_SYSTEM.equals(theme)) {
            textCurrentAppTheme.setText("System Default (Auto)");
        } else {
            textCurrentAppTheme.setText("Dark (AMOLED)");
        }
    }

    private void updateWebThemeText() {
        if (textCurrentWebTheme == null) return;
        String theme = ThemeManager.getWebTheme(this);
        if (ThemeManager.WEB_THEME_DARK.equals(theme)) {
            textCurrentWebTheme.setText("Dark (Requests Dark Theme)");
        } else if (ThemeManager.WEB_THEME_LIGHT.equals(theme)) {
            textCurrentWebTheme.setText("Light (Requests Light Theme)");
        } else if (ThemeManager.WEB_THEME_SYSTEM.equals(theme)) {
            textCurrentWebTheme.setText("System Default (Auto)");
        } else {
            textCurrentWebTheme.setText("Force Dark (Inverted)");
        }
    }

    private void updatePageZoomText() {
        if (textCurrentPageZoom == null) return;
        int zoom = getPageZoom(this);
        if (zoom == 80) {
            textCurrentPageZoom.setText("80% (Small / High Density)");
        } else if (zoom == 85) {
            textCurrentPageZoom.setText("85% (Compact Phone Layout)");
        } else if (zoom == 90) {
            textCurrentPageZoom.setText("90% (Slightly Compact)");
        } else if (zoom == 110) {
            textCurrentPageZoom.setText("110% (Comfortable)");
        } else if (zoom == 125) {
            textCurrentPageZoom.setText("125% (Large)");
        } else {
            textCurrentPageZoom.setText("100% (Standard Mobile Default)");
        }
    }

    private void showPageZoomDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_select_page_zoom, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        RadioGroup group = view.findViewById(R.id.dialog_radio_group_page_zoom);

        int currentZoom = getPageZoom(this);
        if (currentZoom == 80) {
            group.check(R.id.radio_zoom_80);
        } else if (currentZoom == 85) {
            group.check(R.id.radio_zoom_85);
        } else if (currentZoom == 90) {
            group.check(R.id.radio_zoom_90);
        } else if (currentZoom == 110) {
            group.check(R.id.radio_zoom_110);
        } else if (currentZoom == 125) {
            group.check(R.id.radio_zoom_125);
        } else {
            group.check(R.id.radio_zoom_100);
        }

        group.setOnCheckedChangeListener((rg, checkedId) -> {
            int selectedZoom = 100;
            if (checkedId == R.id.radio_zoom_80) {
                selectedZoom = 80;
            } else if (checkedId == R.id.radio_zoom_85) {
                selectedZoom = 85;
            } else if (checkedId == R.id.radio_zoom_90) {
                selectedZoom = 90;
            } else if (checkedId == R.id.radio_zoom_110) {
                selectedZoom = 110;
            } else if (checkedId == R.id.radio_zoom_125) {
                selectedZoom = 125;
            }
            setPageZoom(SettingsActivity.this, selectedZoom);
            updatePageZoomText();
            Toast.makeText(SettingsActivity.this, "Page zoom updated to " + selectedZoom + "%", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    private void showAppThemeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_select_app_theme, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        RadioGroup group = view.findViewById(R.id.dialog_radio_group_app_theme);

        String currentTheme = ThemeManager.getAppTheme(this);
        if (ThemeManager.APP_THEME_LIGHT.equals(currentTheme)) {
            group.check(R.id.radio_app_theme_light);
        } else if (ThemeManager.APP_THEME_SYSTEM.equals(currentTheme)) {
            group.check(R.id.radio_app_theme_system);
        } else {
            group.check(R.id.radio_app_theme_dark);
        }

        group.setOnCheckedChangeListener((rg, checkedId) -> {
            String selected = ThemeManager.APP_THEME_DARK;
            if (checkedId == R.id.radio_app_theme_light) {
                selected = ThemeManager.APP_THEME_LIGHT;
            } else if (checkedId == R.id.radio_app_theme_system) {
                selected = ThemeManager.APP_THEME_SYSTEM;
            }
            ThemeManager.setAppTheme(SettingsActivity.this, selected);
            updateAppThemeText();
            dialog.dismiss();
            recreate();
        });

        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    private void showWebThemeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_select_web_theme, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        RadioGroup group = view.findViewById(R.id.dialog_radio_group_web_theme);

        String currentTheme = ThemeManager.getWebTheme(this);
        if (ThemeManager.WEB_THEME_DARK.equals(currentTheme)) {
            group.check(R.id.radio_web_theme_dark);
        } else if (ThemeManager.WEB_THEME_LIGHT.equals(currentTheme)) {
            group.check(R.id.radio_web_theme_light);
        } else if (ThemeManager.WEB_THEME_SYSTEM.equals(currentTheme)) {
            group.check(R.id.radio_web_theme_system);
        } else {
            group.check(R.id.radio_web_theme_force_dark);
        }

        group.setOnCheckedChangeListener((rg, checkedId) -> {
            String selected = ThemeManager.WEB_THEME_FORCE_DARK;
            if (checkedId == R.id.radio_web_theme_dark) {
                selected = ThemeManager.WEB_THEME_DARK;
            } else if (checkedId == R.id.radio_web_theme_light) {
                selected = ThemeManager.WEB_THEME_LIGHT;
            } else if (checkedId == R.id.radio_web_theme_system) {
                selected = ThemeManager.WEB_THEME_SYSTEM;
            }
            ThemeManager.setWebTheme(SettingsActivity.this, selected);
            updateWebThemeText();
            Toast.makeText(SettingsActivity.this, "Website preference saved", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    private void updateCurrentEngineText() {
        SearchEngineManager.SearchEngine engine = SearchEngineManager.getSelectedEngine(this);
        textCurrentEngine.setText(engine.getName() + " (" + (engine.isPrivacyFocused() ? "Privacy-first" : "Standard") + ")");
    }

    private void showSearchEngineDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, ThemeManager.getDialogTheme(this));
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_search_engine, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        RadioGroup radioGroup = view.findViewById(R.id.search_engine_radio_group);
        LinearLayout layoutCustomUrl = view.findViewById(R.id.layout_custom_search_url);
        EditText editCustomUrl = view.findViewById(R.id.edit_custom_search_url);

        String currentId = SearchEngineManager.getSelectedEngine(this).getId();
        if ("brave".equals(currentId)) {
            radioGroup.check(R.id.radio_engine_brave);
        } else if ("startpage".equals(currentId)) {
            radioGroup.check(R.id.radio_engine_startpage);
        } else if ("searxng".equals(currentId)) {
            radioGroup.check(R.id.radio_engine_searxng);
        } else if ("google".equals(currentId)) {
            radioGroup.check(R.id.radio_engine_google);
        } else if ("bing".equals(currentId)) {
            radioGroup.check(R.id.radio_engine_bing);
        } else if ("custom".equals(currentId)) {
            radioGroup.check(R.id.radio_engine_custom);
            layoutCustomUrl.setVisibility(View.VISIBLE);
        } else {
            radioGroup.check(R.id.radio_engine_ddg);
        }

        editCustomUrl.setText(SearchEngineManager.getCustomUrl(this));

        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_engine_custom) {
                layoutCustomUrl.setVisibility(View.VISIBLE);
            } else {
                layoutCustomUrl.setVisibility(View.GONE);
            }
        });

        Button btnCancel = view.findViewById(R.id.dialog_search_btn_cancel);
        Button btnSave = view.findViewById(R.id.dialog_search_btn_save);

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            int selectedId = radioGroup.getCheckedRadioButtonId();
            String newEngineId = "duckduckgo";

            if (selectedId == R.id.radio_engine_brave) {
                newEngineId = "brave";
            } else if (selectedId == R.id.radio_engine_startpage) {
                newEngineId = "startpage";
            } else if (selectedId == R.id.radio_engine_searxng) {
                newEngineId = "searxng";
            } else if (selectedId == R.id.radio_engine_google) {
                newEngineId = "google";
            } else if (selectedId == R.id.radio_engine_bing) {
                newEngineId = "bing";
            } else if (selectedId == R.id.radio_engine_custom) {
                newEngineId = "custom";
                String customUrl = editCustomUrl.getText().toString().trim();
                SearchEngineManager.setCustomUrl(SettingsActivity.this, customUrl);
            }

            SearchEngineManager.setSelectedEngine(SettingsActivity.this, newEngineId);
            updateCurrentEngineText();
            Toast.makeText(SettingsActivity.this, "Search engine updated", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
        applyAmoledDialogTheme(dialog);
    }

    private void applyAmoledDialogTheme(AlertDialog dialog) {
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}

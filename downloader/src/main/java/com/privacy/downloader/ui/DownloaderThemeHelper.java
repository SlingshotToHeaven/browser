package com.privacy.downloader.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;

/**
 * Pure Java Theme helper for Downloader module.
 * Synchronizes seamlessly with the browser core settings and applies
 * semantic colors, status bar lighting, and dialog themes without circular dependencies.
 */
public final class DownloaderThemeHelper {

    public static final String PREFS_NAME = "browser_core_settings";
    public static final String KEY_APP_THEME = "pref_app_theme";
    public static final String APP_THEME_DARK = "dark";
    public static final String APP_THEME_LIGHT = "light";
    public static final String APP_THEME_SYSTEM = "system";

    private DownloaderThemeHelper() {}

    public static boolean isAppDarkTheme(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String theme = prefs.getString(KEY_APP_THEME, APP_THEME_DARK);
        if (APP_THEME_LIGHT.equals(theme)) {
            return false;
        } else if (APP_THEME_DARK.equals(theme)) {
            return true;
        } else {
            int nightMask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return nightMask == Configuration.UI_MODE_NIGHT_YES;
        }
    }

    public static int getDialogTheme(@NonNull Context context) {
        return isAppDarkTheme(context)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    public static int resolveColor(@NonNull Context context, int attrResId, int fallbackColor) {
        TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(attrResId, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data;
            }
        }
        return fallbackColor;
    }

    public static void applyTheme(@NonNull Activity activity) {
        boolean isDark = isAppDarkTheme(activity);
        String pkg = activity.getPackageName();
        int themeResId = activity.getResources().getIdentifier(
                isDark ? "Theme_MinimalistBrowser" : "Theme_MinimalistBrowser_Light",
                "style",
                pkg
        );
        if (themeResId == 0) {
            themeResId = activity.getResources().getIdentifier(
                    isDark ? "Theme.MinimalistBrowser" : "Theme.MinimalistBrowser.Light",
                    "style",
                    pkg
            );
        }
        if (themeResId != 0) {
            activity.setTheme(themeResId);
        }

        Window window = activity.getWindow();
        if (window != null) {
            window.getDecorView().post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowInsetsController controller = window.getInsetsController();
                        if (controller != null) {
                            int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                            if (isDark) {
                                controller.setSystemBarsAppearance(0, flags);
                            } else {
                                controller.setSystemBarsAppearance(flags, flags);
                            }
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        int flags = window.getDecorView().getSystemUiVisibility();
                        if (isDark) {
                            flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        } else {
                            flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        }
                        window.getDecorView().setSystemUiVisibility(flags);
                    }
                } catch (Throwable ignored) {}
            });
        }
    }
}

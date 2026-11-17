package com.privacy.imagesearch.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

public final class ImageSearchThemeHelper {

    public static final String PREFS_NAME = "browser_core_settings";
    public static final String KEY_APP_THEME = "pref_app_theme";
    public static final String APP_THEME_DARK = "dark";
    public static final String APP_THEME_LIGHT = "light";

    private ImageSearchThemeHelper() {}

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

    public static int getDialogThemeResId(@NonNull Context context) {
        boolean isDark = isAppDarkTheme(context);
        String pkg = context.getPackageName();
        int resId = context.getResources().getIdentifier(
                isDark ? "Theme_MinimalistBrowser" : "Theme_MinimalistBrowser_Light",
                "style",
                pkg
        );
        if (resId == 0) {
            resId = context.getResources().getIdentifier(
                    isDark ? "Theme.MinimalistBrowser" : "Theme.MinimalistBrowser.Light",
                    "style",
                    pkg
            );
        }
        return resId != 0 ? resId : (isDark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }
}

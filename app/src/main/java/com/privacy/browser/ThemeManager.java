package com.privacy.browser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

/**
 * Central theme and display mode manager.
 * Manages:
 * 1. App Theme: Dark (AMOLED pitch black), Light, or System Default.
 * 2. Web Content Appearance:
 *    - Dark: requests website's native dark theme (prefers-color-scheme: dark)
 *    - Light: requests website's native light theme (prefers-color-scheme: light)
 *    - System Default: follows device day/night
 *    - Force Dark: algorithmic / inverted darkening for all web pages
 */
public final class ThemeManager {

    public static final String PREFS_NAME = "browser_core_settings";

    public static final String KEY_APP_THEME = "pref_app_theme";
    public static final String APP_THEME_DARK = "dark";
    public static final String APP_THEME_LIGHT = "light";
    public static final String APP_THEME_SYSTEM = "system";

    public static final String KEY_WEB_THEME = "pref_web_theme";
    public static final String WEB_THEME_DARK = "dark";
    public static final String WEB_THEME_LIGHT = "light";
    public static final String WEB_THEME_SYSTEM = "system";
    public static final String WEB_THEME_FORCE_DARK = "force_dark";

    private ThemeManager() {}

    @NonNull
    public static String getAppTheme(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_APP_THEME, APP_THEME_DARK);
    }

    public static void setAppTheme(@NonNull Context context, @NonNull String theme) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_APP_THEME, theme).apply();
    }

    public static boolean isAppDarkTheme(@NonNull Context context) {
        String theme = getAppTheme(context);
        if (APP_THEME_LIGHT.equals(theme)) {
            return false;
        } else if (APP_THEME_DARK.equals(theme)) {
            return true;
        } else {
            // System Default
            int nightMask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return nightMask == Configuration.UI_MODE_NIGHT_YES;
        }
    }

    /**
     * Returns the appropriate dialog alert theme resource ID for the current app theme.
     */
    public static int getDialogTheme(@NonNull Context context) {
        return isAppDarkTheme(context)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    /**
     * Applies the configured app theme to an activity before setContentView.
     */
    public static void applyAppTheme(@NonNull Activity activity) {
        boolean isDark = isAppDarkTheme(activity);
        if (isDark) {
            activity.setTheme(R.style.Theme_MinimalistBrowser);
        } else {
            activity.setTheme(R.style.Theme_MinimalistBrowser_Light);
        }

        // Adjust system bars light/dark icons accordingly (must be guarded as decorView may not be attached yet)
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
                    } else {
                        View decor = window.getDecorView();
                        int flags = decor.getSystemUiVisibility();
                        if (isDark) {
                            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                            }
                        } else {
                            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                            }
                        }
                        decor.setSystemUiVisibility(flags);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    @NonNull
    public static String getWebTheme(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Default to force_dark to preserve past user experience unless configured otherwise
        return prefs.getString(KEY_WEB_THEME, WEB_THEME_FORCE_DARK);
    }

    public static void setWebTheme(@NonNull Context context, @NonNull String theme) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_WEB_THEME, theme).apply();
    }

    /**
     * Configures a PrivacyWebView's settings based on the user's Web Content Appearance preference.
     */
    public static void applyWebTheme(@NonNull PrivacyWebView webView) {
        applyWebTheme(webView, webView.getUrl());
    }

    static void applyWebTheme(@NonNull PrivacyWebView webView, String targetUrl) {
        Context context = webView.getContext();
        boolean lightHome = InternalHome.isHome(targetUrl) && !isAppDarkTheme(context);
        String webTheme = lightHome ? WEB_THEME_LIGHT : getWebTheme(context);
        if (!webView.changeWebTheme(webTheme)) return;
        android.webkit.WebSettings settings = webView.getSettings();

        if (WEB_THEME_FORCE_DARK.equals(webTheme)) {
            // Algorithmic force darkening (auto-inverts white web pages)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON);
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(settings,
                            WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
                }
            }
        } else if (WEB_THEME_DARK.equals(webTheme)) {
            // Request native dark theme without brute-force algorithmic inversion
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON);
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(settings,
                            WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
                }
            }
        } else if (WEB_THEME_LIGHT.equals(webTheme)) {
            // Request native light theme (turn off force dark)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF);
            }
        } else {
            // System Default (auto)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO);
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(settings,
                            WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
                }
            }
        }
    }

    /** Once per finished Home document; appearance refreshes do not re-evaluate this script. */
    static void applyHomeUi(PrivacyWebView view) {
        if (!InternalHome.isHome(view.getUrl()) || !view.getSettings().getJavaScriptEnabled()) return;
        view.evaluateJavascript("if(location.origin==='" + InternalHome.ORIGIN + "' && typeof applyTheme==='function') applyTheme('"
                + (isAppDarkTheme(view.getContext()) ? "dark" : "light") + "');", null);
    }

}

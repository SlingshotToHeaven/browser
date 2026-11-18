package com.privacy.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Only explicit all-sites operations on regular WebView and the browser's own selected data. */
final class BrowserDataCleaner {
    private static final long COOKIE_CONFIRMATION_TIMEOUT_MS = 10_000;
    private static boolean clearing; // UI thread

    static final class Report {
        private final Map<String, String> outcomes = new LinkedHashMap<>();
        private boolean incomplete;

        private void put(String operation, String result, boolean uncertain) {
            outcomes.put(operation, result);
            incomplete |= uncertain;
        }

        String summary() {
            StringBuilder text = new StringBuilder();
            outcomes.forEach((key, value) -> {
                if (text.length() > 0) text.append('\n');
                text.append(key).append(": ").append(value);
            });
            return text.toString();
        }

        String toJson() {
            try {
                return new JSONObject().put("scope", "all_regular_sites")
                        .put("status", incomplete ? "partial_or_unconfirmed" : "finished")
                        .put("outcomes", new JSONObject(outcomes)).toString();
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private BrowserDataCleaner() {}

    static boolean inProgress() { return clearing; }

    @FunctionalInterface interface LocalClear {
        /** Invoke completion on the UI thread only after the selected local records are removed. */
        void start(Consumer<Boolean> complete);
    }

    /** Call on the UI thread, after the user confirms the exact Plan in native UI. */
    static void clear(Context context, BrowserDataPolicy.Plan plan, LocalClear clearLocalHistoryAndTabs,
                      Consumer<Report> callback) {
        if (clearing || plan.isEmpty()) throw new IllegalStateException("Another data clear is running or no data was selected");
        clearing = true;
        Report report = new Report();
        if (plan.localHistoryAndTabs) {
            boolean[] confirmed = {false};
            Consumer<Boolean> afterLocal = removed -> {
                if (confirmed[0]) return;
                confirmed[0] = true;
                boolean ok = Boolean.TRUE.equals(removed);
                report.put("History and saved tabs", ok ? "removed" : "could not confirm removal of all selected records", !ok);
                clearWebViewData(context, plan, report, callback);
            };
            try { clearLocalHistoryAndTabs.start(afterLocal); }
            catch (RuntimeException e) {
                if (confirmed[0]) throw e;
                afterLocal.accept(false);
            }
        } else {
            clearWebViewData(context, plan, report, callback);
        }
    }

    private static void clearWebViewData(Context context, BrowserDataPolicy.Plan plan, Report report, Consumer<Report> callback) {
        if (plan.urlCache) {
            WebView blank = null;
            try {
                // This API clears the app-wide WebView URL cache; no page is loaded in this view.
                blank = new WebView(context);
                blank.clearCache(true);
                report.put("WebView URL cache", "clear requested", true);
            } catch (RuntimeException e) {
                report.put("WebView URL cache", "clear could not be requested", true);
            } finally {
                if (blank != null) {
                    try { WebViewDisposal.detachAndDestroy(blank); }
                    catch (RuntimeException e) { report.put("Cache view teardown", "unconfirmed", true); }
                }
            }
        }
        if (plan.legacyStorage) {
            try {
                WebStorage.getInstance().deleteAllData();
                report.put("Legacy Web Storage", "deletion requested; no completion callback", true);
            } catch (RuntimeException e) {
                report.put("Legacy Web Storage", "deletion could not be requested", true);
            }
        }
        if (!plan.cookies) {
            clearing = false;
            callback.accept(report);
            return;
        }

        Handler ui = new Handler(Looper.getMainLooper());
        boolean[] done = {false};
        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            clearing = false;
            callback.accept(report);
        };
        Runnable timeout = () -> {
            if (done[0]) return;
            report.put("WebView cookies", "deletion was not confirmed by the provider", true);
            finish.run();
        };
        ui.postDelayed(timeout, COOKIE_CONFIRMATION_TIMEOUT_MS);
        try {
            CookieManager cookies = CookieManager.getInstance();
            cookies.removeAllCookies(removed -> {
                if (done[0]) return;
                ui.removeCallbacks(timeout);
                try {
                    cookies.flush();
                    report.put("WebView cookies", Boolean.TRUE.equals(removed) ? "removed" : "provider reported none removed", false);
                } catch (RuntimeException e) {
                    report.put("WebView cookies", "provider responded, but persistence could not be confirmed", true);
                }
                finish.run();
            });
        } catch (RuntimeException e) {
            ui.removeCallbacks(timeout);
            report.put("WebView cookies", "deletion could not be requested", true);
            finish.run();
        }
    }
}

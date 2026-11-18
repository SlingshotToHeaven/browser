package com.privacy.browser.adblock;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.R;
import com.privacy.browser.ThemeManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full uBlock Origin dashboard control dialog presenting master power toggle, quick tools
 * (Zapper, Picker, My Filters), per-site switches, and real-time page/all-time telemetry.
 */
public class AdBlockDashboardDialog {

    public static void show(@NonNull Context context,
                            @Nullable String tabId,
                            @Nullable String currentUrl,
                            @Nullable Runnable onReloadNeeded) {
        show(context, tabId, currentUrl, onReloadNeeded, null, null, null);
    }

    public static void show(@NonNull Context context,
                            @Nullable String tabId,
                            @Nullable String currentUrl,
                            @Nullable Runnable onReloadNeeded,
                            @Nullable Runnable onStartZapper,
                            @Nullable Runnable onStartPicker,
                            @Nullable Runnable onOpenMyFilters) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_adblock_dashboard, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        AdBlockManager manager = AdBlockManager.getInstance(context);
        String cleanHost = AdBlockManager.extractCleanHost(currentUrl);
        if (cleanHost == null || cleanHost.isEmpty()) {
            cleanHost = "Local / System Page";
        }

        ImageView ivIcon = view.findViewById(R.id.iv_adblock_icon);
        TextView tvSiteHost = view.findViewById(R.id.tv_adblock_site_host);
        tvSiteHost.setText(cleanHost);

        ImageButton btnClose = view.findViewById(R.id.btn_close_adblock);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Site Protection Toggle (Master Power Button)
        Switch switchSite = view.findViewById(R.id.switch_site_protection);
        TextView tvSiteStatus = view.findViewById(R.id.tv_site_protection_status);

        boolean isWhitelisted = manager.isSiteWhitelisted(cleanHost);
        boolean isSiteActive = !isWhitelisted && manager.isGlobalEnabled();

        switchSite.setChecked(isSiteActive);
        updateSiteStatusUI(isSiteActive, tvSiteStatus, ivIcon);

        final String targetHost = cleanHost;
        switchSite.setOnCheckedChangeListener((buttonView, isChecked) -> {
            manager.setSiteWhitelisted(targetHost, !isChecked);
            updateSiteStatusUI(isChecked, tvSiteStatus, ivIcon);
            if (onReloadNeeded != null) {
                onReloadNeeded.run();
            }
        });

        // Quick Tools Toolbar
        Button btnZapper = view.findViewById(R.id.btn_tool_zapper);
        btnZapper.setOnClickListener(v -> {
            dialog.dismiss();
            if (onStartZapper != null) onStartZapper.run();
        });

        Button btnPicker = view.findViewById(R.id.btn_tool_picker);
        btnPicker.setOnClickListener(v -> {
            dialog.dismiss();
            if (onStartPicker != null) onStartPicker.run();
        });

        Button btnMyFilters = view.findViewById(R.id.btn_tool_my_filters);
        btnMyFilters.setOnClickListener(v -> {
            dialog.dismiss();
            if (onOpenMyFilters != null) onOpenMyFilters.run();
        });

        Button btnEditModules = view.findViewById(R.id.btn_tool_edit_modules);
        if (btnEditModules != null) {
            btnEditModules.setOnClickListener(v -> {
                dialog.dismiss();
                AdBlockModuleManagerDialog.show(context, onReloadNeeded);
            });
        }

        // Statistics (Page, Percent, Total)
        TextView tvPageBlocked = view.findViewById(R.id.tv_count_page_blocked);
        TextView tvPercentBlocked = view.findViewById(R.id.tv_count_percent_blocked);
        TextView tvTotalBlocked = view.findViewById(R.id.tv_count_total_blocked);

        int pageBlocked = manager.getPageBlockedCount(tabId);
        int percentBlocked = manager.getPageBlockedPercentage(tabId);
        long totalBlocked = manager.getTotalBlockedCount();

        tvPageBlocked.setText(String.valueOf(pageBlocked));
        tvPercentBlocked.setText(percentBlocked + "%");
        tvTotalBlocked.setText(String.format(Locale.US, "%,d", totalBlocked));

        // Per-Site Granular uBlock Switches
        Switch switchJs = view.findViewById(R.id.switch_site_js);
        View clearNoScript = view.findViewById(R.id.btn_clear_no_script_rules);
        TextView noScriptStatus = view.findViewById(R.id.tv_no_script_status);
        Runnable renderNoScript = () -> {
            boolean active = manager.hasNoScriptRules();
            clearNoScript.setEnabled(active);
            noScriptStatus.setVisibility(active ? View.VISIBLE : View.GONE);
        };
        boolean isWebSite = com.privacy.downloader.net.RequestContext.origin(currentUrl) != null
                && !com.privacy.browser.InternalHome.ownsHost(currentUrl);
        switchJs.setEnabled(isWebSite);
        switchJs.setChecked(isWebSite && !manager.isSiteJavaScriptEnabled(targetHost));
        switchJs.setOnCheckedChangeListener((btn, isChecked) -> {
            if (!isWebSite) return;
            manager.setSiteJavaScriptEnabled(targetHost, !isChecked);
            renderNoScript.run();
            if (onReloadNeeded != null) onReloadNeeded.run();
        });
        renderNoScript.run();
        clearNoScript.setOnClickListener(v -> new android.app.AlertDialog.Builder(context, ThemeManager.getDialogTheme(context))
                .setTitle("Re-enable JavaScript in all tabs?")
                .setMessage("This removes all saved NoScript rules and reloads the open pages without their Back history.")
                .setPositiveButton("Enable JavaScript", (confirm, which) -> {
                    manager.clearNoScriptRules();
                    dialog.dismiss();
                    if (onReloadNeeded != null) onReloadNeeded.run();
                }).setNegativeButton("Cancel", null).show());

        Switch switchPopups = view.findViewById(R.id.switch_site_popups);
        if (switchPopups != null) {
            switchPopups.setChecked(manager.isSitePopupsBlocked(targetHost));
            switchPopups.setOnCheckedChangeListener((btn, isChecked) -> {
                manager.setSitePopupsBlocked(targetHost, isChecked);
                if (onReloadNeeded != null) onReloadNeeded.run();
            });
        }

        Switch switchCosmetic = view.findViewById(R.id.switch_site_cosmetic);
        if (switchCosmetic != null) {
            switchCosmetic.setChecked(manager.isSiteCosmeticEnabled(targetHost));
            switchCosmetic.setOnCheckedChangeListener((btn, isChecked) -> {
                manager.setSiteCosmeticEnabled(targetHost, isChecked);
                if (onReloadNeeded != null) onReloadNeeded.run();
            });
        }

        Switch switchFonts = view.findViewById(R.id.switch_site_fonts);
        if (switchFonts != null) {
            switchFonts.setChecked(manager.isSiteFontsBlocked(targetHost));
            switchFonts.setOnCheckedChangeListener((btn, isChecked) -> {
                manager.setSiteFontsBlocked(targetHost, isChecked);
                if (onReloadNeeded != null) onReloadNeeded.run();
            });
        }

        Switch switchMedia = view.findViewById(R.id.switch_site_media);
        if (switchMedia != null) {
            switchMedia.setChecked(manager.isSiteLargeMediaBlocked(targetHost));
            switchMedia.setOnCheckedChangeListener((btn, isChecked) -> {
                manager.setSiteLargeMediaBlocked(targetHost, isChecked);
                if (onReloadNeeded != null) onReloadNeeded.run();
            });
        }

        // Anti-Adblock Defeater Details
        LinearLayout llAntiAdblock = view.findViewById(R.id.ll_antiadblock_log);
        TextView tvAntiAdblockEmpty = view.findViewById(R.id.tv_antiadblock_empty);
        List<AdBlockManager.AntiAdblockEvent> events = manager.getNeutralizedAntiAdblockForPage(tabId);

        boolean isDark = ThemeManager.isAppDarkTheme(context);
        int textPrimaryColor = isDark ? Color.WHITE : Color.parseColor("#111827");
        int textSecondaryColor = isDark ? Color.parseColor("#8E8EA8") : Color.parseColor("#6B7280");

        if (!events.isEmpty()) {
            tvAntiAdblockEmpty.setVisibility(View.GONE);
            for (AdBlockManager.AntiAdblockEvent event : events) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, 4, 0, 8);

                TextView tvName = new TextView(context);
                tvName.setText("[Neutralized] " + event.name);
                tvName.setTextColor(textPrimaryColor);
                tvName.setTextSize(13);

                TextView tvDetail = new TextView(context);
                tvDetail.setText("  " + event.detail);
                tvDetail.setTextColor(textSecondaryColor);
                tvDetail.setTextSize(11);

                row.addView(tvName);
                row.addView(tvDetail);
                llAntiAdblock.addView(row);
            }
        } else {
            tvAntiAdblockEmpty.setVisibility(View.VISIBLE);
        }

        // Blocked Domains List
        LinearLayout llBlockedDomains = view.findViewById(R.id.ll_blocked_domains);
        TextView tvBlockedEmpty = view.findViewById(R.id.tv_blocked_domains_empty);
        Map<String, Integer> blockedHosts = manager.getBlockedHostsForPage(tabId);

        if (!blockedHosts.isEmpty()) {
            tvBlockedEmpty.setVisibility(View.GONE);
            for (Map.Entry<String, Integer> entry : blockedHosts.entrySet()) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 4, 0, 4);

                TextView tvHost = new TextView(context);
                tvHost.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                tvHost.setText(entry.getKey());
                tvHost.setTextColor(textPrimaryColor);
                tvHost.setTextSize(12);

                TextView tvCount = new TextView(context);
                tvCount.setText(String.valueOf(entry.getValue()));
                tvCount.setTextColor(Color.parseColor("#4CAF50"));
                tvCount.setTextSize(12);

                row.addView(tvHost);
                row.addView(tvCount);
                llBlockedDomains.addView(row);
            }
        } else {
            tvBlockedEmpty.setVisibility(View.VISIBLE);
        }

        // Reload Button
        Button btnReload = view.findViewById(R.id.btn_reload_page);
        btnReload.setOnClickListener(v -> {
            dialog.dismiss();
            if (onReloadNeeded != null) {
                onReloadNeeded.run();
            }
        });

        dialog.show();
    }

    private static void updateSiteStatusUI(boolean active, TextView tvStatus, ImageView ivIcon) {
        if (active) {
            tvStatus.setText("ACTIVE");
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            ivIcon.setColorFilter(Color.parseColor("#4CAF50"));
        } else {
            tvStatus.setText("PAUSED (Whitelisted)");
            tvStatus.setTextColor(Color.GRAY);
            ivIcon.setColorFilter(Color.GRAY);
        }
    }
}

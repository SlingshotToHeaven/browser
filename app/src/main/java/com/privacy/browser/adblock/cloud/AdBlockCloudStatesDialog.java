package com.privacy.browser.adblock.cloud;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import com.privacy.browser.R;
import com.privacy.browser.adblock.AdBlockFileManager;
import com.privacy.browser.adblock.AdBlockManager;
import com.privacy.browser.adblock.AntiAdblockDefuser;
import com.privacy.browser.adblock.PopupClickDefuser;
import com.privacy.browser.adblock.YouTubeAdDefuser;
import com.privacy.browser.adblock.rules.UserFiltersManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Cloud States synchronization dialog:
 * - List community state commits
 * - Commit and upload current modifications
 * - Download and merge with 3 modes: MAKE SAME, ADD ONLY, REMOVE ONLY
 */
public class AdBlockCloudStatesDialog {

    public static void show(@NonNull Context context, @Nullable Runnable onReloadNeeded) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_adblock_cloud_states, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.95);
            int height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.88);
            window.setLayout(width, height);
        }

        final boolean[] stateApplied = new boolean[]{false};
        Runnable stateAppliedCallback = () -> {
            stateApplied[0] = true;
            if (onReloadNeeded != null) {
                onReloadNeeded.run();
            }
        };

        dialog.setOnDismissListener(d -> {
            if (stateApplied[0] && onReloadNeeded != null) {
                onReloadNeeded.run();
            }
        });

        AdBlockCloudClient cloudClient = new AdBlockCloudClient(context);
        AdBlockFileManager fileManager = AdBlockFileManager.getInstance(context);

        ImageButton btnClose = view.findViewById(R.id.btn_close_cloud_dialog);
        ImageButton btnSettings = view.findViewById(R.id.btn_cloud_settings);
        Button btnCommit = view.findViewById(R.id.btn_action_commit_state);
        Button btnRefresh = view.findViewById(R.id.btn_action_refresh_states);

        TextView tvEndpoint = view.findViewById(R.id.tv_cloud_endpoint_label);
        LinearLayout llContainer = view.findViewById(R.id.ll_cloud_states_container);
        TextView tvEmpty = view.findViewById(R.id.tv_cloud_states_empty);

        tvEndpoint.setText("Endpoint: " + cloudClient.getEndpointUrl());

        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Cloud Endpoint Settings Dialog
        btnSettings.setOnClickListener(v -> {
            Dialog settingsDialog = new Dialog(context);
            settingsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            View settingsView = LayoutInflater.from(context).inflate(R.layout.dialog_cloud_endpoint_settings, null);
            settingsDialog.setContentView(settingsView);

            Window settingsWin = settingsDialog.getWindow();
            if (settingsWin != null) {
                settingsWin.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90);
                settingsWin.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            EditText input = settingsView.findViewById(R.id.edit_endpoint_url);
            Button btnSave = settingsView.findViewById(R.id.btn_save_endpoint);
            Button btnCancel = settingsView.findViewById(R.id.btn_cancel_endpoint);
            ImageButton btnCloseSettings = settingsView.findViewById(R.id.btn_close_endpoint_settings);

            input.setText(cloudClient.getEndpointUrl());

            btnSave.setOnClickListener(saveView -> {
                String newUrl = input.getText() != null ? input.getText().toString().trim() : "";
                if (!newUrl.isEmpty()) {
                    cloudClient.setEndpointUrl(newUrl);
                    tvEndpoint.setText("Endpoint: " + cloudClient.getEndpointUrl());
                    Toast.makeText(context, "Saved cloud endpoint", Toast.LENGTH_SHORT).show();
                    settingsDialog.dismiss();
                } else {
                    Toast.makeText(context, "Please enter a valid URL", Toast.LENGTH_SHORT).show();
                }
            });

            btnCancel.setOnClickListener(cancelView -> settingsDialog.dismiss());
            if (btnCloseSettings != null) {
                btnCloseSettings.setOnClickListener(closeView -> settingsDialog.dismiss());
            }

            settingsDialog.show();
        });

        // Refresh List Runnable
        Runnable refreshList = () -> {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("Fetching state commits from cloud...");
            llContainer.removeAllViews();
            llContainer.addView(tvEmpty);

            cloudClient.listStates(new AdBlockCloudClient.Callback<List<AdBlockCloudClient.StateSummary>>() {
                @Override
                public void onSuccess(List<AdBlockCloudClient.StateSummary> list) {
                    llContainer.removeAllViews();
                    if (list.isEmpty()) {
                        tvEmpty.setText("No community state commits yet. Be the first to commit!");
                        llContainer.addView(tvEmpty);
                        return;
                    }

                    LayoutInflater inflater = LayoutInflater.from(context);
                    SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());

                    for (AdBlockCloudClient.StateSummary summary : list) {
                        View item = inflater.inflate(R.layout.item_cloud_state, llContainer, false);
                        TextView tvTitle = item.findViewById(R.id.tv_state_item_title);
                        TextView tvTime = item.findViewById(R.id.tv_state_item_time);
                        TextView tvAuthor = item.findViewById(R.id.tv_state_item_author);
                        Button btnApply = item.findViewById(R.id.btn_state_item_apply);

                        tvTitle.setText(summary.description);
                        tvTime.setText(summary.timestamp > 0 ? sdf.format(new Date(summary.timestamp)) : "");
                        String filePlural = summary.filesChanged == 1 ? "1 file modified" : summary.filesChanged + " files modified";
                        tvAuthor.setText(filePlural + " | ID: " + summary.stateId);

                        btnApply.setOnClickListener(v -> {
                            promptApplyState(context, summary.stateId, summary.description, cloudClient, fileManager, stateAppliedCallback);
                        });

                        llContainer.addView(item);
                    }
                }

                @Override
                public void onError(String message) {
                    tvEmpty.setText("Cloud sync error: " + message);
                }
            });
        };

        btnRefresh.setOnClickListener(v -> refreshList.run());

        // Commit Current State
        btnCommit.setOnClickListener(v -> {
            promptCommitState(context, cloudClient, fileManager, refreshList);
        });

        refreshList.run();
        dialog.show();
    }

    /**
     * Prompts the user to enter a short description and commits modified diffs to the cloud.
     */
    private static void promptCommitState(@NonNull Context context,
                                          @NonNull AdBlockCloudClient cloudClient,
                                          @NonNull AdBlockFileManager fileManager,
                                          @NonNull Runnable onUploaded) {
        // Collect diffs
        JSONObject diffsObj = new JSONObject();

        java.util.function.Consumer<JSONObject> addDiffIfNonEmpty = (d) -> {
            if (d == null) return;
            JSONArray added = d.optJSONArray("added");
            if (added == null) added = d.optJSONArray("added_lines");
            JSONArray removed = d.optJSONArray("removed");
            if (removed == null) removed = d.optJSONArray("removed_lines");
            int addCount = added != null ? added.length() : 0;
            int remCount = removed != null ? removed.length() : 0;
            if (addCount > 0 || remCount > 0) {
                String fileKey = d.optString("file", "");
                if (!fileKey.isEmpty()) {
                    try {
                        diffsObj.put(fileKey, d);
                    } catch (Exception ignored) {}
                }
            }
        };

        // 1. hosts.txt (skip massive 2MB master blocklist diff on UI thread)
        File hostsFile = fileManager.getFile(AdBlockFileManager.Category.NETWORK, AdBlockFileManager.FILE_HOSTS);
        if (hostsFile.exists() && hostsFile.length() < 100000) {
            String baseHosts = fileManager.readBundledAssetContent(AdBlockFileManager.Category.NETWORK, AdBlockFileManager.FILE_HOSTS);
            String currHosts = fileManager.readFileContent(AdBlockFileManager.Category.NETWORK, AdBlockFileManager.FILE_HOSTS);
            if (!baseHosts.equals(currHosts)) {
                addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("network/" + AdBlockFileManager.FILE_HOSTS, baseHosts, currHosts));
            }
        }

        // 2. user_network.txt
        String baseUserNet = fileManager.readBundledAssetContent(AdBlockFileManager.Category.NETWORK, AdBlockFileManager.FILE_USER_NETWORK);
        String currUserNet = fileManager.readFileContent(AdBlockFileManager.Category.NETWORK, AdBlockFileManager.FILE_USER_NETWORK);
        if (!baseUserNet.equals(currUserNet)) {
            addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("network/" + AdBlockFileManager.FILE_USER_NETWORK, baseUserNet, currUserNet));
        }

        // 3. cosmetic_rules.txt
        String baseCosmetic = fileManager.readBundledAssetContent(AdBlockFileManager.Category.COSMETIC, AdBlockFileManager.FILE_COSMETIC_RULES);
        String currCosmetic = fileManager.readFileContent(AdBlockFileManager.Category.COSMETIC, AdBlockFileManager.FILE_COSMETIC_RULES);
        if (!baseCosmetic.equals(currCosmetic)) {
            addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("cosmetic/" + AdBlockFileManager.FILE_COSMETIC_RULES, baseCosmetic, currCosmetic));
        }

        // 4. user_cosmetic.txt (Element Picker rules)
        String baseUserCosmetic = fileManager.readBundledAssetContent(AdBlockFileManager.Category.COSMETIC, AdBlockFileManager.FILE_USER_COSMETIC);
        String currUserCosmetic = fileManager.readFileContent(AdBlockFileManager.Category.COSMETIC, AdBlockFileManager.FILE_USER_COSMETIC);
        if (!baseUserCosmetic.equals(currUserCosmetic)) {
            addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("cosmetic/" + AdBlockFileManager.FILE_USER_COSMETIC, baseUserCosmetic, currUserCosmetic));
        }

        // 5. anti_adblock.js
        String baseAnti = fileManager.readBundledAssetContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_ANTI_ADBLOCK);
        String currAnti = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_ANTI_ADBLOCK);
        if (!baseAnti.equals(currAnti)) {
            addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("scriptlets/" + AdBlockFileManager.FILE_SCRIPTLET_ANTI_ADBLOCK, baseAnti, currAnti));
        }

        // 6. cosmetic_pruner.js
        String basePrune = fileManager.readBundledAssetContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_COSMETIC_PRUNER);
        String currPrune = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_COSMETIC_PRUNER);
        if (!basePrune.equals(currPrune)) {
            addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("scriptlets/" + AdBlockFileManager.FILE_SCRIPTLET_COSMETIC_PRUNER, basePrune, currPrune));
        }

        // 7. popup_defuser.js
        String basePopup = fileManager.readBundledAssetContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER);
        String currPopup = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER);
        if (!basePopup.equals(currPopup)) {
            addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("scriptlets/" + AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER, basePopup, currPopup));
        }

        // 8. youtube_defuser.js
        String baseYt = fileManager.readBundledAssetContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER);
        String currYt = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER);
        if (!baseYt.equals(currYt)) {
            addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("scriptlets/" + AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER, baseYt, currYt));
        }

        // 9. Custom User Scripts (user_scripts/*.js)
        for (File scriptFile : fileManager.getUserScriptFiles()) {
            String scriptName = scriptFile.getName();
            String currScript = fileManager.readUserScript(scriptName);
            if (!currScript.trim().isEmpty()) {
                addDiffIfNonEmpty.accept(AdBlockDiffEngine.computeDiff("user_scripts/" + scriptName, "", currScript));
            }
        }

        int changedCount = diffsObj.length();
        if (changedCount == 0) {
            Toast.makeText(context, "No modifications detected from factory baseline to commit.", Toast.LENGTH_LONG).show();
            return;
        }

        final int totalChanged = changedCount;

        Dialog commitDialog = new Dialog(context);
        commitDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View commitView = LayoutInflater.from(context).inflate(R.layout.dialog_commit_cloud_state, null);
        commitDialog.setContentView(commitView);

        Window commitWin = commitDialog.getWindow();
        if (commitWin != null) {
            commitWin.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90);
            commitWin.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvTitle = commitView.findViewById(R.id.tv_commit_dialog_title);
        TextView tvSubtitle = commitView.findViewById(R.id.tv_commit_dialog_subtitle);
        TextView tvCommitDiffStats = commitView.findViewById(R.id.tv_commit_diff_stats_summary);
        ScrollView scrollCommitDiff = commitView.findViewById(R.id.scroll_commit_diff_container);
        LinearLayout llCommitDiffContent = commitView.findViewById(R.id.ll_commit_diff_content);
        EditText inputDesc = commitView.findViewById(R.id.edit_commit_description);
        Button btnSubmit = commitView.findViewById(R.id.btn_submit_commit);
        Button btnCancel = commitView.findViewById(R.id.btn_cancel_commit);
        ImageButton btnClose = commitView.findViewById(R.id.btn_close_commit_dialog);

        tvTitle.setText("Commit AdBlock State");
        tvSubtitle.setText(totalChanged + (totalChanged == 1 ? " file modified from baseline" : " files modified from baseline"));

        populateDiffView(context, diffsObj, llCommitDiffContent, tvCommitDiffStats, scrollCommitDiff);

        btnCancel.setOnClickListener(v -> commitDialog.dismiss());
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> commitDialog.dismiss());
        }

        btnSubmit.setOnClickListener(v -> {
            String desc = inputDesc.getText() != null ? inputDesc.getText().toString().trim() : "";
            if (desc.isEmpty()) desc = "AdBlock state update";

            try {
                JSONObject payload = new JSONObject();
                payload.put("description", desc);
                payload.put("author", "User");
                payload.put("files_changed", totalChanged);
                payload.put("diffs", diffsObj);

                Toast.makeText(context, "Uploading commit to cloud...", Toast.LENGTH_SHORT).show();
                commitDialog.dismiss();

                cloudClient.commitState(payload, new AdBlockCloudClient.Callback<String>() {
                    @Override
                    public void onSuccess(String stateId) {
                        Toast.makeText(context, "State committed successfully!", Toast.LENGTH_SHORT).show();
                        onUploaded.run();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(context, "Commit error: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Toast.makeText(context, "Error packing state payload: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        commitDialog.show();
    }

    /**
     * Prompts the user with the three merge modes:
     * - MAKE SAME: overwrite base files to match exact state of uploader
     * - ADD ONLY: merge additions from the commit without removing any local rules/code
     * - REMOVE ONLY: apply removals only
     */
    private static void promptApplyState(@NonNull Context context,
                                         @NonNull String stateId,
                                         @NonNull String description,
                                         @NonNull AdBlockCloudClient cloudClient,
                                         @NonNull AdBlockFileManager fileManager,
                                         @Nullable Runnable onStateApplied) {
        Dialog applyDialog = new Dialog(context);
        applyDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View applyView = LayoutInflater.from(context).inflate(R.layout.dialog_apply_cloud_state, null);
        applyDialog.setContentView(applyView);

        Window applyWin = applyDialog.getWindow();
        if (applyWin != null) {
            applyWin.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90);
            applyWin.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvSubtitle = applyView.findViewById(R.id.tv_apply_dialog_subtitle);
        ImageButton btnClose = applyView.findViewById(R.id.btn_close_apply_dialog);
        TextView tvDiffStatsSummary = applyView.findViewById(R.id.tv_diff_stats_summary);
        ScrollView scrollDiff = applyView.findViewById(R.id.scroll_diff_container);
        LinearLayout llDiffContent = applyView.findViewById(R.id.ll_diff_content);
        TextView tvDiffLoading = applyView.findViewById(R.id.tv_diff_loading);

        View cardMakeSame = applyView.findViewById(R.id.card_mode_make_same);
        View cardAddOnly = applyView.findViewById(R.id.card_mode_add_only);
        View cardRemoveOnly = applyView.findViewById(R.id.card_mode_remove_only);
        Button btnCancel = applyView.findViewById(R.id.btn_cancel_apply);

        tvSubtitle.setText(description != null && !description.isEmpty() ? description : "State ID: " + stateId);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> applyDialog.dismiss());
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> applyDialog.dismiss());
        }

        final JSONObject[] cachedPayload = new JSONObject[]{null};

        // Fetch state payload & diffs from cloud immediately to display in the dialog
        cloudClient.downloadState(stateId, new AdBlockCloudClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject statePayload) {
                cachedPayload[0] = statePayload;
                JSONObject diffs = statePayload.optJSONObject("diffs");
                if (diffs != null && diffs.length() > 0) {
                    populateDiffView(context, diffs, llDiffContent, tvDiffStatsSummary, scrollDiff);
                } else {
                    if (tvDiffLoading != null) {
                        tvDiffLoading.setText("No diffs present in this state.");
                    }
                    if (tvDiffStatsSummary != null) {
                        tvDiffStatsSummary.setText("0 changes");
                    }
                }
            }

            @Override
            public void onError(String message) {
                if (tvDiffLoading != null) {
                    tvDiffLoading.setText("Failed loading diff: " + message);
                    tvDiffLoading.setTextColor(Color.parseColor("#EF4444"));
                }
                if (tvDiffStatsSummary != null) {
                    tvDiffStatsSummary.setText("Error");
                }
            }
        });

        cardMakeSame.setOnClickListener(v -> {
            applyDialog.dismiss();
            applyStatePayload(context, stateId, cachedPayload[0], AdBlockDiffEngine.ApplyMode.MAKE_SAME, cloudClient, fileManager, onStateApplied);
        });

        cardAddOnly.setOnClickListener(v -> {
            applyDialog.dismiss();
            applyStatePayload(context, stateId, cachedPayload[0], AdBlockDiffEngine.ApplyMode.ADD_ONLY, cloudClient, fileManager, onStateApplied);
        });

        cardRemoveOnly.setOnClickListener(v -> {
            applyDialog.dismiss();
            applyStatePayload(context, stateId, cachedPayload[0], AdBlockDiffEngine.ApplyMode.REMOVE_ONLY, cloudClient, fileManager, onStateApplied);
        });

        applyDialog.show();
    }

    private static void applyStatePayload(@NonNull Context context,
                                          @NonNull String stateId,
                                          @Nullable JSONObject preloadedPayload,
                                          @NonNull AdBlockDiffEngine.ApplyMode mode,
                                          @NonNull AdBlockCloudClient cloudClient,
                                          @NonNull AdBlockFileManager fileManager,
                                          @Nullable Runnable onStateApplied) {
        if (preloadedPayload != null) {
            executeMerge(context, preloadedPayload, mode, fileManager, onStateApplied);
            return;
        }

        Toast.makeText(context, "Downloading state payload...", Toast.LENGTH_SHORT).show();
        cloudClient.downloadState(stateId, new AdBlockCloudClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject statePayload) {
                executeMerge(context, statePayload, mode, fileManager, onStateApplied);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(context, "Failed fetching state: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void executeMerge(@NonNull Context context,
                                     @NonNull JSONObject statePayload,
                                     @NonNull AdBlockDiffEngine.ApplyMode mode,
                                     @NonNull AdBlockFileManager fileManager,
                                     @Nullable Runnable onStateApplied) {
        try {
            JSONObject diffs = statePayload.optJSONObject("diffs");
            if (diffs == null) {
                Toast.makeText(context, "State contains no diffs", Toast.LENGTH_SHORT).show();
                return;
            }

            Iterator<String> keys = diffs.keys();
            int appliedCount = 0;

            while (keys.hasNext()) {
                String relPath = keys.next();
                JSONObject fileDiff = diffs.getJSONObject(relPath);

                AdBlockFileManager.Category cat = AdBlockFileManager.Category.SCRIPTLETS;
                String fileName = "";

                if (relPath.startsWith("network/")) {
                    cat = AdBlockFileManager.Category.NETWORK;
                    fileName = relPath.substring("network/".length());
                } else if (relPath.startsWith("cosmetic/")) {
                    cat = AdBlockFileManager.Category.COSMETIC;
                    fileName = relPath.substring("cosmetic/".length());
                } else if (relPath.startsWith("scriptlets/")) {
                    cat = AdBlockFileManager.Category.SCRIPTLETS;
                    fileName = relPath.substring("scriptlets/".length());
                } else if (relPath.startsWith("user_scripts/")) {
                    cat = AdBlockFileManager.Category.USER_SCRIPTS;
                    fileName = relPath.substring("user_scripts/".length());
                }

                String currentContent = fileManager.readFileContent(cat, fileName);
                String newContent = AdBlockDiffEngine.applyDiff(currentContent, fileDiff, mode);
                fileManager.writeFileContent(cat, fileName, newContent);
                appliedCount++;
            }

            // Hot reload engines
            AdBlockManager.getInstance(context).reloadRules();
            UserFiltersManager.getInstance(context).loadRules();
            AntiAdblockDefuser.clearCache();
            PopupClickDefuser.clearCache();
            YouTubeAdDefuser.clearCache();
            com.privacy.browser.adblock.UserScriptManager.getInstance(context).clearCache();

            if (onStateApplied != null) {
                onStateApplied.run();
            }

            String modeName = (mode == AdBlockDiffEngine.ApplyMode.MAKE_SAME) ? "Make Same" :
                (mode == AdBlockDiffEngine.ApplyMode.ADD_ONLY) ? "Add Only" : "Remove Only";
            Toast.makeText(context, "Applied " + modeName + " across " + appliedCount + " files!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "Failed merging state: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void populateDiffView(@NonNull Context context,
                                         @NonNull JSONObject diffs,
                                         @NonNull LinearLayout container,
                                         @NonNull TextView tvStatsSummary,
                                         @NonNull ScrollView scrollView) {
        container.removeAllViews();
        int totalFiles = 0;
        int totalAdded = 0;
        int totalRemoved = 0;

        int textColorPrimary = resolveThemeColor(context, R.attr.textPrimary, Color.WHITE);
        int textColorMuted = resolveThemeColor(context, R.attr.textMuted, Color.GRAY);

        Iterator<String> keys = diffs.keys();
        while (keys.hasNext()) {
            String relPath = keys.next();
            JSONObject fileDiff = diffs.optJSONObject(relPath);
            if (fileDiff == null) continue;

            JSONArray addedArr = fileDiff.optJSONArray("added");
            if (addedArr == null) addedArr = fileDiff.optJSONArray("added_lines");

            JSONArray removedArr = fileDiff.optJSONArray("removed");
            if (removedArr == null) removedArr = fileDiff.optJSONArray("removed_lines");

            int addCount = addedArr != null ? addedArr.length() : 0;
            int remCount = removedArr != null ? removedArr.length() : 0;

            if (addCount == 0 && remCount == 0) continue;

            totalFiles++;
            totalAdded += addCount;
            totalRemoved += remCount;

            // File Header Row
            LinearLayout fileHeader = new LinearLayout(context);
            fileHeader.setOrientation(LinearLayout.HORIZONTAL);
            fileHeader.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lpHeader = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (totalFiles > 1) {
                lpHeader.topMargin = (int) (8 * context.getResources().getDisplayMetrics().density);
            }
            lpHeader.bottomMargin = (int) (2 * context.getResources().getDisplayMetrics().density);
            fileHeader.setLayoutParams(lpHeader);

            TextView tvFile = new TextView(context);
            tvFile.setText(relPath);
            tvFile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvFile.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tvFile.setTextColor(textColorPrimary);
            LinearLayout.LayoutParams lpTvFile = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            tvFile.setLayoutParams(lpTvFile);
            fileHeader.addView(tvFile);

            TextView tvStats = new TextView(context);
            StringBuilder sbStats = new StringBuilder();
            if (addCount > 0) sbStats.append("+").append(addCount);
            if (remCount > 0) {
                if (sbStats.length() > 0) sbStats.append(" / ");
                sbStats.append("-").append(remCount);
            }
            tvStats.setText(sbStats.toString());
            tvStats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvStats.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tvStats.setTextColor(addCount > 0 ? Color.parseColor("#10B981") : Color.parseColor("#EF4444"));
            fileHeader.addView(tvStats);

            container.addView(fileHeader);

            // Added Lines (cap at 25 displayed lines per file for performance)
            if (addedArr != null) {
                int displayLimit = Math.min(addedArr.length(), 25);
                for (int i = 0; i < displayLimit; i++) {
                    String line = addedArr.optString(i, "").trim();
                    if (line.isEmpty()) continue;
                    TextView tvLine = new TextView(context);
                    tvLine.setText("+ " + line);
                    tvLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    tvLine.setTypeface(Typeface.MONOSPACE);
                    tvLine.setTextColor(Color.parseColor("#10B981"));
                    container.addView(tvLine);
                }
                if (addedArr.length() > displayLimit) {
                    TextView tvMore = new TextView(context);
                    tvMore.setText("  ... and " + (addedArr.length() - displayLimit) + " more additions");
                    tvMore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                    tvMore.setTypeface(Typeface.MONOSPACE);
                    tvMore.setTextColor(textColorMuted);
                    container.addView(tvMore);
                }
            }

            // Removed Lines (cap at 25 displayed lines per file)
            if (removedArr != null) {
                int displayLimit = Math.min(removedArr.length(), 25);
                for (int i = 0; i < displayLimit; i++) {
                    String line = removedArr.optString(i, "").trim();
                    if (line.isEmpty()) continue;
                    TextView tvLine = new TextView(context);
                    tvLine.setText("- " + line);
                    tvLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    tvLine.setTypeface(Typeface.MONOSPACE);
                    tvLine.setTextColor(Color.parseColor("#EF4444"));
                    container.addView(tvLine);
                }
                if (removedArr.length() > displayLimit) {
                    TextView tvMore = new TextView(context);
                    tvMore.setText("  ... and " + (removedArr.length() - displayLimit) + " more removals");
                    tvMore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                    tvMore.setTypeface(Typeface.MONOSPACE);
                    tvMore.setTextColor(textColorMuted);
                    container.addView(tvMore);
                }
            }
        }

        if (totalFiles == 0) {
            TextView tvEmpty = new TextView(context);
            tvEmpty.setText("No line changes detected in diff payload.");
            tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvEmpty.setTextColor(textColorMuted);
            container.addView(tvEmpty);
            tvStatsSummary.setText("0 changes");
        } else {
            tvStatsSummary.setText(String.format(Locale.US, "+%d / -%d in %d %s",
                totalAdded, totalRemoved, totalFiles, totalFiles == 1 ? "file" : "files"));
        }

        scrollView.post(() -> {
            int maxHeightPx = (int) (140 * context.getResources().getDisplayMetrics().density);
            if (container.getHeight() > maxHeightPx) {
                ViewGroup.LayoutParams lp = scrollView.getLayoutParams();
                lp.height = maxHeightPx;
                scrollView.setLayoutParams(lp);
            }
        });
    }

    private static int resolveThemeColor(@NonNull Context context, int attrRes, int fallbackColor) {
        TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(attrRes, typedValue, true)) {
            return typedValue.data;
        }
        return fallbackColor;
    }
}

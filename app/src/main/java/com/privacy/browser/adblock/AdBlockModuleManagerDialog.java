package com.privacy.browser.adblock;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.R;
import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.RuleWork;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;
import com.privacy.browser.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-app interactive editor dialog for modifying, creating, validating,
 * and hot-reloading all ad-blocking components (Network hosts, Cosmetic rules,
 * and JavaScript scriptlets) directly from app storage without recompiling.
 */
public class AdBlockModuleManagerDialog {

    public enum ActiveTab {
        NETWORK,
        CUSTOM_NETWORK,
        COSMETIC,
        SCRIPT_ANTI_ADBLOCK,
        SCRIPT_COSMETIC_PRUNER,
        SCRIPT_POPUPS,
        SCRIPT_YOUTUBE,
        USER_SCRIPT
    }

    public static void show(@NonNull Context context, @Nullable Runnable onReloadNeeded) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_adblock_module_manager, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.95);
            int height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.88);
            window.setLayout(width, height);
        }

        AdBlockFileManager fileManager = AdBlockFileManager.getInstance(context);

        ImageButton btnClose = view.findViewById(R.id.btn_close_manager);
        Button btnCloudStates = view.findViewById(R.id.btn_open_cloud_states);
        Button btnTabNetwork = view.findViewById(R.id.btn_tab_network);
        Button btnTabCustomNetwork = view.findViewById(R.id.btn_tab_custom_network);
        Button btnTabCosmetic = view.findViewById(R.id.btn_tab_cosmetic);
        Button btnTabScriptAntiAb = view.findViewById(R.id.btn_tab_script_anti_adblock);
        Button btnTabScriptCosmetic = view.findViewById(R.id.btn_tab_script_cosmetic_pruner);
        Button btnTabScriptPopups = view.findViewById(R.id.btn_tab_script_popups);
        Button btnTabScriptYt = view.findViewById(R.id.btn_tab_script_youtube);

        TextView tvActiveFile = view.findViewById(R.id.tv_active_file_label);
        TextView tvFileStats = view.findViewById(R.id.tv_file_stats);
        EditText editContent = view.findViewById(R.id.edit_code_content);

        Button btnRestoreDefault = view.findViewById(R.id.btn_restore_default);
        Button btnValidateSyntax = view.findViewById(R.id.btn_validate_syntax);
        Button btnSaveAndApply = view.findViewById(R.id.btn_save_and_apply);

        LinearLayout llCustomTabs = view.findViewById(R.id.ll_custom_tabs_container);
        Button btnAddUserScript = view.findViewById(R.id.btn_add_user_script);
        View llUserScriptControls = view.findViewById(R.id.ll_user_script_controls);
        Button btnScriptTiming = view.findViewById(R.id.btn_script_timing);
        Button btnDeleteUserScript = view.findViewById(R.id.btn_delete_user_script);

        final ActiveTab[] currentTab = new ActiveTab[]{ActiveTab.SCRIPT_ANTI_ADBLOCK};
        final boolean[] savingRules = {false};
        final String[] activeUserScript = new String[]{""};
        final FilterSnapshot[] loadedRules = {fileManager.ruleSnapshot()};
        final String[] loadedInitialContent = new String[]{""};
        final String[] activeBundledContent = new String[]{""};
        final boolean[] isCurrentlyDeviated = new boolean[]{false};

        int accentColor = Color.parseColor("#4A88F7");
        int neutralBg = Color.parseColor("#1F2430");
        float density = context.getResources().getDisplayMetrics().density;
        int cornerRadiusPx = (int) (12 * density);

        boolean isDark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int tabActiveBg = Color.parseColor("#4A88F7");
        int tabActiveText = Color.WHITE;
        int tabInactiveBg = isDark ? Color.parseColor("#1E202B") : Color.parseColor("#E6E8EE");
        int tabInactiveBorder = isDark ? Color.parseColor("#34374A") : Color.parseColor("#CBD5E1");
        int tabInactiveText = isDark ? Color.parseColor("#CBD5E1") : Color.parseColor("#334155");

        java.util.function.BiConsumer<Button, Boolean> applyTabStyle = (btn, isSelected) -> {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(8 * density);
            if (isSelected) {
                gd.setColor(tabActiveBg);
                btn.setTextColor(tabActiveText);
            } else {
                gd.setColor(tabInactiveBg);
                gd.setStroke((int) (1 * density), tabInactiveBorder);
                btn.setTextColor(tabInactiveText);
            }
            btn.setBackground(gd);
        };

        java.util.function.Consumer<Boolean> updateRestoreButtonState = (deviated) -> {
            isCurrentlyDeviated[0] = deviated;
            btnRestoreDefault.setEnabled(deviated);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(cornerRadiusPx);
            if (deviated) {
                // Light up with accent highlight
                gd.setColor(Color.parseColor("#E53935")); // Amber/Red alert highlight
                btnRestoreDefault.setTextColor(Color.WHITE);
                btnRestoreDefault.setAlpha(1.0f);
            } else {
                // Dimmed / disabled neutral appearance matching secondary button
                gd.setColor(neutralBg);
                gd.setStroke((int) (1 * density), Color.parseColor("#36364E"));
                btnRestoreDefault.setTextColor(Color.parseColor("#71717A"));
                btnRestoreDefault.setAlpha(0.45f);
            }
            btnRestoreDefault.setBackground(gd);
        };

        Runnable updateStats = () -> {
            if (currentTab[0] == ActiveTab.NETWORK) {
                int count = AdBlockManager.getInstance(context).getBlockedHostsCount();
                double sizeKb = fileManager.ruleSnapshot().bytes(Part.HOSTS) / 1024.0;
                tvFileStats.setText(String.format(Locale.US, "%d rules | %.1f KB (Database View)", count, sizeKb));
                return;
            }
            String text = editContent.getText() != null ? editContent.getText().toString() : "";
            int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
            double sizeKb = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 1024.0;
            tvFileStats.setText(String.format(Locale.US, "%d lines | %.1f KB", lines, sizeKb));
        };

        editContent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Check deviation as soon as edit begins without repeated heavy checking
                if (!isCurrentlyDeviated[0]) {
                    String currentText = s != null ? s.toString() : "";
                    if (!currentText.equals(activeBundledContent[0])) {
                        updateRestoreButtonState.accept(true);
                    }
                }
            }
            @Override
            public void afterTextChanged(Editable s) {
                updateStats.run();
            }
        });

        Runnable loadTab = () -> {
            String content = "";
            String bundled = "";
            String label = "";
            boolean isJs = false;
            loadedRules[0] = fileManager.ruleSnapshot();
            switch (currentTab[0]) {
                case NETWORK:
                    label = "hosts.txt (Network Database - 100k+ rules)";
                    long hSize = fileManager.ruleSnapshot().bytes(Part.HOSTS);
                    int hRules = AdBlockManager.getInstance(context).getBlockedHostsCount();
                    String preview = fileManager.readFilePreview(AdBlockFileManager.Category.NETWORK, AdBlockFileManager.FILE_HOSTS, 150);
                    content = "# ==============================================================================\n" +
                              "# UBLOCK ORIGIN UNIFIED NETWORK FILTER DATABASE (HOSTS.TXT)\n" +
                              "# ==============================================================================\n" +
                              "# Engine Status: Loaded into memory (" + hRules + " rules active, " + String.format(Locale.US, "%.1f KB", hSize / 1024.0) + ")\n" +
                              "# Note: In-place text editing of 100k+ lines is restricted to prevent Android UI freeze.\n" +
                              "# To add custom network rules (||domain^ or @@||domain^), use My Filters.\n" +
                              "#\n" +
                              "# First 150 rules preview:\n" +
                              preview;
                    bundled = content;
                    break;
                case CUSTOM_NETWORK:
                    label = "user_network.txt (Custom block and exception rules)";
                    content = loadedRules[0].text(Part.NETWORK);
                    bundled = fileManager.readBundledAssetContent(AdBlockFileManager.Category.NETWORK, AdBlockFileManager.FILE_USER_NETWORK);
                    break;
                case COSMETIC:
                    label = "cosmetic_rules.txt (EasyList Cosmetic)";
                    content = loadedRules[0].text(Part.COSMETIC);
                    bundled = fileManager.readBundledAssetContent(AdBlockFileManager.Category.COSMETIC, AdBlockFileManager.FILE_COSMETIC_RULES);
                    break;
                case SCRIPT_ANTI_ADBLOCK:
                    label = "anti_adblock.js (Defuser Scriptlet)";
                    content = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_ANTI_ADBLOCK);
                    bundled = fileManager.readBundledAssetContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_ANTI_ADBLOCK);
                    isJs = true;
                    break;
                case SCRIPT_COSMETIC_PRUNER:
                    label = "cosmetic_pruner.js (Cosmetic Stylesheet)";
                    content = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_COSMETIC_PRUNER);
                    bundled = fileManager.readBundledAssetContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_COSMETIC_PRUNER);
                    isJs = true;
                    break;
                case SCRIPT_POPUPS:
                    label = "popup_defuser.js (Window.open & Overlay Defuser)";
                    content = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER);
                    bundled = fileManager.readBundledAssetContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER);
                    isJs = true;
                    break;
                case SCRIPT_YOUTUBE:
                    label = "youtube_defuser.js (In-Stream Video Ad Defuser)";
                    content = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER);
                    bundled = fileManager.readBundledAssetContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER);
                    isJs = true;
                    break;
                case USER_SCRIPT:
                    label = activeUserScript[0] + " (Custom Scriptlet)";
                    content = fileManager.readUserScript(activeUserScript[0]);
                    bundled = "";
                    isJs = true;
                    break;
            }

            loadedInitialContent[0] = content;
            activeBundledContent[0] = bundled;

            Part dataPart = editorPart(currentTab[0]);
            String warning = dataPart == null ? null : fileManager.ruleWarning();
            int unsupported = dataPart == null ? 0 : loadedRules[0].unsupported(dataPart);
            tvActiveFile.setText("Editing: " + label + (warning != null ? "\n" + warning
                    : unsupported > 0 ? "\n" + unsupported + " unsupported legacy lines are inactive." : ""));
            editContent.setText(content);
            btnValidateSyntax.setVisibility(isJs ? View.VISIBLE : View.GONE);
            updateStats.run();

            if (currentTab[0] == ActiveTab.USER_SCRIPT) {
                llUserScriptControls.setVisibility(View.VISIBLE);
                btnRestoreDefault.setVisibility(View.GONE);
                UserScriptManager.ScriptModel model = UserScriptManager.parseScript(activeUserScript[0], content);
                btnScriptTiming.setText(model.runAt.displayLabel());
            } else if (currentTab[0] == ActiveTab.NETWORK) {
                llUserScriptControls.setVisibility(View.GONE);
                btnRestoreDefault.setVisibility(View.GONE);
            } else {
                llUserScriptControls.setVisibility(View.GONE);
                btnRestoreDefault.setVisibility(View.VISIBLE);
            }

            boolean isNetworkDb = (currentTab[0] == ActiveTab.NETWORK);
            editContent.setEnabled(!isNetworkDb && warning == null);
            btnSaveAndApply.setEnabled(!savingRules[0] && !isNetworkDb && warning == null);
            btnSaveAndApply.setAlpha(isNetworkDb ? 0.35f : 1.0f);

            // Check deviation immediately at start of tab load
            boolean initialDeviation = !content.equals(bundled);
            updateRestoreButtonState.accept(initialDeviation && warning == null);

            // Highlight selected tab button
            applyTabStyle.accept(btnTabNetwork, currentTab[0] == ActiveTab.NETWORK);
            applyTabStyle.accept(btnTabCustomNetwork, currentTab[0] == ActiveTab.CUSTOM_NETWORK);
            applyTabStyle.accept(btnTabCosmetic, currentTab[0] == ActiveTab.COSMETIC);
            applyTabStyle.accept(btnTabScriptAntiAb, currentTab[0] == ActiveTab.SCRIPT_ANTI_ADBLOCK);
            applyTabStyle.accept(btnTabScriptCosmetic, currentTab[0] == ActiveTab.SCRIPT_COSMETIC_PRUNER);
            applyTabStyle.accept(btnTabScriptPopups, currentTab[0] == ActiveTab.SCRIPT_POPUPS);
            applyTabStyle.accept(btnTabScriptYt, currentTab[0] == ActiveTab.SCRIPT_YOUTUBE);

            for (int i = 0; i < llCustomTabs.getChildCount(); i++) {
                View child = llCustomTabs.getChildAt(i);
                if (child instanceof Button) {
                    Button b = (Button) child;
                    boolean isSel = (currentTab[0] == ActiveTab.USER_SCRIPT && b.getText().toString().equals(activeUserScript[0]));
                    applyTabStyle.accept(b, isSel);
                }
            }
        };

        Runnable refreshCustomTabs = () -> {
            llCustomTabs.removeAllViews();
            List<File> userScripts = fileManager.getUserScriptFiles();

            for (File f : userScripts) {
                String scriptName = f.getName();
                Button btnScript = new Button(context);
                btnScript.setText(scriptName);
                btnScript.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                btnScript.setAllCaps(false);
                btnScript.setStateListAnimator(null);
                btnScript.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
                btnScript.setMinHeight(0);
                btnScript.setMinimumHeight(0);
                btnScript.setMinWidth(0);
                btnScript.setMinimumWidth(0);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (int) (34 * density)
                );
                lp.setMarginEnd((int) (6 * density));
                btnScript.setLayoutParams(lp);

                boolean isSelected = (currentTab[0] == ActiveTab.USER_SCRIPT && scriptName.equals(activeUserScript[0]));
                applyTabStyle.accept(btnScript, isSelected);

                btnScript.setOnClickListener(v -> {
                    activeUserScript[0] = scriptName;
                    currentTab[0] = ActiveTab.USER_SCRIPT;
                    loadTab.run();
                });

                llCustomTabs.addView(btnScript);
            }
        };

        if (btnCloudStates != null) {
            btnCloudStates.setOnClickListener(v -> {
                if (!editContent.getText().toString().equals(loadedInitialContent[0])) {
                    Toast.makeText(context, "Save or close the current draft before reviewing cloud rules.", Toast.LENGTH_LONG).show();
                    return;
                }
                com.privacy.browser.adblock.cloud.AdBlockCloudStatesDialog.show(context, () -> {
                    loadTab.run();
                    if (onReloadNeeded != null) {
                        onReloadNeeded.run();
                    }
                });
            });
        }

        btnTabNetwork.setOnClickListener(v -> {
            currentTab[0] = ActiveTab.NETWORK;
            activeUserScript[0] = "";
            refreshCustomTabs.run();
            loadTab.run();
        });
        btnTabCustomNetwork.setOnClickListener(v -> {
            currentTab[0] = ActiveTab.CUSTOM_NETWORK;
            activeUserScript[0] = "";
            refreshCustomTabs.run();
            loadTab.run();
        });
        btnTabCosmetic.setOnClickListener(v -> {
            currentTab[0] = ActiveTab.COSMETIC;
            activeUserScript[0] = "";
            refreshCustomTabs.run();
            loadTab.run();
        });
        btnTabScriptAntiAb.setOnClickListener(v -> {
            currentTab[0] = ActiveTab.SCRIPT_ANTI_ADBLOCK;
            activeUserScript[0] = "";
            refreshCustomTabs.run();
            loadTab.run();
        });
        btnTabScriptCosmetic.setOnClickListener(v -> {
            currentTab[0] = ActiveTab.SCRIPT_COSMETIC_PRUNER;
            activeUserScript[0] = "";
            refreshCustomTabs.run();
            loadTab.run();
        });
        btnTabScriptPopups.setOnClickListener(v -> {
            currentTab[0] = ActiveTab.SCRIPT_POPUPS;
            activeUserScript[0] = "";
            refreshCustomTabs.run();
            loadTab.run();
        });
        btnTabScriptYt.setOnClickListener(v -> {
            currentTab[0] = ActiveTab.SCRIPT_YOUTUBE;
            activeUserScript[0] = "";
            refreshCustomTabs.run();
            loadTab.run();
        });

        // Add Custom Scriptlet Button
        if (btnAddUserScript != null) {
            btnAddUserScript.setOnClickListener(v -> {
                Dialog createDialog = new Dialog(context);
                createDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                View createView = LayoutInflater.from(context).inflate(R.layout.dialog_create_user_script, null);
                createDialog.setContentView(createView);

                EditText editName = createView.findViewById(R.id.edit_script_name);
                EditText editMatch = createView.findViewById(R.id.edit_script_match);
                TextView tvPresetDesc = createView.findViewById(R.id.tv_preset_desc);
                LinearLayout layoutChips = createView.findViewById(R.id.layout_preset_chips);
                android.widget.RadioButton rbStart = createView.findViewById(R.id.rb_timing_start);
                android.widget.RadioButton rbEnd = createView.findViewById(R.id.rb_timing_end);
                View optStart = createView.findViewById(R.id.layout_option_doc_start);
                View optEnd = createView.findViewById(R.id.layout_option_doc_end);
                Button btnCancel = createView.findViewById(R.id.btn_cancel_create_script);
                Button btnConfirm = createView.findViewById(R.id.btn_confirm_create_script);
                ImageButton btnCloseCreate = createView.findViewById(R.id.btn_close_create_script);

                List<UserScriptManager.ScriptPreset> presets = UserScriptManager.getBuiltInPresets();
                final int[] selectedPresetIndex = new int[]{0};
                final List<Button> chipButtons = new ArrayList<>();
                int accentBlue = Color.parseColor("#4A88F7");

                Runnable updateChipStyles = () -> {
                    for (int i = 0; i < chipButtons.size(); i++) {
                        Button b = chipButtons.get(i);
                        boolean isSel = (i == selectedPresetIndex[0]);
                        GradientDrawable gd = new GradientDrawable();
                        gd.setCornerRadius(16 * density);
                        if (isSel) {
                            gd.setColor(accentBlue);
                            b.setTextColor(Color.WHITE);
                        } else {
                            gd.setColor(isDark ? Color.parseColor("#1F202E") : Color.parseColor("#EAEAEA"));
                            gd.setStroke((int) (1 * density), isDark ? Color.parseColor("#33354A") : Color.parseColor("#D0D0D0"));
                            b.setTextColor(isDark ? Color.parseColor("#9E9EAF") : Color.parseColor("#555555"));
                        }
                        b.setBackground(gd);
                    }
                };

                for (int i = 0; i < presets.size(); i++) {
                    final int index = i;
                    UserScriptManager.ScriptPreset p = presets.get(i);
                    Button chip = new Button(context);
                    chip.setText(p.label);
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    chip.setAllCaps(false);
                    chip.setStateListAnimator(null);
                    chip.setPadding((int) (14 * density), 0, (int) (14 * density), 0);
                    chip.setMinHeight(0);
                    chip.setMinimumHeight(0);
                    chip.setMinWidth(0);
                    chip.setMinimumWidth(0);

                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            (int) (32 * density)
                    );
                    lp.setMarginEnd((int) (6 * density));
                    chip.setLayoutParams(lp);

                    chip.setOnClickListener(cv -> {
                        selectedPresetIndex[0] = index;
                        updateChipStyles.run();
                        UserScriptManager.ScriptPreset sel = presets.get(index);
                        tvPresetDesc.setText(sel.description);

                        // Auto-fill filename if user hasn't typed a custom one
                        String curName = editName.getText() != null ? editName.getText().toString().trim() : "";
                        boolean isDefault = curName.isEmpty();
                        for (UserScriptManager.ScriptPreset other : presets) {
                            if (curName.equals(other.defaultFileName)) {
                                isDefault = true;
                                break;
                            }
                        }
                        if (isDefault) {
                            editName.setText(sel.defaultFileName);
                            editName.setSelection(sel.defaultFileName.length());
                        }

                        // Auto-fill match
                        editMatch.setText(sel.defaultMatch);

                        // Auto-select recommended timing
                        if (sel.recommendedRunAt == UserScriptManager.RunAt.DOCUMENT_END) {
                            rbStart.setChecked(false);
                            rbEnd.setChecked(true);
                        } else {
                            rbStart.setChecked(true);
                            rbEnd.setChecked(false);
                        }
                    });

                    chipButtons.add(chip);
                    layoutChips.addView(chip);
                }

                updateChipStyles.run();

                optStart.setOnClickListener(v2 -> {
                    rbStart.setChecked(true);
                    rbEnd.setChecked(false);
                });
                optEnd.setOnClickListener(v2 -> {
                    rbStart.setChecked(false);
                    rbEnd.setChecked(true);
                });

                btnCancel.setOnClickListener(v2 -> createDialog.dismiss());
                btnCloseCreate.setOnClickListener(v2 -> createDialog.dismiss());

                btnConfirm.setOnClickListener(v2 -> {
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";
                    if (name.isEmpty()) {
                        Toast.makeText(context, "Please enter a script name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String match = editMatch.getText() != null ? editMatch.getText().toString().trim() : "*";
                    if (match.isEmpty()) {
                        match = "*";
                    }
                    String timing = rbStart.isChecked() ? "document-start" : "document-end";
                    UserScriptManager.ScriptPreset selectedPreset = presets.get(selectedPresetIndex[0]);
                    boolean created = fileManager.createUserScript(name, timing, match, selectedPreset.codeTemplate);
                    if (!created) {
                        Toast.makeText(context, "Script already exists or failed to create", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createDialog.dismiss();
                    String finalFileName = name.endsWith(".js") ? name : name + ".js";
                    activeUserScript[0] = finalFileName;
                    currentTab[0] = ActiveTab.USER_SCRIPT;
                    refreshCustomTabs.run();
                    loadTab.run();
                    Toast.makeText(context, "Created " + finalFileName, Toast.LENGTH_SHORT).show();
                    if (onReloadNeeded != null) onReloadNeeded.run();
                });

                createDialog.show();
                Window createWin = createDialog.getWindow();
                if (createWin != null) {
                    createWin.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    DisplayMetrics dm = context.getResources().getDisplayMetrics();
                    int dialogWidth = Math.min((int) (dm.widthPixels * 0.92f), (int) (520 * dm.density));
                    createWin.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            });
        }

        // Script Timing Toggle
        if (btnScriptTiming != null) {
            btnScriptTiming.setOnClickListener(v -> {
                if (currentTab[0] != ActiveTab.USER_SCRIPT || activeUserScript[0].isEmpty()) return;
                String curText = editContent.getText() != null ? editContent.getText().toString() : "";
                UserScriptManager.ScriptModel curModel = UserScriptManager.parseScript(activeUserScript[0], curText);
                UserScriptManager.RunAt nextTiming = (curModel.runAt == UserScriptManager.RunAt.DOCUMENT_START)
                        ? UserScriptManager.RunAt.DOCUMENT_END
                        : UserScriptManager.RunAt.DOCUMENT_START;
                String updatedText = UserScriptManager.updateRunAtInContent(curText, nextTiming);
                editContent.setText(updatedText);
                btnScriptTiming.setText(nextTiming.displayLabel());
                Toast.makeText(context, "Timing changed to " + nextTiming.displayLabel(), Toast.LENGTH_SHORT).show();
            });
        }

        // Delete Custom Scriptlet Button
        if (btnDeleteUserScript != null) {
            btnDeleteUserScript.setOnClickListener(v -> {
                if (currentTab[0] != ActiveTab.USER_SCRIPT || activeUserScript[0].isEmpty()) return;
                Dialog deleteDialog = new Dialog(context);
                deleteDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                View deleteView = LayoutInflater.from(context).inflate(R.layout.dialog_delete_user_script, null);
                deleteDialog.setContentView(deleteView);

                TextView tvScriptName = deleteView.findViewById(R.id.tv_delete_script_name);
                Button btnCancelDel = deleteView.findViewById(R.id.btn_cancel_delete);
                Button btnConfirmDel = deleteView.findViewById(R.id.btn_confirm_delete);
                ImageButton btnCloseDel = deleteView.findViewById(R.id.btn_close_delete_dialog);

                tvScriptName.setText(activeUserScript[0]);

                GradientDrawable dangerGd = new GradientDrawable();
                dangerGd.setCornerRadius(8 * density);
                dangerGd.setColor(Color.parseColor("#EF4444"));
                btnConfirmDel.setBackground(dangerGd);

                GradientDrawable cancelGd = new GradientDrawable();
                cancelGd.setCornerRadius(8 * density);
                cancelGd.setColor(isDark ? Color.parseColor("#1F202E") : Color.parseColor("#EAEAEA"));
                cancelGd.setStroke((int) (1 * density), isDark ? Color.parseColor("#33354A") : Color.parseColor("#CBD5E1"));
                btnCancelDel.setBackground(cancelGd);
                btnCancelDel.setTextColor(isDark ? Color.parseColor("#9E9EAF") : Color.parseColor("#555555"));

                btnCancelDel.setOnClickListener(v2 -> deleteDialog.dismiss());
                if (btnCloseDel != null) btnCloseDel.setOnClickListener(v2 -> deleteDialog.dismiss());

                btnConfirmDel.setOnClickListener(v2 -> {
                    deleteDialog.dismiss();
                    String deletedFile = activeUserScript[0];
                    if (!fileManager.deleteUserScript(deletedFile)) {
                        Toast.makeText(context, "Could not delete script", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    UserScriptManager.getInstance(context).clearCache();
                    Toast.makeText(context, "Deleted " + deletedFile, Toast.LENGTH_SHORT).show();
                    currentTab[0] = ActiveTab.NETWORK;
                    activeUserScript[0] = "";
                    refreshCustomTabs.run();
                    loadTab.run();
                    if (onReloadNeeded != null) onReloadNeeded.run();
                });

                deleteDialog.show();
                Window deleteWin = deleteDialog.getWindow();
                if (deleteWin != null) {
                    deleteWin.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    DisplayMetrics dm = context.getResources().getDisplayMetrics();
                    int dialogWidth = Math.min((int) (dm.widthPixels * 0.88f), (int) (380 * dm.density));
                    deleteWin.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            });
        }

        // A short-lived local parser view, with no network or host-file access.
        WebView[] validationView = new WebView[1];
        Runnable disposeValidator = () -> {
            WebView closing = validationView[0];
            validationView[0] = null;
            if (closing != null) com.privacy.browser.WebViewDisposal.detachAndDestroy(closing);
        };
        dialog.setOnDismissListener(d -> disposeValidator.run());
        btnValidateSyntax.setOnClickListener(v -> {
            disposeValidator.run();
            String code = editContent.getText() == null ? "" : editContent.getText().toString();
            if (code.length() > 256 * 1024) {
                Toast.makeText(context, "Script is too large to validate here", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                WebView testView = new WebView(context);
                validationView[0] = testView;
                testView.getSettings().setJavaScriptEnabled(true);
                testView.getSettings().setBlockNetworkLoads(true);
                testView.getSettings().setAllowFileAccess(false);
                testView.getSettings().setAllowContentAccess(false);
                testView.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override
                    public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                        if (view == validationView[0]) {
                            disposeValidator.run();
                            if (dialog.isShowing()) Toast.makeText(context, "Syntax checker stopped; try again", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                });
                testView.evaluateJavascript("try { new Function(" + org.json.JSONObject.quote(code)
                        + "); 'SYNTAX_OK'; } catch(e) { 'SYNTAX_ERROR: ' + e.message; }", result -> {
                    if (validationView[0] != testView) return;
                    disposeValidator.run();
                    if (dialog.isShowing()) {
                        String message = "\"SYNTAX_OK\"".equals(result) ? "JavaScript syntax is valid" : "JavaScript syntax could not be validated";
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (RuntimeException e) {
                disposeValidator.run();
                Toast.makeText(context, "Syntax check unavailable", Toast.LENGTH_SHORT).show();
            }
        });

        // Restore Default
        btnRestoreDefault.setOnClickListener(v -> {
            boolean restored = false;
            switch (currentTab[0]) {
                case CUSTOM_NETWORK:
                case COSMETIC:
                    editContent.setText(activeBundledContent[0]);
                    updateRestoreButtonState.accept(false);
                    Toast.makeText(context, "Default loaded into the editor. Save to apply it.", Toast.LENGTH_LONG).show();
                    return;
                case NETWORK:
                    return; // Never save/reset the hosts preview as an editable document.
                case SCRIPT_ANTI_ADBLOCK:
                    restored = fileManager.restoreToDefault(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_ANTI_ADBLOCK);
                    break;
                case SCRIPT_COSMETIC_PRUNER:
                    restored = fileManager.restoreToDefault(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_COSMETIC_PRUNER);
                    break;
                case SCRIPT_POPUPS:
                    restored = fileManager.restoreToDefault(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER);
                    break;
                case SCRIPT_YOUTUBE:
                    restored = fileManager.restoreToDefault(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER);
                    break;
            }
            if (restored) {
                switch (currentTab[0]) {
                    case SCRIPT_ANTI_ADBLOCK:
                    case SCRIPT_COSMETIC_PRUNER: AntiAdblockDefuser.clearCache(); break;
                    case SCRIPT_POPUPS: PopupClickDefuser.clearCache(); break;
                    case SCRIPT_YOUTUBE: YouTubeAdDefuser.clearCache(); break;
                    default: break;
                }
                if (onReloadNeeded != null) onReloadNeeded.run();
                Toast.makeText(context, "Restored default configuration", Toast.LENGTH_SHORT).show();
                loadTab.run();
            } else {
                Toast.makeText(context, "Failed to restore defaults", Toast.LENGTH_SHORT).show();
            }
        });

        // Save & Apply
        btnSaveAndApply.setOnClickListener(v -> {
            String newContent = editContent.getText() != null ? editContent.getText().toString() : "";
            boolean saved = false;
            switch (currentTab[0]) {
                case CUSTOM_NETWORK:
                case COSMETIC:
                    if (savingRules[0]) return;
                    savingRules[0] = true;
                    btnSaveAndApply.setEnabled(false);
                    Part editing = editorPart(currentTab[0]);
                    FilterSnapshot expected = loadedRules[0];
                    RuleWork.run(() -> {
                        FilterSnapshot next = expected.replace(editing, newContent);
                        fileManager.applyRuleSnapshot(expected, next);
                        return next;
                    }, (next, error) -> {
                        savingRules[0] = false;
                        if (context instanceof android.app.Activity) {
                            android.app.Activity owner = (android.app.Activity) context;
                            if (owner.isFinishing() || owner.isDestroyed()) return;
                        }
                        if (error == null && next != expected && onReloadNeeded != null) onReloadNeeded.run();
                        if (!dialog.isShowing()) return;
                        btnSaveAndApply.setEnabled(currentTab[0] != ActiveTab.NETWORK && fileManager.ruleWarning() == null);
                        if (error != null) {
                            Toast.makeText(context, "Rules not saved: " + RuleWork.message(error), Toast.LENGTH_LONG).show();
                        } else {
                            // Do not clobber another section or a newer unsaved draft while the worker was busy.
                            if (editing == editorPart(currentTab[0]) && newContent.equals(editContent.getText().toString())) loadTab.run();
                            Toast.makeText(context, "Saved local rules", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                case NETWORK:
                    return;
                case SCRIPT_ANTI_ADBLOCK:
                    saved = fileManager.writeFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_ANTI_ADBLOCK, newContent);
                    if (saved) AntiAdblockDefuser.clearCache();
                    break;
                case SCRIPT_COSMETIC_PRUNER:
                    saved = fileManager.writeFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_COSMETIC_PRUNER, newContent);
                    if (saved) AntiAdblockDefuser.clearCache();
                    break;
                case SCRIPT_POPUPS:
                    saved = fileManager.writeFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER, newContent);
                    if (saved) PopupClickDefuser.clearCache();
                    break;
                case SCRIPT_YOUTUBE:
                    saved = fileManager.writeFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER, newContent);
                    if (saved) YouTubeAdDefuser.clearCache();
                    break;
                case USER_SCRIPT:
                    saved = fileManager.writeUserScript(activeUserScript[0], newContent);
                    if (saved) {
                        UserScriptManager.getInstance(context).clearCache();
                    }
                    break;
            }

            if (saved) {
                Toast.makeText(context, "Saved & hot-reloaded into adblock engine", Toast.LENGTH_SHORT).show();
                if (onReloadNeeded != null) {
                    onReloadNeeded.run();
                }
            } else {
                Toast.makeText(context, "Error saving file to storage", Toast.LENGTH_SHORT).show();
            }
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Initialize custom script tabs and first tab
        refreshCustomTabs.run();
        loadTab.run();

        dialog.show();
    }

    private static Part editorPart(ActiveTab tab) {
        if (tab == ActiveTab.CUSTOM_NETWORK) return Part.NETWORK;
        if (tab == ActiveTab.COSMETIC) return Part.COSMETIC;
        return null;
    }

    private static String quoteJs(String str) {
        if (str == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}

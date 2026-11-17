package com.privacy.browser.adblock.picker;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.R;
import com.privacy.browser.ThemeManager;
import com.privacy.browser.adblock.AdBlockManager;
import com.privacy.browser.adblock.rules.UserFiltersManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive uBlock Origin-style Element Picker Dialog allowing selector inspection,
 * hierarchy widening/narrowing, live hiding preview, and custom cosmetic rule creation.
 */
public class ElementPickerDialog {

    public static class NodeInfo {
        public String tag;
        public String id;
        public List<String> classes = new ArrayList<>();
        public String text;
        public List<String> selectors = new ArrayList<>();
    }

    public static void show(@NonNull Context context,
                            @NonNull WebView webView,
                            @Nullable String domain,
                            @NonNull String nodesJson,
                            @Nullable Runnable onFilterApplied) {
        List<NodeInfo> nodes = parseNodes(nodesJson);
        if (nodes.isEmpty()) return;

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_element_picker, null);
        dialog.setContentView(view);

        boolean isDark = ThemeManager.isAppDarkTheme(context);
        float density = context.getResources().getDisplayMetrics().density;
        view.setBackgroundResource(isDark ? R.drawable.bg_picker_dialog_surface : R.drawable.bg_picker_dialog_surface_light);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.0f);
        }

        String cleanDomain = AdBlockManager.extractCleanHost(domain);
        if (cleanDomain == null || cleanDomain.isEmpty()) {
            cleanDomain = "example.com";
        }

        TextView tvDomain = view.findViewById(R.id.tv_picker_domain);
        tvDomain.setText(cleanDomain);

        View stepperContainer = view.findViewById(R.id.layout_stepper_container);
        ImageButton btnClose = view.findViewById(R.id.btn_picker_close);
        Button btnNarrow = view.findViewById(R.id.btn_hierarchy_narrow);
        Button btnWider = view.findViewById(R.id.btn_hierarchy_wider);
        TextView tvNode = view.findViewById(R.id.tv_hierarchy_node);
        LinearLayout llChips = view.findViewById(R.id.ll_selector_chips);
        EditText editRule = view.findViewById(R.id.edit_filter_rule);
        Button btnPreview = view.findViewById(R.id.btn_picker_preview);
        Button btnCreate = view.findViewById(R.id.btn_picker_create);

        SeekBar seekOpacity = view.findViewById(R.id.seek_picker_opacity);
        TextView tvOpacityValue = view.findViewById(R.id.tv_picker_opacity_value);

        android.content.SharedPreferences prefs = context.getSharedPreferences("element_picker_prefs", Context.MODE_PRIVATE);
        int initialOpacity = prefs.getInt("picker_opacity", 45);
        if (initialOpacity < 10) initialOpacity = 10;
        if (initialOpacity > 100) initialOpacity = 100;

        java.util.function.IntConsumer applyOpacity = (int percent) -> {
            int clamped = Math.max(10, Math.min(100, percent));
            if (tvOpacityValue != null) {
                tvOpacityValue.setText(clamped + "%");
            }
            int alpha255 = (int) (clamped * 2.55f);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadii(new float[]{20 * density, 20 * density, 20 * density, 20 * density, 0, 0, 0, 0});
            int baseColor = isDark ? Color.parseColor("#0F0F16") : Color.parseColor("#F8FAFC");
            int colorWithAlpha = Color.argb(alpha255, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor));
            int strokeAlpha = Math.min(255, (int) (alpha255 * 1.25f));
            int strokeBase = isDark ? Color.parseColor("#484860") : Color.parseColor("#CBD5E1");
            int strokeWithAlpha = Color.argb(strokeAlpha, Color.red(strokeBase), Color.green(strokeBase), Color.blue(strokeBase));
            bg.setColor(colorWithAlpha);
            bg.setStroke((int) (1 * density), strokeWithAlpha);
            view.setBackground(bg);

            int boxAlpha = Math.max(15, (int) (alpha255 * 0.65f));
            int boxColor = Color.argb(boxAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor));
            int boxStrokeColor = Color.argb(Math.min(255, (int) (boxAlpha * 1.3f)), Color.red(strokeBase), Color.green(strokeBase), Color.blue(strokeBase));

            GradientDrawable boxBg = new GradientDrawable();
            boxBg.setShape(GradientDrawable.RECTANGLE);
            boxBg.setCornerRadius(10 * density);
            boxBg.setColor(boxColor);
            boxBg.setStroke((int) (1 * density), boxStrokeColor);
            if (stepperContainer != null) {
                stepperContainer.setBackground(boxBg);
            }

            GradientDrawable boxBg2 = new GradientDrawable();
            boxBg2.setShape(GradientDrawable.RECTANGLE);
            boxBg2.setCornerRadius(10 * density);
            boxBg2.setColor(boxColor);
            boxBg2.setStroke((int) (1 * density), boxStrokeColor);
            editRule.setBackground(boxBg2);
        };

        if (seekOpacity != null) {
            seekOpacity.setProgress(initialOpacity);
            applyOpacity.accept(initialOpacity);

            seekOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int clamped = Math.max(10, progress);
                        applyOpacity.accept(clamped);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    int finalProgress = Math.max(10, seekBar.getProgress());
                    prefs.edit().putInt("picker_opacity", finalProgress).apply();
                }
            });
        } else {
            applyOpacity.accept(initialOpacity);
        }

        GradientDrawable stepperNarrowBg = new GradientDrawable();
        stepperNarrowBg.setShape(GradientDrawable.RECTANGLE);
        stepperNarrowBg.setCornerRadius(10 * density);
        stepperNarrowBg.setColor(isDark ? Color.parseColor("#4D181824") : Color.parseColor("#66F1F5F9"));
        stepperNarrowBg.setStroke((int) (1 * density), isDark ? Color.parseColor("#4D36364E") : Color.parseColor("#66CBD5E1"));
        btnNarrow.setBackground(stepperNarrowBg);

        GradientDrawable stepperWiderBg = new GradientDrawable();
        stepperWiderBg.setShape(GradientDrawable.RECTANGLE);
        stepperWiderBg.setCornerRadius(10 * density);
        stepperWiderBg.setColor(isDark ? Color.parseColor("#4D181824") : Color.parseColor("#66F1F5F9"));
        stepperWiderBg.setStroke((int) (1 * density), isDark ? Color.parseColor("#4D36364E") : Color.parseColor("#66CBD5E1"));
        btnWider.setBackground(stepperWiderBg);

        final int[] currentDepth = {0};
        final boolean[] isPreviewing = {false};
        final String finalCleanDomain = cleanDomain;

        Runnable updatePreviewBtn = () -> {
            GradientDrawable pd = new GradientDrawable();
            pd.setShape(GradientDrawable.RECTANGLE);
            pd.setCornerRadius(12 * density);
            if (isPreviewing[0]) {
                btnPreview.setText("Reset Preview");
                pd.setColor(isDark ? Color.parseColor("#4DF59E0B") : Color.parseColor("#66FEF3C7"));
                pd.setStroke((int) (1.2f * density), Color.parseColor("#F59E0B"));
                btnPreview.setTextColor(Color.parseColor("#F59E0B"));
            } else {
                btnPreview.setText("Preview");
                pd.setColor(isDark ? Color.parseColor("#4D181824") : Color.parseColor("#66F1F5F9"));
                pd.setStroke((int) (1 * density), isDark ? Color.parseColor("#4D36364E") : Color.parseColor("#66CBD5E1"));
                btnPreview.setTextColor(isDark ? Color.parseColor("#F1F5F9") : Color.parseColor("#0F172A"));
            }
            btnPreview.setBackground(pd);
        };
        updatePreviewBtn.run();

        Runnable updateUI = () -> {
            NodeInfo node = nodes.get(currentDepth[0]);
            String nodeDisplay = node.tag;
            if (!TextUtils.isEmpty(node.id)) {
                nodeDisplay += "#" + node.id;
            } else if (!node.classes.isEmpty()) {
                nodeDisplay += "." + node.classes.get(0);
            }
            tvNode.setText(nodeDisplay + " (" + (currentDepth[0] + 1) + "/" + nodes.size() + ")");

            btnNarrow.setEnabled(currentDepth[0] > 0);
            btnNarrow.setAlpha(currentDepth[0] > 0 ? 1.0f : 0.4f);

            btnWider.setEnabled(currentDepth[0] < nodes.size() - 1);
            btnWider.setAlpha(currentDepth[0] < nodes.size() - 1 ? 1.0f : 0.4f);

            // Rebuild chips
            llChips.removeAllViews();
            String defaultSelector = node.selectors.isEmpty() ? node.tag : node.selectors.get(0);
            editRule.setText(finalCleanDomain + "##" + defaultSelector);

            Runnable refreshChipStyles = () -> {
                String currentRule = editRule.getText().toString().trim();
                String currentSel = extractSelectorFromRule(currentRule);
                for (int i = 0; i < llChips.getChildCount(); i++) {
                    View child = llChips.getChildAt(i);
                    if (child instanceof Button) {
                        Button c = (Button) child;
                        boolean isSel = c.getText().toString().equals(currentSel);
                        GradientDrawable cd = new GradientDrawable();
                        cd.setShape(GradientDrawable.RECTANGLE);
                        cd.setCornerRadius(15 * density);
                        if (isSel) {
                            cd.setColor(isDark ? Color.parseColor("#4D10B981") : Color.parseColor("#66D1FAE5"));
                            cd.setStroke((int) (1.5f * density), Color.parseColor("#10B981"));
                            c.setTextColor(isDark ? Color.parseColor("#34D399") : Color.parseColor("#065F46"));
                        } else {
                            cd.setColor(isDark ? Color.parseColor("#4D181824") : Color.parseColor("#66E2E8F0"));
                            cd.setStroke((int) (1 * density), isDark ? Color.parseColor("#4D3D3D56") : Color.parseColor("#66CBD5E1"));
                            c.setTextColor(isDark ? Color.parseColor("#F1F5F9") : Color.parseColor("#0F172A"));
                        }
                        c.setBackground(cd);
                    }
                }
            };

            for (String sel : node.selectors) {
                Button chip = new Button(context);
                chip.setText(sel);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                chip.setTypeface(Typeface.MONOSPACE);
                chip.setAllCaps(false);
                chip.setStateListAnimator(null);
                chip.setPadding((int) (14 * density), 0, (int) (14 * density), 0);
                chip.setMinHeight(0);
                chip.setMinimumHeight(0);
                chip.setMinWidth(0);
                chip.setMinimumWidth(0);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (int) (30 * density)
                );
                lp.setMarginEnd((int) (8 * density));
                chip.setLayoutParams(lp);

                chip.setOnClickListener(v -> {
                    editRule.setText(finalCleanDomain + "##" + sel);
                    refreshChipStyles.run();
                    if (isPreviewing[0]) {
                        webView.evaluateJavascript(ElementPickerEngine.getPreviewScript(sel), null);
                    }
                });
                llChips.addView(chip);
            }
            refreshChipStyles.run();

            // Highlight node in webView
            webView.evaluateJavascript(ElementPickerEngine.getHighlightNodeScript(currentDepth[0]), null);
        };

        btnNarrow.setOnClickListener(v -> {
            if (currentDepth[0] > 0) {
                currentDepth[0]--;
                updateUI.run();
            }
        });

        btnWider.setOnClickListener(v -> {
            if (currentDepth[0] < nodes.size() - 1) {
                currentDepth[0]++;
                updateUI.run();
            }
        });

        btnPreview.setOnClickListener(v -> {
            String fullRule = editRule.getText().toString().trim();
            String selector = extractSelectorFromRule(fullRule);
            if (isPreviewing[0]) {
                // Clear preview
                webView.evaluateJavascript(ElementPickerEngine.getPreviewScript(""), null);
                isPreviewing[0] = false;
            } else {
                // Apply preview
                webView.evaluateJavascript(ElementPickerEngine.getPreviewScript(selector), null);
                isPreviewing[0] = true;
            }
            updatePreviewBtn.run();
        });

        btnCreate.setOnClickListener(v -> {
            String rule = editRule.getText().toString().trim();
            if (!TextUtils.isEmpty(rule)) {
                UserFiltersManager.getInstance(context).addRule(rule);
                String selector = extractSelectorFromRule(rule);
                // Permanently inject hiding style
                String hideJs = "var s = document.createElement('style');" +
                        "s.textContent = '" + selector.replace("'", "\\'") + " { display: none !important; }';" +
                        "(document.head || document.documentElement).appendChild(s);";
                webView.evaluateJavascript(hideJs, null);
                webView.evaluateJavascript(ElementPickerEngine.getCleanupScript(), null);

                Toast.makeText(context, "Filter created: " + rule, Toast.LENGTH_SHORT).show();
                dialog.dismiss();

                if (onFilterApplied != null) {
                    onFilterApplied.run();
                }
            }
        });

        btnClose.setOnClickListener(v -> {
            webView.evaluateJavascript(ElementPickerEngine.getCleanupScript(), null);
            dialog.dismiss();
        });

        dialog.setOnCancelListener(d -> webView.evaluateJavascript(ElementPickerEngine.getCleanupScript(), null));

        updateUI.run();
        dialog.show();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.0f);
        }
    }

    private static String extractSelectorFromRule(String rule) {
        int hash = rule.indexOf("##");
        if (hash != -1) {
            return rule.substring(hash + 2).trim();
        }
        return rule;
    }

    private static List<NodeInfo> parseNodes(String json) {
        List<NodeInfo> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                NodeInfo info = new NodeInfo();
                info.tag = obj.optString("tag", "div");
                info.id = obj.optString("id", "");
                info.text = obj.optString("text", "");

                JSONArray classesArr = obj.optJSONArray("classes");
                if (classesArr != null) {
                    for (int c = 0; c < classesArr.length(); c++) {
                        info.classes.add(classesArr.getString(c));
                    }
                }

                JSONArray selArr = obj.optJSONArray("selectors");
                if (selArr != null) {
                    for (int s = 0; s < selArr.length(); s++) {
                        info.selectors.add(selArr.getString(s));
                    }
                }
                list.add(info);
            }
        } catch (Exception ignored) {}
        return list;
    }
}

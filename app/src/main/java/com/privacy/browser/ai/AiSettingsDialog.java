package com.privacy.browser.ai;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.privacy.browser.R;
import com.privacy.browser.ThemeManager;

import java.util.function.Consumer;

/** The original Agent settings card presentation, with only the supported consent choices. */
final class AiSettingsDialog {
    enum LocalAction { REVIEW_RECIPE, REMINDERS, CLEANUP }

    private AiSettingsDialog() { }

    static Dialog create(Activity owner, Runnable onSaved, Consumer<LocalAction> onLocalAction) {
        AiConfigManager config = new AiConfigManager(owner);
        boolean dark = ThemeManager.isAppDarkTheme(owner);
        ContextThemeWrapper themed = new ContextThemeWrapper(owner,
                dark ? R.style.Theme_MinimalistBrowser : R.style.Theme_MinimalistBrowser_Light);
        Dialog dialog = new Dialog(owner, ThemeManager.getDialogTheme(owner));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View panel = LayoutInflater.from(themed).inflate(R.layout.dialog_ai_settings, null);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            DisplayMetrics metrics = owner.getResources().getDisplayMetrics();
            int width = Math.min(Math.round(metrics.widthPixels * .94f), Math.round(500 * metrics.density));
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(.55f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        panel.findViewById(R.id.ll_settings_root).requestFocus();

        int card = dark ? R.drawable.bg_settings_card : R.drawable.bg_settings_card_light;
        int field = dark ? R.drawable.bg_agent_input_bar : R.drawable.bg_agent_input_bar_light;
        panel.findViewById(R.id.agent_settings_provider_card).setBackgroundResource(card);
        panel.findViewById(R.id.agent_settings_tools_card).setBackgroundResource(card);
        EditText model = panel.findViewById(R.id.et_gemini_model);
        EditText key = panel.findViewById(R.id.et_gemini_api_key);
        model.setBackgroundResource(field);
        key.setBackgroundResource(field);
        model.setText(config.geminiModel());
        key.setText(config.geminiKey());

        RadioGroup providers = panel.findViewById(R.id.rg_agent_chat_provider);
        RadioButton gemini = panel.findViewById(R.id.tab_provider_gemini);
        RadioButton off = panel.findViewById(R.id.tab_provider_off);
        View fields = panel.findViewById(R.id.layout_gemini_config);
        TextView title = panel.findViewById(R.id.tv_agent_settings_recipient);
        TextView summary = panel.findViewById(R.id.tv_provider_summary);
        providers.setOnCheckedChangeListener((group, checkedId) -> {
            boolean cloud = checkedId == R.id.tab_provider_gemini;
            styleChoice(gemini, cloud, dark);
            styleChoice(off, !cloud, dark);
            title.setText(cloud ? R.string.agent_settings_recipient : R.string.agent_settings_off_title);
            summary.setText(cloud ? R.string.agent_settings_disclosure : R.string.agent_settings_off_summary);
            fields.setVisibility(cloud ? View.VISIBLE : View.GONE);
        });
        providers.check(config.isGeminiSelected() ? R.id.tab_provider_gemini : R.id.tab_provider_off);

        Button cancel = panel.findViewById(R.id.btn_agent_settings_cancel);
        Button save = panel.findViewById(R.id.btn_agent_settings_save);
        styleButton(cancel, dark ? R.drawable.bg_settings_btn_secondary : R.drawable.bg_settings_btn_secondary_light, dark);
        styleButton(save, R.drawable.bg_agent_pill_selected, true);
        panel.findViewById(R.id.btn_settings_close).setOnClickListener(v -> dialog.dismiss());
        cancel.setOnClickListener(v -> dialog.dismiss());
        bind(save, dialog, owner, () -> {
            if (providers.getCheckedRadioButtonId() == R.id.tab_provider_gemini) {
                String modelId = model.getText().toString().trim();
                String token = key.getText().toString().trim();
                if (!GeminiSettings.validModel(modelId)) {
                    model.requestFocus();
                    model.setError(themed.getString(R.string.agent_settings_model_error));
                    return;
                }
                if (!GeminiSettings.validKey(token)) {
                    key.requestFocus();
                    key.setError(themed.getString(R.string.agent_settings_key_error));
                    return;
                }
                config.selectGemini(modelId, token);
            } else {
                config.turnOffCloudChat();
            }
            dialog.dismiss();
            onSaved.run();
        });

        bindAction(panel, R.id.btn_agent_settings_review, dialog, owner, onLocalAction, LocalAction.REVIEW_RECIPE);
        bindAction(panel, R.id.btn_agent_settings_reminders, dialog, owner, onLocalAction, LocalAction.REMINDERS);
        bindAction(panel, R.id.btn_agent_settings_cleanup, dialog, owner, onLocalAction, LocalAction.CLEANUP);
        Button forget = panel.findViewById(R.id.btn_agent_settings_forget_login);
        if (config.hasLegacyCloudCodeCredential()) {
            forget.setVisibility(View.VISIBLE);
            bind(forget, dialog, owner, () -> {
                config.forgetLegacyCloudCodeCredential();
                forget.setText(R.string.agent_settings_login_forgotten);
                forget.setEnabled(false);
            });
        }
        return dialog;
    }

    private static void styleChoice(RadioButton choice, boolean checked, boolean dark) {
        int background = checked ? R.drawable.bg_agent_pill_selected
                : dark ? R.drawable.bg_agent_pill_unselected : R.drawable.bg_agent_pill_unselected_light;
        styleButton(choice, background, checked || dark);
        choice.setTextColor(checked ? Color.WHITE : dark ? 0xFFCBD5E1 : 0xFF334155);
    }

    private static void styleButton(View button, int background, boolean dark) {
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x22FFFFFF : 0x1A1E293B),
                button.getContext().getDrawable(background), null));
    }

    private static void bind(View view, Dialog dialog, Activity owner, Runnable action) {
        view.setFilterTouchesWhenObscured(true);
        view.setOnClickListener(v -> {
            if (dialog.isShowing() && !owner.isFinishing() && !owner.isDestroyed()) action.run();
        });
    }

    private static void bindAction(View panel, int id, Dialog dialog, Activity owner,
                                   Consumer<LocalAction> handler, LocalAction action) {
        bind(panel.findViewById(id), dialog, owner, () -> {
            dialog.dismiss();
            handler.accept(action);
        });
    }
}

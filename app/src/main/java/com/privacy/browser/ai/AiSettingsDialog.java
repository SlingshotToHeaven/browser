package com.privacy.browser.ai;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.privacy.browser.ThemeManager;

final class AiSettingsDialog {
    private AiSettingsDialog() { }

    static void show(Context context, Runnable onSaved) {
        AiConfigManager config = new AiConfigManager(context);
        AlertDialog.Builder builder = new AlertDialog.Builder(context, ThemeManager.getDialogTheme(context));
        Context theme = builder.getContext();
        LinearLayout form = new LinearLayout(theme);
        form.setOrientation(LinearLayout.VERTICAL);
        int inset = (int) (24 * theme.getResources().getDisplayMetrics().density);
        form.setPadding(inset, inset / 2, inset, 0);
        ScrollView scroll = new ScrollView(theme);
        scroll.addView(form);

        TextView note = new TextView(theme);
        note.setText("Google AI Studio receives your prompts, recent chat context, and approved tool results. "
                + "Termux is retired, Antigravity sign-in is disabled, and images are not sent.");
        form.addView(note);
        EditText model = new EditText(theme);
        model.setSingleLine(true);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setHint("Gemini model ID");
        model.setText(config.geminiModel());
        model.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        form.addView(model);
        EditText key = new EditText(theme);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint("Gemini API key");
        key.setText(config.geminiKey());
        key.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        key.setSaveEnabled(false);
        form.addView(key);
        if (config.hasLegacyCloudCodeCredential()) {
            Button forget = new Button(theme);
            forget.setText("Remove old Antigravity credentials from this app");
            forget.setFilterTouchesWhenObscured(true);
            forget.setOnClickListener(v -> {
                config.forgetLegacyCloudCodeCredential();
                forget.setText("Local copy removed. This does not revoke access at Google.");
                forget.setEnabled(false);
            });
            form.addView(forget);
        }

        AlertDialog dialog = builder.setTitle("Agent cloud chat").setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Turn off cloud chat", (d, which) -> {
                    config.turnOffCloudChat();
                    onSaved.run();
                })
                .setPositiveButton("Use Gemini", null).create();
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setFilterTouchesWhenObscured(true);
            save.setOnClickListener(v -> {
                String modelId = model.getText().toString().trim();
                String token = key.getText().toString().trim();
                if (!GeminiSettings.validModel(modelId)) { model.setError("Enter a model ID without models/."); return; }
                if (!GeminiSettings.validKey(token)) { key.setError("Enter a Gemini API key."); return; }
                config.selectGemini(modelId, token);
                onSaved.run();
                dialog.dismiss();
            });
        });
        dialog.show();
    }
}

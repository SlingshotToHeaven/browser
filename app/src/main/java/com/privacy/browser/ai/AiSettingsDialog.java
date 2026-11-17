package com.privacy.browser.ai;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.privacy.browser.R;
import com.privacy.browser.ThemeManager;
import org.json.JSONObject;

public class AiSettingsDialog {

    public interface OnSettingsSavedListener {
        void onSaved();
    }

    public static void show(final Context context, final OnSettingsSavedListener listener) {
        final AiConfigManager configManager = new AiConfigManager(context);
        final boolean isDark = ThemeManager.isAppDarkTheme(context);

        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ai_settings, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int width = Math.min((int) (dm.widthPixels * 0.94f), (int) (500 * dm.density));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // Apply theme styling dynamically to dialog surface
        view.setBackgroundResource(isDark ? R.drawable.bg_dialog_surface : R.drawable.bg_dialog_surface_light);

        int textPrimary = isDark ? Color.WHITE : Color.parseColor("#0F172A");
        int textSecondary = isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B");
        int dividerColor = isDark ? Color.parseColor("#222434") : Color.parseColor("#E2E8F0");
        int btnSecondaryBg = isDark ? R.drawable.bg_settings_btn_secondary : R.drawable.bg_settings_btn_secondary_light;
        int btnSecondaryText = isDark ? Color.parseColor("#CBD5E1") : Color.parseColor("#475569");
        int inputBg = isDark ? R.drawable.bg_agent_input_bar : R.drawable.bg_agent_input_bar_light;
        int cardBg = isDark ? R.drawable.bg_settings_card : R.drawable.bg_settings_card_light;

        // Header Views
        ImageView ivIconTile = view.findViewById(R.id.iv_settings_icon_tile);
        TextView tvTitle = view.findViewById(R.id.tv_settings_title);
        TextView tvSub = view.findViewById(R.id.tv_settings_subtitle);
        ImageButton btnClose = view.findViewById(R.id.btn_settings_close);

        View vHeaderDivider = view.findViewById(R.id.v_settings_header_divider);
        View vFooterDivider = view.findViewById(R.id.v_settings_footer_divider);
        if (vHeaderDivider != null) vHeaderDivider.setBackgroundColor(dividerColor);
        if (vFooterDivider != null) vFooterDivider.setBackgroundColor(dividerColor);

        if (ivIconTile != null) {
            ivIconTile.setBackgroundResource(isDark ? R.drawable.bg_circle_button : R.drawable.bg_circle_button_light);
        }
        if (tvTitle != null) tvTitle.setTextColor(textPrimary);
        if (tvSub != null) tvSub.setTextColor(textSecondary);
        if (btnClose != null) {
            btnClose.setColorFilter(textSecondary);
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AntigravityOAuthManager.stopLoopbackServer();
                    dialog.dismiss();
                }
            });
        }

        // Provider Segmented Tabs
        TextView tvProviderLabel = view.findViewById(R.id.tv_provider_label);
        final TextView tabAg = view.findViewById(R.id.tab_provider_antigravity);
        final TextView tabTermux = view.findViewById(R.id.tab_provider_termux);
        final TextView tabGemini = view.findViewById(R.id.tab_provider_gemini);
        final TextView tvProviderSummary = view.findViewById(R.id.tv_provider_summary);

        if (tvProviderLabel != null) tvProviderLabel.setTextColor(textSecondary);
        if (tvProviderSummary != null) tvProviderSummary.setTextColor(textSecondary);

        // Sub-Card Containers
        final LinearLayout layoutAntigravity = view.findViewById(R.id.layout_antigravity_config);
        final LinearLayout layoutTermux = view.findViewById(R.id.layout_termux_config);
        final LinearLayout layoutGemini = view.findViewById(R.id.layout_gemini_config);

        layoutAntigravity.setBackgroundResource(cardBg);
        layoutTermux.setBackgroundResource(cardBg);
        layoutGemini.setBackgroundResource(cardBg);

        final String[] selectedProvider = new String[] { configManager.getProvider() };

        final Runnable updateProviderUI = new Runnable() {
            @Override
            public void run() {
                int selBg = R.drawable.bg_agent_pill_selected;
                int unselBg = isDark ? R.drawable.bg_agent_pill_unselected : R.drawable.bg_agent_pill_unselected_light;
                int unselText = isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B");

                tabAg.setBackgroundResource(unselBg);
                tabAg.setTextColor(unselText);
                tabTermux.setBackgroundResource(unselBg);
                tabTermux.setTextColor(unselText);
                tabGemini.setBackgroundResource(unselBg);
                tabGemini.setTextColor(unselText);

                if (AiConfigManager.PROVIDER_ANTIGRAVITY.equals(selectedProvider[0])) {
                    tabAg.setBackgroundResource(selBg);
                    tabAg.setTextColor(Color.WHITE);
                    layoutAntigravity.setVisibility(View.VISIBLE);
                    layoutTermux.setVisibility(View.GONE);
                    layoutGemini.setVisibility(View.GONE);
                    tvProviderSummary.setText("CloudCode OAuth engine with unmetered access.");
                } else if (AiConfigManager.PROVIDER_TERMUX_BRIDGE.equals(selectedProvider[0])) {
                    tabTermux.setBackgroundResource(selBg);
                    tabTermux.setTextColor(Color.WHITE);
                    layoutAntigravity.setVisibility(View.GONE);
                    layoutTermux.setVisibility(View.VISIBLE);
                    layoutGemini.setVisibility(View.GONE);
                    tvProviderSummary.setText("Local Termux sidecar on localhost:8080 with terminal CLI tools.");
                } else {
                    tabGemini.setBackgroundResource(selBg);
                    tabGemini.setTextColor(Color.WHITE);
                    layoutAntigravity.setVisibility(View.GONE);
                    layoutTermux.setVisibility(View.GONE);
                    layoutGemini.setVisibility(View.VISIBLE);
                    tvProviderSummary.setText("Direct Google AI Studio developer API key.");
                }
            }
        };

        tabAg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedProvider[0] = AiConfigManager.PROVIDER_ANTIGRAVITY;
                updateProviderUI.run();
            }
        });

        tabTermux.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedProvider[0] = AiConfigManager.PROVIDER_TERMUX_BRIDGE;
                updateProviderUI.run();
            }
        });

        tabGemini.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedProvider[0] = AiConfigManager.PROVIDER_GEMINI;
                updateProviderUI.run();
            }
        });

        updateProviderUI.run();

        // 1. Antigravity controls
        final TextView tvAgAccountStatus = view.findViewById(R.id.tv_ag_account_status);
        final Button btnAgSignIn = view.findViewById(R.id.btn_ag_signin);
        final Button btnAgSignOut = view.findViewById(R.id.btn_ag_signout);
        TextView tvAgModelLabel = view.findViewById(R.id.tv_ag_model_label);
        RadioGroup rgAgModel = view.findViewById(R.id.rg_ag_model);
        RadioButton rbAgFlash = view.findViewById(R.id.rb_ag_flash);
        RadioButton rbAgPro = view.findViewById(R.id.rb_ag_pro);
        RadioButton rbAgSonnet = view.findViewById(R.id.rb_ag_sonnet);
        RadioButton rbAgOpus = view.findViewById(R.id.rb_ag_opus);

        if (tvAgModelLabel != null) tvAgModelLabel.setTextColor(textSecondary);
        rbAgFlash.setTextColor(textPrimary);
        rbAgPro.setTextColor(textPrimary);
        rbAgSonnet.setTextColor(textPrimary);
        rbAgOpus.setTextColor(textPrimary);

        // 2. Termux controls
        final TextView tvTermuxStatus = view.findViewById(R.id.tv_termux_status);
        TextView tvTermuxEpLabel = view.findViewById(R.id.tv_termux_endpoint_label);
        final EditText etTermuxEndpoint = view.findViewById(R.id.et_termux_endpoint);
        Button btnOpenTermux = view.findViewById(R.id.btn_open_termux);
        Button btnPingBridge = view.findViewById(R.id.btn_ping_bridge);
        TextView tvTermuxModelLabel = view.findViewById(R.id.tv_termux_model_label);
        final RadioGroup rgTermuxModel = view.findViewById(R.id.rg_termux_model);
        final RadioButton rbTermuxFlash = view.findViewById(R.id.rb_termux_flash);
        final RadioButton rbTermuxPro = view.findViewById(R.id.rb_termux_pro);
        final RadioButton rbTermuxSonnet = view.findViewById(R.id.rb_termux_sonnet);

        if (tvTermuxEpLabel != null) tvTermuxEpLabel.setTextColor(textSecondary);
        etTermuxEndpoint.setBackgroundResource(inputBg);
        etTermuxEndpoint.setTextColor(textPrimary);
        etTermuxEndpoint.setHintTextColor(isDark ? Color.parseColor("#64748B") : Color.parseColor("#94A3B8"));

        btnOpenTermux.setBackgroundResource(btnSecondaryBg);
        btnOpenTermux.setTextColor(btnSecondaryText);
        btnPingBridge.setBackgroundResource(btnSecondaryBg);
        btnPingBridge.setTextColor(btnSecondaryText);

        if (tvTermuxModelLabel != null) tvTermuxModelLabel.setTextColor(textSecondary);
        rbTermuxFlash.setTextColor(textPrimary);
        rbTermuxPro.setTextColor(textPrimary);
        rbTermuxSonnet.setTextColor(textPrimary);

        // 3. Gemini controls
        TextView tvKeyLabel = view.findViewById(R.id.tv_settings_key_label);
        final EditText etApiKey = view.findViewById(R.id.et_gemini_api_key);
        TextView tvModelLabel = view.findViewById(R.id.tv_settings_model_label);
        RadioGroup rgModel = view.findViewById(R.id.rg_ai_model);
        RadioButton rbFlash = view.findViewById(R.id.rb_model_flash);
        RadioButton rbPro = view.findViewById(R.id.rb_model_pro);

        if (tvKeyLabel != null) tvKeyLabel.setTextColor(textSecondary);
        if (tvModelLabel != null) tvModelLabel.setTextColor(textSecondary);
        etApiKey.setBackgroundResource(inputBg);
        etApiKey.setTextColor(textPrimary);
        etApiKey.setHintTextColor(isDark ? Color.parseColor("#64748B") : Color.parseColor("#94A3B8"));
        rbFlash.setTextColor(textPrimary);
        rbPro.setTextColor(textPrimary);

        // Footer Actions
        Button btnCancel = view.findViewById(R.id.btn_cancel);
        Button btnSave = view.findViewById(R.id.btn_save);

        btnCancel.setBackgroundResource(btnSecondaryBg);
        btnCancel.setTextColor(btnSecondaryText);
        btnSave.setBackgroundResource(R.drawable.bg_agent_pill_selected);
        btnSave.setTextColor(Color.WHITE);

        // Initial Values
        etTermuxEndpoint.setText(configManager.getTermuxEndpoint());
        etApiKey.setText(configManager.getApiKey());

        // Antigravity Account Status Update
        final Runnable updateAgStatus = new Runnable() {
            @Override
            public void run() {
                String authJson = configManager.getAntigravityAuthJson();
                if (authJson != null && !authJson.isEmpty()) {
                    String email = "Connected";
                    try {
                        JSONObject obj = new JSONObject(authJson);
                        email = obj.optString("email", "Connected");
                    } catch (Exception ignored) {}

                    tvAgAccountStatus.setText("Connected: " + email);
                    tvAgAccountStatus.setTextColor(isDark ? Color.parseColor("#34D399") : Color.parseColor("#047857"));
                    tvAgAccountStatus.setBackgroundResource(isDark ? R.drawable.bg_settings_badge_connected : R.drawable.bg_settings_badge_connected_light);

                    btnAgSignIn.setText("Switch Account");
                    btnAgSignIn.setBackgroundResource(isDark ? R.drawable.bg_settings_btn_secondary : R.drawable.bg_settings_btn_secondary_light);
                    btnAgSignIn.setTextColor(isDark ? Color.parseColor("#CBD5E1") : Color.parseColor("#475569"));

                    btnAgSignOut.setVisibility(View.VISIBLE);
                    btnAgSignOut.setBackgroundResource(isDark ? R.drawable.bg_settings_btn_danger : R.drawable.bg_settings_btn_danger_light);
                    btnAgSignOut.setTextColor(isDark ? Color.parseColor("#F87171") : Color.parseColor("#DC2626"));
                } else {
                    tvAgAccountStatus.setText("Google Account: Not connected");
                    tvAgAccountStatus.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));
                    tvAgAccountStatus.setBackgroundResource(isDark ? R.drawable.bg_settings_badge_neutral : R.drawable.bg_settings_badge_neutral_light);

                    btnAgSignIn.setText("Connect Google Account");
                    btnAgSignIn.setBackgroundResource(R.drawable.bg_agent_pill_selected);
                    btnAgSignIn.setTextColor(Color.WHITE);

                    btnAgSignOut.setVisibility(View.GONE);
                }
            }
        };
        updateAgStatus.run();

        btnAgSignIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvAgAccountStatus.setText("Awaiting Google authorization in browser...");
                tvAgAccountStatus.setBackgroundResource(isDark ? R.drawable.bg_settings_badge_neutral : R.drawable.bg_settings_badge_neutral_light);
                tvAgAccountStatus.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));

                AntigravityOAuthManager.startOAuthFlow(context, configManager, new AntigravityOAuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(final String email) {
                        tvAgAccountStatus.post(new Runnable() {
                            @Override
                            public void run() {
                                updateAgStatus.run();
                                Toast.makeText(context, "Connected to " + email, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(final Exception e) {
                        tvAgAccountStatus.post(new Runnable() {
                            @Override
                            public void run() {
                                tvAgAccountStatus.setText("Sign in failed: " + e.getMessage());
                                tvAgAccountStatus.setBackgroundResource(isDark ? R.drawable.bg_settings_badge_error : R.drawable.bg_settings_badge_error_light);
                                tvAgAccountStatus.setTextColor(isDark ? Color.parseColor("#F87171") : Color.parseColor("#DC2626"));
                            }
                        });
                    }
                });
            }
        });

        btnAgSignOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                configManager.setAntigravityAuthJson("");
                updateAgStatus.run();
                Toast.makeText(context, "Signed out of Google Account", Toast.LENGTH_SHORT).show();
            }
        });

        // Termux Bridge Ping action
        final Runnable testBridgePing = new Runnable() {
            @Override
            public void run() {
                String ep = etTermuxEndpoint.getText() != null ? etTermuxEndpoint.getText().toString().trim() : "";
                tvTermuxStatus.setText("Testing bridge connection...");
                tvTermuxStatus.setBackgroundResource(isDark ? R.drawable.bg_settings_badge_neutral : R.drawable.bg_settings_badge_neutral_light);
                tvTermuxStatus.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));

                TermuxBridgeManager.pingBridge(ep, new TermuxBridgeManager.PingCallback() {
                    @Override
                    public void onResult(final boolean isOnline, long latencyMs, String engineName, final String message) {
                        tvTermuxStatus.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isOnline) {
                                    tvTermuxStatus.setText("Bridge Online: " + message);
                                    tvTermuxStatus.setBackgroundResource(isDark ? R.drawable.bg_settings_badge_connected : R.drawable.bg_settings_badge_connected_light);
                                    tvTermuxStatus.setTextColor(isDark ? Color.parseColor("#34D399") : Color.parseColor("#047857"));
                                } else {
                                    tvTermuxStatus.setText("Bridge Offline: " + message);
                                    tvTermuxStatus.setBackgroundResource(isDark ? R.drawable.bg_settings_badge_error : R.drawable.bg_settings_badge_error_light);
                                    tvTermuxStatus.setTextColor(isDark ? Color.parseColor("#F87171") : Color.parseColor("#DC2626"));
                                }
                            }
                        });
                    }
                });
            }
        };

        btnPingBridge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testBridgePing.run();
            }
        });

        btnOpenTermux.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TermuxBridgeManager.openTermux(context);
            }
        });

        testBridgePing.run();

        // Model Selections
        String termuxModel = configManager.getTermuxModel();
        if (AiConfigManager.TERMUX_MODEL_GEMINI_2_5_PRO.equals(termuxModel)) {
            rbTermuxPro.setChecked(true);
        } else if (AiConfigManager.TERMUX_MODEL_CLAUDE_SONNET.equals(termuxModel)) {
            rbTermuxSonnet.setChecked(true);
        } else {
            rbTermuxFlash.setChecked(true);
        }

        String agModel = configManager.getAntigravityModel();
        if (AiConfigManager.AG_MODEL_GEMINI_2_5_PRO.equals(agModel)) {
            rbAgPro.setChecked(true);
        } else if (AiConfigManager.AG_MODEL_CLAUDE_SONNET.equals(agModel)) {
            rbAgSonnet.setChecked(true);
        } else if (AiConfigManager.AG_MODEL_CLAUDE_OPUS.equals(agModel)) {
            rbAgOpus.setChecked(true);
        } else {
            rbAgFlash.setChecked(true);
        }

        if (AiConfigManager.MODEL_GEMINI_2_5_PRO.equals(configManager.getModel())) {
            rbPro.setChecked(true);
        } else {
            rbFlash.setChecked(true);
        }

        // Cancel Button
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AntigravityOAuthManager.stopLoopbackServer();
                dialog.dismiss();
            }
        });

        // Save Button
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 1. Save provider from segmented control
                configManager.setProvider(selectedProvider[0]);

                // 2. Save Termux endpoint & model
                String termuxEp = etTermuxEndpoint.getText() != null ? etTermuxEndpoint.getText().toString().trim() : "";
                configManager.setTermuxEndpoint(termuxEp);
                if (rbTermuxPro.isChecked()) {
                    configManager.setTermuxModel(AiConfigManager.TERMUX_MODEL_GEMINI_2_5_PRO);
                } else if (rbTermuxSonnet.isChecked()) {
                    configManager.setTermuxModel(AiConfigManager.TERMUX_MODEL_CLAUDE_SONNET);
                } else {
                    configManager.setTermuxModel(AiConfigManager.TERMUX_MODEL_GEMINI_3_8_FLASH);
                }

                // 3. Save Antigravity model
                if (rbAgPro.isChecked()) {
                    configManager.setAntigravityModel(AiConfigManager.AG_MODEL_GEMINI_2_5_PRO);
                } else if (rbAgSonnet.isChecked()) {
                    configManager.setAntigravityModel(AiConfigManager.AG_MODEL_CLAUDE_SONNET);
                } else if (rbAgOpus.isChecked()) {
                    configManager.setAntigravityModel(AiConfigManager.AG_MODEL_CLAUDE_OPUS);
                } else {
                    configManager.setAntigravityModel(AiConfigManager.AG_MODEL_GEMINI_3_8_FLASH);
                }

                // 4. Save Gemini API key & model
                String key = etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
                configManager.setApiKey(key);
                if (rbPro.isChecked()) {
                    configManager.setModel(AiConfigManager.MODEL_GEMINI_2_5_PRO);
                } else {
                    configManager.setModel(AiConfigManager.MODEL_GEMINI_3_8_FLASH);
                }

                AntigravityOAuthManager.stopLoopbackServer();
                Toast.makeText(context, "Agent settings saved", Toast.LENGTH_SHORT).show();
                dialog.dismiss();

                if (listener != null) {
                    listener.onSaved();
                }
            }
        });

        dialog.show();
    }
}

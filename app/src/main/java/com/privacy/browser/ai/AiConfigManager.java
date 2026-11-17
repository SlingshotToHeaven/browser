package com.privacy.browser.ai;

import android.content.Context;
import android.content.SharedPreferences;

public class AiConfigManager {
    private static final String PREF_NAME = "agent_prefs";

    // General toggles
    public static final String PREF_AGENT_ENABLED = "pref_agent_enabled";
    public static final String PREF_AI_PROVIDER = "pref_ai_provider";
    public static final String PREF_AI_MODE = "pref_ai_mode";

    // Providers
    public static final String PROVIDER_TERMUX_BRIDGE = "termux_bridge";
    public static final String PROVIDER_ANTIGRAVITY = "antigravity";
    public static final String PROVIDER_GEMINI = "gemini";

    // Mode
    public static final String MODE_STRUCTURED = "structured";
    public static final String MODE_RAW = "raw";

    // Provider 1: Termux Bridge
    public static final String PREF_TERMUX_ENDPOINT = "pref_termux_endpoint";
    public static final String PREF_TERMUX_MODEL = "pref_termux_model";
    public static final String PREF_TERMUX_EFFORT = "pref_termux_effort";
    public static final String DEFAULT_TERMUX_ENDPOINT = "http://127.0.0.1:8080";
    public static final String DEFAULT_TERMUX_EFFORT = "low";
    public static final String TERMUX_MODEL_GEMINI_3_8_FLASH = "gemini-3.8-flash";
    public static final String TERMUX_MODEL_GEMINI_2_5_PRO = "gemini-2.5-pro";
    public static final String TERMUX_MODEL_CLAUDE_SONNET = "claude-sonnet-4-6";
    public static final String TERMUX_MODEL_CLAUDE_OPUS = "claude-opus-4-6";

    // Provider 2: Antigravity Direct CloudCode
    public static final String PREF_AG_MODEL = "pref_ag_model";
    public static final String PREF_AG_AUTH_JSON = "pref_ag_auth_json"; // stores access_token, refresh_token, email
    public static final String AG_MODEL_GEMINI_3_8_FLASH = "gemini-3.8-flash-low";
    public static final String AG_MODEL_GEMINI_2_5_PRO = "gemini-2.5-pro";
    public static final String AG_MODEL_CLAUDE_SONNET = "claude-sonnet-4-6";
    public static final String AG_MODEL_CLAUDE_OPUS = "claude-opus-4-6";
    public static final String AG_MODEL_GPT_OSS = "gpt-oss-120b-medium";

    // Provider 3: Gemini AI Studio Developer API
    public static final String PREF_GEMINI_API_KEY = "pref_gemini_api_key";
    public static final String PREF_GEMINI_MODEL = "pref_ai_model";
    public static final String MODEL_GEMINI_3_8_FLASH = "gemini-3.8-flash";
    public static final String MODEL_GEMINI_2_5_PRO = "gemini-2.5-pro";

    private final SharedPreferences prefs;

    public AiConfigManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isAgentEnabled() {
        return prefs.getBoolean(PREF_AGENT_ENABLED, false);
    }

    public void setAgentEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_AGENT_ENABLED, enabled).apply();
    }

    public String getProvider() {
        return prefs.getString(PREF_AI_PROVIDER, PROVIDER_TERMUX_BRIDGE);
    }

    public void setProvider(String provider) {
        prefs.edit().putString(PREF_AI_PROVIDER, provider).apply();
    }

    public String getTermuxEndpoint() {
        String ep = prefs.getString(PREF_TERMUX_ENDPOINT, DEFAULT_TERMUX_ENDPOINT);
        if (ep == null || ep.trim().isEmpty()) {
            return DEFAULT_TERMUX_ENDPOINT;
        }
        return ep.trim();
    }

    public void setTermuxEndpoint(String endpoint) {
        prefs.edit().putString(PREF_TERMUX_ENDPOINT, endpoint != null ? endpoint.trim() : DEFAULT_TERMUX_ENDPOINT).apply();
    }

    public String getTermuxModel() {
        return prefs.getString(PREF_TERMUX_MODEL, TERMUX_MODEL_GEMINI_3_8_FLASH);
    }

    public void setTermuxModel(String model) {
        prefs.edit().putString(PREF_TERMUX_MODEL, model != null ? model.trim() : TERMUX_MODEL_GEMINI_3_8_FLASH).apply();
    }

    public String getTermuxEffort() {
        return prefs.getString(PREF_TERMUX_EFFORT, DEFAULT_TERMUX_EFFORT);
    }

    public void setTermuxEffort(String effort) {
        prefs.edit().putString(PREF_TERMUX_EFFORT, effort != null ? effort.trim() : DEFAULT_TERMUX_EFFORT).apply();
    }

    public String getAntigravityAuthJson() {
        return prefs.getString(PREF_AG_AUTH_JSON, "");
    }

    public void setAntigravityAuthJson(String authJson) {
        prefs.edit().putString(PREF_AG_AUTH_JSON, authJson != null ? authJson.trim() : "").apply();
    }

    public String getAntigravityModel() {
        return prefs.getString(PREF_AG_MODEL, AG_MODEL_GEMINI_3_8_FLASH);
    }

    public void setAntigravityModel(String model) {
        prefs.edit().putString(PREF_AG_MODEL, model).apply();
    }

    public String getApiKey() {
        return prefs.getString(PREF_GEMINI_API_KEY, "");
    }

    public void setApiKey(String apiKey) {
        prefs.edit().putString(PREF_GEMINI_API_KEY, apiKey != null ? apiKey.trim() : "").apply();
    }

    public String getModel() {
        String model = prefs.getString(PREF_GEMINI_MODEL, MODEL_GEMINI_3_8_FLASH);
        // Automatically upgrade deprecated models to avoid 404 retired model errors
        if (model == null || model.isEmpty() || model.contains("2.5-flash") || model.contains("1.5-flash") || model.contains("2.0-flash")) {
            model = MODEL_GEMINI_3_8_FLASH;
            prefs.edit().putString(PREF_GEMINI_MODEL, model).apply();
        } else if (model.contains("1.5-pro")) {
            model = MODEL_GEMINI_2_5_PRO;
            prefs.edit().putString(PREF_GEMINI_MODEL, model).apply();
        }
        return model;
    }

    public void setModel(String model) {
        prefs.edit().putString(PREF_GEMINI_MODEL, model).apply();
    }

    public String getMode() {
        return prefs.getString(PREF_AI_MODE, MODE_STRUCTURED);
    }

    public void setMode(String mode) {
        prefs.edit().putString(PREF_AI_MODE, mode).apply();
    }
}

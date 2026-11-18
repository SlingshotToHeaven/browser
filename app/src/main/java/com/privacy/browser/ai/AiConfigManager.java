package com.privacy.browser.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

public class AiConfigManager {
    private static final String PREF_NAME = "agent_prefs";
    private static final String PREF_AGENT_ENABLED = "pref_agent_enabled";
    private static final String PREF_AI_PROVIDER = "pref_ai_provider";
    private static final String PREF_AI_MODE = "pref_ai_mode";
    private static final String PREF_GEMINI_API_KEY = "pref_gemini_api_key";
    private static final String PREF_GEMINI_MODEL = "pref_ai_model";
    private static final String LEGACY_CLOUDCODE_AUTH = "pref_ag_auth_json";
    static final String PROVIDER_GEMINI = "gemini";
    public static final String MODE_STRUCTURED = "structured";
    public static final String MODE_RAW = "raw"; // Legacy preference name; this is now text-only.

    private final SharedPreferences prefs;

    public AiConfigManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isAgentEnabled() { return prefs.getBoolean(PREF_AGENT_ENABLED, false); }
    public void setAgentEnabled(boolean enabled) { prefs.edit().putBoolean(PREF_AGENT_ENABLED, enabled).apply(); }

    String getMode() { return MODE_RAW.equals(prefs.getString(PREF_AI_MODE, null)) ? MODE_RAW : MODE_STRUCTURED; }
    void setMode(String mode) { prefs.edit().putString(PREF_AI_MODE, mode).apply(); }

    boolean isGeminiSelected() { return PROVIDER_GEMINI.equals(prefs.getAll().get(PREF_AI_PROVIDER)); }
    String geminiKey() { return prefs.getString(PREF_GEMINI_API_KEY, ""); }
    String geminiModel() { return prefs.getString(PREF_GEMINI_MODEL, ""); }

    void selectGemini(String model, String key) {
        if (!GeminiSettings.validModel(model)) throw new IllegalArgumentException("Enter a Gemini model ID, without the models/ prefix.");
        if (!GeminiSettings.validKey(key)) throw new IllegalArgumentException("Enter a valid Gemini API key.");
        prefs.edit().putString(PREF_AI_PROVIDER, PROVIDER_GEMINI).putString(PREF_GEMINI_MODEL, model)
                .putString(PREF_GEMINI_API_KEY, key).apply();
    }

    void turnOffCloudChat() { prefs.edit().putString(PREF_AI_PROVIDER, "off").apply(); }
    boolean hasLegacyCloudCodeCredential() { return !prefs.getString(LEGACY_CLOUDCODE_AUTH, "").isEmpty(); }
    void forgetLegacyCloudCodeCredential() { prefs.edit().remove(LEGACY_CLOUDCODE_AUTH).apply(); }

    /** Compare the same recipient, credentials, model and mode for review, send, and context reuse. */
    GeminiSettings connection() {
        Map<String, ?> saved = prefs.getAll();
        if (!Boolean.TRUE.equals(saved.get(PREF_AGENT_ENABLED)) || !PROVIDER_GEMINI.equals(saved.get(PREF_AI_PROVIDER))) return null;
        Object model = saved.get(PREF_GEMINI_MODEL), apiKey = saved.get(PREF_GEMINI_API_KEY);
        return GeminiSettings.create(model instanceof String ? (String) model : null,
                apiKey instanceof String ? (String) apiKey : "", !MODE_RAW.equals(saved.get(PREF_AI_MODE)));
    }
}

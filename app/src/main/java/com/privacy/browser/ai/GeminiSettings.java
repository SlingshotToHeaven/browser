package com.privacy.browser.ai;

/** Run-local credentials. Never stored in a transcript, serialized to JSON, or logged. */
final class GeminiSettings {
    final String model;
    final String apiKey;
    final boolean tools;

    private GeminiSettings(String model, String apiKey, boolean tools) {
        this.model = model;
        this.apiKey = apiKey;
        this.tools = tools;
    }

    static boolean validModel(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"); }
    static boolean validKey(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,256}"); }

    static GeminiSettings create(String model, String key, boolean tools) {
        return validModel(model) && validKey(key) ? new GeminiSettings(model, key, tools) : null;
    }

    boolean matches(GeminiSettings current) {
        return current != null && model.equals(current.model) && apiKey.equals(current.apiKey) && tools == current.tools;
    }
}

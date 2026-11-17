package com.privacy.browser.ai;

import android.content.Context;
import org.json.JSONObject;

public class RawModeExecutor {
    private final Context context;

    public interface RawModeCallback {
        void onOutput(String output);
        void onError(String error);
    }

    public RawModeExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    public void executeRawScript(String script, RawModeCallback callback) {
        // Raw Mode is stubbed for preview
        if (callback != null) {
            callback.onError("Raw Mode is currently in preview. Please switch to Structured Mode for active tool calling.");
        }
    }
}

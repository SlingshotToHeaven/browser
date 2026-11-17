package com.privacy.browser.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Executes dynamic, AI-generated multi-step automation recipes sequentially.
 * Supports arbitrary tool dispatching, async delays, and smart DOM condition polling.
 */
public class AutomationRecipeRunner {
    private static final String TAG = "AutomationRecipeRunner";

    public interface RecipeCallback {
        void onStepStarted(int stepIndex, String actionName);
        void onStepFinished(int stepIndex, String actionName, boolean success);
        void onRecipeCompleted(boolean success, String summary);
    }

    private final Context context;
    private final Handler handler;
    private final ToolDispatcher toolDispatcher;
    private WebView currentWebView;

    public AutomationRecipeRunner(Context context, ToolDispatcher toolDispatcher, WebView webView) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.toolDispatcher = toolDispatcher;
        this.currentWebView = webView;
    }

    public void setWebView(WebView webView) {
        this.currentWebView = webView;
        if (toolDispatcher != null) {
            toolDispatcher.setCurrentWebView(webView);
        }
    }

    public void runRecipe(final String recipeJsonStr, final RecipeCallback callback) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray steps = new JSONArray(recipeJsonStr);
                    if (steps.length() == 0) {
                        if (callback != null) callback.onRecipeCompleted(true, "Recipe was empty.");
                        return;
                    }
                    executeStep(steps, 0, callback);
                } catch (Exception e) {
                    Log.e(TAG, "Failed parsing recipe JSON: " + recipeJsonStr, e);
                    if (callback != null) callback.onRecipeCompleted(false, "Invalid recipe JSON: " + e.getMessage());
                }
            }
        });
    }

    private void executeStep(final JSONArray steps, final int index, final RecipeCallback callback) {
        if (index >= steps.length()) {
            Log.d(TAG, "All recipe steps executed successfully.");
            Toast.makeText(context, "Automation completed successfully (" + steps.length() + " steps)", Toast.LENGTH_SHORT).show();
            if (callback != null) callback.onRecipeCompleted(true, "All " + steps.length() + " steps completed successfully.");
            return;
        }

        try {
            JSONObject step = steps.getJSONObject(index);
            final String action = step.optString("action", "");
            final JSONObject params = step.optJSONObject("params") != null ? step.optJSONObject("params") : new JSONObject();

            Log.d(TAG, "Executing Recipe Step " + (index + 1) + "/" + steps.length() + ": " + action);
            if (callback != null) callback.onStepStarted(index, action);

            // Special orchestrator control flow: wait_for_element
            if ("wait_for_element".equalsIgnoreCase(action)) {
                handleWaitForElement(params, new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onStepFinished(index, action, true);
                        executeStep(steps, index + 1, callback);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onStepFinished(index, action, false);
                        executeStep(steps, index + 1, callback); // proceed resiliently
                    }
                });
                return;
            }

            // Special orchestrator control flow: delay
            if ("delay".equalsIgnoreCase(action)) {
                long delayMs = params.optLong("ms", 1500);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onStepFinished(index, action, true);
                        executeStep(steps, index + 1, callback);
                    }
                }, delayMs);
                return;
            }

            // Standard tool execution via ToolDispatcher
            toolDispatcher.executeTool(action, params, new ToolDispatcher.ExecutionCallback() {
                @Override
                public void onCompleted(String resultJson) {
                    Log.d(TAG, "Step " + (index + 1) + " (" + action + ") finished: " + resultJson);
                    if (callback != null) callback.onStepFinished(index, action, true);

                    // Add small safety pause between sequential page interactions
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            executeStep(steps, index + 1, callback);
                        }
                    }, 500);
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "Step " + (index + 1) + " (" + action + ") error: " + error);
                    if (callback != null) callback.onStepFinished(index, action, false);

                    // Proceed to next step to allow resilient recovery
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            executeStep(steps, index + 1, callback);
                        }
                    }, 500);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Exception executing step " + index, e);
            if (callback != null) callback.onStepFinished(index, "unknown", false);
            executeStep(steps, index + 1, callback);
        }
    }

    private void handleWaitForElement(final JSONObject params, final Runnable onSuccess, final Runnable onTimeout) {
        final String selector = params.optString("css_selector", "");
        final long timeoutMs = params.optLong("timeout_ms", 8000);
        final long pollIntervalMs = 400;
        final long startTime = System.currentTimeMillis();

        if (selector.isEmpty() || currentWebView == null) {
            onSuccess.run();
            return;
        }

        final Runnable[] checkRunnable = new Runnable[1];
        checkRunnable[0] = new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    Log.w(TAG, "Timeout waiting for element: " + selector);
                    onTimeout.run();
                    return;
                }

                String js = "(function() {"
                        + "  var el = document.querySelector('" + selector.replace("'", "\\'") + "');"
                        + "  return !!el;"
                        + "})();";

                currentWebView.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equalsIgnoreCase(value)) {
                            Log.d(TAG, "Element found in DOM: " + selector);
                            onSuccess.run();
                        } else {
                            handler.postDelayed(checkRunnable[0], pollIntervalMs);
                        }
                    }
                });
            }
        };

        handler.post(checkRunnable[0]);
    }
}

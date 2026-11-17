package com.privacy.browser.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public class GeminiClient {
    private static final String TAG = "AgentClient";
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String CLOUDCODE_GENERATE_URL = "https://daily-cloudcode-pa.googleapis.com/v1internal:generateContent";

    private final Context context;
    private final AiConfigManager configManager;
    private final ExecutorService executorService;
    private volatile HttpURLConnection activeConnection;

    private JSONObject fullGuideJson;
    private JSONArray toolsDeclarationJson;
    private String systemInstructionText;

    public interface ResponseCallback {
        void onSuccess(AiResponse response);
        void onError(String errorMessage);
    }

    public static class ToolCall {
        public final String name;
        public final JSONObject arguments;

        public ToolCall(String name, JSONObject arguments) {
            this.name = name;
            this.arguments = arguments != null ? arguments : new JSONObject();
        }
    }

    public static class AiResponse {
        public final String text;
        public final List<ToolCall> toolCalls;
        public final JSONObject rawContent;

        public AiResponse(String text, List<ToolCall> toolCalls) {
            this(text, toolCalls, null);
        }

        public AiResponse(String text, List<ToolCall> toolCalls, JSONObject rawContent) {
            this.text = text != null ? text : "";
            this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<ToolCall>();
            this.rawContent = rawContent;
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    public GeminiClient(Context context) {
        this.context = context.getApplicationContext();
        this.configManager = new AiConfigManager(this.context);
        this.executorService = Executors.newSingleThreadExecutor();
        loadGuideAndTools();
    }

    private void loadGuideAndTools() {
        try {
            InputStream is = context.getAssets().open("ai/ai_assistant_guide.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            is.close();

            fullGuideJson = new JSONObject(sb.toString());

            // Build system instruction string
            JSONObject manifest = fullGuideJson.optJSONObject("system_manifest");
            JSONObject internals = fullGuideJson.optJSONObject("browser_internals");
            JSONArray playbooks = fullGuideJson.optJSONArray("execution_playbooks");

            StringBuilder sysBuilder = new StringBuilder();
            sysBuilder.append("You are the native in-browser Agent. Adhere strictly to the following parameters:\n");
            if (manifest != null) {
                JSONArray rules = manifest.optJSONArray("communication_rules");
                if (rules != null) {
                    sysBuilder.append("Rules:\n");
                    for (int i = 0; i < rules.length(); i++) {
                        sysBuilder.append("- ").append(rules.getString(i)).append("\n");
                    }
                }
            }
            if (internals != null) {
                sysBuilder.append("\nBrowser Internals:\n").append(internals.toString(2)).append("\n");
            }
            if (playbooks != null) {
                sysBuilder.append("\nExecution Playbooks:\n").append(playbooks.toString(2)).append("\n");
            }

            systemInstructionText = sysBuilder.toString();

            // Build Gemini tools declaration array
            JSONArray toolsArray = fullGuideJson.optJSONArray("tools");
            if (toolsArray != null) {
                JSONArray functionDeclarations = new JSONArray();
                for (int i = 0; i < toolsArray.length(); i++) {
                    JSONObject tool = toolsArray.getJSONObject(i);
                    JSONObject fn = new JSONObject();
                    fn.put("name", tool.getString("name"));
                    fn.put("description", tool.getString("description"));
                    if (tool.has("parameters")) {
                        fn.put("parameters", tool.getJSONObject("parameters"));
                    }
                    functionDeclarations.put(fn);
                }

                JSONObject toolsObj = new JSONObject();
                toolsObj.put("function_declarations", functionDeclarations);

                toolsDeclarationJson = new JSONArray();
                toolsDeclarationJson.put(toolsObj);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ai_assistant_guide.json from assets", e);
        }
    }

    public JSONObject getFullGuideJson() {
        return fullGuideJson;
    }

    public void cancelCurrentRequest() {
        HttpURLConnection conn = activeConnection;
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
            activeConnection = null;
        }
    }

    public void generateContent(final JSONArray contents, final String optionalBase64Image, final ResponseCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                String provider = configManager.getProvider();

                if (AiConfigManager.PROVIDER_TERMUX_BRIDGE.equals(provider)) {
                    executeViaTermuxBridge(contents, optionalBase64Image, callback);
                } else if (AiConfigManager.PROVIDER_ANTIGRAVITY.equals(provider)) {
                    executeViaAntigravityCloud(contents, callback);
                } else {
                    executeViaGeminiStudio(contents, callback);
                }
            }
        });
    }

    // --------------------------------------------------------------------------
    // PROVIDER 1: TERMUX LOCAL BRIDGE (http://127.0.0.1:8080 via ant.py)
    // --------------------------------------------------------------------------
    private void executeViaTermuxBridge(final JSONArray contents, final String optionalBase64Image, final ResponseCallback callback) {
        String endpoint = configManager.getTermuxEndpoint();
        if (endpoint.isEmpty()) {
            endpoint = AiConfigManager.DEFAULT_TERMUX_ENDPOINT;
        }

        HttpURLConnection conn = null;
        try {
            String cleanUrl = endpoint;
            if (!cleanUrl.endsWith("/")) {
                cleanUrl += "/";
            }

            StringBuilder sysBuilder = new StringBuilder();
            if (systemInstructionText != null) {
                sysBuilder.append(systemInstructionText).append("\n\n");
            }
            sysBuilder.append("IMPORTANT INSTRUCTION FOR TOOL CALLING:\n");
            sysBuilder.append("When an action requires browser execution, output a single JSON block:\n");
            sysBuilder.append("```json\n{\"tool\": \"tool_name\", \"parameters\": {...}}\n```\n");
            sysBuilder.append("If a multi-step routine is needed, output:\n");
            sysBuilder.append("```json\n{\"recipe\": [{\"action\": \"tool_name\", \"parameters\": {...}}]}\n```\n");
            sysBuilder.append("Otherwise, provide a clear, direct conversational answer.");

            String userPrompt = extractPromptFromContents(contents);

            JSONObject payload = new JSONObject();
            payload.put("model", configManager.getTermuxModel());
            payload.put("effort", configManager.getTermuxEffort());
            payload.put("system", sysBuilder.toString());
            payload.put("prompt", userPrompt);
            if (optionalBase64Image != null && !optionalBase64Image.isEmpty()) {
                payload.put("mime_type", "image/jpeg");
                payload.put("data", optionalBase64Image);
            }

            URL url = new URL(cleanUrl);
            conn = (HttpURLConnection) url.openConnection();
            activeConnection = conn;
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000); // Allow time for deep reasoning / complex runs

            byte[] postBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(postBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseStr = readStream(is);

            if (code < 200 || code >= 300) {
                callback.onError("Termux Bridge Error (" + code + "): " + responseStr);
                return;
            }

            String outputText = responseStr;
            try {
                JSONObject resObj = new JSONObject(responseStr);
                if (resObj.has("response")) {
                    outputText = resObj.getString("response");
                } else if (resObj.has("output")) {
                    outputText = resObj.getString("output");
                } else if (resObj.has("error")) {
                    callback.onError("Termux Bridge: " + resObj.getString("error"));
                    return;
                }
            } catch (Exception ignored) {
            }

            AiResponse parsed = parseTermuxBridgeResponse(outputText);
            callback.onSuccess(parsed);

        } catch (Exception e) {
            Log.e(TAG, "Termux bridge communication failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (msg.contains("refused") || msg.contains("failed to connect") || msg.contains("ECONNREFUSED")) {
                callback.onError("Termux Bridge is offline. Launch Termux and run 'python ant.py'.");
            } else {
                callback.onError("Termux Bridge Error: " + msg);
            }
        } finally {
            if (activeConnection == conn) {
                activeConnection = null;
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private AiResponse parseTermuxBridgeResponse(String rawResult) {
        if (rawResult == null || rawResult.trim().isEmpty()) {
            return new AiResponse("No response received from Termux Bridge.", null);
        }

        String text = rawResult.trim();
        List<ToolCall> toolCalls = new ArrayList<>();

        // 1. Check for markdown json block: ```json ... ```
        int jsonStart = text.indexOf("```json");
        if (jsonStart != -1) {
            int contentStart = jsonStart + 7;
            int jsonEnd = text.indexOf("```", contentStart);
            if (jsonEnd != -1) {
                String jsonSnippet = text.substring(contentStart, jsonEnd).trim();
                boolean parsed = tryParseJsonToolCall(jsonSnippet, toolCalls);
                if (parsed) {
                    text = (text.substring(0, jsonStart) + "\n" + text.substring(jsonEnd + 3)).trim();
                }
            }
        } else if (text.startsWith("{") && text.endsWith("}")) {
            boolean parsed = tryParseJsonToolCall(text, toolCalls);
            if (parsed) {
                text = "";
            }
        }

        return new AiResponse(text, toolCalls);
    }

    private boolean tryParseJsonToolCall(String jsonSnippet, List<ToolCall> toolCalls) {
        try {
            JSONObject obj = new JSONObject(jsonSnippet);
            if (obj.has("tool")) {
                String name = obj.getString("tool");
                JSONObject params = obj.optJSONObject("parameters");
                if (params == null) params = obj.optJSONObject("arguments");
                toolCalls.add(new ToolCall(name, params != null ? params : new JSONObject()));
                return true;
            } else if (obj.has("functionCall")) {
                JSONObject fn = obj.getJSONObject("functionCall");
                String name = fn.getString("name");
                JSONObject args = fn.optJSONObject("args");
                toolCalls.add(new ToolCall(name, args != null ? args : new JSONObject()));
                return true;
            } else if (obj.has("recipe")) {
                toolCalls.add(new ToolCall("automation_schedule_task", obj));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // --------------------------------------------------------------------------
    // PROVIDER 2: ANTIGRAVITY CLOUD (daily-cloudcode-pa.googleapis.com)
    // --------------------------------------------------------------------------
    private void executeViaAntigravityCloud(final JSONArray contents, final ResponseCallback callback) {
        HttpsURLConnection conn = null;
        try {
            String accessToken = AntigravityOAuthManager.getOrRefreshAccessToken(configManager);
            String modelId = configManager.getAntigravityModel();

            JSONObject root = new JSONObject();
            root.put("model", modelId);

            JSONObject reqObj = new JSONObject();
            reqObj.put("contents", contents);

            if (systemInstructionText != null && !systemInstructionText.isEmpty()) {
                JSONObject sysInstructionObj = new JSONObject();
                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject();
                part.put("text", systemInstructionText);
                parts.put(part);
                sysInstructionObj.put("parts", parts);
                reqObj.put("systemInstruction", sysInstructionObj);
            }

            if (AiConfigManager.MODE_STRUCTURED.equals(configManager.getMode()) && toolsDeclarationJson != null) {
                reqObj.put("tools", toolsDeclarationJson);
            }

            root.put("request", reqObj);

            URL url = new URL(CLOUDCODE_GENERATE_URL);
            conn = (HttpsURLConnection) url.openConnection();
            activeConnection = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "antigravity/2.28.2");

            byte[] postBytes = root.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(postBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = readStream(stream);

            if (code < 200 || code >= 300) {
                Log.e(TAG, "Antigravity Cloud HTTP " + code + ": " + responseBody);
                callback.onError("Antigravity Cloud Error (" + code + "): " + extractErrorMessage(responseBody));
                return;
            }

            JSONObject respJson = new JSONObject(responseBody);
            JSONObject responseObj = respJson.optJSONObject("response");
            if (responseObj == null) responseObj = respJson;

            AiResponse parsed = parseGeminiResponse(responseObj.toString());
            callback.onSuccess(parsed);

        } catch (Exception e) {
            Log.e(TAG, "Antigravity Cloud request failed", e);
            callback.onError("Antigravity Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            if (activeConnection == conn) {
                activeConnection = null;
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // --------------------------------------------------------------------------
    // PROVIDER 3: GEMINI AI STUDIO DEVELOPER API
    // --------------------------------------------------------------------------
    private void executeViaGeminiStudio(final JSONArray contents, final ResponseCallback callback) {
        String apiKey = configManager.getApiKey();
        if (apiKey.isEmpty()) {
            callback.onError("Gemini API key is not configured. Please enter your API key in Agent settings.");
            return;
        }

        String model = configManager.getModel();
        String endpoint = GEMINI_BASE_URL + model + ":generateContent?key=" + apiKey;

        HttpURLConnection conn = null;
        try {
            JSONObject requestBody = new JSONObject();

            if (systemInstructionText != null && !systemInstructionText.isEmpty()) {
                JSONObject sysInstructionObj = new JSONObject();
                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject();
                part.put("text", systemInstructionText);
                parts.put(part);
                sysInstructionObj.put("parts", parts);
                requestBody.put("system_instruction", sysInstructionObj);
            }

            requestBody.put("contents", contents);

            if (AiConfigManager.MODE_STRUCTURED.equals(configManager.getMode()) && toolsDeclarationJson != null) {
                requestBody.put("tools", toolsDeclarationJson);
            }

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            activeConnection = conn;
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(25000);
            conn.setReadTimeout(35000);

            byte[] postBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(postBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            InputStream stream = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String responseSb = readStream(stream);

            if (responseCode < 200 || responseCode >= 300) {
                Log.e(TAG, "Gemini API HTTP Error " + responseCode + ": " + responseSb);
                callback.onError("Gemini API Error (" + responseCode + "): " + extractErrorMessage(responseSb));
                return;
            }

            AiResponse parsedResponse = parseGeminiResponse(responseSb);
            callback.onSuccess(parsedResponse);

        } catch (Exception e) {
            Log.e(TAG, "Exception during Gemini API request", e);
            callback.onError("Gemini API Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            if (activeConnection == conn) {
                activeConnection = null;
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String extractPromptFromContents(JSONArray contents) {
        StringBuilder sb = new StringBuilder();
        if (contents != null) {
            for (int i = 0; i < contents.length(); i++) {
                JSONObject turn = contents.optJSONObject(i);
                if (turn == null) continue;
                String role = turn.optString("role", "user");
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) {
                    for (int p = 0; p < parts.length(); p++) {
                        JSONObject part = parts.optJSONObject(p);
                        if (part != null && part.has("text")) {
                            String t = part.optString("text", "");
                            if (!t.isEmpty()) {
                                if (contents.length() > 1) {
                                    sb.append("[").append(role.toUpperCase()).append("]: ");
                                }
                                sb.append(t).append("\n");
                            }
                        }
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private String extractErrorMessage(String errorJsonStr) {
        try {
            JSONObject errJson = new JSONObject(errorJsonStr);
            if (errJson.has("error")) {
                JSONObject errObj = errJson.getJSONObject("error");
                return errObj.optString("message", errorJsonStr);
            }
        } catch (Exception ignored) {
        }
        return errorJsonStr;
    }

    private AiResponse parseGeminiResponse(String responseJsonStr) throws JSONException {
        JSONObject root = new JSONObject(responseJsonStr);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return new AiResponse("No response generated.", null);
        }

        JSONObject firstCandidate = candidates.getJSONObject(0);
        JSONObject content = firstCandidate.optJSONObject("content");
        if (content == null) {
            return new AiResponse("Empty response content.", null);
        }

        JSONArray parts = content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) {
            return new AiResponse("No content parts.", null, content);
        }

        StringBuilder textBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part.has("text")) {
                textBuilder.append(part.getString("text"));
            }
            if (part.has("functionCall")) {
                JSONObject fnCall = part.getJSONObject("functionCall");
                String name = fnCall.getString("name");
                JSONObject args = fnCall.optJSONObject("args");
                toolCalls.add(new ToolCall(name, args));
            }
        }

        return new AiResponse(textBuilder.toString().trim(), toolCalls, content);
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toString("UTF-8");
    }
}

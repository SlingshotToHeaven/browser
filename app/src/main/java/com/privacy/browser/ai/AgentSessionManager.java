package com.privacy.browser.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import com.privacy.browser.MainActivity;
import com.privacy.browser.TabsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AgentSessionManager {

    private static final String TAG = "AgentSessionManager";

    public interface SessionCallback {
        void onMessageAdded(AiChatAdapter.ChatMessage message);
        void onToolStatusUpdated(String text, boolean inProgress);
        void onThinkingState(boolean isThinking, String text);
        void onSessionLoaded(String sessionId, String title, List<AiChatAdapter.ChatMessage> messages);
        void onSessionStateChanged(int state);
    }

    private static AgentSessionManager instance;

    public static synchronized AgentSessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new AgentSessionManager(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AgentDbHelper dbHelper;
    private final GeminiClient geminiClient;
    private final ToolDispatcher toolDispatcher;

    private String currentSessionId;
    private String currentTitle = "New Conversation";
    private int sessionState = AiFloatingButtonView.STATE_IDLE;
    private long currentRunToken = 0;

    private JSONArray conversationContents = new JSONArray();
    private JSONObject pendingModelContent = null;
    private final List<AiChatAdapter.ChatMessage> activeMessages = new ArrayList<>();

    private SessionCallback sessionCallback;
    private AiFloatingButtonView floatingButton;

    private AgentSessionManager(Context context) {
        this.context = context;
        this.dbHelper = AgentDbHelper.getInstance(context);
        this.geminiClient = new GeminiClient(context);
        this.toolDispatcher = new ToolDispatcher(context);
    }

    public void setSessionCallback(SessionCallback callback) {
        this.sessionCallback = callback;
    }

    public void setFloatingButton(AiFloatingButtonView button) {
        this.floatingButton = button;
        if (this.floatingButton != null) {
            this.floatingButton.setSessionState(sessionState);
        }
    }

    public void updateActiveContext(WebView webView, MainActivity activity, TabsManager tabsManager) {
        if (toolDispatcher != null) {
            toolDispatcher.setCurrentWebView(webView);
            toolDispatcher.setActivityAndTabs(activity, tabsManager);
        }
    }

    public int getSessionState() {
        return sessionState;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public List<AiChatAdapter.ChatMessage> getActiveMessages() {
        return new ArrayList<>(activeMessages);
    }

    public boolean hasActiveConversation() {
        return !activeMessages.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Session Initialization & Lifecycle
    // -------------------------------------------------------------------------

    public void initialize() {
        // Cold start Option A: Restore the last active session, or create fresh
        String activeId = dbHelper.getActiveSessionId();
        if (activeId != null) {
            loadSession(activeId);
        } else {
            startNewSession();
        }

        // Clean orphaned screenshot files in background
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Set<String> referenced = dbHelper.getAllReferencedAttachmentPaths();
                    AgentAttachmentManager.cleanOrphanedAttachments(context, referenced);
                } catch (Exception e) {
                    Log.e(TAG, "Error cleaning orphaned attachments", e);
                }
            }
        }).start();
    }

    public void startNewSession() {
        currentRunToken++;
        currentSessionId = UUID.randomUUID().toString();
        currentTitle = "New Conversation";
        activeMessages.clear();
        conversationContents = new JSONArray();
        pendingModelContent = null;
        notifyThinking(false, null);
        setSessionStateInternal(AiFloatingButtonView.STATE_IDLE);

        dbHelper.createSession(currentSessionId, currentTitle);
        dbHelper.saveContext(currentSessionId, conversationContents, null);

        notifySessionLoaded();
    }

    public void loadSession(String sessionId) {
        if (sessionId == null) return;
        currentRunToken++;
        currentSessionId = sessionId;
        dbHelper.setActiveSession(sessionId);

        // Load messages from DB
        List<AiChatAdapter.ChatMessage> loaded = dbHelper.getMessages(sessionId);
        activeMessages.clear();
        activeMessages.addAll(loaded);

        // Load conversation context for Gemini turn continuity
        conversationContents = dbHelper.getContext(sessionId);
        pendingModelContent = dbHelper.getPendingModelContent(sessionId);

        notifyThinking(false, null);

        // Determine title
        List<AgentDbHelper.SessionRecord> all = dbHelper.getAllSessions();
        for (AgentDbHelper.SessionRecord s : all) {
            if (s.sessionId.equals(sessionId)) {
                currentTitle = s.title;
                break;
            }
        }

        int newState = activeMessages.isEmpty() ? AiFloatingButtonView.STATE_IDLE : AiFloatingButtonView.STATE_ACTIVE;
        setSessionStateInternal(newState);

        notifySessionLoaded();
    }

    public void deleteSession(String sessionId) {
        dbHelper.deleteSession(sessionId);
        if (sessionId.equals(currentSessionId)) {
            startNewSession();
        }
    }

    // -------------------------------------------------------------------------
    // Messaging & Gemini Pipeline
    // -------------------------------------------------------------------------

    public void sendUserPrompt(final String text, final String imagePath) {
        if (currentSessionId == null) {
            startNewSession();
        }

        // Auto-title session from the first prompt
        if (activeMessages.isEmpty()) {
            if (text != null && !text.trim().isEmpty()) {
                String title = text.trim();
                if (title.length() > 32) {
                    title = title.substring(0, 29) + "...";
                }
                currentTitle = title;
                dbHelper.updateSessionTitle(currentSessionId, title);
            } else if (imagePath != null) {
                currentTitle = "Visual Inspection";
                dbHelper.updateSessionTitle(currentSessionId, currentTitle);
            }
        }

        // 1. Add User Message with optional persistent attachment
        AiChatAdapter.ChatMessage userMsg = new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_USER, text, imagePath);
        addMessageInternal(userMsg);

        // 2. Build Gemini contents turn
        try {
            JSONObject userContent = new JSONObject();
            userContent.put("role", "user");
            JSONArray parts = new JSONArray();

            if (text != null && !text.isEmpty()) {
                JSONObject textPart = new JSONObject();
                textPart.put("text", text);
                parts.put(textPart);
            }

            if (imagePath != null && !imagePath.isEmpty()) {
                String base64Image = AgentAttachmentManager.getBase64FromPath(imagePath);
                if (base64Image != null && !base64Image.isEmpty()) {
                    String mimeType = AgentAttachmentManager.getMimeTypeFromPath(imagePath);
                    JSONObject imagePart = new JSONObject();
                    JSONObject inlineData = new JSONObject();
                    inlineData.put("mime_type", mimeType);
                    inlineData.put("data", base64Image);
                    imagePart.put("inline_data", inlineData);
                    parts.put(imagePart);
                }
            }

            userContent.put("parts", parts);
            conversationContents.put(userContent);
            dbHelper.saveContext(currentSessionId, conversationContents, pendingModelContent);

            // 3. Set processing state and invoke Gemini
            final long runToken = ++currentRunToken;
            final String runSessionId = currentSessionId;
            setSessionStateInternal(AiFloatingButtonView.STATE_PROCESSING);
            notifyThinking(true, "Agent is thinking...");

            callGemini(runSessionId, runToken);

        } catch (Exception e) {
            Log.e(TAG, "Error formulating request", e);
            notifyThinking(false, null);
            setSessionStateInternal(AiFloatingButtonView.STATE_ACTIVE);
            addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_ERROR, "Error formulating request: " + e.getMessage()));
        }
    }

    private void callGemini(final String runSessionId, final long runToken) {
        geminiClient.generateContent(conversationContents, null, new GeminiClient.ResponseCallback() {
            @Override
            public void onSuccess(final GeminiClient.AiResponse response) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Guard against race conditions when user switches or creates a new chat
                        if (runToken != currentRunToken || !runSessionId.equals(currentSessionId)) {
                            Log.d(TAG, "Ignoring Gemini response for superseded session: " + runSessionId);
                            return;
                        }

                        notifyThinking(false, null);

                        if (!response.text.isEmpty()) {
                            AiChatAdapter.ChatMessage astMsg = new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_ASSISTANT, response.text);
                            addMessageInternal(astMsg);
                        }

                        if (response.hasToolCalls()) {
                            pendingModelContent = response.rawContent;
                            dbHelper.saveContext(currentSessionId, conversationContents, pendingModelContent);

                            for (GeminiClient.ToolCall tool : response.toolCalls) {
                                if (toolDispatcher.isConfirmationRequired(tool.name)) {
                                    AiChatAdapter.ChatMessage confirmMsg = new AiChatAdapter.ChatMessage(
                                            AiChatAdapter.TYPE_CONFIRMATION,
                                            "Tool '" + tool.name + "' wants to execute with: " + tool.arguments.toString(),
                                            tool.name,
                                            tool.arguments
                                    );
                                    addMessageInternal(confirmMsg);
                                    setSessionStateInternal(AiFloatingButtonView.STATE_ACTIVE);
                                } else {
                                    executeApprovedTool(tool.name, tool.arguments, runSessionId, runToken);
                                }
                            }
                        } else {
                            // Normal model turn with no tools: commit to conversation context
                            if (response.rawContent != null) {
                                conversationContents.put(response.rawContent);
                            } else if (!response.text.isEmpty()) {
                                try {
                                    JSONObject modelContent = new JSONObject();
                                    modelContent.put("role", "model");
                                    JSONArray parts = new JSONArray();
                                    JSONObject textPart = new JSONObject();
                                    textPart.put("text", response.text);
                                    parts.put(textPart);
                                    modelContent.put("parts", parts);
                                    conversationContents.put(modelContent);
                                } catch (Exception ignored) {
                                }
                            }
                            dbHelper.saveContext(currentSessionId, conversationContents, null);
                            setSessionStateInternal(AiFloatingButtonView.STATE_ACTIVE);
                        }
                    }
                });
            }

            @Override
            public void onError(final String errorMessage) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (runToken != currentRunToken || !runSessionId.equals(currentSessionId)) {
                            return;
                        }
                        notifyThinking(false, null);
                        setSessionStateInternal(AiFloatingButtonView.STATE_ACTIVE);
                        addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_ERROR, errorMessage));
                    }
                });
            }
        });
    }

    public void executeApprovedTool(final String name, final JSONObject args) {
        executeApprovedTool(name, args, currentSessionId, currentRunToken);
    }

    public void executeApprovedTool(final String name, final JSONObject args, final String runSessionId, final long runToken) {
        if (runToken != currentRunToken || !runSessionId.equals(currentSessionId)) {
            return;
        }

        setSessionStateInternal(AiFloatingButtonView.STATE_PROCESSING);
        AiChatAdapter.ChatMessage toolRunningMsg = new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_TOOL, "Running " + name + "...", name, args, true);
        addMessageInternal(toolRunningMsg);

        toolDispatcher.executeTool(name, args, new ToolDispatcher.ExecutionCallback() {
            @Override
            public void onCompleted(final String resultJson) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (runToken != currentRunToken || !runSessionId.equals(currentSessionId)) {
                            Log.d(TAG, "Ignoring tool result for superseded session: " + runSessionId);
                            return;
                        }

                        String summary = formatToolSummary(name, resultJson);
                        updateToolStatusInternal(summary, false);

                        try {
                            if (pendingModelContent != null) {
                                conversationContents.put(pendingModelContent);
                                pendingModelContent = null;
                            } else {
                                JSONObject modelContent = new JSONObject();
                                modelContent.put("role", "model");
                                JSONArray modelParts = new JSONArray();
                                JSONObject fnCallPart = new JSONObject();
                                JSONObject fnCall = new JSONObject();
                                fnCall.put("name", name);
                                fnCall.put("args", args);
                                fnCallPart.put("functionCall", fnCall);
                                modelParts.put(fnCallPart);
                                modelContent.put("parts", modelParts);
                                conversationContents.put(modelContent);
                            }

                            JSONObject toolContent = new JSONObject();
                            toolContent.put("role", "user");
                            JSONArray toolParts = new JSONArray();
                            JSONObject fnRespPart = new JSONObject();
                            JSONObject fnResp = new JSONObject();
                            fnResp.put("name", name);

                            JSONObject responseObj;
                            try {
                                String trimmed = resultJson != null ? resultJson.trim() : "";
                                if (trimmed.startsWith("{")) {
                                    responseObj = new JSONObject(trimmed);
                                } else if (trimmed.startsWith("[")) {
                                    responseObj = new JSONObject();
                                    responseObj.put("result", new JSONArray(trimmed));
                                } else {
                                    responseObj = new JSONObject();
                                    responseObj.put("output", resultJson != null ? resultJson : "success");
                                }
                            } catch (Exception e) {
                                responseObj = new JSONObject();
                                responseObj.put("output", resultJson != null ? resultJson : "success");
                            }

                            fnResp.put("response", responseObj);
                            fnRespPart.put("functionResponse", fnResp);
                            toolParts.put(fnRespPart);
                            toolContent.put("parts", toolParts);
                            conversationContents.put(toolContent);

                            dbHelper.saveContext(currentSessionId, conversationContents, null);

                            // Trigger follow-up AI reasoning
                            notifyThinking(true, "Agent is thinking...");
                            callGemini(runSessionId, runToken);

                        } catch (Exception e) {
                            Log.e(TAG, "Tool response integration error", e);
                            setSessionStateInternal(AiFloatingButtonView.STATE_ACTIVE);
                            addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_ERROR, "Tool response error: " + e.getMessage()));
                        }
                    }
                });
            }

            @Override
            public void onError(final String error) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (runToken != currentRunToken || !runSessionId.equals(currentSessionId)) {
                            return;
                        }
                        updateToolStatusInternal("Error running " + name + ": " + error, false);
                        setSessionStateInternal(AiFloatingButtonView.STATE_ACTIVE);
                    }
                });
            }
        });
    }

    public void cancelPendingTool(String toolName) {
        pendingModelContent = null;
        dbHelper.saveContext(currentSessionId, conversationContents, null);
        addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_ASSISTANT, "Action cancelled by user: " + toolName));
        setSessionStateInternal(AiFloatingButtonView.STATE_ACTIVE);
    }

    public void stopActiveSession() {
        // Invalidate active generation token so in-flight callbacks are dropped immediately
        currentRunToken++;

        // Disconnect active HTTP socket immediately
        geminiClient.cancelCurrentRequest();

        // Reset processing state and thinking UI
        notifyThinking(false, null);
        setSessionStateInternal(AiFloatingButtonView.STATE_IDLE);

        // Mark last in-progress message as stopped if present
        if (!activeMessages.isEmpty()) {
            int lastIdx = activeMessages.size() - 1;
            AiChatAdapter.ChatMessage last = activeMessages.get(lastIdx);
            if (last.inProgress) {
                AiChatAdapter.ChatMessage stoppedMsg = new AiChatAdapter.ChatMessage(
                        last.type,
                        last.content + " (Stopped)",
                        last.toolName,
                        last.toolArgs,
                        false,
                        last.imagePath
                );
                activeMessages.set(lastIdx, stoppedMsg);
                dbHelper.updateLastToolMessage(currentSessionId, last.content + " (Stopped)");
                if (sessionCallback != null) {
                    sessionCallback.onToolStatusUpdated(last.content + " (Stopped)", false);
                }
            }
        }

        // Clear pending model content and persist clean context
        pendingModelContent = null;
        dbHelper.saveContext(currentSessionId, conversationContents, null);
    }

    // -------------------------------------------------------------------------
    // Internal Notifications & State Updates
    // -------------------------------------------------------------------------

    private void addMessageInternal(AiChatAdapter.ChatMessage message) {
        activeMessages.add(message);
        dbHelper.insertMessage(currentSessionId, message);
        if (sessionCallback != null) {
            sessionCallback.onMessageAdded(message);
        }
    }

    private void updateToolStatusInternal(String text, boolean inProgress) {
        if (!activeMessages.isEmpty()) {
            int lastIdx = activeMessages.size() - 1;
            AiChatAdapter.ChatMessage last = activeMessages.get(lastIdx);
            if (last.type == AiChatAdapter.TYPE_TOOL || last.type == AiChatAdapter.TYPE_THINKING) {
                activeMessages.set(lastIdx, new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_TOOL, text, last.toolName, last.toolArgs, inProgress));
            }
        }
        dbHelper.updateLastToolMessage(currentSessionId, text);
        if (sessionCallback != null) {
            sessionCallback.onToolStatusUpdated(text, inProgress);
        }
    }

    private void notifyThinking(boolean isThinking, String text) {
        if (sessionCallback != null) {
            sessionCallback.onThinkingState(isThinking, text);
        }
    }

    private void notifySessionLoaded() {
        if (sessionCallback != null) {
            sessionCallback.onSessionLoaded(currentSessionId, currentTitle, new ArrayList<>(activeMessages));
        }
    }

    private void setSessionStateInternal(int state) {
        this.sessionState = state;
        if (floatingButton != null) {
            floatingButton.setSessionState(state);
        }
        if (sessionCallback != null) {
            sessionCallback.onSessionStateChanged(state);
        }
    }

    private String formatToolSummary(String name, String resultJson) {
        if (resultJson == null || resultJson.trim().isEmpty()) {
            return "Executed " + name;
        }
        try {
            String trimmed = resultJson.trim();
            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                if (obj.has("count")) {
                    int count = obj.getInt("count");
                    String itemWord = "items";
                    if (name.contains("bookmark")) itemWord = "bookmarks";
                    else if (name.contains("history")) itemWord = "history entries";
                    else if (name.contains("download")) itemWord = "downloads";
                    else if (name.contains("tab")) itemWord = "tabs";
                    return count == 0 ? "No " + itemWord + " found" : "Found " + count + " " + itemWord;
                }
                if (obj.has("status")) {
                    String status = obj.getString("status");
                    if ("clicked".equalsIgnoreCase(status)) return "Clicked element";
                    if ("typed".equalsIgnoreCase(status)) return "Entered text";
                    if ("scrolled".equalsIgnoreCase(status)) return "Scrolled page";
                    if ("created".equalsIgnoreCase(status)) return "Created successfully";
                    if ("deleted".equalsIgnoreCase(status)) {
                        int c = obj.optInt("count", 1);
                        return "Deleted " + c + " item(s)";
                    }
                    if ("not_found".equalsIgnoreCase(status)) return "Element not found";
                    return "Status: " + status;
                }
                if (obj.has("headings")) {
                    JSONArray h = obj.getJSONArray("headings");
                    return "Found " + h.length() + " headings";
                }
                if (obj.has("links")) {
                    JSONArray l = obj.getJSONArray("links");
                    return "Found " + l.length() + " links";
                }
                if (obj.has("title")) {
                    return "Page: " + obj.getString("title");
                }
                if (obj.has("text")) {
                    return "Extracted page text";
                }
            } else if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                return "Found " + arr.length() + " items";
            }
        } catch (Exception ignored) {
        }

        String clean = resultJson.replace("{", "").replace("}", "").replace("\"", "").replace("[", "").replace("]", "").trim();
        if (clean.length() > 40) {
            clean = clean.substring(0, 37) + "...";
        }
        return "Executed " + name + (clean.isEmpty() ? "" : " (" + clean + ")");
    }
}

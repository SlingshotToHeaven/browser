package com.privacy.browser.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.privacy.browser.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class AgentSessionManager {

    private static final int MAX_CONTEXT = 96 * 1024;
    private static final int MAX_TOOL_TURNS = 3;
    private static final long ACTION_TIMEOUT_MS = 80_000L;

    public interface SessionCallback {
        void onMessageAdded(AiChatAdapter.ChatMessage message);
        boolean canReview();
        void onThinkingState(boolean isThinking, String text);
        void onSessionLoaded(String sessionId, String title, List<AiChatAdapter.ChatMessage> messages);
        void onSessionStateChanged(int state);
    }

    // Instance-local ports; production binds the Android queue, SQLite, Gemini and native reviews.
    interface MainQueue {
        void post(Runnable task);
        void postDelayed(Runnable task, long delayMs);
        void removeCallbacks(Runnable task);
    }

    interface Provider {
        interface ResponseCallback {
            void onSuccess(GeminiProtocol.Response response);
            void onError(String message);
        }

        void generateContent(GeminiSettings selection, JSONArray contents, ResponseCallback callback);
        void cancelCurrentRequest();
    }

    interface Reviews {
        MainActivity owner();
        void attach(MainActivity owner);
        void detach(MainActivity owner);
        AssistantApproval.Page page();
        void cancelReview();
        void review(AssistantAction action, AssistantApproval.Source source, AssistantApproval.Scope expected,
                    Supplier<AssistantApproval.Scope> live, ToolDispatcher.ExecutionCallback result);
    }

    interface Transcript {
        String getActiveSessionId();
        void createSession(String sessionId, String title);
        void setActiveSession(String sessionId);
        List<AiChatAdapter.ChatMessage> getMessages(String sessionId);
        String getSessionTitle(String sessionId);
        void updateSessionTitle(String sessionId, String title);
        void deleteSession(String sessionId);
        void insertMessage(String sessionId, AiChatAdapter.ChatMessage message);
    }

    private static AgentSessionManager instance;

    public static synchronized AgentSessionManager getInstance(Context context) {
        if (instance == null) {
            instance = createForAndroid(context.getApplicationContext());
        }
        return instance;
    }

    private final MainQueue mainQueue;
    private final Transcript transcript;
    private final Provider provider;
    private final Reviews reviews;
    private final Supplier<GeminiSettings> connectionSettings;
    private final BooleanSupplier agentEnabled;

    private String currentSessionId;
    private String currentTitle = "New Conversation";
    private int sessionState = AiFloatingButtonView.STATE_IDLE;
    private long currentRunToken = 0;

    // Provider context is bounded and in-memory. The durable transcript remains readable after restart.
    private final Deque<JSONArray> exchanges = new ArrayDeque<>();
    private GeminiSettings contextConnection;
    private Run activeRun;

    private static final class Run {
        final String session;
        final GeminiSettings connection;
        final long token;
        final AssistantApproval.Scope originalScope;
        final JSONArray turns = new JSONArray();
        int toolTurns, providerRequest;
        boolean awaitingProvider;
        Runnable actionTimeout;
        Run(String session, GeminiSettings connection, long token, AssistantApproval.Page originalPage) {
            this.session = session; this.connection = connection; this.token = token;
            originalScope = new AssistantApproval.Scope(session, token, originalPage);
        }
    }
    private final List<AiChatAdapter.ChatMessage> activeMessages = new ArrayList<>();

    private SessionCallback sessionCallback;
    private AiFloatingButtonView floatingButton;

    private static AgentSessionManager createForAndroid(Context context) {
        Handler handler = new Handler(Looper.getMainLooper());
        MainQueue queue = new MainQueue() {
            @Override public void post(Runnable task) { handler.post(task); }
            @Override public void postDelayed(Runnable task, long delayMs) { handler.postDelayed(task, delayMs); }
            @Override public void removeCallbacks(Runnable task) { handler.removeCallbacks(task); }
        };
        AiConfigManager config = new AiConfigManager(context);
        return new AgentSessionManager(queue, AgentDbHelper.getInstance(context), new GeminiClient(context),
                new ToolDispatcher(context), config::connection, config::isAgentEnabled);
    }

    AgentSessionManager(MainQueue mainQueue, Transcript transcript, Provider provider, Reviews reviews,
                        Supplier<GeminiSettings> connectionSettings, BooleanSupplier agentEnabled) {
        this.mainQueue = mainQueue;
        this.transcript = transcript;
        this.provider = provider;
        this.reviews = reviews;
        this.connectionSettings = connectionSettings;
        this.agentEnabled = agentEnabled;
    }

    void openSheet(MainActivity owner, SessionCallback sheet, AiFloatingButtonView button) {
        if (reviews.owner() != owner || sessionCallback != sheet) {
            if (activeRun != null) stopActiveSession();
            reviews.attach(owner);
        }
        sessionCallback = sheet;
        floatingButton = button;
        if (floatingButton != null) floatingButton.setSessionState(sessionState);
        if (currentSessionId == null) initialize();
    }

    void closeSheet(MainActivity owner, SessionCallback sheet) {
        if (reviews.owner() != owner || sessionCallback != sheet) return;
        sessionCallback = null;
        stopActiveSession();
    }

    void detachActivity(MainActivity owner, SessionCallback sheet) {
        closeSheet(owner, sheet);
        if (reviews.owner() == owner) {
            floatingButton = null;
            reviews.detach(owner);
        }
    }

    void pauseActivity(MainActivity owner) {
        if (reviews.owner() == owner) stopActiveSession();
    }

    void pageChanged(MainActivity owner) {
        if (reviews.owner() == owner) reviews.cancelReview();
    }

    void settingsChanged() {
        stopActiveSession();
        exchanges.clear();
        contextConnection = null;
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

    private void initialize() {
        // Cold start Option A: Restore the last active session, or create fresh
        String activeId = transcript.getActiveSessionId();
        if (activeId != null) {
            loadSession(activeId);
        } else {
            startNewSession();
        }

        // Legacy storage cleanup is an explicit action, not a side effect of reopening Agent.
    }

    private void invalidateRun() {
        currentRunToken++;
        if (activeRun != null && activeRun.actionTimeout != null) mainQueue.removeCallbacks(activeRun.actionTimeout);
        activeRun = null;
        provider.cancelCurrentRequest();
        reviews.cancelReview();
    }

    public void startNewSession() {
        invalidateRun();
        exchanges.clear();
        contextConnection = null;
        currentSessionId = UUID.randomUUID().toString();
        currentTitle = "New Conversation";
        activeMessages.clear();
        notifyThinking(false, null);
        setSessionStateInternal(AiFloatingButtonView.STATE_IDLE);

        transcript.createSession(currentSessionId, currentTitle);

        notifySessionLoaded();
    }

    public void loadSession(String sessionId) {
        if (sessionId == null) return;
        invalidateRun();
        exchanges.clear();
        contextConnection = null;
        currentSessionId = sessionId;
        transcript.setActiveSession(sessionId);

        // Load messages from DB
        List<AiChatAdapter.ChatMessage> loaded = transcript.getMessages(sessionId);
        activeMessages.clear();
        activeMessages.addAll(loaded);

        // Legacy stored provider context can contain screenshots and unpaired calls.
        // Keep the transcript, but never replay that context or mint approvals from it.
        currentTitle = "Conversation";

        notifyThinking(false, null);

        String title = transcript.getSessionTitle(sessionId);
        if (title != null) currentTitle = title;

        int newState = activeMessages.isEmpty() ? AiFloatingButtonView.STATE_IDLE : AiFloatingButtonView.STATE_ACTIVE;
        setSessionStateInternal(newState);

        notifySessionLoaded();
    }

    public void deleteSession(String sessionId) {
        if (sessionId == null) return;
        transcript.deleteSession(sessionId);
        if (sessionId.equals(currentSessionId)) {
            startNewSession();
        }
    }

    // -------------------------------------------------------------------------
    // Messaging & Gemini Pipeline
    // -------------------------------------------------------------------------

    public void sendUserPrompt(final String text, final String imagePath) {
        if (currentSessionId == null) startNewSession();
        if (activeRun != null) {
            showError("Stop the current request before starting another.");
            return;
        }
        if (!sheetShowing()) return;
        GeminiSettings connection = connectionSettings.get();
        if (connection == null) {
            showError("Cloud chat is not configured. Select Gemini in Agent settings. Nothing was sent.");
            return;
        }
        if (imagePath != null) { showError("Image attachments are unavailable. No image was sent."); return; }
        if (text == null || text.trim().isEmpty() || text.length() > 8_192) { showError("Enter a prompt of 1–8192 characters."); return; }
        if (!connection.matches(contextConnection)) {
            exchanges.clear();
            contextConnection = connection;
        }
        if (activeMessages.isEmpty()) {
            currentTitle = text.length() > 32 ? text.substring(0, 29) + "..." : text;
            transcript.updateSessionTitle(currentSessionId, currentTitle);
        }
        addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_USER, text));
        Run run = new Run(currentSessionId, connection, ++currentRunToken, reviews.page());
        activeRun = run;
        try {
            run.turns.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", text))));
            setSessionStateInternal(AiFloatingButtonView.STATE_PROCESSING);
            callProvider(run);
        } catch (Exception e) { finish(run, "Could not prepare the request.", false); }
    }

    private boolean isActive(Run run) { return run == activeRun && run.token == currentRunToken && run.session.equals(currentSessionId); }
    private boolean sheetShowing() { return sessionCallback != null && sessionCallback.canReview(); }
    private boolean sameConnection(Run run) { return run.connection.matches(connectionSettings.get()); }

    private void callProvider(Run run) {
        if (!isActive(run)) return;
        if (!sheetShowing() || !sameConnection(run)) { finish(run, "Agent settings or visibility changed. Nothing further was sent.", false); return; }
        // Trim only completed prompt groups; never orphan a function call or response.
        int length = run.turns.toString().length();
        if (length > MAX_CONTEXT) { finish(run, "Assistant context limit reached. Send a new prompt to continue.", false); return; }
        for (JSONArray old : exchanges) length += old.toString().length();
        while (length > MAX_CONTEXT && !exchanges.isEmpty()) length -= exchanges.removeFirst().toString().length();
        JSONArray input = new JSONArray();
        for (JSONArray old : exchanges) append(input, old);
        append(input, run.turns);
        int request = ++run.providerRequest;
        run.awaitingProvider = true;
        notifyThinking(true, "Agent is thinking…");
        provider.generateContent(run.connection, input, new Provider.ResponseCallback() {
            @Override public void onSuccess(GeminiProtocol.Response response) {
                mainQueue.post(() -> { if (takeProviderReply(run, request)) acceptResponse(run, response); });
            }
            @Override public void onError(String error) {
                mainQueue.post(() -> { if (takeProviderReply(run, request)) finish(run, error, false); });
            }
        });
    }

    private boolean takeProviderReply(Run run, int request) {
        // A duplicate callback or a late error from a previous request in the same run
        // must not start another batch, end the follow-up, or produce more transcript rows.
        if (!isActive(run) || !run.awaitingProvider || run.providerRequest != request) return false;
        run.awaitingProvider = false;
        return true;
    }

    private void acceptResponse(Run run, GeminiProtocol.Response response) {
        if (!isActive(run)) return;
        if (!sheetShowing() || !sameConnection(run)) { finish(run, "Agent settings or visibility changed. No actions ran from this response.", false); return; }
        notifyThinking(false, null);
        if (!response.text.isEmpty()) addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_ASSISTANT, response.text));
        try {
            AgentToolBatch batch = response.content == null ? null : new AgentToolBatch(response.content);
            if (batch == null || batch.calls.isEmpty()) {
                if (response.content != null) run.turns.put(response.content);
                finish(run, null, true);
                return;
            }
            if (!run.connection.tools) {
                finish(run, "This mode does not execute provider actions.", false);
                return;
            }
            if (++run.toolTurns > MAX_TOOL_TURNS) { finish(run, "Action review limit reached; no further tools ran.", false); return; }
            reviewNext(run, batch, 0);
        } catch (Exception invalid) { finish(run, "The provider returned an unsupported tool turn. No actions ran from it.", false); }
    }

    private AssistantApproval.Scope liveScope(Run run) {
        if (!isActive(run) || !sameConnection(run) || !sheetShowing()) return null;
        return new AssistantApproval.Scope(currentSessionId, currentRunToken, reviews.page());
    }

    private void reviewNext(Run run, AgentToolBatch batch, int index) {
        if (!isActive(run)) return;
        if (!sheetShowing() || !sameConnection(run)) { finish(run, "Review stopped. Approved effects may already have occurred.", false); return; }
        if (index == batch.calls.size()) {
            try {
                JSONArray paired = batch.finish();
                if (paired != null) { append(run.turns, paired); callProvider(run); }
            } catch (Exception invalid) { finish(run, "Could not correlate the tool responses.", false); }
            return;
        }
        AgentToolBatch.Call call = batch.calls.get(index);
        AssistantAction.Kind kind = AssistantAction.Kind.find(call.name);
        boolean pageBoundResult = kind != null && (kind.scripts || kind == AssistantAction.Kind.INFO || kind == AssistantAction.Kind.CLEAR);
        final class Result implements ToolDispatcher.ExecutionCallback {
            private final Runnable timer = () -> onStopped("timeout", "No result in time. An approved action may already have taken effect.");
            @Override public void onCompleted(JSONObject outcome) { settle(outcome, null); }
            @Override public void onStopped(String status, String reason) { settle(AgentToolBatch.terminal(status, reason), reason); }
            private void settle(JSONObject value, String reason) {
                // A queued result can outlive both its review dialog and the document that produced it.
                mainQueue.post(() -> accept(value, reason));
            }
            private void accept(JSONObject value, String reason) {
                mainQueue.removeCallbacks(timer);
                if (!isActive(run)) return;
                AssistantApproval.Scope live = liveScope(run);
                if (live == null) { finish(run, "Review stopped. Approved effects may already have occurred.", false); return; }
                // Keep effect receipts such as navigation_requested, which do not carry page data.
                if (reason == null && pageBoundResult && !run.originalScope.matches(live)) {
                    reason = "The page changed before its result was accepted. Approved effects may already have occurred.";
                    value = AgentToolBatch.terminal("page_changed", reason);
                }
                try {
                    if (!batch.complete(index, value)) return;
                    if (run.actionTimeout == timer) run.actionTimeout = null;
                    String name = call.name.replace('_', ' ');
                    String status = value.optString("status", "result received").replace('_', ' ');
                    String display = reason == null ? status.substring(0, Math.min(120, status.length())) : reason;
                    addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_TOOL, name + ": " + display));
                    reviewNext(run, batch, index + 1);
                } catch (Exception e) { finish(run, "Could not record the tool outcome.", false); }
            }
        }
        Result result = new Result();
        try {
            AssistantAction action = AssistantAction.parse(call.name, call.args(), false);
            run.actionTimeout = result.timer;
            mainQueue.postDelayed(result.timer, ACTION_TIMEOUT_MS);
            reviews.review(action, AssistantApproval.Source.CHAT, run.originalScope, () -> liveScope(run), result);
        } catch (Exception invalid) {
            result.onStopped("unsupported", invalid instanceof IllegalArgumentException ? invalid.getMessage() : "Invalid action arguments.");
        }
    }

    void reviewManualStep(MainActivity owner, BooleanSupplier showing, AssistantAction action, ToolDispatcher.ExecutionCallback result) {
        if (!agentEnabled.getAsBoolean() || activeRun != null || currentSessionId == null || reviews.owner() != owner || !showing.getAsBoolean()) {
            result.onStopped("unavailable", "Open the Agent and stop the current request before reviewing this step.");
            return;
        }
        AssistantApproval.Scope expected = new AssistantApproval.Scope(currentSessionId, currentRunToken, reviews.page());
        reviews.review(action, AssistantApproval.Source.MANUAL_RECIPE, expected,
                () -> agentEnabled.getAsBoolean() && showing.getAsBoolean() && activeRun == null && reviews.owner() == owner
                        ? new AssistantApproval.Scope(currentSessionId, currentRunToken, reviews.page()) : null, result);
    }

    public void stopActiveSession() {
        boolean hadRequest = activeRun != null;
        invalidateRun();
        notifyThinking(false, null);
        setSessionStateInternal(AiFloatingButtonView.STATE_IDLE);
        if (hadRequest) addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_TOOL,
                "Stopped future actions. Already approved effects may still have occurred."));
    }

    private void finish(Run run, String error, boolean keepContext) {
        if (!isActive(run)) return;
        invalidateRun();
        if (keepContext && sameConnection(run) && run.turns.toString().length() <= MAX_CONTEXT) {
            exchanges.addLast(run.turns);
            int size = 0;
            for (JSONArray group : exchanges) size += group.toString().length();
            while (size > MAX_CONTEXT || exchanges.size() > 12) size -= exchanges.removeFirst().toString().length();
        }
        notifyThinking(false, null);
        setSessionStateInternal(AiFloatingButtonView.STATE_ACTIVE);
        if (error != null) showError(error);
    }

    private static void append(JSONArray to, JSONArray from) { for (int i = 0; i < from.length(); i++) to.put(from.opt(i)); }
    private void showError(String error) { addMessageInternal(new AiChatAdapter.ChatMessage(AiChatAdapter.TYPE_ERROR, error)); }

    private void addMessageInternal(AiChatAdapter.ChatMessage message) {
        activeMessages.add(message);
        transcript.insertMessage(currentSessionId, message);
        if (sessionCallback != null) {
            sessionCallback.onMessageAdded(message);
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

}

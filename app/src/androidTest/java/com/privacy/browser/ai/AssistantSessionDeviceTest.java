package com.privacy.browser.ai;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.browser.MainActivity;
import com.privacy.browser.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Actual sheet, native approval and WebView on a disposable debug profile. No provider sockets or saved keys. */
public class AssistantSessionDeviceTest {
    private static final String URL = "http://127.0.0.1:8011/assistant-turn";
    private static final String FIRST_TEXT = "Fixture first document text";
    private static final String SECOND_TEXT = "Fixture second document text";
    private static final String FIND = "dom_find_elements";

    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();
    private final Context context = device.getTargetContext();
    private final String database = "assistant-turn-fixture-" + UUID.randomUUID() + ".db";
    private final FakeProvider provider = new FakeProvider();
    private MainActivity browser;
    private AgentDbHelper transcript;
    private AgentBottomSheet sheet;
    private AgentSessionManager manager;
    private DeviceQueue queue;
    private HeldReviews reviews;
    private AiConfigManager mode;
    private String previousMode;

    @Before public void launch() throws Exception {
        assertEquals("Use the isolated test package", "com.browser", context.getPackageName());
        assertTrue("Use the disposable debug app", (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        browser = (MainActivity) device.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_LOAD_URL, URL));
        await("No committed assistant fixture page; check tab_trust.py, adb reverse and NoScript", () -> {
            AssistantApproval.Page page = browser.assistantPage();
            return page != null && URL.equals(page.url);
        });
        ui(() -> {
            Field slot = field(MainActivity.class, "agentBottomSheet");
            assertNull("Fixture must not replace a live Agent session", slot.get(browser));
            transcript = new AgentDbHelper(context, database, context.getCacheDir());
            queue = new DeviceQueue();
            reviews = new HeldReviews(new ToolDispatcher(context));
            mode = new AiConfigManager(context);
            previousMode = mode.getMode();
            mode.setMode(AiConfigManager.MODE_STRUCTURED);
            // The mode toggle is real; the instance-local provider never uses persisted credentials.
            manager = new AgentSessionManager(queue, transcript, provider, reviews,
                    () -> GeminiSettings.create("fixture-model", "offline-fixture", AiConfigManager.MODE_STRUCTURED.equals(mode.getMode())),
                    () -> true);
            AiFloatingButtonView button = browser.findViewById(R.id.ai_floating_button);
            sheet = new AgentBottomSheet(browser, button, manager);
            // No production provider override or registered test Activity. Invoke the normal open
            // path so the browser binds the document marker and forwards lifecycle callbacks.
            slot.set(browser, sheet);
            AiFloatingButtonView.OnAiButtonClickListener open = (AiFloatingButtonView.OnAiButtonClickListener)
                    field(AiFloatingButtonView.class, "clickListener").get(button);
            open.onAiButtonClick();
            assertTrue(sheet.isShowing());
            return null;
        });
    }

    @After public void close() throws Exception {
        try {
            if (browser != null) ui(() -> {
                if (sheet != null) {
                    sheet.dispose();
                    field(MainActivity.class, "agentBottomSheet").set(browser, null);
                }
                if (previousMode != null) mode.setMode(previousMode);
                browser.finish();
                return null;
            });
            device.waitForIdleSync();
        } finally {
            if (transcript != null) transcript.close();
            context.deleteDatabase(database);
        }
    }

    @Test public void denyAndApprovedTimeoutStayPairedInOneFollowUp() throws Exception {
        startTurn();
        clickReview(AlertDialog.BUTTON_NEGATIVE);
        await("Second native review did not open", () -> reviews.offered == 2 && showingReview());
        approveAndHold(SECOND_TEXT);
        ui(() -> { queue.expireAction(); return null; });
        assertOutcomes("denied", "timeout");
        ui(() -> { reviews.release(); return null; });
        // A previous provider error and a late WebView result cannot reopen the completed batch.
        ui(() -> { provider.requests.get(0).callback.onError("Old fixture request"); return null; });
        device.waitForIdleSync();
        assertEquals(2, (int) ui(() -> provider.requests.size()));
        assertFalse(ui(this::showingReview));
        assertFalse(ui(() -> provider.requests.get(1).contents.toString().contains(SECOND_TEXT)));
    }

    @Test public void stopButtonRevokesLateResultAndDuplicateProviderReply() throws Exception {
        startTurn();
        approveAndHold(FIRST_TEXT);
        ui(() -> { tap(R.id.btn_ai_sheet_stop); return null; });
        assertFalse(ui(sheet::isShowing));
        assertCancelledBeforeSettlement();
    }

    @Test public void switchingConversationDoesNotSettleIntoTheNewTranscript() throws Exception {
        startTurn();
        approveAndHold(FIRST_TEXT);
        String previous = ui(manager::getCurrentSessionId);
        ui(() -> { tap(R.id.btn_ai_sheet_new_chat); return null; });
        String next = ui(manager::getCurrentSessionId);
        assertNotEquals(previous, next);
        assertCancelledBeforeSettlement();
        assertTrue(transcript.getMessages(next).isEmpty());
        assertEquals(2, transcript.getMessages(previous).size()); // prompt and visible model text, not the held result
    }

    @Test public void changingConnectionDropsTheHeldResult() throws Exception {
        startTurn();
        approveAndHold(FIRST_TEXT);
        ui(() -> { tap(R.id.tab_mode_raw); return null; });
        assertEquals(AiConfigManager.MODE_RAW, ui(mode::getMode));
        assertCancelledBeforeSettlement();
    }

    @Test public void settingsGearOpensCustomPanelAndStopsTheHeldRun() throws Exception {
        startTurn();
        approveAndHold(FIRST_TEXT);
        ui(() -> {
            boolean selectedBefore = mode.isGeminiSelected();
            tap(R.id.btn_ai_sheet_settings);
            Dialog settings = (Dialog) field(AgentBottomSheet.class, "settingsDialog").get(sheet);
            assertNotNull(settings);
            assertTrue(settings.isShowing());
            assertTrue(settings.findViewById(R.id.agent_settings_panel) instanceof ScrollView);
            RadioGroup providers = settings.findViewById(R.id.rg_agent_chat_provider);
            assertEquals(2, providers.getChildCount());
            assertEquals(selectedBefore ? R.id.tab_provider_gemini : R.id.tab_provider_off, providers.getCheckedRadioButtonId());
            providers.check(R.id.tab_provider_gemini);
            assertEquals(View.VISIBLE, settings.findViewById(R.id.layout_gemini_config).getVisibility());
            assertEquals(selectedBefore, mode.isGeminiSelected());
            EditText model = settings.findViewById(R.id.et_gemini_model);
            model.setText("models/invalid-for-fixture");
            assertTrue(settings.findViewById(R.id.btn_agent_settings_save).performClick());
            assertTrue(settings.isShowing());
            assertNotNull(model.getError());
            assertEquals(selectedBefore, mode.isGeminiSelected());
            providers.check(R.id.tab_provider_off);
            assertEquals(View.GONE, settings.findViewById(R.id.layout_gemini_config).getVisibility());
            assertTrue(settings.findViewById(R.id.btn_agent_settings_cancel).performClick());
            assertFalse(settings.isShowing());
            assertEquals(selectedBefore, mode.isGeminiSelected());
            return null;
        });
        assertCancelledBeforeSettlement();
    }

    @Test public void realActivityPauseStopsTheRunEvenIfTheSheetWasStillVisible() throws Exception {
        startTurn();
        approveAndHold(FIRST_TEXT);
        assertTrue(ui(() -> browser.moveTaskToBack(true)));
        await("Browser did not reach onPause", () -> browser.assistantPage() == null && provider.requests.get(0).canceled);
        assertCancelledBeforeSettlement();
    }

    @Test public void sameUrlReloadDropsOldDocumentTextBeforeTheProviderFollowUp() throws Exception {
        startTurn();
        approveAndHold(FIRST_TEXT);
        AssistantApproval.Page old = ui(browser::assistantPage);
        assertTrue(ui(() -> browser.reloadApprovedAssistantPage(old)));
        await("Reload did not commit a new page at the same address", () -> {
            AssistantApproval.Page page = browser.assistantPage();
            return page != null && URL.equals(page.url) && !old.matches(page);
        });
        ui(() -> { reviews.release(); return null; });
        assertOutcomes("page_changed", "stale");
        assertFalse(ui(() -> provider.requests.get(1).contents.toString().contains(FIRST_TEXT)));
        assertFalse(ui(this::showingReview));
    }

    private void startTurn() throws Exception {
        ui(() -> {
            EditText prompt = pane().findViewById(R.id.et_ai_prompt);
            prompt.setText("Check two synthetic elements");
            tap(R.id.btn_ai_send);
            assertEquals(1, provider.requests.size());
            assertTrue(provider.requests.get(0).selection.tools);
            assertEquals(1, provider.requests.get(0).contents.length());
            provider.requests.get(0).toolTurn();
            return null;
        });
        await("First native review did not open", () -> reviews.offered == 1 && showingReview());
    }

    private void approveAndHold(String expected) throws Exception {
        clickReview(AlertDialog.BUTTON_POSITIVE);
        await("No real WebView result; check the loopback fixture and that page scripts are allowed", () -> reviews.held != null);
        assertEquals(expected, ui(() -> reviews.held.getJSONArray("items").getJSONObject(0).getString("text")));
        assertEquals(1, (int) ui(() -> provider.requests.size()));
        assertFalse(ui(this::showingReview));
    }

    private void assertCancelledBeforeSettlement() throws Exception {
        ui(() -> {
            assertTrue(provider.requests.get(0).canceled);
            assertNull(queue.deadline);
            reviews.release();
            provider.requests.get(0).toolTurn(); // A transport may deliver after cancellation.
            return null;
        });
        device.waitForIdleSync();
        assertEquals(1, (int) ui(() -> reviews.offered));
        assertEquals(1, (int) ui(() -> provider.requests.size()));
        assertFalse(ui(this::showingReview));
        for (AiChatAdapter.ChatMessage row : ui(manager::getActiveMessages)) {
            assertFalse(row.content != null && row.content.contains(FIRST_TEXT));
        }
    }

    private void assertOutcomes(String first, String second) throws Exception {
        await("Expected exactly one paired provider follow-up", () -> provider.requests.size() >= 2);
        ui(() -> {
            assertEquals(2, provider.requests.size());
            JSONArray contents = provider.requests.get(1).contents;
            assertEquals(3, contents.length());
            JSONObject model = contents.getJSONObject(1);
            assertEquals("fixture-signature", model.getJSONArray("parts").getJSONObject(1).getString("thoughtSignature"));
            JSONArray replies = contents.getJSONObject(2).getJSONArray("parts");
            assertEquals(2, replies.length());
            assertReply(replies, 0, "first", first);
            assertReply(replies, 1, "second", second);
            return null;
        });
    }

    private static void assertReply(JSONArray replies, int index, String id, String status) throws Exception {
        JSONObject reply = replies.getJSONObject(index).getJSONObject("functionResponse");
        assertEquals(id, reply.getString("id"));
        assertEquals(FIND, reply.getString("name"));
        assertEquals(status, reply.getJSONObject("response").getString("status"));
    }

    private void clickReview(int button) throws Exception {
        ui(() -> {
            AlertDialog open = reviewDialog();
            assertNotNull(open);
            assertTrue(open.isShowing());
            assertTrue(open.getButton(button).performClick());
            return null;
        });
    }

    private boolean showingReview() throws Exception {
        AlertDialog review = reviewDialog();
        return review != null && review.isShowing();
    }

    private Dialog pane() throws Exception { return (Dialog) field(AgentBottomSheet.class, "dialog").get(sheet); }
    private AlertDialog reviewDialog() throws Exception { return (AlertDialog) field(ToolDispatcher.class, "review").get(reviews.nativeUi); }
    private void tap(int id) throws Exception {
        View button = pane().findViewById(id);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertTrue(button.performClick());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        device.runOnMainSync(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private void await(String message, Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            if (ui(condition)) return;
            SystemClock.sleep(35);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message);
    }

    private static final class DeviceQueue implements AgentSessionManager.MainQueue {
        final Handler main = new Handler(Looper.getMainLooper());
        Runnable deadline;
        @Override public void post(Runnable task) { main.post(task); }
        @Override public void postDelayed(Runnable task, long delayMs) {
            assertNull(deadline);
            deadline = task; // Exercise execution timeout without sleeping for the production deadline.
        }
        @Override public void removeCallbacks(Runnable task) { if (deadline == task) deadline = null; }
        void expireAction() { assertNotNull(deadline); deadline.run(); }
    }

    private static final class HeldReviews implements AgentSessionManager.Reviews {
        final ToolDispatcher nativeUi;
        int offered;
        JSONObject held;
        ToolDispatcher.ExecutionCallback deliver;
        HeldReviews(ToolDispatcher nativeUi) { this.nativeUi = nativeUi; }
        @Override public MainActivity owner() { return nativeUi.owner(); }
        @Override public void attach(MainActivity owner) { nativeUi.attach(owner); }
        @Override public void detach(MainActivity owner) { nativeUi.detach(owner); }
        @Override public AssistantApproval.Page page() { return nativeUi.page(); }
        @Override public void cancelReview() { nativeUi.cancelReview(); }
        @Override public void review(AssistantAction action, AssistantApproval.Source source, AssistantApproval.Scope expected,
                                     Supplier<AssistantApproval.Scope> live, ToolDispatcher.ExecutionCallback result) {
            offered++;
            nativeUi.review(action, source, expected, live, new ToolDispatcher.ExecutionCallback() {
                @Override public void onCompleted(JSONObject value) { held = value; deliver = result; }
                @Override public void onStopped(String status, String reason) { result.onStopped(status, reason); }
            });
        }
        void release() {
            assertNotNull(deliver);
            ToolDispatcher.ExecutionCallback callback = deliver;
            JSONObject result = held;
            deliver = null;
            held = null;
            callback.onCompleted(result);
        }
    }

    private static final class FakeProvider implements AgentSessionManager.Provider {
        final List<Request> requests = new ArrayList<>();
        @Override public void generateContent(GeminiSettings selection, JSONArray contents, ResponseCallback callback) {
            requests.add(new Request(selection, contents, callback));
        }
        @Override public void cancelCurrentRequest() { if (!requests.isEmpty()) requests.get(requests.size() - 1).canceled = true; }
    }

    private static final class Request {
        final GeminiSettings selection;
        final JSONArray contents;
        final AgentSessionManager.Provider.ResponseCallback callback;
        boolean canceled;
        Request(GeminiSettings selection, JSONArray contents, AgentSessionManager.Provider.ResponseCallback callback) {
            this.selection = selection;
            this.contents = contents;
            this.callback = callback;
        }
        void toolTurn() throws Exception {
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", "Fixture review"))
                    .put(action("first").put("thoughtSignature", "fixture-signature")).put(action("second"));
            JSONObject content = new JSONObject().put("role", "model").put("parts", parts);
            String response = new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("content", content))).toString();
            callback.onSuccess(GeminiProtocol.parse(response, selection.tools));
        }
        private static JSONObject action(String id) throws Exception {
            JSONObject args = new JSONObject().put("css_selector", "#fixture-" + id);
            return new JSONObject().put("functionCall", new JSONObject().put("name", FIND).put("id", id).put("args", args));
        }
    }
}

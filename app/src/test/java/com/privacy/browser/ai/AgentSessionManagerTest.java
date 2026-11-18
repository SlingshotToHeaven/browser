package com.privacy.browser.ai;

import com.privacy.browser.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.privacy.browser.ai.AiChatAdapter.TYPE_ASSISTANT;
import static com.privacy.browser.ai.AiChatAdapter.TYPE_ERROR;
import static com.privacy.browser.ai.AiChatAdapter.TYPE_TOOL;
import static com.privacy.browser.ai.AiChatAdapter.TYPE_USER;
import static org.junit.Assert.*;

/** Full session pipeline, with no Android runtime, credentials, or sockets. */
public class AgentSessionManagerTest {
    // Same function name, distinct IDs and reviewed arguments. Preserve the signed model part.
    private static final String TOOL = "dom_find_elements";
    private static final String TOOL_TURN = "{\"role\":\"model\",\"parts\":[{\"text\":\"Two lookups\"},"
            + "{\"functionCall\":{\"name\":\"" + TOOL + "\",\"id\":\"first\",\"args\":{\"css_selector\":\".first\"}},\"thoughtSignature\":\"fixture\"},"
            + "{\"functionCall\":{\"name\":\"" + TOOL + "\",\"id\":\"second\",\"args\":{\"css_selector\":\".second\"}}}]}";
    private static final String ANSWER = "{\"role\":\"model\",\"parts\":[{\"text\":\"Fixture answer\"}]}";
    private static final String PROMPT = "Find two fixture elements";
    private static final long TOOL_TIMEOUT_MS = 80_000L;

    @Test public void stopDropsQueuedAndLateResultsWithoutDispatchingTheSecondAction() throws Exception {
        Fixture f = new Fixture();
        Review first = f.startToolTurn();
        first.approve();
        first.callback.onCompleted(result("queued_callback")); // Posted but not settled on the main queue.
        String session = f.manager.getCurrentSessionId();
        f.manager.stopActiveSession();
        assertTrue(f.provider.requests.get(0).canceled);
        assertFalse(f.screen.thinking);
        assertEquals(AiFloatingButtonView.STATE_IDLE, f.manager.getSessionState());

        first.callback.onCompleted(result("late_callback")); // A port may deliver even after cancellation.
        f.queue.advance(TOOL_TIMEOUT_MS * 2);
        assertEquals(1, f.reviews.attempts);
        assertEquals(1, f.reviews.approved);
        assertEquals(1, f.provider.requests.size());
        assertEquals(3, f.store.getMessages(session).size());
        assertEquals(f.store.getMessages(session), f.screen.messages);
        assertEquals(List.of("Stopped future actions. Already approved effects may still have occurred."), f.messagesOfType(TYPE_TOOL));

        f.manager.sendUserPrompt("Fresh after Stop", null);
        assertPromptOnly(f.provider.requests.get(1), "Fresh after Stop");
    }

    @Test public void switchingAwayAndBackDoesNotReviveAnOldRunOrChangeEitherTranscript() throws Exception {
        Fixture f = new Fixture();
        String other = f.manager.getCurrentSessionId();
        f.manager.startNewSession();
        String original = f.manager.getCurrentSessionId();
        Review first = f.startToolTurn();
        first.approve();

        f.manager.loadSession(other);
        first.callback.onCompleted(result("old_result"));
        f.queue.drain();
        assertEquals(other, f.manager.getCurrentSessionId());
        assertTrue(f.screen.messages.isEmpty());
        assertTrue(f.store.getMessages(other).isEmpty());
        assertEquals(2, f.store.getMessages(original).size());
        assertEquals(1, f.provider.requests.size());

        f.manager.loadSession(original);
        f.manager.sendUserPrompt("New run in original session", null);
        first.callback.onStopped("error", "Stale tool failure");
        f.provider.requests.get(0).callback.onError("Stale provider failure");
        f.queue.advance(TOOL_TIMEOUT_MS * 2);
        assertEquals(1, f.reviews.attempts);
        assertEquals(1, f.reviews.approved);
        assertEquals(2, f.provider.requests.size());
        assertPromptOnly(f.provider.requests.get(1), "New run in original session");
        assertEquals(List.of(PROMPT, "New run in original session"), f.messagesOfType(TYPE_USER));
        assertTrue(f.messagesOfType(TYPE_TOOL).isEmpty());
        assertTrue(f.messagesOfType(TYPE_ERROR).isEmpty());
        f.finishReply(1);
        assertEquals(List.of("Two lookups", "Fixture answer"), f.messagesOfType(TYPE_ASSISTANT));
    }

    @Test public void denialAndExecutionTimeoutHaveOneOutcomeEachAndOneFollowUp() throws Exception {
        Fixture f = new Fixture();
        Review first = f.startToolTurn();
        first.deny();
        f.queue.drain();
        Review second = f.secondReview();
        second.approve(); // Dispatch succeeds but the fake action withholds its result.
        f.queue.advance(TOOL_TIMEOUT_MS - 1);
        assertEquals(1, f.provider.requests.size());
        assertEquals(1, f.messagesOfType(TYPE_TOOL).size());

        f.queue.advance(1);
        JSONArray replies = f.followUp();
        assertOutcome(replies, 0, "first", "denied");
        assertOutcome(replies, 1, "second", "timeout");
        second.callback.onCompleted(result("too_late"));
        first.callback.onStopped("error", "Duplicate denial callback");
        f.queue.advance(TOOL_TIMEOUT_MS);
        assertEquals(2, f.provider.requests.size());
        assertEquals(2, f.reviews.attempts);
        assertEquals(1, f.reviews.approved);
        assertEquals(2, f.messagesOfType(TYPE_TOOL).size());
        assertTrue(f.messagesOfType(TYPE_TOOL).get(1).contains("may already have taken effect"));

        f.finishReply(1);
        f.manager.sendUserPrompt("Another prompt", null);
        JSONArray retained = f.provider.requests.get(2).contents;
        assertEquals(5, retained.length());
        assertEquals(f.provider.requests.get(1).contents.getJSONObject(1).toString(), retained.getJSONObject(1).toString());
        assertEquals(f.provider.requests.get(1).contents.getJSONObject(2).toString(), retained.getJSONObject(2).toString());
        assertEquals("Another prompt", retained.getJSONObject(4).getJSONArray("parts").getJSONObject(0).getString("text"));
    }

    @Test public void toolErrorAndSuccessStayPairedDespiteAnOutOfOrderDuplicate() throws Exception {
        Fixture f = new Fixture();
        Review first = f.startToolTurn();
        first.approve();
        first.callback.onStopped("error", "Fixture action failed");
        f.queue.drain();
        Review second = f.secondReview();
        second.approve();
        second.callback.onCompleted(result("second_ok").put("items", new JSONArray().put("fixture element")));
        first.callback.onCompleted(result("duplicate_first"));
        f.queue.drain();

        JSONArray replies = f.followUp();
        assertOutcome(replies, 0, "first", "error");
        JSONObject success = assertOutcome(replies, 1, "second", "second_ok");
        assertEquals("fixture element", success.getJSONArray("items").getString(0));
        assertEquals(2, f.reviews.approved);
        assertEquals(2, f.messagesOfType(TYPE_TOOL).size());
        assertTrue(f.queue.events.isEmpty());
        f.finishReply(1);
    }

    @Test public void pauseDropsAProviderReplyAlreadyPostedToTheMainQueue() throws Exception {
        Fixture f = new Fixture();
        f.manager.sendUserPrompt(PROMPT, null);
        Request request = f.provider.requests.get(0);
        request.reply(TOOL_TURN);
        f.manager.pauseActivity(null); // The host fixture has no Activity instance.
        f.queue.drain();
        assertTrue(request.canceled);
        assertEquals(0, f.reviews.attempts);
        assertEquals(1, f.provider.requests.size());
        assertTrue(f.messagesOfType(TYPE_ASSISTANT).isEmpty());
        assertFalse(f.screen.thinking);
    }

    @Test public void changingSettingsWhileAResultIsPendingStopsTheOldRun() throws Exception {
        Fixture f = new Fixture();
        Review first = f.startToolTurn();
        first.approve();
        f.selection = GeminiSettings.create("text-only-fixture", "synthetic-key", false);
        f.manager.settingsChanged();
        first.callback.onCompleted(result("old_setting_result"));
        f.queue.advance(TOOL_TIMEOUT_MS * 2);
        assertEquals(1, f.provider.requests.size());
        assertEquals(1, f.reviews.attempts);
        assertEquals(1, f.messagesOfType(TYPE_TOOL).size()); // Stop notice only.

        f.manager.sendUserPrompt("Prompt in text-only mode", null);
        Request next = f.provider.requests.get(1);
        assertFalse(next.payload.has("tools"));
        assertPromptOnly(next, "Prompt in text-only mode");
        f.finishReply(1);
    }

    @Test public void aConnectionChangeBeforeSettlementDoesNotAcceptTheQueuedResult() throws Exception {
        Fixture f = new Fixture();
        Review first = f.startToolTurn();
        first.approve();
        first.callback.onCompleted(result("old_recipient_result"));
        f.selection = null; // Saved settings changed before the UI has notified the session manager.
        f.queue.drain();
        assertEquals(1, f.provider.requests.size());
        assertEquals(1, f.reviews.attempts);
        assertTrue(f.messagesOfType(TYPE_TOOL).isEmpty());
        assertEquals(List.of("Review stopped. Approved effects may already have occurred."), f.messagesOfType(TYPE_ERROR));
        assertFalse(f.screen.thinking);
    }

    @Test public void duplicateProviderRepliesAndPreviousErrorsDoNotReopenOrEndAnotherBatch() throws Exception {
        Fixture f = new Fixture();
        Review first = f.startToolTurn();
        Request old = f.provider.requests.get(0);
        old.reply(TOOL_TURN);
        old.callback.onError("Duplicate failure while a review is open");
        f.queue.drain();
        assertEquals(1, f.reviews.offered.size());
        assertEquals(List.of("Two lookups"), f.messagesOfType(TYPE_ASSISTANT));
        assertTrue(f.messagesOfType(TYPE_ERROR).isEmpty());

        first.approve();
        first.callback.onCompleted(result("first_ok"));
        f.queue.drain();
        Review second = f.secondReview();
        second.approve();
        second.callback.onCompleted(result("second_ok"));
        f.queue.drain();
        JSONArray parts = f.followUp();
        assertOutcome(parts, 0, "first", "first_ok");
        assertOutcome(parts, 1, "second", "second_ok");

        old.reply(TOOL_TURN);
        old.callback.onError("Previous failure while the follow-up is pending");
        f.queue.drain();
        assertEquals(2, f.reviews.offered.size());
        assertEquals(2, f.provider.requests.size());
        assertTrue(f.messagesOfType(TYPE_ERROR).isEmpty());
        assertTrue(f.screen.thinking);
        f.finishReply(1);
        assertEquals(List.of("Two lookups", "Fixture answer"), f.messagesOfType(TYPE_ASSISTANT));
    }

    @Test public void reopeningTranscriptDoesNotSendPriorTextOrAttachmentReferences() throws Exception {
        Fixture f = new Fixture();
        String session = f.manager.getCurrentSessionId();
        f.store.updateSessionTitle(session, "Legacy title");
        f.store.insertMessage(session, new AiChatAdapter.ChatMessage(TYPE_USER, "Previous transcript line", null, false,
                "/fixture/agent_images/img_12345678-1234-1234-1234-000000000001.webp"));
        f.manager.loadSession(session);
        assertEquals("Legacy title", f.manager.getCurrentTitle());
        assertEquals(List.of("Previous transcript line"), f.messagesOfType(TYPE_USER));
        assertTrue(f.provider.requests.isEmpty());
        f.manager.sendUserPrompt("New approved prompt", null);
        assertPromptOnly(f.provider.requests.get(0), "New approved prompt");
    }

    @Test public void providerFailureDoesNotReplayTheUnfinishedPrompt() throws Exception {
        Fixture f = new Fixture();
        f.manager.sendUserPrompt(PROMPT, null);
        f.provider.requests.get(0).callback.onError("Fixture provider error");
        f.queue.drain();
        assertEquals(List.of("Fixture provider error"), f.messagesOfType(TYPE_ERROR));
        assertFalse(f.screen.thinking);
        assertEquals(0, f.reviews.attempts);
        f.manager.sendUserPrompt("Try again", null);
        assertPromptOnly(f.provider.requests.get(1), "Try again");
    }

    @Test public void sameAddressReloadDropsAQueuedPageResultBeforeFollowUp() throws Exception {
        Fixture f = new Fixture();
        Review first = f.startToolTurn();
        first.approve();
        first.callback.onCompleted(result("success").put("items", new JSONArray().put("old document text")));
        f.reviews.page = f.reviews.page("replacement-document"); // Same tab and URL, different document.
        f.manager.pageChanged(null);
        f.queue.drain();

        JSONArray replies = f.followUp();
        assertFalse(replies.toString().contains("old document text"));
        assertOutcome(replies, 0, "first", "page_changed");
        assertOutcome(replies, 1, "second", "stale");
        assertEquals(1, f.reviews.offered.size());
        assertEquals(1, f.reviews.approved);
    }

    @Test public void approvedReloadCanAcknowledgeItsRequestWithoutReusingTheOldDocument() throws Exception {
        Fixture f = new Fixture();
        JSONObject model = new JSONObject(TOOL_TURN);
        model.getJSONArray("parts").getJSONObject(1).getJSONObject("functionCall")
                .put("name", "nav_reload_page").put("args", new JSONObject());
        f.manager.sendUserPrompt("Reload and look up a fixture element", null);
        f.provider.requests.get(0).reply(model.toString());
        f.queue.drain();
        Review first = f.reviews.offered.get(0);
        assertEquals(AssistantAction.Kind.RELOAD, first.action.kind);
        first.approve();
        f.reviews.page = null; // As soon as a reload is requested there is no committed document to reuse.
        f.manager.pageChanged(null);
        first.callback.onCompleted(result("reload_requested"));
        f.queue.drain();

        JSONArray replies = f.followUp(model.toString());
        assertOutcome(replies, 0, "nav_reload_page", "first", "reload_requested");
        assertOutcome(replies, 1, "second", "stale");
        assertEquals(1, f.reviews.offered.size());
    }

    private static JSONObject result(String status) throws Exception { return new JSONObject().put("status", status); }

    private static JSONObject assertOutcome(JSONArray parts, int index, String id, String status) throws Exception {
        return assertOutcome(parts, index, TOOL, id, status);
    }

    private static JSONObject assertOutcome(JSONArray parts, int index, String name, String id, String status) throws Exception {
        JSONObject reply = parts.getJSONObject(index).getJSONObject("functionResponse");
        assertEquals(name, reply.getString("name"));
        assertEquals(id, reply.getString("id"));
        JSONObject outcome = reply.getJSONObject("response");
        assertEquals(status, outcome.getString("status"));
        return outcome;
    }

    private static void assertPromptOnly(Request request, String prompt) throws Exception {
        assertEquals(1, request.contents.length());
        JSONObject turn = request.contents.getJSONObject(0);
        assertEquals("user", turn.getString("role"));
        JSONArray parts = turn.getJSONArray("parts");
        assertEquals(1, parts.length());
        assertEquals(prompt, parts.getJSONObject(0).getString("text"));
    }

    private static final class Fixture {
        final ManualQueue queue = new ManualQueue();
        final FakeProvider provider = new FakeProvider();
        final FakeReviews reviews = new FakeReviews(queue);
        final MemoryTranscript store = new MemoryTranscript();
        final Screen screen = new Screen();
        GeminiSettings selection = GeminiSettings.create("structured-fixture", "synthetic-key", true);
        final AgentSessionManager manager = new AgentSessionManager(queue, store, provider, reviews, () -> selection, () -> true);

        Fixture() { manager.openSheet(null, screen, null); }

        Review startToolTurn() throws Exception {
            manager.sendUserPrompt(PROMPT, null);
            assertEquals(AiFloatingButtonView.STATE_PROCESSING, manager.getSessionState());
            assertTrue(screen.thinking);
            assertEquals(1, provider.requests.size());
            assertPromptOnly(provider.requests.get(0), PROMPT);
            provider.requests.get(0).reply(TOOL_TURN);
            queue.drain();
            assertEquals(1, provider.requests.size());
            assertEquals(1, reviews.attempts);
            assertEquals(1, reviews.offered.size());
            assertEquals(0, reviews.approved);
            Review first = reviews.offered.get(0);
            assertEquals(".first", first.action.args().getString("css_selector"));
            return first;
        }

        Review secondReview() throws Exception {
            assertEquals(1, provider.requests.size());
            assertEquals(2, reviews.offered.size());
            Review second = reviews.offered.get(1);
            assertEquals(".second", second.action.args().getString("css_selector"));
            return second;
        }

        JSONArray followUp() throws Exception { return followUp(TOOL_TURN); }

        JSONArray followUp(String model) throws Exception {
            assertEquals(2, provider.requests.size());
            JSONArray contents = provider.requests.get(1).contents;
            assertEquals(3, contents.length());
            assertEquals(provider.requests.get(0).contents.getJSONObject(0).toString(), contents.getJSONObject(0).toString());
            assertEquals(new JSONObject(model).toString(), contents.getJSONObject(1).toString());
            JSONObject returned = contents.getJSONObject(2);
            assertEquals("user", returned.getString("role"));
            JSONArray parts = returned.getJSONArray("parts");
            assertEquals(2, parts.length());
            return parts;
        }

        void finishReply(int request) throws Exception {
            provider.requests.get(request).reply(ANSWER);
            queue.drain();
            assertFalse(screen.thinking);
            assertEquals(AiFloatingButtonView.STATE_ACTIVE, manager.getSessionState());
        }

        List<String> messagesOfType(int type) {
            List<String> found = new ArrayList<>();
            for (AiChatAdapter.ChatMessage message : manager.getActiveMessages()) if (message.type == type) found.add(message.content);
            return found;
        }
    }

    private static final class ManualQueue implements AgentSessionManager.MainQueue {
        private static final class Event {
            final long due;
            final Runnable task;
            Event(long due, Runnable task) { this.due = due; this.task = task; }
        }
        final List<Event> events = new ArrayList<>();
        long now;

        @Override public void post(Runnable task) { postDelayed(task, 0); }
        @Override public void postDelayed(Runnable task, long delayMs) { events.add(new Event(now + delayMs, task)); }
        @Override public void removeCallbacks(Runnable task) { events.removeIf(event -> event.task == task); }

        void drain() { advance(0); }
        void advance(long ms) {
            long end = now + ms;
            // FIFO among events due at the same time, as with the main message queue.
            for (int step = 0; step < 100; step++) {
                Event next = null;
                for (Event event : events) if (event.due <= end && (next == null || event.due < next.due)) next = event;
                if (next == null) { now = end; return; }
                events.remove(next);
                now = next.due;
                next.task.run();
            }
            fail("Unexpected loop in session callbacks");
        }
    }

    private static final class Request {
        final GeminiSettings selection;
        final JSONArray contents;
        final JSONObject payload;
        final AgentSessionManager.Provider.ResponseCallback callback;
        boolean canceled;

        Request(GeminiSettings selection, JSONArray contents, AgentSessionManager.Provider.ResponseCallback callback) throws Exception {
            this.selection = selection;
            this.payload = new JSONObject(GeminiProtocol.payload(contents, new JSONArray(), selection.tools).toString());
            this.contents = payload.getJSONArray("contents");
            this.callback = callback;
        }

        void reply(String turn) throws Exception {
            callback.onSuccess(GeminiProtocol.parse("{\"candidates\":[{\"content\":" + turn + "}]}", selection.tools));
        }
    }

    private static final class FakeProvider implements AgentSessionManager.Provider {
        final List<Request> requests = new ArrayList<>();
        Request current;

        @Override public void generateContent(GeminiSettings selection, JSONArray contents, ResponseCallback callback) {
            try { current = new Request(selection, contents, callback); }
            catch (Exception invalid) { throw new AssertionError(invalid); }
            requests.add(current);
        }
        @Override public void cancelCurrentRequest() {
            if (current != null) current.canceled = true;
            current = null;
        }
    }

    private static final class FakeReviews implements AgentSessionManager.Reviews {
        final ManualQueue clock;
        final Object browserIdentity = new Object();
        final List<Review> offered = new ArrayList<>();
        AssistantApproval.Page page = page("initial-document");
        Review pending;
        int attempts, approved;
        MainActivity owner;

        FakeReviews(ManualQueue clock) { this.clock = clock; }
        AssistantApproval.Page page(String document) {
            return new AssistantApproval.Page(browserIdentity, "fixture-tab", document, 1, "https://fixture.invalid/");
        }

        @Override public MainActivity owner() { return owner; }
        @Override public void attach(MainActivity owner) { this.owner = owner; }
        @Override public void detach(MainActivity owner) { this.owner = null; }
        @Override public AssistantApproval.Page page() { return page; }
        @Override public void cancelReview() {
            if (pending != null) pending.reject("stale", "Review canceled");
        }

        @Override public void review(AssistantAction action, AssistantApproval.Source source, AssistantApproval.Scope expected,
                                     Supplier<AssistantApproval.Scope> live, ToolDispatcher.ExecutionCallback callback) {
            attempts++;
            assertNull("Reviews must be serial", pending);
            try {
                AssistantApproval approval = AssistantApproval.request(action, source, expected, live.get(), clock.now);
                pending = new Review(this, action, approval, live, callback);
                offered.add(pending);
            } catch (IllegalArgumentException stale) { callback.onStopped("stale", stale.getMessage()); }
        }
    }

    private static final class Review {
        final FakeReviews host;
        final AssistantAction action;
        final AssistantApproval approval;
        final Supplier<AssistantApproval.Scope> live;
        final ToolDispatcher.ExecutionCallback callback;

        Review(FakeReviews host, AssistantAction action, AssistantApproval approval, Supplier<AssistantApproval.Scope> live,
               ToolDispatcher.ExecutionCallback callback) {
            this.host = host;
            this.action = action;
            this.approval = approval;
            this.live = live;
            this.callback = callback;
        }
        void approve() {
            assertSame(this, host.pending);
            assertSame(action, approval.consume(live.get(), host.clock.now));
            host.pending = null;
            host.approved++;
        }
        void deny() { reject("denied", "Fixture user denied the action"); }
        void reject(String status, String message) {
            assertSame(this, host.pending);
            approval.deny();
            host.pending = null;
            callback.onStopped(status, message);
        }
    }

    private static final class MemoryTranscript implements AgentSessionManager.Transcript {
        private final Map<String, List<AiChatAdapter.ChatMessage>> messages = new LinkedHashMap<>();
        private final Map<String, String> titles = new LinkedHashMap<>();
        private String active;

        @Override public String getActiveSessionId() { return active; }
        @Override public void createSession(String id, String title) {
            assertFalse(messages.containsKey(id));
            messages.put(id, new ArrayList<>());
            titles.put(id, title);
            active = id;
        }
        @Override public void setActiveSession(String id) {
            assertTrue(messages.containsKey(id));
            active = id;
        }
        @Override public List<AiChatAdapter.ChatMessage> getMessages(String id) { return new ArrayList<>(messages.get(id)); }
        @Override public String getSessionTitle(String id) { return titles.get(id); }
        @Override public void updateSessionTitle(String id, String title) { titles.put(id, title); }
        @Override public void deleteSession(String id) {
            titles.remove(id);
            messages.remove(id);
            if (id.equals(active)) active = null;
        }
        @Override public void insertMessage(String id, AiChatAdapter.ChatMessage message) { messages.get(id).add(message); }
    }

    private static final class Screen implements AgentSessionManager.SessionCallback {
        final List<AiChatAdapter.ChatMessage> messages = new ArrayList<>();
        boolean thinking;

        @Override public boolean canReview() { return true; }
        @Override public void onMessageAdded(AiChatAdapter.ChatMessage message) { messages.add(message); }
        @Override public void onThinkingState(boolean thinking, String text) { this.thinking = thinking; }
        @Override public void onSessionLoaded(String id, String title, List<AiChatAdapter.ChatMessage> loaded) {
            messages.clear();
            messages.addAll(loaded);
        }
        @Override public void onSessionStateChanged(int state) { }
    }
}

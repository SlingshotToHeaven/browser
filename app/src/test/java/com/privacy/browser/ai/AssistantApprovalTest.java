package com.privacy.browser.ai;

import org.json.JSONObject;
import org.junit.Test;

import static com.privacy.browser.ai.AssistantApproval.Source.*;
import static org.junit.Assert.*;

public class AssistantApprovalTest {
    private final Object activity = new Object();
    private final AssistantApproval.Scope scope = scope(activity, "session", 4, "tab", "doc", 6);

    @Test public void authorizesOnlyTheNormalizedImmutableActionOnce() throws Exception {
        JSONObject original = new JSONObject().put("url", " https://example.invalid/a?key=Case ");
        AssistantAction action = AssistantAction.parse("nav_open_url", original, false);
        AssistantApproval approval = AssistantApproval.request(action, CHAT, scope, scope, 100);
        original.put("url", "https://other.invalid/").put("in_new_tab", true);
        action.args().put("url", "https://another.invalid/");
        AssistantAction accepted = approval.consume(scope, 101);
        assertEquals("https://example.invalid/a?key=Case", accepted.args().getString("url"));
        assertFalse(accepted.args().getBoolean("in_new_tab"));
        assertTrue(accepted.reviewText().contains("in_new_tab"));
        assertNull(approval.consume(scope, 102));
    }

    @Test public void anyScopeChangeIncludingSameUrlReloadInvalidatesAnApproval() throws Exception {
        AssistantAction action = AssistantAction.parse("nav_reload_page", new JSONObject(), false);
        AssistantApproval.Scope[] changes = {scope(activity, "another", 4, "tab", "doc", 6),
                scope(activity, "session", 5, "tab", "doc", 6), scope(activity, "session", 4, "otherTab", "doc", 6),
                scope(activity, "session", 4, "tab", "sameUrlNewDoc", 6), scope(activity, "session", 4, "tab", "doc", 7),
                scope(new Object(), "session", 4, "tab", "doc", 6),
                new AssistantApproval.Scope("session", 4, new AssistantApproval.Page(activity, "tab", "doc", 6, "https://example.invalid/b")), null};
        for (AssistantApproval.Scope changed : changes) {
            AssistantApproval approval = AssistantApproval.request(action, CHAT, scope, scope, 100);
            assertNull(approval.consume(changed, 101));
            assertNull(approval.consume(scope, 102));
            assertThrows(IllegalArgumentException.class, () -> AssistantApproval.request(action, CHAT, scope, changed, 100));
        }
    }

    @Test public void deniesExpiredCancelledExternalOrAlarmRequests() throws Exception {
        AssistantAction action = AssistantAction.parse("nav_reload_page", new JSONObject(), false);
        assertThrows(IllegalArgumentException.class, () -> AssistantApproval.request(action, EXTERNAL_INTENT, scope, scope, 100));
        assertThrows(IllegalArgumentException.class, () -> AssistantApproval.request(action, ALARM, scope, scope, 100));
        AssistantApproval stale = AssistantApproval.request(action, CHAT, scope, scope, 100);
        assertNull(stale.consume(scope, 100 + AssistantApproval.LIFETIME_MS));
        AssistantApproval manual = AssistantApproval.request(action, MANUAL_RECIPE, scope, scope, 100);
        manual.deny();
        assertNull(manual.consume(scope, 101));
    }

    @Test public void actionSchemaRejectsAmbiguousOrUnsafeDestinations() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AssistantAction.parse("nav_open_url", new JSONObject().put("url", "file:///some/path"), false));
        assertThrows(IllegalArgumentException.class, () -> AssistantAction.parse("nav_open_url", new JSONObject().put("url", "https://home.privacy-browser.invalid/"), false));
        assertThrows(IllegalArgumentException.class, () -> AssistantAction.parse("nav_open_url", new JSONObject().put("url", "https://example.invalid/").put("in_new_tab", "true"), false));
        assertThrows(IllegalArgumentException.class, () -> AssistantAction.parse("privacy_clear_site_data", new JSONObject().put("domain", "example.invalid").put("clear_cookies", true), false));
        assertThrows(IllegalArgumentException.class, () -> AssistantAction.parse("privacy_clear_site_data", new JSONObject().put("domain", "*"), false));
        assertThrows(IllegalArgumentException.class, () -> AssistantAction.parse("termux_execute_command", new JSONObject(), false));
        assertThrows(IllegalArgumentException.class, () -> AssistantAction.parse("adblock_test_rule", new JSONObject(), false));
    }

    @Test public void recipesOnlyReturnParsedStepsAndCannotScheduleOrInvokeUnapprovedTools() throws Exception {
        RecipePlan plan = RecipePlan.parse("[{\"action\":\"nav_open_url\",\"params\":{\"url\":\"https://example.invalid/\"}},"
                + "{\"action\":\"dom_find_elements\",\"params\":{\"css_selector\":\".headline\"}}]");
        assertEquals(2, plan.steps.size());
        assertEquals(plan.json, RecipePlan.parse(plan.json).json);
        assertThrows(IllegalArgumentException.class, () -> RecipePlan.parse("[{\"action\":\"automation_schedule_task\",\"params\":{}}]"));
        assertThrows(IllegalArgumentException.class, () -> RecipePlan.parse("[{\"action\":\"termux_execute_command\",\"params\":{}}]"));
        assertThrows(IllegalArgumentException.class, () -> RecipePlan.parse("[".repeat(100) + "]".repeat(100)));
        assertThrows(IllegalArgumentException.class, () -> RecipePlan.parse("[]"));
    }

    private static AssistantApproval.Scope scope(Object activity, String session, long run, String tab, String doc, long epoch) {
        return new AssistantApproval.Scope(session, run, new AssistantApproval.Page(activity, tab, doc, epoch, "https://example.invalid/a"));
    }
}

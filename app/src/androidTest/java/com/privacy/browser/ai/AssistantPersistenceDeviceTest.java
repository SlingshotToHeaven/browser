package com.privacy.browser.ai;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.browser.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Uses synthetic rows and real Android SQLite/AlarmManager. Never contacts a provider. */
public class AssistantPersistenceDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void legacyPendingConfirmationIsReadableButHasNoApproveButton() throws Exception {
        AgentDbHelper helper = AgentDbHelper.getInstance(context);
        String id = UUID.randomUUID().toString();
        helper.createSession(id, "Disposable assistant test");
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues row = new ContentValues();
            row.put(AgentDbHelper.COL_MSG_SESSION_ID, id);
            row.put(AgentDbHelper.COL_MSG_TYPE, AiChatAdapter.TYPE_CONFIRMATION);
            row.put(AgentDbHelper.COL_MSG_CONTENT, "Review a reload");
            row.put(AgentDbHelper.COL_MSG_TOOL_NAME, "nav_reload_page");
            row.put(AgentDbHelper.COL_MSG_TOOL_ARGS, "{}");
            row.put(AgentDbHelper.COL_MSG_IS_RESOLVED, 0);
            row.put(AgentDbHelper.COL_MSG_CREATED_AT, 1);
            assertTrue(db.insertOrThrow(AgentDbHelper.TABLE_MESSAGES, null, row) > 0);
            List<AiChatAdapter.ChatMessage> restored = helper.getMessages(id);
            assertEquals(1, restored.size());
            assertEquals("Review a reload", restored.get(0).content);
            instrumentation.runOnMainSync(() -> {
                AiChatAdapter adapter = new AiChatAdapter(false);
                adapter.setMessages(restored);
                RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(context), adapter.getItemViewType(0));
                adapter.onBindViewHolder(holder, 0);
                assertEquals(View.GONE, holder.itemView.findViewById(R.id.btn_confirm_execute).getVisibility());
            });
        } finally { helper.deleteSession(id); }
    }

    @Test public void availableAlarmRegistersAndCancellationRemovesItsDurableRecord() throws Exception {
        AutomationManager manager = new AutomationManager(context);
        RecipePlan plan = RecipePlan.parse("[{\"action\":\"nav_reload_page\",\"params\":{}}]");
        AutomationManager.Task saved = manager.schedule("Disposable fixture", System.currentTimeMillis() + 600_000, plan);
        try {
            assertTrue(saved.status == ReminderPlan.Status.INEXACT_REQUESTED || saved.status == ReminderPlan.Status.EXACT_REQUESTED);
            assertTrue(new AutomationManager(context).list().stream().anyMatch(task -> task.id.equals(saved.id)));
            assertTrue(manager.cancel(saved.id));
            assertFalse(new AutomationManager(context).list().stream().anyMatch(task -> task.id.equals(saved.id)));
            assertFalse(manager.cancel(saved.id));
        } finally {
            manager.cancel(saved.id);
            SharedPreferences prefs = context.getSharedPreferences("agent_automations_pref", Context.MODE_PRIVATE);
            if ("[]".equals(prefs.getString("recipe_review_reminders_v2", null))) prefs.edit().remove("recipe_review_reminders_v2").commit();
        }
    }

    @Test public void overdueRecordReopensForReviewAndLegacyRecipeStaysInert() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("agent_automations_pref", Context.MODE_PRIVATE);
        assertFalse("Use a disposable install without saved reminders", prefs.contains("recipe_review_reminders_v2"));
        assertFalse("Use a disposable install without saved reminders", prefs.contains("automation_tasks_json"));
        String id = UUID.randomUUID().toString();
        JSONObject record = new JSONObject().put("task_id", id).put("title", "Overdue fixture")
                .put("recipe", "[{\"action\":\"nav_reload_page\",\"params\":{}}]")
                .put("trigger_time_epoch_ms", System.currentTimeMillis() - 60_000).put("status", "exact_requested");
        assertTrue(prefs.edit().putString("recipe_review_reminders_v2", new JSONArray().put(record).toString())
                .putString("automation_tasks_json", "[{\"recipe\":\"fixture\"}]").commit());
        try {
            AutomationManager manager = new AutomationManager(context);
            List<AutomationManager.Task> list = manager.list();
            assertEquals(1, list.size());
            assertEquals(id, list.get(0).id);
            assertEquals(ReminderPlan.Status.READY_FOR_REVIEW, list.get(0).status);
            assertTrue(manager.hasLegacyRecipes());
            assertFalse(list.get(0).summary().getBoolean("background_execution"));
        } finally {
            assertTrue(prefs.edit().remove("recipe_review_reminders_v2").remove("automation_tasks_json").commit());
        }
    }
}

package com.privacy.browser.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Schedules review reminders only. A receiver never dispatches tools or starts an Activity. */
public final class AutomationManager {
    private static final String PREF_NAME = "agent_automations_pref";
    private static final String KEY = "recipe_review_reminders_v2";
    private static final String DUE = "com.privacy.browser.action.RECIPE_REVIEW_DUE_V2";
    private static final String SCHEME = "privacy-recipe-reminder";
    private static final int MAX_TASKS = 16;
    private static final Object LOCK = new Object();
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor();
    private static Boolean lastExactCapability;

    static final class Task {
        final String id, title, recipe;
        final long trigger;
        final ReminderPlan.Status status;

        Task(String id, String title, String recipe, long trigger, ReminderPlan.Status status) {
            this.id = id; this.title = title; this.recipe = recipe; this.trigger = trigger; this.status = status;
        }

        Task withStatus(ReminderPlan.Status next) { return new Task(id, title, recipe, trigger, next); }

        JSONObject summary() throws JSONException {
            return new JSONObject().put("task_id", id).put("title", title).put("trigger_time_epoch_ms", trigger)
                    .put("status", status.name().toLowerCase(java.util.Locale.ROOT)).put("background_execution", false);
        }

        String statusLabel() {
            switch (status) {
                case EXACT_REQUESTED: return "Exact alarm requested; delivery is not guaranteed";
                case INEXACT_REQUESTED: return "Inexact alarm requested; Android may delay it";
                case READY_FOR_REVIEW: return "Due for manual review";
                default: return "Alarm unavailable; not scheduled";
            }
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final AlarmManager manager;

    public AutomationManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        manager = (AlarmManager) this.context.getSystemService(Context.ALARM_SERVICE);
    }

    Task schedule(String title, long trigger, RecipePlan recipe) throws IOException, JSONException {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (!ReminderPlan.validNewTime(trigger, now)) throw new IllegalArgumentException("Choose a future time within one year.");
            List<Task> tasks = read();
            if (tasks.size() >= MAX_TASKS) throw new IllegalArgumentException("Remove an old reminder first (limit 16).");
            Task task = new Task(UUID.randomUUID().toString(), title, recipe.json, trigger, ReminderPlan.Status.UNAVAILABLE);
            ReminderPlan.Alarms alarms = registration(task);
            Task armed = task.withStatus(ReminderPlan.reconcile(task.status, trigger, now, alarms));
            if (armed.status == ReminderPlan.Status.UNAVAILABLE) throw new IOException("Android did not accept an alarm. Nothing was scheduled.");
            List<Task> next = new ArrayList<>(tasks);
            next.add(armed);
            try { write(next); }
            catch (IOException | JSONException e) {
                ReminderPlan.cancel(alarms);
                try { write(tasks); } catch (IOException | JSONException ignored) { }
                throw new IOException("Could not save the reminder. Registration was withdrawn.", e);
            }
            return armed;
        }
    }

    boolean cancel(String id) throws IOException, JSONException {
        synchronized (LOCK) {
            List<Task> tasks = read();
            for (Task task : new ArrayList<>(tasks)) if (task.id.equals(id)) {
                tasks.remove(task);
                write(tasks); // A stale delivery has no record to mark due.
                ReminderPlan.cancel(registration(task));
                return true;
            }
            return false;
        }
    }

    List<Task> list() throws IOException, JSONException {
        synchronized (LOCK) {
            // Also notices an overdue reminder even if Android suppressed its delivery.
            reconcileLocked(false);
            return read();
        }
    }

    boolean hasLegacyRecipes() { return prefs.contains("automation_tasks_json"); }

    private List<Task> read() throws IOException, JSONException {
        String raw = prefs.getString(KEY, "[]");
        if (raw == null || raw.length() > 512 * 1024 || !RecipePlan.boundedJson(raw, 3)) throw new IOException("Reminder data is unreadable.");
        JSONArray array = new JSONArray(raw);
        if (array.length() > MAX_TASKS) throw new IOException("Reminder data exceeds its limit.");
        List<Task> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String id = item.getString("task_id");
            if (!ReminderPlan.validId(id)) throw new IOException("Invalid reminder ID.");
            list.add(new Task(id, item.getString("title"), item.getString("recipe"), item.getLong("trigger_time_epoch_ms"),
                    ReminderPlan.Status.valueOf(item.getString("status").toUpperCase(java.util.Locale.ROOT))));
        }
        return list;
    }

    private void write(List<Task> list) throws IOException, JSONException {
        JSONArray array = new JSONArray();
        for (Task task : list) array.put(task.summary().put("recipe", task.recipe));
        if (!prefs.edit().putString(KEY, array.toString()).commit()) throw new IOException("Reminder storage write failed.");
    }

    private void reconcileLocked(boolean rearm) throws IOException, JSONException {
        List<Task> tasks = read();
        long now = System.currentTimeMillis();
        boolean exact = canSetExact();
        boolean changed = false;
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (!rearm && task.trigger > now && !(task.status == ReminderPlan.Status.EXACT_REQUESTED && !exact)) continue;
            ReminderPlan.Status next = ReminderPlan.reconcile(task.status, task.trigger, now, registration(task));
            if (next != task.status) {
                tasks.set(i, task.withStatus(next));
                changed = true;
            }
        }
        if (changed) write(tasks);
    }

    private void delivered(String id) throws IOException, JSONException {
        synchronized (LOCK) {
            List<Task> tasks = read();
            long now = System.currentTimeMillis();
            for (int i = 0; i < tasks.size(); i++) {
                Task task = tasks.get(i);
                if (task.id.equals(id) && ReminderPlan.canMarkDue(task.status, task.trigger, now)) {
                    tasks.set(i, task.withStatus(ReminderPlan.Status.READY_FOR_REVIEW));
                    write(tasks);
                    ReminderPlan.cancel(registration(task));
                    return;
                }
            }
        }
    }

    private boolean canSetExact() {
        return manager != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms());
    }

    private ReminderPlan.Alarms registration(Task task) {
        Intent intent = new Intent(context, AutomationAlarmReceiver.class).setAction(DUE)
                .setData(Uri.parse(SCHEME + "://" + task.id));
        return new ReminderPlan.Alarms() {
            private PendingIntent pending(int flags) {
                return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
            }
            @Override public boolean canSetExact() { return AutomationManager.this.canSetExact(); }
            @Override public void setExact() {
                if (manager == null) throw new IllegalStateException("No AlarmManager");
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.trigger, pending(0));
            }
            @Override public void setInexact() {
                if (manager == null) throw new IllegalStateException("No AlarmManager");
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.trigger, pending(0));
            }
            @Override public void cancel() {
                PendingIntent token = pending(PendingIntent.FLAG_NO_CREATE);
                if (token != null) {
                    try { if (manager != null) manager.cancel(token); }
                    finally { token.cancel(); }
                }
            }
        };
    }

    public static void onBrowserResume(Context context) {
        Context app = context.getApplicationContext();
        BACKGROUND.execute(() -> {
            AutomationManager reminders = new AutomationManager(app);
            synchronized (LOCK) {
                try {
                    boolean exact = reminders.canSetExact();
                    reminders.reconcileLocked(lastExactCapability == null || lastExactCapability != exact);
                    lastExactCapability = exact;
                } catch (Exception ignored) { /* list exposes unreadable data */ }
            }
        });
    }

    public static final class AutomationAlarmReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            Uri data = intent.getData();
            String id = DUE.equals(action) && data != null && SCHEME.equals(data.getScheme()) ? data.getHost() : null;
            boolean rearm = Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                    || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);
            if (!rearm && !ReminderPlan.validId(id)) return; // Includes all old recipe-bearing PendingIntents.
            PendingResult result = goAsync();
            Context app = context.getApplicationContext();
            BACKGROUND.execute(() -> {
                try {
                    AutomationManager reminders = new AutomationManager(app);
                    if (rearm) {
                        synchronized (LOCK) { reminders.reconcileLocked(true); }
                    } else reminders.delivered(id);
                } catch (Exception ignored) { /* never execute or notify on failure */ }
                finally { result.finish(); }
            });
        }
    }
}

package com.privacy.browser.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AutomationManager {
    private static final String TAG = "AutomationManager";
    private static final String PREF_NAME = "agent_automations_pref";
    private static final String KEY_TASKS = "automation_tasks_json";

    public static class AutomationTask {
        public final String id;
        public final String title;
        public final long triggerTimeEpochMs;
        public final String recipeJson;

        public AutomationTask(String id, String title, long triggerTimeEpochMs, String recipeJson) {
            this.id = id;
            this.title = title;
            this.triggerTimeEpochMs = triggerTimeEpochMs;
            this.recipeJson = recipeJson;
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("title", title);
                obj.put("trigger_time_epoch_ms", triggerTimeEpochMs);
                obj.put("recipe_json", recipeJson);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    public static class AutomationAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String taskId = intent.getStringExtra("task_id");
            String title = intent.getStringExtra("title");
            String recipeJson = intent.getStringExtra("recipe_json");
            Log.d(TAG, "Automation Alarm Fired! Task ID: " + taskId + " Title: " + title);

            // Execute scheduled action by launching main activity with recipe
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launchIntent.putExtra("automation_recipe_json", recipeJson);
                launchIntent.putExtra("automation_title", title);
                context.startActivity(launchIntent);
            }
        }
    }

    private final Context context;
    private final SharedPreferences prefs;

    public AutomationManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void scheduleTask(AutomationTask task) {
        List<AutomationTask> tasks = listTasks();
        tasks.add(task);
        saveTasks(tasks);

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, AutomationAlarmReceiver.class);
        intent.putExtra("task_id", task.id);
        intent.putExtra("title", task.title);
        intent.putExtra("recipe_json", task.recipeJson);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        int requestCode = task.id.hashCode();
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode, intent, flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.triggerTimeEpochMs, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, task.triggerTimeEpochMs, pi);
        }
    }

    public synchronized boolean cancelTask(String taskId) {
        List<AutomationTask> tasks = listTasks();
        AutomationTask found = null;
        for (AutomationTask t : tasks) {
            if (t.id.equals(taskId)) {
                found = t;
                break;
            }
        }
        if (found == null) return false;

        tasks.remove(found);
        saveTasks(tasks);

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            Intent intent = new Intent(context, AutomationAlarmReceiver.class);
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, found.id.hashCode(), intent, flags);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
        return true;
    }

    public synchronized List<AutomationTask> listTasks() {
        List<AutomationTask> list = new ArrayList<>();
        String jsonStr = prefs.getString(KEY_TASKS, "[]");
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new AutomationTask(
                        obj.getString("id"),
                        obj.getString("title"),
                        obj.getLong("trigger_time_epoch_ms"),
                        obj.getString("recipe_json")
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading automations", e);
        }
        return list;
    }

    private void saveTasks(List<AutomationTask> tasks) {
        JSONArray arr = new JSONArray();
        for (AutomationTask t : tasks) {
            arr.put(t.toJson());
        }
        prefs.edit().putString(KEY_TASKS, arr.toString()).apply();
    }
}

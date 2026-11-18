package com.privacy.browser.ai;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.Toast;

import com.privacy.browser.MainActivity;
import com.privacy.browser.ThemeManager;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Manual recipe review UI; selecting one step never starts the others. */
final class AutomationRecipeRunner {
    private AutomationRecipeRunner() { }

    static void paste(MainActivity owner, AgentSessionManager session, BooleanSupplier visible) {
        EditText input = new EditText(owner);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(RecipePlan.MAX_RECIPE)});
        input.setHint("[{\"action\":\"nav_get_active_tab_info\",\"params\":{}}]");
        input.setMinLines(3);
        AlertDialog dialog = builder(owner, "Review recipe JSON").setView(input)
                .setMessage("Paste a recipe to inspect its steps. Nothing runs on import.")
                .setNegativeButton("Cancel", null).setPositiveButton("Review steps", null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!visible.getAsBoolean()) { dialog.dismiss(); return; }
            try {
                RecipePlan plan = RecipePlan.parse(input.getText().toString());
                dialog.dismiss();
                showSteps(owner, session, visible, plan);
            } catch (Exception invalid) {
                input.setError(invalid instanceof IllegalArgumentException ? invalid.getMessage() : "Invalid recipe JSON.");
            }
        }));
        dialog.show();
    }

    private static void showSteps(MainActivity owner, AgentSessionManager session, BooleanSupplier visible, RecipePlan plan) {
        String[] descriptions = new String[plan.steps.size()];
        for (int i = 0; i < descriptions.length; i++) descriptions[i] = (i + 1) + ". " + plan.steps.get(i).kind.label;
        builder(owner, "Choose one step to review").setItems(descriptions, (prompt, index) ->
                session.reviewManualStep(owner, visible, plan.steps.get(index), new ToolDispatcher.ExecutionCallback() {
                    @Override public void onCompleted(JSONObject outcome) {
                        if (visible.getAsBoolean()) builder(owner, "Step result").setMessage(outcome.toString())
                                .setPositiveButton("Close", null).show();
                    }
                    @Override public void onStopped(String status, String reason) {
                        if (visible.getAsBoolean()) Toast.makeText(owner, reason, Toast.LENGTH_LONG).show();
                    }
                })).setNegativeButton("Close", null).show();
    }

    static void reminders(MainActivity owner, AgentSessionManager session, BooleanSupplier visible) {
        AutomationManager manager = new AutomationManager(owner);
        try {
            List<AutomationManager.Task> tasks = manager.list();
            String explanation = "In-app only. Reminders do not open the app, send notifications, or run recipes."
                    + (manager.hasLegacyRecipes() ? " Old scheduled recipes were disabled; their original data was kept." : "");
            if (tasks.isEmpty()) {
                builder(owner, "Recipe reminders").setMessage("No review reminders.\n\n" + explanation).setPositiveButton("Close", null).show();
                return;
            }
            String[] labels = new String[tasks.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = tasks.get(i).title + "\n" + tasks.get(i).statusLabel();
            builder(owner, "Recipe review reminders").setItems(labels, (prompt, index) -> {
                AutomationManager.Task task = tasks.get(index);
                builder(owner, "Reminder details").setMessage(task.title + "\n" + DateFormat.getDateTimeInstance().format(new Date(task.trigger))
                                + "\n" + task.statusLabel() + "\n\n" + explanation)
                        .setPositiveButton("Review recipe", (confirm, button) -> {
                            if (!visible.getAsBoolean()) return;
                            try { showSteps(owner, session, visible, RecipePlan.parse(task.recipe)); }
                            catch (Exception invalid) { Toast.makeText(owner, "Saved recipe is not supported.", Toast.LENGTH_LONG).show(); }
                        }).setNeutralButton("Cancel reminder", (confirm, button) -> {
                            if (!visible.getAsBoolean()) return;
                            try {
                                boolean removed = manager.cancel(task.id);
                                Toast.makeText(owner, removed ? "Reminder cancelled" : "Reminder no longer exists", Toast.LENGTH_SHORT).show();
                            } catch (Exception error) { Toast.makeText(owner, "Could not confirm cancellation", Toast.LENGTH_LONG).show(); }
                        }).setNegativeButton("Close", null).show();
            }).setNegativeButton("Close", null).show();
        } catch (Exception error) {
            builder(owner, "Reminders unavailable").setMessage("Could not verify the saved reminder state.").setPositiveButton("Close", null).show();
        }
    }

    private static AlertDialog.Builder builder(MainActivity owner, String title) {
        return new AlertDialog.Builder(owner, ThemeManager.getDialogTheme(owner)).setTitle(title);
    }
}

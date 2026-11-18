package com.privacy.browser.adblock.cloud;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.R;
import com.privacy.browser.ThemeManager;
import com.privacy.browser.adblock.AdBlockFileManager;
import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import java.text.DateFormat;
import java.util.Date;

/** Community data-only snapshots. Fetch, parse, plan, review, then commit the reviewed plan. */
public final class AdBlockCloudStatesDialog {
    public static void show(@NonNull Context context, @Nullable Runnable onReloadNeeded) {
        new AdBlockCloudStatesDialog(context, onReloadNeeded).show();
    }

    private final Context context;
    private final AdBlockCloudClient client;
    private final AdBlockFileManager files;
    private final Runnable onReload;
    private final Dialog dialog;
    private final LinearLayout entries;
    private final TextView status, endpoint;
    private final View close, settings, refresh, publish;
    private boolean busy;

    private AdBlockCloudStatesDialog(Context context, Runnable onReload) {
        this.context = context;
        this.onReload = onReload;
        client = new AdBlockCloudClient(context.getApplicationContext());
        files = AdBlockFileManager.getInstance(context);
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_adblock_cloud_states, null);
        dialog.setContentView(view);
        close = view.findViewById(R.id.btn_close_cloud_dialog);
        settings = view.findViewById(R.id.btn_cloud_settings);
        refresh = view.findViewById(R.id.btn_action_refresh_states);
        publish = view.findViewById(R.id.btn_action_commit_state);
        entries = view.findViewById(R.id.ll_cloud_states_container);
        status = view.findViewById(R.id.tv_cloud_states_empty);
        endpoint = view.findViewById(R.id.tv_cloud_endpoint_label);
        ((Button) publish).setText("Publish rules");
        dialog.setOnDismissListener(event -> client.close());
        close.setOnClickListener(v -> dialog.dismiss());
        refresh.setOnClickListener(v -> list(client.getEndpointUrl(), 0));
        settings.setOnClickListener(v -> endpointSettings());
        publish.setOnClickListener(v -> promptPublish());
    }

    private void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (context.getResources().getDisplayMetrics().widthPixels * .95),
                    (int) (context.getResources().getDisplayMetrics().heightPixels * .88));
        }
        list(client.getEndpointUrl(), 0);
    }

    private AlertDialog.Builder prompt(String title) {
        return new AlertDialog.Builder(context, ThemeManager.getDialogTheme(context)).setTitle(title);
    }

    private void setBusy(boolean value, boolean committing) {
        busy = value;
        settings.setEnabled(!value);
        publish.setEnabled(!value);
        refresh.setEnabled(!value);
        close.setEnabled(!committing);
        dialog.setCancelable(!committing);
    }

    private void announce(String text) {
        if (dialog.isShowing()) Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    private void endpointSettings() {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setText(client.getEndpointUrl());
        AlertDialog editor = prompt("Filter snapshot catalog").setView(field)
                .setMessage("Use the HTTPS origin of a version 2 Worker.")
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        editor.setOnShowListener(ignored -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                client.setEndpointUrl(field.getText().toString());
                editor.dismiss();
                list(client.getEndpointUrl(), 0);
            } catch (IllegalArgumentException e) { field.setError(e.getMessage()); }
        }));
        editor.show();
    }

    private void list(String source, long before) {
        if (busy) return;
        endpoint.setText("Catalog: " + source);
        entries.removeAllViews();
        status.setText("Loading rule snapshots…");
        status.setVisibility(View.VISIBLE);
        entries.addView(status);
        setBusy(true, false);
        client.list(source, before, new AdBlockCloudClient.Callback<AdBlockCloudClient.Page>() {
            @Override public void onSuccess(AdBlockCloudClient.Page page) {
                setBusy(false, false);
                entries.removeAllViews();
                if (page.entries.isEmpty()) {
                    status.setText("No rule snapshots. Legacy diffs are not imported.");
                    entries.addView(status);
                }
                LayoutInflater inflater = LayoutInflater.from(context);
                for (AdBlockCloudClient.Summary entry : page.entries) {
                    View row = inflater.inflate(R.layout.item_cloud_state, entries, false);
                    ((TextView) row.findViewById(R.id.tv_state_item_title)).setText(entry.description);
                    ((TextView) row.findViewById(R.id.tv_state_item_time)).setText(DateFormat.getDateTimeInstance().format(new Date(entry.timestamp)));
                    ((TextView) row.findViewById(R.id.tv_state_item_author)).setText("Rule data only · " + entry.id.substring(0, 15));
                    Button button = row.findViewById(R.id.btn_state_item_apply);
                    button.setText("Review");
                    button.setOnClickListener(v -> download(source, entry));
                    entries.addView(row);
                }
                if (page.next > 0) {
                    Button older = new Button(context);
                    older.setText("Older snapshots");
                    older.setOnClickListener(v -> list(source, page.next));
                    entries.addView(older);
                }
            }
            @Override public void onError(String error) {
                setBusy(false, false);
                status.setText(error);
            }
        });
    }

    private void download(String source, AdBlockCloudClient.Summary entry) {
        if (busy) return;
        if (files.ruleWarning() != null) { announce(files.ruleWarning()); return; }
        setBusy(true, false);
        client.download(source, entry.id, new AdBlockCloudClient.Callback<FilterSnapshot>() {
            @Override public void onSuccess(FilterSnapshot incoming) {
                setBusy(false, false);
                prompt(entry.description).setItems(new String[]{"Make Same — replace all four files", "Merge — add rules, keep local rules"},
                        (confirm, which) -> preview(incoming, which == 0 ? AdBlockDiffEngine.ApplyMode.MAKE_SAME : AdBlockDiffEngine.ApplyMode.MERGE))
                        .setNegativeButton("Cancel", null).show();
            }
            @Override public void onError(String error) { setBusy(false, false); announce(error); }
        });
    }

    private void preview(FilterSnapshot incoming, AdBlockDiffEngine.ApplyMode mode) {
        FilterSnapshot before = files.ruleSnapshot();
        setBusy(true, false);
        client.run(() -> AdBlockDiffEngine.preview(before, incoming, mode), new AdBlockCloudClient.Callback<AdBlockDiffEngine.Plan>() {
            @Override public void onSuccess(AdBlockDiffEngine.Plan plan) {
                setBusy(false, false);
                if (plan.changes.isEmpty()) { announce("No changes to apply."); return; }
                StringBuilder text = new StringBuilder(mode == AdBlockDiffEngine.ApplyMode.MAKE_SAME
                        ? "Replace local rules with the exact uploaded snapshot.\n" : "Keep local rules and append new rule lines.\n");
                for (AdBlockDiffEngine.Change change : plan.changes) {
                    text.append('\n').append(change.part.fileName).append(": +").append(change.added).append(" / −")
                            .append(change.removed).append(" distinct lines; result ").append(change.resultRules)
                            .append(" rules, ").append(change.resultBytes).append(" bytes.\n");
                    if (change.samples.isEmpty()) text.append("Text or rule order changed.\n");
                    for (String sample : change.samples) text.append(sample).append('\n');
                }
                prompt("Review rule changes").setMessage(text).setNegativeButton("Cancel", null)
                        .setPositiveButton("Apply this preview", (confirm, which) -> apply(plan)).show();
            }
            @Override public void onError(String error) { setBusy(false, false); announce(error); }
        });
    }

    private void apply(AdBlockDiffEngine.Plan plan) {
        setBusy(true, true);
        client.run(() -> { files.applyRuleSnapshot(plan.expected, plan.result); return plan.changes.size(); }, new AdBlockCloudClient.Callback<Integer>() {
            @Override public void onSuccess(Integer changed) {
                setBusy(false, false);
                announce("Applied " + changed + " rule files together.");
                if (onReload != null) onReload.run();
            }
            @Override public void onError(String error) { setBusy(false, false); announce("Not applied: " + error); }
        });
    }

    private void promptPublish() {
        if (files.ruleWarning() != null) { announce(files.ruleWarning()); return; }
        FilterSnapshot snapshot = files.ruleSnapshot();
        try { snapshot.requireSupported(); }
        catch (IllegalArgumentException e) { announce(e.getMessage()); return; }
        String source = client.getEndpointUrl();
        StringBuilder summary = new StringBuilder("Send all four rule files to " + source
                + ". Custom rules will be visible to catalog readers. Scripts are not sent.\n");
        for (Part part : Part.values()) summary.append('\n').append(part.fileName).append(": ")
                .append(snapshot.ruleCount(part)).append(" rules; ").append(snapshot.bytes(part)).append(" bytes");
        EditText description = new EditText(context);
        description.setSingleLine(true);
        description.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        description.setHint("Snapshot description");
        AlertDialog confirm = prompt("Review publication").setMessage(summary).setView(description).setNegativeButton("Cancel", null)
                .setPositiveButton("Publish snapshot", (d, which) -> publishReviewed(source, description.getText().toString().trim(), snapshot)).create();
        confirm.show();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
    }

    private void publishReviewed(String source, String description, FilterSnapshot snapshot) {
        if (!dialog.isShowing() || busy || !source.equals(client.getEndpointUrl())) return;
        try { RuleSnapshotWire.validDescription(description); }
        catch (IllegalArgumentException invalid) { announce(invalid.getMessage()); return; }
        setBusy(true, true);
        client.publish(source, description, snapshot, new AdBlockCloudClient.Callback<String>() {
            @Override public void onSuccess(String id) {
                setBusy(false, false);
                announce("Published " + id.substring(0, 15) + "…");
                list(source, 0);
            }
            @Override public void onError(String error) {
                setBusy(false, false);
                if (!dialog.isShowing()) return;
                prompt("Publication not confirmed").setMessage(error + "\n\nRetry sends the same reviewed rule snapshot and description to "
                                + source + ". An already accepted snapshot is not added twice.")
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Retry same snapshot", (d, which) -> publishReviewed(source, description, snapshot)).show();
            }
        });
    }
}

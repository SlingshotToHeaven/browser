package com.privacy.browser.adblock.rules;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.R;

import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

/**
 * Full-screen/modal editor for uBlock Origin "My Filters" rule configuration.
 */
public class MyFiltersDialog {

    public static void show(@NonNull Context context, @Nullable Runnable onFiltersSaved) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_my_filters, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.95);
            int height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.85);
            window.setLayout(width, height);
        }

        UserFiltersManager manager = UserFiltersManager.getInstance(context);

        TextView tvCount = view.findViewById(R.id.tv_rules_count);
        EditText editContent = view.findViewById(R.id.edit_my_filters_content);
        ImageButton btnClose = view.findViewById(R.id.btn_close_my_filters);
        Button btnReset = view.findViewById(R.id.btn_reset_filters);
        Button btnSave = view.findViewById(R.id.btn_save_filters);

        FilterSnapshot before = manager.snapshot();
        editContent.setText(before.text(Part.USER_FILTERS));
        int unsupported = before.unsupported(Part.USER_FILTERS);
        String warning = manager.ruleWarning();
        tvCount.setText(warning != null ? warning : before.ruleCount(Part.USER_FILTERS) + " supported rules"
                + (unsupported == 0 ? "" : "; " + unsupported + " unsupported legacy lines are inactive"));
        btnSave.setEnabled(warning == null);
        btnReset.setEnabled(warning == null);

        btnSave.setOnClickListener(v -> {
            String draft = editContent.getText().toString();
            btnSave.setEnabled(false);
            btnReset.setEnabled(false);
            editContent.setEnabled(false);
            RuleWork.run(() -> manager.setAllRules(before, draft), (saved, error) -> {
                if (context instanceof Activity && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed())) return;
                if (error == null && saved != before && onFiltersSaved != null) onFiltersSaved.run();
                if (!dialog.isShowing()) return;
                if (error == null) {
                    Toast.makeText(context, "Saved " + saved.ruleCount(Part.USER_FILTERS) + " custom filter rules", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    tvCount.setText("Not saved: " + RuleWork.message(error));
                    editContent.setEnabled(true);
                    btnSave.setEnabled(true);
                    btnReset.setEnabled(true);
                }
            });
        });

        btnReset.setOnClickListener(v -> {
            editContent.setText("! Custom filters: ||host^, @@||host^, and [host]##selector\n");
            tvCount.setText("Reset draft. Save to replace the current rules.");
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }
}

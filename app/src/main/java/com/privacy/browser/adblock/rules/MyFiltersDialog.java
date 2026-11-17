package com.privacy.browser.adblock.rules;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

        List<String> currentRules = manager.getRawRules();
        StringBuilder sb = new StringBuilder();
        for (String r : currentRules) {
            sb.append(r).append("\n");
        }
        editContent.setText(sb.toString());
        tvCount.setText(manager.getRuleCount() + " custom rules active");

        btnSave.setOnClickListener(v -> {
            String text = editContent.getText().toString();
            String[] lines = text.split("\n");
            manager.setAllRules(new ArrayList<>(Arrays.asList(lines)));
            Toast.makeText(context, "Saved " + manager.getRuleCount() + " custom filter rules", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (onFiltersSaved != null) {
                onFiltersSaved.run();
            }
        });

        btnReset.setOnClickListener(v -> {
            editContent.setText("! *** User-defined filters (uBlock Origin format) ***\n");
            tvCount.setText("0 rules active");
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }
}

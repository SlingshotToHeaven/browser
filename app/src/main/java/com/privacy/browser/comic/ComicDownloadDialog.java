package com.privacy.browser.comic;

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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.R;

import java.io.File;

/**
 * AMOLED-styled dialog presenting comic archive options, format selection (.cbr / .cbz),
 * and real-time page download progress.
 */
public class ComicDownloadDialog {

    public static void show(@NonNull Context context,
                            @NonNull ComicManager.ComicItem item,
                            @Nullable String userAgent) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_comic_download, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        EditText editTitle = view.findViewById(R.id.edit_comic_title);
        editTitle.setText(item.title);

        TextView tvPageCount = view.findViewById(R.id.tv_comic_page_count);
        tvPageCount.setText(item.getPageCount() == 1 ? "1 page" : item.getPageCount() + " pages");

        RadioButton rbCbr = view.findViewById(R.id.rb_format_cbr);
        RadioButton rbCbz = view.findViewById(R.id.rb_format_cbz);

        Button btnDownload = view.findViewById(R.id.btn_start_comic_download);
        rbCbr.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) btnDownload.setText("Download .CBR Archive");
        });
        rbCbz.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) btnDownload.setText("Download .CBZ Archive");
        });

        LinearLayout llProgress = view.findViewById(R.id.ll_comic_download_progress);
        TextView tvProgress = view.findViewById(R.id.tv_comic_download_status);
        ProgressBar pbProgress = view.findViewById(R.id.pb_comic_download);

        ImageButton btnClose = view.findViewById(R.id.btn_close_comic_dialog);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        btnDownload.setOnClickListener(v -> {
            String title = editTitle.getText().toString().trim();
            if (title.isEmpty()) title = "Comic";

            String format = rbCbr.isChecked() ? ".cbr" : ".cbz";

            btnDownload.setEnabled(false);
            editTitle.setEnabled(false);
            rbCbr.setEnabled(false);
            rbCbz.setEnabled(false);
            llProgress.setVisibility(View.VISIBLE);

            final int total = item.getPageCount();
            pbProgress.setMax(total);

            ComicArchiver.buildArchive(
                    context,
                    title,
                    item.imageUrls,
                    item.referer,
                    userAgent,
                    format,
                    new ComicArchiver.ArchiveProgressListener() {
                        @Override
                        public void onProgress(int current, int total) {
                            pbProgress.setProgress(current);
                            tvProgress.setText("Downloading page " + current + " of " + total + "...");
                        }

                        @Override
                        public void onCompleted(@NonNull File archiveFile) {
                            Toast.makeText(context, "Comic archive saved: " + archiveFile.getName(), Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                        }

                        @Override
                        public void onError(@NonNull String errorMessage) {
                            Toast.makeText(context, "Download error: " + errorMessage, Toast.LENGTH_LONG).show();
                            btnDownload.setEnabled(true);
                            editTitle.setEnabled(true);
                            rbCbr.setEnabled(true);
                            rbCbz.setEnabled(true);
                        }
                    }
            );
        });

        dialog.show();
    }
}

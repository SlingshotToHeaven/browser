package com.privacy.downloader.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import com.privacy.downloader.engine.BlobIntake;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;

final class DownloadRecoveryDialog {
    private DownloadRecoveryDialog() {}

    static void retry(Context context, DownloadEngine engine, DownloadItem item) {
        if (item.getUrl() != null && item.getUrl().startsWith("blob:")) {
            Toast.makeText(context, BlobIntake.SOURCE_GONE, Toast.LENGTH_LONG).show();
            return;
        }
        if (!DownloadEngine.canStartOver(item)) {
            engine.resume(item.getId());
            return;
        }
        String reason = item.getErrorMessage();
        new AlertDialog.Builder(context, DownloaderThemeHelper.getDialogTheme(context))
                .setTitle("Retry download")
                .setMessage((reason == null ? "" : reason + "\n\n")
                        + "Retry only reuses progress that can be verified. Start over downloads the file from the beginning and leaves any old partial file untouched.")
                .setPositiveButton("Retry", (dialog, which) -> engine.resume(item.getId()))
                .setNeutralButton("Start over", (dialog, which) -> engine.restart(item.getId(), accepted -> {
                    if (!accepted) Toast.makeText(context, "Wait for the earlier attempt to stop, then try again.", Toast.LENGTH_SHORT).show();
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }
}

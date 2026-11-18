package com.privacy.browser.comic;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.privacy.browser.R;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.ui.DownloadPromptDialog;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Foreground-only comic job. WebView checks and dialogs stay on the UI thread. */
public final class ComicDownloadDialog extends Dialog {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> new Thread(task, "ComicArchive"));

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Activity owner;
    private final Context app;
    private final ComicManager.ComicItem comic;
    private final RequestContext request;
    private final BooleanSupplier currentPage;
    private final EditText editTitle;
    private final Button download;
    private final View progressPanel;
    private final TextView status;
    private final ProgressBar progress;
    private Attempt running;
    private boolean closed;

    private final class Attempt {
        final ComicArchiver.Job job = new ComicArchiver.Job();
        final Runnable deadline = () -> expire(this);
        final FutureTask<Void> worker;
        Dialog challenge;

        Attempt(String title) { worker = new FutureTask<>(() -> { build(this, title); return null; }); }

        void stopWorker() {
            worker.cancel(true);
            EXECUTOR.remove(worker);
            closeChallenge();
        }

        void closeChallenge() {
            Dialog prompt = challenge;
            challenge = null;
            if (prompt != null) prompt.dismiss();
        }
    }

    public static ComicDownloadDialog show(Activity owner, ComicManager.ComicItem comic, RequestContext request,
                                           BooleanSupplier currentPage) {
        if (request.isPrivate() || !currentPage.getAsBoolean()) return null;
        ComicDownloadDialog dialog = new ComicDownloadDialog(owner, comic, request, currentPage);
        try {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            return dialog;
        } catch (WindowManager.BadTokenException gone) {
            dialog.dismiss();
            return null;
        }
    }

    private ComicDownloadDialog(Activity owner, ComicManager.ComicItem comic, RequestContext request, BooleanSupplier currentPage) {
        super(owner);
        this.owner = owner;
        this.app = owner.getApplicationContext();
        this.comic = comic;
        this.request = request;
        this.currentPage = currentPage;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_comic_download);
        Window window = getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        editTitle = findViewById(R.id.edit_comic_title);
        editTitle.setText(comic.title);
        TextView count = findViewById(R.id.tv_comic_page_count);
        count.setText(owner.getResources().getQuantityString(R.plurals.comic_detected_page_count, comic.getPageCount(), comic.getPageCount()));
        findViewById(R.id.btn_close_comic_dialog).setOnClickListener(v -> dismiss());
        download = findViewById(R.id.btn_start_comic_download);
        download.setFilterTouchesWhenObscured(true);
        download.setOnClickListener(v -> start());
        progressPanel = findViewById(R.id.ll_comic_download_progress);
        status = findViewById(R.id.tv_comic_download_status);
        progress = findViewById(R.id.pb_comic_download);
    }

    private boolean active(Attempt attempt) {
        if (running != attempt || closed || !isShowing()) return false;
        if (!currentPage.getAsBoolean()) { dismiss(); return false; }
        return true;
    }

    private void start() {
        if (closed || !isShowing() || !currentPage.getAsBoolean()) { dismiss(); return; }
        if (running != null) return;
        Attempt attempt = new Attempt(editTitle.getText().toString().trim());
        running = attempt;
        download.setEnabled(false);
        editTitle.setEnabled(false);
        progress.setMax(comic.getPageCount());
        progress.setProgress(0);
        progressPanel.setVisibility(View.VISIBLE);
        status.setText(R.string.comic_preparing_archive);
        try {
            EXECUTOR.execute(attempt.worker);
            main.postDelayed(attempt.deadline, ComicArchiver.JOB_TIMEOUT_MS);
        } catch (RejectedExecutionException busy) {
            finish(attempt, null, owner.getString(R.string.comic_archive_busy));
        }
    }

    @Override public void dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::dismiss); return; }
        if (!closed) {
            closed = true;
            Attempt attempt = running;
            running = null;
            if (attempt != null) {
                main.removeCallbacks(attempt.deadline);
                if (attempt.job.cancel()) attempt.stopWorker();
            }
        }
        super.dismiss();
    }

    private void expire(Attempt attempt) {
        if (running != attempt || !attempt.job.cancel()) return;
        attempt.stopWorker();
        finish(attempt, null, owner.getString(R.string.comic_archive_timeout));
    }

    private void build(Attempt attempt, String title) {
        final File archive;
        try {
            attempt.job.check();
            File folder = new File(DownloadPromptDialog.getSafeDownloadsDir(app), "Comics");
            archive = ComicArchiver.buildArchive(app.getCacheDir(), folder, title, comic.imageUrls, request, attempt.job,
                    (url, scope, wait) -> verify(attempt, url, scope, wait),
                    (received, total) -> main.post(() -> {
                        if (!active(attempt)) return;
                        progress.setProgress(received);
                        status.setText(received == total ? owner.getString(R.string.comic_saving_archive)
                                : owner.getString(R.string.comic_page_progress, received, total));
                    }));
        } catch (ComicArchiver.Failure incomplete) {
            main.post(() -> finish(attempt, null, incomplete.getMessage()));
            return;
        } catch (IOException | RuntimeException failed) {
            main.post(() -> finish(attempt, null, owner.getString(R.string.comic_archive_failed)));
            return;
        }
        try {
            MediaScannerConnection.scanFile(app, new String[]{archive.getAbsolutePath()}, new String[]{ComicArchiver.MIME}, null);
        } catch (RuntimeException ignored) { /* A media index is not the publication commit. */ }
        main.post(() -> finish(attempt, archive, null));
    }

    private boolean verify(Attempt attempt, String url, RequestContext scope, long maxWaitMs) throws InterruptedException {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        main.post(() -> {
            if (!active(attempt) || attempt.job.stopped() || decision.isDone()) { decision.complete(false); return; }
            try {
                attempt.challenge = CaptchaVerificationDialog.show(owner, url, scope,
                        confirmed -> decision.complete(confirmed && active(attempt) && !attempt.job.stopped()));
            } catch (RuntimeException unavailable) {
                decision.complete(false);
            }
        });
        try {
            return decision.get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException unavailable) {
            return false;
        } finally {
            // Also fences a prompt still queued when the connection's verification wait expires.
            decision.complete(false);
            main.post(attempt::closeChallenge);
        }
    }

    private void finish(Attempt attempt, File archive, String error) {
        if (!active(attempt)) return;
        main.removeCallbacks(attempt.deadline);
        running = null;
        attempt.closeChallenge();
        if (archive != null) {
            status.setText(owner.getString(R.string.comic_archive_saved, archive.getAbsolutePath()));
            download.setText(R.string.comic_archive_done);
            download.setOnClickListener(v -> dismiss());
        } else {
            status.setText(error);
            editTitle.setEnabled(true);
        }
        download.setEnabled(true);
    }
}

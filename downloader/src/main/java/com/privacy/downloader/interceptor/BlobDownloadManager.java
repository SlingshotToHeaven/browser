package com.privacy.downloader.interceptor;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.UiThread;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.privacy.downloader.engine.BlobIntake;
import com.privacy.downloader.engine.DownloadEngine;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.ui.DownloadPromptDialog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** UI-thread coordinator for one WebView. Only queued workers can initiate intake. */
@UiThread
public final class BlobDownloadManager {
    private static final String BRIDGE = "PrivacyBrowserBlobIntake";
    private static final String PAGE_LEFT = "The original page was closed or navigated away before the blob finished.";
    private static String script; // bundled script; read once on the UI thread

    private final WebView webView;
    private final BooleanSupplier canRetainSource;
    private final Runnable sourceReleased;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final boolean supported;
    private boolean closed;
    private boolean rendererGone;
    private BlobDocument<JavaScriptReplyProxy> document;
    private Prompt prompt;
    private Request active;

    public BlobDownloadManager(WebView webView, BooleanSupplier canRetainSource, Runnable sourceReleased) {
        this.webView = webView;
        this.canRetainSource = canRetainSource;
        this.sourceReleased = sourceReleased;
        boolean listening = false;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                // Tabs navigate to arbitrary HTTP(S) sites. The injected object grants no
                // actions: callbacks require a main frame, verified origin, and a pinned proxy.
                WebViewCompat.addWebMessageListener(webView, BRIDGE, Collections.singleton("*"), this::onMessage);
                listening = true;
            } catch (RuntimeException ignored) {
                // No addJavascriptInterface fallback: it cannot prove which frame called it.
            }
        }
        supported = listening;
    }

    /** Queued or streaming intake still depends on this document. Prompts alone do not pin it. */
    public boolean hasPendingTransfer() { return active != null; }

    public void onNavigation() {
        document = null;
        Prompt oldPrompt = prompt;
        prompt = null;
        if (oldPrompt != null) oldPrompt.dialog.dismiss();
        Request oldRequest = active;
        if (oldRequest != null) {
            active = null;
            ui.removeCallbacks(oldRequest.admissionTimeout);
            oldRequest.intake.abort(PAGE_LEFT);
            if (!closed) sourceReleased.run();
        }
    }

    public void onDocumentCommitted(String url) {
        onNavigation();
        if (closed || !supported) return;
        String origin = BlobDocument.webOrigin(url);
        if (origin == null || !origin.equals(BlobDocument.webOrigin(webView.getUrl()))) return;
        try {
            BlobDocument<JavaScriptReplyProxy> next = new BlobDocument<>(webView, origin);
            document = next;
            // This only binds the reply endpoint. The script doesn't know any blob URL yet.
            webView.evaluateJavascript("(" + readScript(webView.getContext()) + ")('" + next.challenge + "');", null);
        } catch (IOException | RuntimeException e) {
            document = null;
        }
    }

    public void rendererGone() {
        rendererGone = true;
        close();
    }

    public void close() {
        if (closed) return;
        closed = true;
        onNavigation();
        if (!rendererGone && supported && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.removeWebMessageListener(webView, BRIDGE);
            } catch (RuntimeException ignored) {}
        }
    }

    void showPrompt(String url, String disposition, String mimeType, long length) {
        Context context = webView.getContext();
        if (closed) return;
        if (!supported) {
            notifyUnavailable(context, "This WebView does not support secure blob downloads. Update Android System WebView.");
            return;
        }
        if (!isCurrent(document) || !url.startsWith("blob:") || url.length() > 8192
                || !document.origin.equals(BlobDocument.webOrigin(url.substring(5)))) {
            notifyUnavailable(context, "This blob is not available in the current main page.");
            return;
        }
        if (length > BlobIntake.MAX_BYTES) {
            notifyUnavailable(context, "This blob exceeds the 512 MiB download limit.");
            return;
        }
        if (prompt != null || active != null) {
            notifyUnavailable(context, "Finish or cancel this tab's other blob download first.");
            return;
        }
        Prompt offer = new Prompt(document, url, Math.max(-1, length));
        offer.dialog = DownloadPromptDialog.showBlob(context, url, disposition, mimeType, offer.length,
                item -> confirm(offer, item));
        prompt = offer;
        offer.dialog.setOnDismissListener(dialog -> {
            if (prompt == offer) prompt = null;
        });
    }

    private void confirm(Prompt offer, DownloadItem item) throws IOException {
        if (prompt != offer || active != null || !isCurrent(offer.document) || !offer.url.equals(item.getUrl())) {
            throw new IOException(BlobIntake.SOURCE_GONE);
        }
        if (!canRetainSource.getAsBoolean()) {
            throw new IOException("Confirm from the source tab. If other tabs have page downloads pending, finish or cancel one first.");
        }
        Request request = new Request(offer, DownloadEngine.getInstance(webView.getContext()));
        active = request;
        try {
            request.engine.enqueueBlob(item, request.intake);
        } catch (IOException | RuntimeException e) {
            active = null;
            request.intake.close();
            throw e;
        }
        prompt = null;
        ui.postDelayed(request.admissionTimeout, BlobIntake.QUEUED_TIMEOUT_MS);
    }

    private void onMessage(WebView view, WebMessageCompat message, Uri sourceOrigin, boolean isMainFrame,
                           JavaScriptReplyProxy reply) {
        BlobDocument<JavaScriptReplyProxy> current = document;
        if (current == null || !current.acceptsFrame(view, sourceOrigin.toString(), isMainFrame)
                || !current.origin.equals(BlobDocument.webOrigin(webView.getUrl()))
                || message.getType() != WebMessageCompat.TYPE_STRING) return;
        String data = message.getData();
        if (data == null || current.bind(data, reply) || !current.owns(reply)) return;
        if (("gone:" + current.challenge).equals(data)) {
            onNavigation();
            return;
        }
        Request request = active;
        if (request != null && request.document == current && request.started && data.startsWith(request.prefix)) {
            int offset = request.prefix.length();
            // Don't copy or decode an oversize payload, even from a bound document.
            request.intake.receive(data.length() - offset > BlobIntake.MAX_FRAME_CHARS ? null : data.substring(offset));
        }
    }

    private boolean isCurrent(BlobDocument<JavaScriptReplyProxy> candidate) {
        return !closed && candidate != null && document == candidate && candidate.endpoint() != null
                && candidate.origin.equals(BlobDocument.webOrigin(webView.getUrl()));
    }

    private static void notifyUnavailable(Context context, String reason) {
        Toast.makeText(context, reason, Toast.LENGTH_LONG).show();
    }

    private static String readScript(Context context) throws IOException {
        if (script == null) {
            try (InputStream in = context.getAssets().open("downloader/blob_intake.js");
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = in.read(buffer)) != -1; ) out.write(buffer, 0, count);
                script = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            }
        }
        return script;
    }

    private static final class Prompt {
        final BlobDocument<JavaScriptReplyProxy> document;
        final String url;
        final long length;
        AlertDialog dialog;

        Prompt(BlobDocument<JavaScriptReplyProxy> document, String url, long length) {
            this.document = document;
            this.url = url;
            this.length = length;
        }
    }

    private final class Request implements BlobIntake.Page {
        final BlobDocument<JavaScriptReplyProxy> document;
        final DownloadEngine engine;
        final BlobIntake intake;
        final String url;
        final String token = UUID.randomUUID().toString();
        final String prefix = token + ":";
        final Runnable admissionTimeout = this::expire;
        boolean started; // UI thread only: a start command was sent to this document

        Request(Prompt offer, DownloadEngine engine) {
            this.document = offer.document;
            this.engine = engine;
            this.url = offer.url;
            this.intake = new BlobIntake(offer.length, this);
        }

        private void expire() {
            intake.abort("The blob expired while waiting for the download queue.");
        }

        @Override public void start() {
            ui.post(() -> {
                ui.removeCallbacks(admissionTimeout);
                if (!intake.isActive()) return;
                if (active != this || !isCurrent(document)) {
                    intake.abort(PAGE_LEFT);
                    return;
                }
                started = true;
                send("start", ",\"url\":" + JSONObject.quote(url) + ",\"chunkBytes\":" + BlobIntake.MAX_CHUNK_BYTES
                        + ",\"maxBytes\":" + BlobIntake.MAX_BYTES);
            });
        }

        @Override public void acknowledge() {
            ui.post(() -> {
                if (intake.isActive() && active == this && isCurrent(document)) send("ack", "");
            });
        }

        @Override public void stop() {
            ui.post(() -> {
                ui.removeCallbacks(admissionTimeout);
                if (active == this) {
                    active = null;
                    sourceReleased.run();
                }
                if (started && !closed && !rendererGone) send("stop", ""); // never call a destroyed view's endpoint
                engine.reconcile(); // expire a bound row still waiting for conditions or a slot
            });
        }

        private void send(String type, String arguments) {
            try {
                document.endpoint().postMessage("{\"type\":\"" + type + "\",\"token\":\"" + token + "\"" + arguments + "}");
            } catch (RuntimeException e) {
                intake.abort(PAGE_LEFT);
            }
        }
    }
}

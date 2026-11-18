package com.privacy.browser;

import com.privacy.browser.media.MediaSniffer;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** UI-thread navigation state. A Page identity may be captured by request/worker threads. */
final class TabNavigation<V> {
    static final class Page {
        final String key = UUID.randomUUID().toString();
        final MediaSniffer media = new MediaSniffer();
    }

    private final String initialTarget;
    private volatile V view; // request callbacks compare identity, never operate on the view off-main
    private volatile Page page;
    private String committedUrl;
    private String pendingUrl;
    private final Set<String> superseded = new LinkedHashSet<>();
    private boolean waiting;
    private boolean ready;
    private boolean rendererLost;
    private boolean finished;
    private boolean contentVisible;
    private int progress = 100;

    TabNavigation(V view, String initialTarget) {
        this(view, initialTarget, false);
    }

    TabNavigation(V view, String initialTarget, boolean rendererLost) {
        this.view = view;
        this.initialTarget = initialTarget;
        this.rendererLost = rendererLost;
    }

    V view() { return view; }
    boolean owns(V candidate) { return candidate != null && view == candidate; }
    Page page() { return page; }
    String url() { return committedUrl; }
    String initialTarget() { return initialTarget; }
    boolean rendererLost() { return rendererLost; }
    boolean ready() { return ready; }
    boolean finished() { return finished; }
    String target() { return waiting ? pendingUrl : committedUrl; }
    boolean canPreview() { return contentVisible && finished; }

    boolean markVisible(V candidate, String nativeUrl) {
        if (!current(candidate, page, nativeUrl)) return false;
        contentVisible = true;
        return true;
    }
    int progress() { return progress; }
    // A slow subresource needn't delay a checkpoint once the native current entry matches.
    boolean canSaveHistory() { return ready && !waiting; }

    void seedRestoredUrl(String url) {
        // A disk recovery point is not a live document and cannot authorize anything.
        committedUrl = url;
    }

    void replaceView(V next) {
        if (view != null) throw new IllegalStateException("Detach the old WebView first");
        view = Objects.requireNonNull(next);
        rendererLost = false;
    }

    boolean start(V candidate, String url) {
        if (!owns(candidate)) return false;
        if (waiting && pendingUrl != null && !pendingUrl.equals(url)) {
            superseded.add(pendingUrl);
            if (superseded.size() > 8) superseded.remove(superseded.iterator().next());
        }
        superseded.remove(url);
        replacePage(new Page());
        pendingUrl = url;
        waiting = true;
        ready = false;
        finished = false;
        contentVisible = false;
        progress = 0;
        return true;
    }

    /** Called only from native main-frame commit/history events, never from page messages. */
    boolean commit(V candidate, String url, String nativeUrl) {
        if (!owns(candidate) || url == null || url.isEmpty() || !url.equals(nativeUrl)) return false;
        // An old document can still be reported while its replacement is loading.
        if (waiting && !url.equals(pendingUrl) && (superseded.contains(url) || (pendingUrl != null && url.equals(committedUrl)))) return false;
        if (ready && url.equals(committedUrl)) return false; // duplicate commit/visited-history event
        if (ready || page == null) replacePage(new Page()); // initial or same-document native history change
        committedUrl = url;
        pendingUrl = null;
        superseded.clear();
        waiting = false;
        ready = true;
        return true;
    }

    boolean current(V candidate, Page expected, String nativeUrl) {
        return owns(candidate) && ready && expected != null && page == expected && committedUrl.equals(nativeUrl);
    }

    boolean updateProgress(V candidate, String nativeUrl, int nativeProgress) {
        if (!owns(candidate) || !(waiting || current(candidate, page, nativeUrl))) return false;
        if (waiting && pendingUrl != null && nativeUrl != null
                && !pendingUrl.equals(nativeUrl) && nativeUrl.equals(committedUrl)) return false;
        progress = Math.max(progress, Math.max(0, Math.min(100, nativeProgress)));
        return true;
    }

    boolean finish(V candidate, String url, String nativeUrl) {
        if (!Objects.equals(url, committedUrl) || !current(candidate, page, nativeUrl) || finished) return false;
        finished = true;
        progress = 100;
        return true;
    }

    boolean canFail(String url, String nativeUrl) {
        return url != null && (waiting && (url.equals(pendingUrl) || url.equals(nativeUrl))
                || ready && url.equals(committedUrl) && url.equals(nativeUrl));
    }

    void abort(V candidate) {
        if (!owns(candidate)) return;
        invalidate();
    }

    boolean detach(V candidate, boolean lost) {
        if (!owns(candidate)) return false;
        view = null;
        rendererLost = lost;
        invalidate();
        return true;
    }

    private void replacePage(Page next) {
        Page previous = page;
        page = next;
        if (previous != null) previous.media.close();
    }

    private void invalidate() {
        replacePage(null);
        pendingUrl = null;
        superseded.clear();
        waiting = false;
        ready = false;
        finished = false;
        contentVisible = false;
        progress = 100;
    }
}

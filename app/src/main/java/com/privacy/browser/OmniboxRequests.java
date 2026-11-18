package com.privacy.browser;

import com.privacy.browser.search.OmniboxSuggestion;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** UI-thread request owner. There is one debounce timer and at most one lookup in flight. */
final class OmniboxRequests {
    static final long QUIET_MS = 100;
    static final int MAX_TEXT_CHARS = 8192;

    interface Ui {
        void later(Runnable action, long delayMs);
        void remove(Runnable action);
        void post(Runnable action); // callable from the worker
    }

    interface Lookup {
        List<OmniboxSuggestion> find(String query, boolean includeHistory, BooleanSupplier canceled);
    }

    static final class Request {
        final String tabId;
        final boolean incognito;
        final String text, query;

        Request(String tabId, boolean incognito, String text, String query) {
            this.tabId = tabId;
            this.incognito = incognito;
            this.text = text;
            this.query = query;
        }

        boolean matches(String activeId, boolean privateMode, CharSequence input) {
            return tabId.equals(activeId) && incognito == privateMode && input != null && text.contentEquals(input);
        }
    }

    private static final class Work {
        final Request request;
        volatile boolean canceled;

        Work(Request request) { this.request = request; }
    }

    private final Ui ui;
    private final Executor worker;
    private final Lookup lookup;
    private final Predicate<Request> eligible;
    private final BiConsumer<Request, List<OmniboxSuggestion>> publish;
    private final Runnable quiet = () -> {
        due = true;
        startIfDue();
    };
    private Request current;
    private Work running;
    private boolean due, closed;

    OmniboxRequests(Ui ui, Executor worker, Lookup lookup, Predicate<Request> eligible,
                    BiConsumer<Request, List<OmniboxSuggestion>> publish) {
        this.ui = ui;
        this.worker = worker;
        this.lookup = lookup;
        this.eligible = eligible;
        this.publish = publish;
    }

    Request submit(String tabId, boolean incognito, CharSequence input) {
        invalidate();
        if (closed || tabId == null || input == null || input.length() > MAX_TEXT_CHARS) return null;
        String text = input.toString();
        String query = text.trim();
        if (query.isEmpty()) return null;
        current = new Request(tabId, incognito, text, query);
        ui.later(quiet, QUIET_MS);
        return current;
    }

    boolean isCurrent(Request request) {
        return !closed && request != null && request == current && eligible.test(request);
    }

    void invalidate() {
        current = null;
        due = false;
        ui.remove(quiet);
        // No helper monitor, Future.get(), or native DB operation on the editing thread.
        if (running != null) running.canceled = true;
    }

    void close() {
        closed = true;
        invalidate();
    }

    private void startIfDue() {
        if (!due || running != null) return;
        due = false;
        if (!isCurrent(current)) return;
        Work job = new Work(current);
        running = job;
        worker.execute(() -> {
            List<OmniboxSuggestion> rows = Collections.emptyList();
            try {
                if (!job.canceled) rows = lookup.find(job.request.query, !job.request.incognito, () -> job.canceled);
            } finally {
                List<OmniboxSuggestion> result = rows;
                ui.post(() -> completed(job, result));
            }
        });
    }

    private void completed(Work job, List<OmniboxSuggestion> rows) {
        running = null;
        if (!job.canceled && isCurrent(job.request)) publish.accept(job.request, rows);
        // Edits while busy replaced the pending request; never queue a lookup for each edit.
        startIfDue();
    }
}

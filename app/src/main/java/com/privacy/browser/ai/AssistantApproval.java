package com.privacy.browser.ai;

import java.util.Objects;

/** In-memory one-shot review; a transcript, intent, alarm and recipe are never authority. */
public final class AssistantApproval {
    static final long LIFETIME_MS = 60_000L;
    enum Source { CHAT, MANUAL_RECIPE, EXTERNAL_INTENT, ALARM }

    public static final class Page {
        private final Object activity;
        private final String tab, document;
        private final long foregroundEpoch;
        public final String url;

        public Page(Object activity, String tab, String document, long foregroundEpoch, String url) {
            this.activity = Objects.requireNonNull(activity);
            this.tab = Objects.requireNonNull(tab);
            this.document = Objects.requireNonNull(document);
            this.foregroundEpoch = foregroundEpoch;
            this.url = Objects.requireNonNull(url);
        }

        public boolean matches(Page current) {
            return current != null && activity == current.activity && tab.equals(current.tab)
                    && document.equals(current.document) && foregroundEpoch == current.foregroundEpoch && url.equals(current.url);
        }
    }

    static final class Scope {
        final String session;
        final long run;
        final Page page;
        Scope(String session, long run, Page page) {
            this.session = session;
            this.run = run;
            this.page = page;
        }
        boolean matches(Scope current) {
            return session != null && page != null && current != null && session.equals(current.session)
                    && run == current.run && page.matches(current.page);
        }
    }

    private final AssistantAction action;
    private final Scope scope;
    private final long issued;
    private boolean used;

    static AssistantApproval request(AssistantAction action, Source source, Scope expected, Scope current, long elapsed) {
        if (source != Source.CHAT && (source != Source.MANUAL_RECIPE || !action.kind.recipe)) {
            throw new IllegalArgumentException("This source cannot run actions. Open the recipe for manual review.");
        }
        if (expected == null || !expected.matches(current)) {
            throw new IllegalArgumentException("The session or page changed. Request the action again in the current tab.");
        }
        return new AssistantApproval(action, expected, elapsed);
    }

    private AssistantApproval(AssistantAction action, Scope scope, long issued) {
        this.action = action;
        this.scope = scope;
        this.issued = issued;
    }

    boolean current(Scope current, long elapsed) {
        return elapsed >= issued && elapsed - issued < LIFETIME_MS && scope.matches(current);
    }

    synchronized AssistantAction consume(Scope current, long elapsed) {
        if (used) return null;
        used = true;
        return current(current, elapsed) ? action : null;
    }

    synchronized void deny() { used = true; }
}

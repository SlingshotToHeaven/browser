package com.privacy.browser;

import java.util.Objects;
import java.util.function.Consumer;

/** Resource callbacks on any thread share one trailing refresh for the visible document. */
final class ShieldRefresh {
    static final long WINDOW_MS = 120;

    interface Scheduler {
        void later(Runnable action, long delayMs);
        void cancel(Runnable action);
    }

    private final Scheduler main;
    private final Consumer<String> render;
    private final Runnable delivery = this::deliver;
    private volatile String visiblePage;
    private volatile boolean scheduled;

    ShieldRefresh(Scheduler main, Consumer<String> render) {
        this.main = main;
        this.render = render;
    }

    /** Owner thread: switch/navigation/pause revoke the old page before it can repaint chrome. */
    synchronized void show(String page) {
        if (Objects.equals(visiblePage, page)) return;
        visiblePage = page;
        if (scheduled) main.cancel(delivery);
        scheduled = false;
    }

    void changed(String page) {
        if (page == null || scheduled || !page.equals(visiblePage)) return;
        synchronized (this) {
            if (!page.equals(visiblePage) || scheduled) return;
            scheduled = true;
            main.later(delivery, WINDOW_MS);
        }
    }

    private void deliver() {
        String page;
        synchronized (this) {
            if (!scheduled) return;
            scheduled = false;
            page = visiblePage;
        }
        if (page != null) render.accept(page);
    }
}

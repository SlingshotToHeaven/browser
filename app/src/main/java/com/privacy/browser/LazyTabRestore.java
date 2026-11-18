package com.privacy.browser;

import java.util.function.Consumer;

/** Owner-thread gate for a single selected tab. A late read can never create a hidden WebView. */
final class LazyTabRestore<T> {
    // Read asynchronously; deliver completion back on the owner thread.
    interface Reader { void read(TabSession.Row row, Consumer<TabSession.StateRead> done); }
    interface Ready<T> { void accept(T tab, TabSession.Row row, TabSession.StateRead result); }

    private final Reader reader;
    private final Ready<T> ready;
    private T selected;
    private TabSession.Row requested;
    private Object ticket;

    LazyTabRestore(Reader reader, Ready<T> ready) {
        this.reader = reader;
        this.ready = ready;
    }

    void select(T tab, TabSession.Row row) {
        if (selected == tab && requested == row && ticket != null) return;
        cancel();
        if (row == null || row.rendererLost) return;
        selected = tab;
        requested = row;
        Object request = new Object();
        ticket = request;
        Consumer<TabSession.StateRead> done = result -> {
            if (ticket != request) return;
            cancel();
            if (result.status != TabSession.StateRead.Status.STALE) ready.accept(tab, row, result);
        };
        if (row.state == null) done.accept(TabSession.StateRead.URL_ONLY);
        else reader.read(row, done);
    }

    void cancel() {
        ticket = null;
        selected = null;
        requested = null;
    }
}

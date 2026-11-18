package com.privacy.browser;

/** One native copy in flight, one latest request. Native callbacks keep only an opaque ticket. */
final class TabPreviewRequests<T> {
    private T wanted;
    private Object ticket;
    private Object copying;

    T wanted() { return wanted; }

    void request(T request) {
        wanted = request;
        ticket = new Object();
    }

    void cancel() {
        wanted = null;
        ticket = null;
    }

    Object begin() {
        if (copying != null || ticket == null) return null;
        return copying = ticket;
    }

    boolean isCopying(Object request) { return request != null && copying == request; }

    T complete(Object request) {
        if (!isCopying(request)) return null;
        copying = null;
        if (ticket != request) return null;
        T result = wanted;
        cancel();
        return result;
    }
}

package com.privacy.browser;

/** Fail closed until an isolated, deletable provider profile has passed the device gate. */
final class PrivateTabPolicy {
    static final String MESSAGE = "Private tabs are unavailable until WebView profile isolation and deletion can be verified.";

    private PrivateTabPolicy() {}

    static void requireAvailable(boolean requested) {
        if (requested) throw new IllegalStateException(MESSAGE);
    }
}

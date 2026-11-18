package com.privacy.downloader.interceptor;

import org.junit.Test;

import static org.junit.Assert.*;

public class BlobDocumentTest {
    @Test public void onlyTheOriginatingViewAndMainFrameCanSupplyMessages() {
        Object tab = new Object();
        BlobDocument<Object> document = new BlobDocument<>(tab, "https://example.test");
        assertTrue(document.acceptsFrame(tab, "https://example.test:443/", true));
        assertFalse(document.acceptsFrame(new Object(), "https://example.test", true));
        assertFalse(document.acceptsFrame(tab, "https://example.test", false));
        assertFalse(document.acceptsFrame(tab, "https://not-example.test", true));
        assertFalse(document.acceptsFrame(tab, "https://example.test:444", true));
        assertFalse(document.acceptsFrame(tab, "http://example.test", true));
        assertFalse(document.acceptsFrame(tab, "null", true));
    }

    @Test public void sameUrlReloadNeedsANewChallengeAndPinsOneEndpoint() {
        Object tab = new Object(), stale = new Object(), fresh = new Object();
        BlobDocument<Object> initial = new BlobDocument<>(tab, "https://example.test");
        String initialHello = "ready:" + initial.challenge;
        assertTrue(initial.bind(initialHello, stale));
        assertFalse(initial.bind(initialHello, fresh));
        assertTrue(initial.owns(stale));
        assertFalse(initial.owns(fresh));

        BlobDocument<Object> reloaded = new BlobDocument<>(tab, initial.origin);
        assertFalse(reloaded.bind(initialHello, stale));
        assertFalse(reloaded.owns(stale));
        assertTrue(reloaded.bind("ready:" + reloaded.challenge, fresh));
        assertFalse(reloaded.owns(stale));
        assertSame(fresh, reloaded.endpoint());
    }

    @Test public void webOriginDoesNotTreatOpaqueDocumentsOrLookalikesAsSameOrigin() {
        assertEquals("https://example.test", BlobDocument.webOrigin("HTTPS://Example.Test:443/path?query#fragment"));
        assertEquals("http://[::1]:8080", BlobDocument.webOrigin("http://[::1]:8080/index"));
        assertEquals("https://other.test", BlobDocument.webOrigin("https://example.test@other.test/blob"));
        assertNull(BlobDocument.webOrigin("null/fixture"));
        assertNull(BlobDocument.webOrigin("file:///android_asset/home.html"));
        assertNull(BlobDocument.webOrigin("about:blank"));
        assertNull(BlobDocument.webOrigin("data:text/html,fixture"));
    }
}

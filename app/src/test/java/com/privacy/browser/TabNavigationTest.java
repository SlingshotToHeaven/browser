package com.privacy.browser;

import org.junit.Test;

import static org.junit.Assert.*;

public class TabNavigationTest {
    private static final String A = "https://a.example/one";
    private static final String B = "https://b.example/two";
    private static final String C = "https://c.example/three";

    @Test public void pendingAndRestoredUrlsAreNotLiveCredentials() {
        Object view = new Object();
        TabNavigation<Object> tab = new TabNavigation<>(view, B);
        assertNull(tab.url());
        tab.seedRestoredUrl(A);
        assertFalse(tab.ready());
        assertTrue(tab.start(view, B));
        TabNavigation.Page document = tab.page();
        assertEquals(A, tab.url());
        assertFalse(tab.canSaveHistory());
        assertFalse(tab.current(view, document, A));
        assertFalse(tab.commit(view, A, A));
        assertFalse(tab.commit(view, B, A));
        assertTrue(tab.commit(view, B, B));
        assertTrue(tab.current(view, document, B));
        assertFalse(tab.commit(view, B, B));
    }

    @Test public void rapidReplacementAndLateFinishDoNotRewindChrome() {
        Object view = new Object();
        TabNavigation<Object> tab = new TabNavigation<>(view, A);
        assertTrue(tab.commit(view, A, A));
        tab.start(view, B);
        tab.start(view, C);
        assertFalse(tab.finish(view, A, A));
        assertFalse(tab.commit(view, B, B));
        assertFalse(tab.commit(view, A, A));
        assertTrue(tab.commit(view, C, C));
        assertFalse(tab.finish(view, B, C));
        assertTrue(tab.finish(view, C, C));
        assertFalse(tab.finish(view, C, C));
        assertEquals(C, tab.url());
    }

    @Test public void nativeSameDocumentChangeRotatesCallbackScopeAndDoesNotStartLoad() {
        Object view = new Object();
        TabNavigation<Object> tab = new TabNavigation<>(view, A);
        tab.start(view, A);
        tab.commit(view, A, A);
        tab.finish(view, A, A);
        TabNavigation.Page old = tab.page();
        String hash = A + "#chapter";
        assertTrue(tab.commit(view, hash, hash));
        assertEquals(hash, tab.url());
        assertEquals(100, tab.progress());
        assertFalse(tab.current(view, old, hash));
        assertTrue(tab.current(view, tab.page(), hash));
        assertTrue(tab.canSaveHistory());
    }

    @Test public void mediaRegistryRetiresOnSpaCommitAndViewDetach() {
        Object view = new Object();
        TabNavigation<Object> tab = new TabNavigation<>(view, A);
        assertTrue(tab.commit(view, A, A));
        TabNavigation.Page before = tab.page();
        before.media.observe(null);
        before.media.elementHint("https://cdn.example/clip.mp4", "video", "Before");
        assertEquals(1, before.media.count());
        assertTrue(tab.commit(view, A + "#next", A + "#next"));
        TabNavigation.Page after = tab.page();
        assertNotSame(before, after);
        assertFalse(before.media.isObserving());
        before.media.observe(null);
        before.media.requestHint("https://cdn.example/old.mp4", "/old.mp4", "Late request");
        assertEquals(0, before.media.count());
        after.media.observe(null);
        assertTrue(tab.detach(view, false));
        after.media.observe(null);
        assertFalse(after.media.isObserving());
        assertNull(tab.page());
    }

    @Test public void reloadAtSameUrlInvalidatesOldDocument() {
        Object view = new Object();
        TabNavigation<Object> tab = new TabNavigation<>(view, A);
        tab.commit(view, A, A);
        TabNavigation.Page old = tab.page();
        tab.start(view, A);
        assertFalse(tab.current(view, old, A));
        assertTrue(tab.commit(view, A, A));
        assertFalse(tab.current(view, old, A));
    }

    @Test public void previewWaitsForVisibilityAndFinishAndDoesNotSurviveADetachedView() {
        Object view = new Object();
        TabNavigation<Object> tab = new TabNavigation<>(view, A);
        tab.start(view, A);
        assertFalse(tab.markVisible(view, A));
        assertTrue(tab.commit(view, A, A));
        assertTrue(tab.finish(view, A, A));
        assertFalse(tab.canPreview());
        assertTrue(tab.markVisible(view, A));
        assertTrue(tab.canPreview());
        TabNavigation.Page old = tab.page();
        assertTrue(tab.detach(view, false));
        tab.seedRestoredUrl(A);
        Object replacement = new Object();
        tab.replaceView(replacement);
        assertFalse(tab.canPreview());
        assertFalse(tab.markVisible(view, A));
        assertFalse(tab.current(replacement, old, A));
        assertTrue(tab.commit(replacement, A, A));
        assertTrue(tab.markVisible(replacement, A));
        assertFalse(tab.canPreview());
        assertTrue(tab.finish(replacement, A, A));
        assertTrue(tab.canPreview());
    }

    @Test public void progressAndRendererLossAreScopedToEachView() {
        Object first = new Object(), second = new Object();
        TabNavigation<Object> a = new TabNavigation<>(first, A);
        TabNavigation<Object> b = new TabNavigation<>(second, B);
        a.commit(first, A, A);
        b.start(second, B);
        assertTrue(b.updateProgress(second, B, 35));
        assertFalse(a.updateProgress(second, B, 65));
        assertEquals(100, a.progress());
        assertEquals(35, b.progress());
        TabNavigation.Page old = a.page();
        assertTrue(a.detach(first, true));
        assertFalse(a.detach(first, true));
        assertTrue(a.rendererLost());
        assertTrue(b.owns(second));
        assertEquals(A, a.url());
        assertNull(a.view());
        Object replacement = new Object();
        a.replaceView(replacement);
        assertFalse(a.commit(first, A, A));
        assertFalse(a.current(replacement, old, A));
        assertTrue(a.commit(replacement, A, A));
        assertTrue(a.current(replacement, a.page(), A));
    }
}

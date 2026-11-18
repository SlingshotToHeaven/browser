package com.privacy.browser;

import com.privacy.browser.search.OmniboxSuggestion;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class OmniboxRequestsTest {
    @Test public void onlyLatestTextRunsAfterQuietAndNothingRunsOnTheEditor() {
        Harness h = new Harness();
        for (int i = 0; i < 50; i++) {
            h.type("prefix " + i);
            h.advance(OmniboxRequests.QUIET_MS - 1);
            assertTrue(h.worker.isEmpty());
        }
        h.advance(1);
        assertEquals(1, h.worker.size());
        assertTrue(h.lookups.isEmpty());
        h.completeLookup();
        assertEquals(Collections.singletonList("prefix 49"), h.delivered);
    }

    @Test public void busyReaderDrainsWhileOnlyTheLatestSettledEditWaits() {
        Harness h = new Harness();
        OmniboxRequests.Request old = h.type("same text");
        h.advance(OmniboxRequests.QUIET_MS);
        h.whileReading = canceled -> {
            for (int i = 0; i < 20; i++) {
                h.type("intermediate " + i);
                h.advance(OmniboxRequests.QUIET_MS);
                assertTrue(h.worker.isEmpty()); // no executor backlog, even for already settled edits
            }
            h.type("same text");
            h.advance(OmniboxRequests.QUIET_MS);
            assertTrue(canceled.getAsBoolean());
            assertFalse(h.requests.isCurrent(old)); // same input is not the same generation
        };
        h.completeLookup();
        assertTrue(h.delivered.isEmpty());
        assertEquals(1, h.worker.size());
        h.whileReading = null;
        h.completeLookup();
        assertEquals(2, h.lookups.size());
        assertEquals(Collections.singletonList("same text"), h.delivered);
    }

    @Test public void drainingAnOldReadDoesNotBypassTheNextDebounce() {
        Harness h = new Harness();
        h.type("old");
        h.advance(OmniboxRequests.QUIET_MS);
        h.worker.remove().run(); // query finished, UI completion has not run yet
        h.type("new");
        h.ui.remove().run();
        assertTrue(h.delivered.isEmpty());
        assertTrue(h.worker.isEmpty());
        h.advance(OmniboxRequests.QUIET_MS);
        h.completeLookup();
        assertEquals(Collections.singletonList("new"), h.delivered);
    }

    @Test public void staleScopesAreRejectedBeforeDispatchAndAgainAtDeliveryAndClick() {
        List<Consumer<Harness>> transitions = List.of(h -> h.input = "new text", h -> h.tab = "another tab",
                h -> h.privateMode = true, h -> h.focused = false, h -> h.resumed = false, h -> h.enabled = false);
        for (Consumer<Harness> transition : transitions) {
            Harness waiting = new Harness();
            waiting.type("old text");
            transition.accept(waiting);
            waiting.advance(OmniboxRequests.QUIET_MS);
            assertTrue(waiting.worker.isEmpty());

            Harness completing = new Harness();
            OmniboxRequests.Request old = completing.type("old text");
            completing.advance(OmniboxRequests.QUIET_MS);
            completing.worker.remove().run();
            transition.accept(completing);
            assertFalse(completing.requests.isCurrent(old));
            completing.ui.remove().run();
            assertTrue(completing.delivered.isEmpty());
        }
    }

    @Test public void privateSearchDoesNotRequestHistoryEvenWithIdenticalText() {
        Harness h = new Harness();
        h.type("term");
        h.advance(OmniboxRequests.QUIET_MS);
        h.privateMode = true;
        h.type("term");
        h.advance(OmniboxRequests.QUIET_MS);
        h.completeLookup(); // canceled before worker entry: no source queried at all
        assertTrue(h.lookups.isEmpty());
        h.completeLookup();
        assertEquals(Collections.singletonList(false), h.lookups);
        assertEquals(Collections.singletonList("term"), h.delivered);
    }

    @Test public void emptyOverlongDismissedAndClosedInputsCannotLeaveWorkQueued() {
        Harness h = new Harness();
        h.type("a");
        assertNull(h.type("  "));
        h.advance(OmniboxRequests.QUIET_MS);
        assertTrue(h.worker.isEmpty());
        assertNull(h.type("a".repeat(OmniboxRequests.MAX_TEXT_CHARS + 1)));
        h.type("next");
        h.requests.invalidate();
        h.advance(OmniboxRequests.QUIET_MS);
        assertTrue(h.worker.isEmpty());
        OmniboxRequests.Request beforeFinish = h.type("last");
        h.advance(OmniboxRequests.QUIET_MS);
        h.worker.remove().run();
        h.requests.close();
        h.ui.remove().run();
        assertNull(h.type("last"));
        assertFalse(h.requests.isCurrent(beforeFinish));
        assertTrue(h.delivered.isEmpty());
    }

    @Test public void likePatternIsBoundAsALiteralSubstring() {
        assertEquals("%50\\%\\_abc\\\\it's%", OmniboxStore.literalSubstring("50%_abc\\it's"));
    }

    private static final class Harness implements OmniboxRequests.Ui {
        final Queue<Runnable> ui = new ArrayDeque<>(), worker = new ArrayDeque<>();
        final List<String> delivered = new ArrayList<>();
        final List<Boolean> lookups = new ArrayList<>();
        Runnable timer;
        long now, deadline;
        String tab = "regular", input;
        boolean privateMode, focused = true, resumed = true, enabled = true;
        Consumer<BooleanSupplier> whileReading;
        final OmniboxRequests requests = new OmniboxRequests(this, worker::add, this::find,
                req -> focused && resumed && enabled && req.matches(tab, privateMode, input),
                (req, result) -> delivered.add(req.query));

        OmniboxRequests.Request type(String text) {
            input = text;
            return requests.submit(tab, privateMode, text);
        }

        List<OmniboxSuggestion> find(String query, boolean includeHistory, BooleanSupplier canceled) {
            lookups.add(includeHistory);
            if (whileReading != null) whileReading.accept(canceled);
            return Collections.emptyList();
        }

        @Override public void later(Runnable action, long delayMs) {
            assertNull(timer);
            timer = action;
            deadline = now + delayMs;
        }

        @Override public void remove(Runnable action) { if (timer == action) timer = null; }
        @Override public void post(Runnable action) { ui.add(action); }

        void advance(long millis) {
            now += millis;
            if (timer != null && now >= deadline) {
                Runnable action = timer;
                timer = null;
                action.run();
            }
        }

        void completeLookup() {
            worker.remove().run();
            ui.remove().run();
        }
    }
}

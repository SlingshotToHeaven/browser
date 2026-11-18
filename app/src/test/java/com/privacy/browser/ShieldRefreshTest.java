package com.privacy.browser;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ShieldRefreshTest {
    @Test public void onlyTheCurrentForegroundPagePostsAndABurstPostsOncePerWindow() {
        Fixture fixture = new Fixture();
        fixture.refresh.show("front");
        for (int i = 0; i < 500; i++) fixture.refresh.changed("back");
        assertEquals(0, fixture.posts);
        for (int i = 0; i < 500; i++) fixture.refresh.changed("front");
        fixture.refresh.show("front"); // duplicate chrome update doesn't postpone the trailing work
        assertEquals(1, fixture.posts);
        fixture.work.remove().run();
        fixture.refresh.changed("front");
        assertEquals(2, fixture.posts);
        fixture.work.remove().run();
        assertEquals(List.of("front", "front"), fixture.renders);
    }

    @Test public void navigationAndPauseRevokeThePendingDelivery() {
        Fixture fixture = new Fixture();
        fixture.refresh.show("old");
        fixture.refresh.changed("old");
        fixture.refresh.show("new");
        fixture.refresh.changed("old");
        assertTrue(fixture.work.isEmpty());
        fixture.refresh.changed("new");
        fixture.work.remove().run();
        fixture.refresh.changed("new");
        fixture.refresh.show(null);
        fixture.refresh.changed("new");
        assertTrue(fixture.work.isEmpty());
        assertEquals(List.of("new"), fixture.renders);
    }

    private static final class Fixture implements ShieldRefresh.Scheduler {
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        final List<String> renders = new ArrayList<>();
        final ShieldRefresh refresh = new ShieldRefresh(this, renders::add);
        int posts;
        @Override public void later(Runnable action, long delayMs) {
            assertEquals(120, delayMs);
            posts++;
            work.add(action);
        }
        @Override public void cancel(Runnable action) { work.remove(action); }
    }
}

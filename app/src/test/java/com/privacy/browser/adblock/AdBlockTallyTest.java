package com.privacy.browser.adblock;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AdBlockTallyTest {
    @Test public void burstKeepsImmediateTotalsButUsesOneTrailingWrite() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        List<Long> saves = new ArrayList<>();
        AdBlockTally tally = new AdBlockTally(9, (task, delay) -> {
            assertEquals(2000, delay);
            work.add(task);
        }, saves::add);
        for (int i = 0; i < 2000; i++) tally.block();
        assertEquals(2009, tally.blocked());
        assertEquals(1, work.size());
        assertTrue(saves.isEmpty());
        work.remove().run();
        assertEquals(List.of(2009L), saves);
        assertTrue(work.isEmpty());
    }

    @Test public void countsArrivingDuringASaveScheduleTheNextWindow() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        List<Long> saves = new ArrayList<>();
        AdBlockTally[] tally = new AdBlockTally[1];
        tally[0] = new AdBlockTally(0, (task, delay) -> work.add(task), value -> {
            saves.add(value);
            if (value == 1) for (int i = 0; i < 50; i++) tally[0].block();
        });
        tally[0].block();
        work.remove().run();
        assertEquals(1, work.size());
        work.remove().run();
        assertEquals(List.of(1L, 51L), saves);
        assertTrue(work.isEmpty());
    }
}

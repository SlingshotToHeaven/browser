package com.privacy.downloader.engine;

import org.junit.Test;

import java.time.Instant;
import java.time.ZonedDateTime;

import static org.junit.Assert.*;

public class DownloadScheduleTest {
    @Test public void noBoundaryWhenIdleDisabledOrWindowIsEmpty() {
        ZonedDateTime now = at("2026-11-17T08:55:00Z");
        assertNull(new DownloadSchedule(true, 540, 600).nextBoundary(now, false));
        assertNull(new DownloadSchedule(false, 540, 600).nextBoundary(now, true));
        DownloadSchedule empty = new DownloadSchedule(true, 540, 540);
        assertNull(empty.nextBoundary(now, true));
        assertFalse(empty.includes(at("2026-11-17T09:00:00Z")));
    }

    @Test public void boundaryIsPreciseAcrossDaytimeAndOvernight() {
        DownloadSchedule window = new DownloadSchedule(true, 540, 600);
        assertFalse(window.includes(at("2026-11-17T08:59:59.900Z")));
        assertTrue(window.includes(at("2026-11-17T09:00:00Z")));
        assertFalse(window.includes(at("2026-11-17T10:00:00Z")));
        assertNext(window, "2026-11-17T08:59:59.900Z", "2026-11-17T09:00:00Z");
        assertNext(window, "2026-11-17T09:00:00Z", "2026-11-17T10:00:00Z");
        assertNext(window, "2026-11-17T10:00:00Z", "2026-11-18T09:00:00Z");
        DownloadSchedule overnight = new DownloadSchedule(true, 22 * 60, 6 * 60);
        assertTrue(overnight.includes(at("2026-11-17T23:59:59Z")));
        assertNext(overnight, "2026-11-17T23:59:59Z", "2026-11-18T06:00:00Z");
        assertNext(overnight, "2026-11-18T06:00:00Z", "2026-11-18T22:00:00Z");
    }

    @Test public void springGapAndFallRepeatRecheckAtTheClockJumpAndBothValidEdges() {
        DownloadSchedule gap = new DownloadSchedule(true, 150, 240);
        assertNext(gap, "2026-03-08T01:55:00-05:00[America/New_York]", "2026-03-08T07:00:00Z");
        assertTrue(gap.includes(at("2026-03-08T03:00:00-04:00[America/New_York]")));
        DownloadSchedule repeat = new DownloadSchedule(true, 90, 120);
        assertNext(repeat, "2026-11-01T01:00:00-04:00[America/New_York]", "2026-11-01T05:30:00Z");
        assertNext(repeat, "2026-11-01T01:30:00-04:00[America/New_York]", "2026-11-01T06:00:00Z");
        assertFalse(repeat.includes(at("2026-11-01T01:00:00-05:00[America/New_York]")));
        assertNext(repeat, "2026-11-01T01:00:00-05:00[America/New_York]", "2026-11-01T06:30:00Z");
        assertTrue(repeat.includes(at("2026-11-01T01:30:00-05:00[America/New_York]")));
    }

    private static ZonedDateTime at(String value) { return ZonedDateTime.parse(value); }
    private static void assertNext(DownloadSchedule schedule, String from, String next) {
        assertEquals(Instant.parse(next), schedule.nextBoundary(at(from), true));
    }
}

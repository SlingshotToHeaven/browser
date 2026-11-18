package com.privacy.downloader.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;

/** Wall-clock windows, including overnight windows, skipped minutes and repeated minutes. */
final class DownloadSchedule {
    final boolean enabled;
    final int start;
    final int end;

    DownloadSchedule(boolean enabled, int start, int end) {
        this.enabled = enabled;
        this.start = start;
        this.end = end;
    }

    boolean includes(ZonedDateTime time) {
        return !enabled || QueuePolicy.withinWindow(time.getHour() * 60 + time.getMinute(), start, end);
    }

    Instant nextBoundary(ZonedDateTime time, boolean hasWork) {
        if (!hasWork || !enabled || start == end) return null;
        Instant now = time.toInstant();
        ZoneRules rules = time.getZone().getRules();
        ZoneOffsetTransition transition = rules.nextTransition(now);
        // A clock jump can cross a window edge even if that local minute doesn't exist.
        Instant next = transition == null ? null : transition.getInstant();
        LocalDate date = time.toLocalDate();
        for (int day = 0; day <= 1; day++) {
            for (int minute : new int[]{start, end}) {
                var edge = date.plusDays(day).atTime(LocalTime.of(minute / 60, minute % 60));
                for (ZoneOffset offset : rules.getValidOffsets(edge)) {
                    Instant candidate = edge.toInstant(offset);
                    if (candidate.isAfter(now) && (next == null || candidate.isBefore(next))) next = candidate;
                }
            }
        }
        return next;
    }
}

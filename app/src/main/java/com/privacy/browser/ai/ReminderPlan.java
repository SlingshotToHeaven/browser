package com.privacy.browser.ai;

import java.util.regex.Pattern;

/** Registration describes what was requested from Android, not a delivery guarantee. */
final class ReminderPlan {
    static final long MAX_FUTURE_MS = 365L * 24L * 60L * 60L * 1000L;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    enum Status { EXACT_REQUESTED, INEXACT_REQUESTED, UNAVAILABLE, READY_FOR_REVIEW, LEGACY_DISABLED }

    interface Alarms {
        boolean canSetExact();
        void setExact();
        void setInexact();
        void cancel();
    }

    static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }
    static boolean validNewTime(long trigger, long now) { return trigger > now && trigger - now <= MAX_FUTURE_MS; }

    static Status reconcile(Status status, long trigger, long now, Alarms alarms) {
        if (status == Status.LEGACY_DISABLED || status == Status.READY_FOR_REVIEW) return status;
        if (trigger <= now) {
            cancel(alarms);
            return Status.READY_FOR_REVIEW;
        }
        try {
            if (alarms.canSetExact()) {
                try { alarms.setExact(); return Status.EXACT_REQUESTED; }
                catch (SecurityException ignored) { /* Capability can change between the check and registration. */ }
            }
            alarms.setInexact();
            return Status.INEXACT_REQUESTED;
        } catch (RuntimeException refused) {
            cancel(alarms);
            return Status.UNAVAILABLE;
        }
    }

    static void cancel(Alarms alarms) {
        // Even if the platform is unavailable, a delivered alarm only consults the durable review record.
        try { alarms.cancel(); } catch (RuntimeException ignored) { }
    }

    static boolean canMarkDue(Status status, long trigger, long now) {
        return trigger <= now && (status == Status.EXACT_REQUESTED || status == Status.INEXACT_REQUESTED);
    }
}

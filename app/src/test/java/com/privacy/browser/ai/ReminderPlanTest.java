package com.privacy.browser.ai;

import org.junit.Test;

import static com.privacy.browser.ai.ReminderPlan.Status.*;
import static org.junit.Assert.*;

public class ReminderPlanTest {
    @Test public void missingAndRevokedExactCapabilityUseInexactRegistration() {
        Port port = new Port();
        assertEquals(INEXACT_REQUESTED, ReminderPlan.reconcile(UNAVAILABLE, 1100, 1000, port));
        assertEquals(0, port.exact);
        assertEquals(1, port.inexact);
        // Startup after permission was revoked and platform dropped the exact alarm.
        assertEquals(INEXACT_REQUESTED, ReminderPlan.reconcile(EXACT_REQUESTED, 1100, 1000, port));
        assertEquals(2, port.inexact);
    }

    @Test public void exactRegistrationCanBeRefusedAfterCapabilityCheck() {
        Port port = new Port();
        port.allowed = true;
        assertEquals(EXACT_REQUESTED, ReminderPlan.reconcile(UNAVAILABLE, 1100, 1000, port));
        port.revoke = true;
        assertEquals(INEXACT_REQUESTED, ReminderPlan.reconcile(EXACT_REQUESTED, 1100, 1000, port));
        assertEquals(2, port.exact);
        assertEquals(1, port.inexact);
        port.unavailable = true;
        assertEquals(UNAVAILABLE, ReminderPlan.reconcile(INEXACT_REQUESTED, 1100, 1000, port));
        assertEquals(1, port.canceled);
    }

    @Test public void overdueAndDuplicateAlarmDeliveriesCannotDispatchRecipes() {
        Port port = new Port();
        assertEquals(READY_FOR_REVIEW, ReminderPlan.reconcile(EXACT_REQUESTED, 1100, 1200, port));
        assertEquals(1, port.canceled);
        assertFalse(ReminderPlan.canMarkDue(EXACT_REQUESTED, 1100, 1000));
        assertTrue(ReminderPlan.canMarkDue(INEXACT_REQUESTED, 1100, 1200));
        assertFalse(ReminderPlan.canMarkDue(READY_FOR_REVIEW, 1100, 1200));
        assertFalse(ReminderPlan.canMarkDue(UNAVAILABLE, 1100, 1200));
        assertEquals(LEGACY_DISABLED, ReminderPlan.reconcile(LEGACY_DISABLED, 1100, 1000, port));
        assertEquals(0, port.exact + port.inexact);
        ReminderPlan.cancel(port);
        assertEquals(2, port.canceled);
    }

    private static final class Port implements ReminderPlan.Alarms {
        boolean allowed, revoke, unavailable;
        int exact, inexact, canceled;
        public boolean canSetExact() { return allowed; }
        public void setExact() { exact++; if (revoke) throw new SecurityException(); }
        public void setInexact() { inexact++; if (unavailable) throw new IllegalStateException(); }
        public void cancel() { canceled++; }
    }
}

package com.newtube.mobile.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The quiet update check's time rule (AppUpdates.isAutoCheckDue). */
public class AutoUpdateCheckTest {
    private static final long MIN = 60_000L;
    private static final long NOW = 1_790_000_000_000L; // wall clock, 2026
    private static final long UP = 5_000_000L; // elapsedRealtime

    @Test
    public void neverCheckedIsDue() {
        assertTrue(AppUpdates.isAutoCheckDue(NOW, 0, UP, 0));
    }

    @Test
    public void anAnswerWithinTheHourIsNotDue() {
        assertFalse(AppUpdates.isAutoCheckDue(NOW, NOW - 59 * MIN, UP, 0));
    }

    @Test
    public void anAnswerOverAnHourOldIsDue() {
        // The shared checker's 12 h interval used to hide a release published in between.
        assertTrue(AppUpdates.isAutoCheckDue(NOW, NOW - 61 * MIN, UP, 0));
    }

    @Test
    public void anAnswerFromTheFutureIsDue() {
        // The clock was set back after the last check: don't wait for the clock to catch up.
        assertTrue(AppUpdates.isAutoCheckDue(NOW, NOW + 3 * 60 * MIN, UP, 0));
    }

    @Test
    public void anUnansweredCheckIsNotRetriedWithinFiveMinutes() {
        assertFalse(AppUpdates.isAutoCheckDue(NOW, NOW - 3 * 60 * MIN, UP, UP - 4 * MIN));
        assertTrue(AppUpdates.isAutoCheckDue(NOW, NOW - 3 * 60 * MIN, UP, UP - 5 * MIN));
    }
}

package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UnplayableAutoplayBudgetTest {
    @Test
    public void aLoneUnplayableVideoStillSkipsOnce() {
        UnplayableAutoplayBudget budget = new UnplayableAutoplayBudget();
        assertTrue(budget.onUnplayable());
        budget.onPlayable();
        assertTrue("a video played in between: a new streak", budget.onUnplayable());
    }

    @Test
    public void aSecondUnplayableVideoInARowEndsTheChain() {
        UnplayableAutoplayBudget budget = new UnplayableAutoplayBudget();
        budget.onOpen(false, 0);
        assertTrue(budget.onUnplayable());   // the video the user opened
        budget.onAdvance(0);
        budget.onOpen(false, 0);             // the one autoplay moved to
        assertFalse(budget.onUnplayable());  // stop here
        assertFalse(budget.onUnplayable());  // and it stays stopped until something plays
        assertEquals(UnplayableAutoplayBudget.MAX_SKIPS, budget.skips());
    }

    @Test
    public void aVideoTheUserOpensStartsOver() {
        UnplayableAutoplayBudget budget = new UnplayableAutoplayBudget();
        assertTrue(budget.onUnplayable());
        assertFalse(budget.onUnplayable());
        budget.onOpen(false, 0);
        assertTrue(budget.onUnplayable());
    }

    @Test
    public void theAppsOwnReloadDoesNotRefillTheBudget() {
        UnplayableAutoplayBudget budget = new UnplayableAutoplayBudget();
        assertTrue(budget.onUnplayable());
        budget.onOpen(true, 0); // reload of the same video
        assertFalse(budget.onUnplayable());
    }

    @Test
    public void anAdvanceThatLandsLateIsStillPartOfTheChain() {
        UnplayableAutoplayBudget budget = new UnplayableAutoplayBudget();
        assertTrue(budget.onUnplayable());
        budget.onAdvance(0);
        budget.onOpen(true, 0);  // a reload before the resolved playlist item opens
        budget.onOpen(false, 0); // the advance lands
        assertFalse(budget.onUnplayable());
        budget.onOpen(false, 0); // then the user picks something: a new start
        assertTrue(budget.onUnplayable());
    }

    @Test
    public void aPickWhileTheAdvanceIsPendingIsTheUsersOwn() {
        UnplayableAutoplayBudget budget = new UnplayableAutoplayBudget();
        assertTrue(budget.onUnplayable());
        budget.onAdvance(0);  // waiting for the suggestions
        budget.onUserPick();  // the user taps a related video first
        budget.onOpen(false, 1_000);
        assertTrue("the user's pick skips once if unplayable", budget.onUnplayable());
    }

    @Test
    public void anAdvanceThatNeverLandedIsForgotten() {
        UnplayableAutoplayBudget budget = new UnplayableAutoplayBudget();
        assertTrue(budget.onUnplayable());
        budget.onAdvance(0);
        budget.onOpen(false, UnplayableAutoplayBudget.ADVANCE_LANDS_WITHIN_MS + 1);
        assertTrue(budget.onUnplayable());
    }
}

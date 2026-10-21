package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** NEWTUBE(shorts): a list thinned out by the Shorts filter fetches more on its own, within a cap. */
public class FilteredPageTopUpTest {
    @Test
    public void aThinnedOutListFetchesUntilItScrolls() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();

        topUp.onPageLanded(134); // "shorts funny": 1 video kept of 135
        assertTrue(topUp.take(1, true));
        topUp.onPageLanded(3);
        assertTrue(topUp.take(4, true));
        topUp.onPageLanded(0);
        assertFalse("enough to scroll: the scroll listener takes over",
                topUp.take(FilteredPageTopUp.ENOUGH, true));
    }

    @Test
    public void aListOfNothingButShortsStopsAtTheCap() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();

        int pages = 0;
        topUp.onPageLanded(20);
        while (topUp.take(0, true) && pages < 100) {
            pages++;
            topUp.onPageLanded(20);
        }
        assertEquals(FilteredPageTopUp.MAX_PAGES, pages);
    }

    @Test
    public void onlyALandedPageTopsUp() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();
        topUp.onPageLanded(10);
        assertTrue(topUp.take(0, true));

        // The spinner goes off again: the request failed, or a new query cancelled it. No page
        // landed since, so nothing more is asked for.
        assertFalse(topUp.take(0, true));
    }

    @Test
    public void aTabShownAgainMayTopItselfUp() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();
        topUp.onPageLanded(8);
        assertTrue(topUp.take(2, true));
        topUp.onPageLanded(8);
        assertFalse("that page ended the list for now", topUp.take(2, false));

        // Later its tab is picked again: one more try, now that the user is looking at it.
        topUp.onShown();
        topUp.onUserAction();
        assertTrue(topUp.take(2, true));

        FilteredPageTopUp plain = new FilteredPageTopUp();
        plain.onPageLanded(0);
        plain.take(2, true);
        plain.onShown();
        assertFalse("nothing dropped: a short tab is just short", plain.take(2, true));
    }

    @Test
    public void theEndOfTheListIsTheEnd() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();
        topUp.onPageLanded(3);

        assertFalse(topUp.take(2, false));
        assertEquals(0, topUp.pages());
    }

    @Test
    public void aListThatIsShortOnItsOwnIsLeftAlone() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();
        topUp.onPageLanded(0);

        assertFalse(topUp.take(3, true));
    }

    @Test
    public void theUserAskingForMoreRefillsTheBudget() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();
        spend(topUp);
        topUp.onPageLanded(1);
        assertFalse(topUp.take(0, true));

        topUp.onUserAction();
        topUp.onPageLanded(1);
        assertTrue(topUp.take(0, true));
    }

    @Test
    public void layoutPassesShareTheBudget() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();
        spend(topUp);

        // The layout pass after the fifth top-up must not fetch a sixth page.
        assertFalse(topUp.takeAutomatic(true));

        topUp.onUserAction(); // a real scroll
        assertTrue(topUp.takeAutomatic(true));
        assertFalse("the list's end", topUp.takeAutomatic(false));
    }

    @Test
    public void layoutPassesAreBoundedEvenWithoutDroppedShorts() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();
        int pages = 0;
        while (topUp.takeAutomatic(true) && pages < 100) {
            pages++;
        }
        assertEquals(FilteredPageTopUp.MAX_PAGES, pages);
    }

    @Test
    public void aNewListForgetsTheOldOne() {
        FilteredPageTopUp topUp = new FilteredPageTopUp();
        topUp.onPageLanded(5);
        topUp.take(0, true);
        topUp.onPageLanded(5);

        topUp.clear();
        assertFalse("nothing landed in the new list yet", topUp.take(0, true));
        assertEquals(0, topUp.pages());
    }

    @Test
    public void lateGroupsOfAnOldListAreRejected() {
        FilteredPageTopUp.Generations<Object> generations = new FilteredPageTopUp.Generations<>();
        Object oldMain = new Object();
        assertTrue(generations.accept(oldMain));
        assertTrue("its own next page", generations.accept(oldMain));

        generations.next(); // a new query
        Object newMain = new Object();
        assertTrue(generations.accept(newMain));
        assertFalse("the old query's page landing late", generations.accept(oldMain));
        assertTrue(generations.accept(newMain));
    }

    private static void spend(FilteredPageTopUp topUp) {
        topUp.onPageLanded(1);
        while (topUp.take(0, true)) {
            topUp.onPageLanded(1);
        }
    }
}

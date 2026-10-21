package com.newtube.mobile.ui.common;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * NEWTUBE(shorts): a paged list that {@link ShortsFilter} thinned out can end up too short to
 * scroll - "shorts funny" kept 1 video of 135 - and a list that cannot scroll never asks for its
 * next page. So after a page lands, the list asks for the next one itself while it keeps fewer
 * than {@link #ENOUGH} items, until the list ends or {@link #MAX_PAGES} pages have been fetched
 * without the user (a new list, a real scroll to the end or a tab pick refills the budget): a query
 * that returns nothing but Shorts ends honestly instead of paging forever.
 *
 * <ul>
 *   <li>Only a list that actually dropped Shorts tops itself up: one that is short on its own is
 *       left as it always was.</li>
 *   <li>Only right after a page has landed ({@link #onPageLanded}), once per page: the spinner also
 *       goes off when a load is cancelled (a new query, the screen going away) or fails, and
 *       neither may start another request.</li>
 *   <li>A layout pass that brings the list's end into view is not the user: those pages come out
 *       of the same budget ({@link #takeAutomatic}).</li>
 * </ul>
 */
public final class FilteredPageTopUp {
    /** Enough kept items to scroll a phone-width grid. */
    public static final int ENOUGH = 10;
    /** Pages fetched without the user per user action. */
    public static final int MAX_PAGES = 5;

    private int mPages;
    private boolean mDropped;
    private boolean mLanded;

    /** A new list: nothing dropped or landed yet, a fresh budget. */
    public void clear() {
        mPages = 0;
        mDropped = false;
        mLanded = false;
    }

    /** The user asked for more (scrolled to the end, picked a tab): a fresh budget. */
    public void onUserAction() {
        mPages = 0;
    }

    /** A page of this list has landed; {@code dropped} of its items were Shorts. */
    public void onPageLanded(int dropped) {
        mLanded = true;
        if (dropped > 0) {
            mDropped = true;
        }
    }

    /**
     * The list is on screen again (its tab was picked): its last page counts as just landed, so a
     * list left short while another one was showing may top itself up now.
     */
    public void onShown() {
        if (mDropped) {
            mLanded = true;
        }
    }

    /**
     * Called when loading stops: whether to fetch one more page now, for a list that keeps
     * {@code kept} items and has a next page when {@code hasMore}. Answers yes at most once per
     * landed page; a yes is counted against the budget.
     */
    public boolean take(int kept, boolean hasMore) {
        boolean landed = mLanded;
        mLanded = false;
        if (!landed || !mDropped || kept >= ENOUGH || !hasMore || mPages >= MAX_PAGES) {
            return false;
        }
        mPages++;
        return true;
    }

    /**
     * Whether a page the user did not scroll for - a layout pass brought the list's end into view -
     * may be fetched now. Shares the budget with {@link #take}; a yes is counted.
     */
    public boolean takeAutomatic(boolean hasMore) {
        if (!hasMore || mPages >= MAX_PAGES) {
            return false;
        }
        mPages++;
        return true;
    }

    /** Pages fetched without the user since the last user action. */
    public int pages() {
        return mPages;
    }

    /**
     * Which list a result group belongs to. A continuation arrives as the same group object that
     * first delivered its list, so a group first seen before the current list began is a late page
     * of an old list (an old query's request still in flight) and must not land in the new one.
     */
    public static final class Generations<G> {
        private final Map<G, Integer> mSeen = new WeakHashMap<>();
        private int mGeneration;

        /** A new list begins; groups seen so far belong to the old ones. */
        public void next() {
            mGeneration++;
        }

        /** Whether {@code group} belongs to the current list (a group never seen before does). */
        public boolean accept(G group) {
            Integer seen = mSeen.get(group);
            if (seen == null) {
                mSeen.put(group, mGeneration);
                return true;
            }
            return seen == mGeneration;
        }
    }
}

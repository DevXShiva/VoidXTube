package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * NEWTUBE(shelf-tail): what a row section (Home, Music, News...) loads once its section list is
 * done - the next page of its shelves, in turn.
 *
 * <p>The phone flattens a row section's shelves into one grid. A TV section list ends early: signed-in
 * Home after about seven pages, signed-out Home after ONE merged page of sixteen topic shelves. Past
 * that, scroll-end used to continue only the shelf of the LAST card: one shelf, a few pages, and then
 * the feed simply stopped (124 cards signed out on 2026-09-30) while every other shelf still had pages
 * of its own - a signed-out News shelf alone runs past 80 cards. Nothing else would ever ask again:
 * with no new card at the bottom the grid cannot scroll, and without a scroll the view never reports
 * the end.</p>
 *
 * <p>Now every shelf that still has a continuation waits in a queue, and each time the grid runs
 * short the head shelf fetches its next page and, if it has more, goes to the back: the shelves take
 * turns, so the feed keeps its mix instead of draining one topic. One page is in flight at a time.
 * The grid reports its size with every request, which is how a page that added no card is noticed
 * (all Shorts, channels or duplicates - the grid filters those out): its shelf leaves the rotation,
 * and after {@link #MAX_EMPTY_PAGES} such pages in a row the tail stops asking until a page adds
 * cards again, so a run of useless pages can never become a request loop.</p>
 *
 * <p>A grid section (Subscriptions, History...) is one group: its tail holds just that group, which
 * never leaves the rotation - a page of nothing but Shorts is simply followed by the next page, up
 * to the same {@link #MAX_EMPTY_PAGES} in a row.</p>
 *
 * <p>When every shelf is spent, the presenter fetches the section again and appends it (a "round":
 * what the reader used to do by hand, back at the top with a pull to refresh - signed in, each Home
 * fetch is a fresh mix). A round that added fewer than {@link #MIN_ROUND_CARDS} cards ends the feed,
 * so a feed that answers the same videos again (signed out) costs one extra round, not a loop.</p>
 *
 * <p>Main-thread only (the presenter's Rx callbacks and the view's scroll/update checks).</p>
 */
final class ShelfTail<G> {
    /** Consecutive pages that added no card before the tail gives up (each one is a request). */
    static final int MAX_EMPTY_PAGES = 10;
    /** Further rounds (the section fetched again and appended) at most, after the first load. */
    static final int MAX_ROUNDS = 20;
    /** A round - its section list plus its shelves' pages - that added fewer cards ends the feed. */
    static final int MIN_ROUND_CARDS = 12;
    /** Fetches in a row that returned nothing after which the next shelf waits for the grid to ask. */
    static final int MAX_CHAINED_NOTHING = 2;

    interface Keys<G> {
        /** Does the shelf have another page to fetch? */
        boolean hasNextPage(G shelf);
    }

    private final Keys<G> mKeys;
    /** Rows: a shelf whose page added no card leaves the rotation. A grid's one group never does. */
    private final boolean mDropEmptyShelves;
    private final ArrayDeque<G> mQueue = new ArrayDeque<>();
    /** The shelf whose page is being fetched, or null. */
    private G mInFlight;
    /** The last shelf whose page landed; judged by the grid size of the next request. */
    private G mLastLanded;
    /** Grid size when the last page was requested (-1: unknown). */
    private int mRequestGridSize = -1;
    private int mEmptyPages;
    /** onNothing() results in a row, reset by a page that landed. */
    private int mNothingRun;
    private boolean mEndNoticed;
    private int mRounds;
    /** Grid size when the current round began (the first load starts from an empty grid). */
    private int mRoundStartGridSize;

    /** A row section's shelves. */
    ShelfTail(Keys<G> keys) {
        this(keys, true);
    }

    /**
     * @param dropEmptyShelves true for a row section's shelves; false for a grid section's single
     *                         group, which is paged again after a page that added no card (all
     *                         Shorts) until {@link #MAX_EMPTY_PAGES} such pages in a row
     */
    ShelfTail(Keys<G> keys, boolean dropEmptyShelves) {
        mKeys = keys;
        mDropEmptyShelves = dropEmptyShelves;
    }

    /** A shelf with more pages joins the back of the rotation (once). */
    void offer(G shelf) {
        if (shelf == null || shelf == mInFlight || !mKeys.hasNextPage(shelf) || contains(shelf)) {
            return;
        }
        mQueue.addLast(shelf);
    }

    /**
     * The grid (currently {@code gridSize} cards, -1 if unknown) is short: the shelf to fetch next, or
     * null when a page is already in flight, no shelf has more, or too many pages in a row added no card.
     */
    G next(int gridSize) {
        if (mInFlight != null) {
            return null;
        }

        judgeLastPage(gridSize);

        if (mEmptyPages >= MAX_EMPTY_PAGES) {
            return null;
        }

        while (!mQueue.isEmpty()) {
            G shelf = mQueue.pollFirst();
            if (mKeys.hasNextPage(shelf)) {
                mInFlight = shelf;
                mRequestGridSize = gridSize;
                mLastLanded = null; // not judged (grid size unknown): never blame it for the next page
                return shelf;
            }
        }

        return null;
    }

    /** The page of {@code shelf} arrived (the grid is updated right after); a shelf with more goes to the back. */
    void onLanded(G shelf) {
        if (shelf != mInFlight) {
            return;
        }
        mInFlight = null;
        mLastLanded = shelf;
        mNothingRun = 0;
        offer(shelf);
    }

    /**
     * The fetch of {@code shelf} returned no page at all (an answer equal to the last one, an HTTP
     * error without a body, or a refused connection - they all arrive as the same null): no grid
     * update follows, so it is counted as an empty page right away and the shelf leaves the rotation.
     *
     * @return whether the caller may ask for the next shelf at once (nothing else will ask - the grid
     *         did not change): only for the first {@link #MAX_CHAINED_NOTHING} in a row, so that a
     *         refused connection costs a couple of requests, not a burst through every shelf
     */
    boolean onNothing(G shelf) {
        if (shelf != mInFlight) {
            return false;
        }
        mInFlight = null;
        mLastLanded = null;
        mEmptyPages++;
        mNothingRun++;
        return mNothingRun <= MAX_CHAINED_NOTHING;
    }

    /**
     * The fetch failed (typically the network): the shelf keeps its place at the back to be retried by
     * a later request, and nothing is counted - nothing re-requests on its own after a failure.
     */
    void onFailed(G shelf) {
        if (shelf != mInFlight) {
            return;
        }
        mInFlight = null;
        mLastLanded = null;
        offer(shelf);
    }

    /** The fetch in flight was disposed with the section's other loads: its shelf goes back to the front. */
    void onCancelled() {
        if (mInFlight != null) {
            G shelf = mInFlight;
            mInFlight = null;
            if (mKeys.hasNextPage(shelf) && !contains(shelf)) {
                mQueue.addFirst(shelf);
            }
        }
    }

    boolean isFetching() {
        return mInFlight != null;
    }

    /** Grid size at the last request, for a follow-up request that has no fresher value. */
    int lastGridSize() {
        return mRequestGridSize;
    }

    int size() {
        return mQueue.size();
    }

    int emptyPages() {
        return mEmptyPages;
    }

    boolean isStopped() {
        return mEmptyPages >= MAX_EMPTY_PAGES;
    }

    /**
     * Every shelf is spent (or the last pages added nothing): may the section be fetched again now,
     * its rows appended to the {@code gridSize} cards shown? Only when the round that just ended added
     * at least {@link #MIN_ROUND_CARDS} - an unproductive round (the same feed again, all duplicates)
     * ends the feed for good - and never more than {@link #MAX_ROUNDS} times. A new round starts with a
     * clean empty-page run.
     */
    boolean startRound(int gridSize) {
        // A grid smaller than at the last round's start was repainted (FeedCache keeps the first 120
        // cards) or had cards removed: judge from zero, as for the first load.
        int roundStart = gridSize < mRoundStartGridSize ? 0 : mRoundStartGridSize;
        if (gridSize < 0 || mRounds >= MAX_ROUNDS || gridSize - roundStart < MIN_ROUND_CARDS) {
            return false;
        }
        mRounds++;
        mRoundStartGridSize = gridSize;
        mEmptyPages = 0;
        mLastLanded = null;
        mEndNoticed = false;
        return true;
    }

    int rounds() {
        return mRounds;
    }

    /** True once, for the first request that finds nothing left to fetch (one log line per tail). */
    boolean consumeEndNotice() {
        if (mEndNoticed) {
            return false;
        }
        mEndNoticed = true;
        return true;
    }

    /** Did the last landed page add cards? Decided once, by the first request after it landed. */
    private void judgeLastPage(int gridSize) {
        G judged = mLastLanded;
        if (judged == null) {
            return;
        }
        if (gridSize < 0 || mRequestGridSize < 0) {
            return; // cannot tell yet; keep it for a request that knows the grid size
        }
        mLastLanded = null;
        if (gridSize < mRequestGridSize) {
            return; // the grid shrank (cards removed, or repainted from the capped FeedCache): cannot tell
        }
        if (gridSize > mRequestGridSize) {
            mEmptyPages = 0;
        } else {
            mEmptyPages++;
            if (mDropEmptyShelves) {
                remove(judged); // its pages add nothing to this grid: let the other shelves take its turns
            }
        }
    }

    private boolean contains(G shelf) {
        for (G queued : mQueue) {
            if (queued == shelf) {
                return true;
            }
        }
        return false;
    }

    private void remove(G shelf) {
        Iterator<G> it = mQueue.iterator();
        while (it.hasNext()) {
            if (it.next() == shelf) {
                it.remove();
                return;
            }
        }
    }
}

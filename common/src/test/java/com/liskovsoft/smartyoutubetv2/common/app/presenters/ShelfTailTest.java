package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A row section's shelves, continued in turn once its section list is done. */
public class ShelfTailTest {
    /** A shelf with a fixed number of pages left. */
    private static final class Shelf {
        final String name;
        int pagesLeft;

        Shelf(String name, int pagesLeft) {
            this.name = name;
            this.pagesLeft = pagesLeft;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static ShelfTail<Shelf> tail() {
        return new ShelfTail<>(shelf -> shelf.pagesLeft > 0);
    }

    /** What the presenter does when a page lands: the shelf spends one page, the grid grows by {@code cards}. */
    private static int land(ShelfTail<Shelf> tail, Shelf shelf, int grid, int cards) {
        shelf.pagesLeft--;
        tail.onLanded(shelf);
        return grid + cards;
    }

    @Test
    public void shelvesTakeTurnsAndOnlyThoseWithPagesJoin() {
        ShelfTail<Shelf> tail = tail();
        Shelf a = new Shelf("a", 2);
        Shelf done = new Shelf("done", 0);
        Shelf b = new Shelf("b", 1);
        tail.offer(a);
        tail.offer(done);
        tail.offer(b);
        tail.offer(a); // offered twice (a later page of the same load): still one turn

        assertEquals(2, tail.size());

        int grid = 20;
        Shelf first = tail.next(grid);
        assertSame(a, first);
        grid = land(tail, first, grid, 5);

        Shelf second = tail.next(grid);
        assertSame("the other shelf goes before a gets a second turn", b, second);
        grid = land(tail, second, grid, 5);

        Shelf third = tail.next(grid);
        assertSame(a, third);
        grid = land(tail, third, grid, 5);

        assertNull("every shelf is out of pages", tail.next(grid));
        assertTrue(tail.consumeEndNotice());
        assertFalse("the end is reported once", tail.consumeEndNotice());
        assertFalse(tail.isStopped());
    }

    @Test
    public void onePageInFlightAtATime() {
        ShelfTail<Shelf> tail = tail();
        Shelf a = new Shelf("a", 3);
        Shelf b = new Shelf("b", 3);
        tail.offer(a);
        tail.offer(b);

        assertSame(a, tail.next(10));
        assertTrue(tail.isFetching());
        assertNull("scroll and runway checks while a page is in flight ask for nothing", tail.next(10));
        assertNull(tail.next(-1));

        land(tail, a, 10, 4);
        assertSame(b, tail.next(14));
    }

    @Test
    public void aShelfWhosePageAddedNoCardLeavesTheRotation() {
        ShelfTail<Shelf> tail = tail();
        Shelf shorts = new Shelf("shorts", 5);
        Shelf videos = new Shelf("videos", 5);
        tail.offer(shorts);
        tail.offer(videos);

        int grid = 30;
        assertSame(shorts, tail.next(grid));
        grid = land(tail, shorts, grid, 0); // the grid filtered every card of the page away

        assertSame(videos, tail.next(grid)); // the same grid size: judged empty now
        assertEquals(1, tail.emptyPages());
        grid = land(tail, videos, grid, 5);

        assertSame("the empty shelf was dropped: videos again", videos, tail.next(grid));
        assertEquals("a page that added cards resets the run", 0, tail.emptyPages());
    }

    @Test
    public void aRunOfEmptyPagesStopsTheTailInsteadOfLooping() {
        ShelfTail<Shelf> tail = tail();
        for (int i = 0; i < ShelfTail.MAX_EMPTY_PAGES + 5; i++) {
            tail.offer(new Shelf("s" + i, 100));
        }

        int grid = 40;
        int requests = 0;
        Shelf shelf;
        // The view's runway check asks again after every update while the grid is short: model that loop.
        while ((shelf = tail.next(grid)) != null) {
            requests++;
            land(tail, shelf, grid, 0);
            assertTrue("bounded", requests <= ShelfTail.MAX_EMPTY_PAGES + 1);
        }

        assertTrue(tail.isStopped());
        assertEquals(ShelfTail.MAX_EMPTY_PAGES, requests);
    }

    @Test
    public void noPageAtAllCountsAsEmptyWithoutAGridUpdate() {
        ShelfTail<Shelf> tail = tail();
        Shelf ended = new Shelf("ended", 1);
        Shelf next = new Shelf("next", 1);
        tail.offer(ended);
        tail.offer(next);

        assertSame(ended, tail.next(12));
        tail.onNothing(ended); // "fromNullable result is null": its key led nowhere
        assertEquals(1, tail.emptyPages());
        assertEquals(12, tail.lastGridSize());

        assertSame("the presenter asks again at once, with the last grid size", next, tail.next(tail.lastGridSize()));
        land(tail, next, 12, 3);
        assertNull("the ended shelf never comes back", tail.next(15));
    }

    @Test
    public void aRefusedConnectionCostsACoupleOfRequestsNotEveryShelf() {
        ShelfTail<Shelf> tail = tail();
        for (int i = 0; i < 8; i++) {
            tail.offer(new Shelf("s" + i, 5));
        }

        // The presenter asks again at once only while onNothing() says so.
        int requests = 0;
        Shelf shelf = tail.next(30);
        while (shelf != null) {
            requests++;
            shelf = tail.onNothing(shelf) ? tail.next(tail.lastGridSize()) : null;
        }

        assertEquals(ShelfTail.MAX_CHAINED_NOTHING + 1, requests);
        assertFalse(tail.isFetching());

        // A page that lands resets the run: a later stale key may be skipped at once again.
        Shelf next = tail.next(30);
        land(tail, next, 30, 5);
        Shelf stale = tail.next(35);
        assertTrue(tail.onNothing(stale));
    }

    @Test
    public void aFailedFetchKeepsItsShelfForALaterRequest() {
        ShelfTail<Shelf> tail = tail();
        Shelf a = new Shelf("a", 2);
        tail.offer(a);

        assertSame(a, tail.next(8));
        tail.onFailed(a); // network: nothing counted, nothing re-requested by the tail itself
        assertEquals(0, tail.emptyPages());
        assertFalse(tail.isFetching());

        assertSame(a, tail.next(8));
    }

    @Test
    public void aDisposedFetchPutsItsShelfBackAtTheFront() {
        ShelfTail<Shelf> tail = tail();
        Shelf a = new Shelf("a", 2);
        Shelf b = new Shelf("b", 2);
        tail.offer(a);
        tail.offer(b);

        assertSame(a, tail.next(8));
        tail.onCancelled(); // section switch: the page never landed
        tail.onLanded(a); // a late callback from the disposed fetch is ignored

        assertSame("a keeps its turn", a, tail.next(8));
    }

    /**
     * Subscriptions (a grid section: one group, many pages): a page of nothing but Shorts adds no
     * card, and the grid - unable to scroll to a new end - never asked again. Its tail pages the same
     * group again, one page at a time, until a page adds cards; 10 empty pages in a row end it.
     */
    @Test
    public void subscriptionsGridKeepsPagingThroughAllShortsPages() {
        ShelfTail<Shelf> tail = new ShelfTail<>(shelf -> shelf.pagesLeft > 0, false);
        Shelf subscriptions = new Shelf("subscriptions", 100);
        tail.offer(subscriptions);

        int grid = 8; // page 1: 8 videos left after its Shorts
        assertSame(subscriptions, tail.next(grid));
        assertNull("one page in flight", tail.next(grid));
        grid = land(tail, subscriptions, grid, 0); // page 2: all Shorts

        assertSame("the same group again, not the end", subscriptions, tail.next(grid));
        assertEquals(1, tail.emptyPages());
        grid = land(tail, subscriptions, grid, 0); // page 3: all Shorts
        assertSame(subscriptions, tail.next(grid));
        assertEquals(2, tail.emptyPages());
        grid = land(tail, subscriptions, grid, 6); // page 4: six videos

        assertSame(subscriptions, tail.next(grid));
        assertEquals("cards again: the run restarts", 0, tail.emptyPages());

        // A subscriptions feed that is nothing but Shorts from here on ends after 10 empty pages.
        int requests = 0;
        Shelf shelf = subscriptions;
        while (shelf != null) {
            land(tail, shelf, grid, 0);
            shelf = tail.next(grid);
            requests++;
            assertTrue("bounded", requests <= ShelfTail.MAX_EMPTY_PAGES + 1);
        }
        assertTrue(tail.isStopped());
        assertEquals(ShelfTail.MAX_EMPTY_PAGES, requests);
    }

    @Test
    public void aGridGroupThatRunsOutEndsWithoutARoundOfItsOwn() {
        ShelfTail<Shelf> tail = new ShelfTail<>(shelf -> shelf.pagesLeft > 0, false);
        Shelf history = new Shelf("history", 1);
        tail.offer(history);

        assertSame(history, tail.next(20));
        land(tail, history, 20, 20);
        assertNull("its last page had no key", tail.next(40));
        assertFalse(tail.isStopped());
    }

    @Test
    public void aRoundThatAddedTooFewCardsEndsTheFeed() {
        ShelfTail<Shelf> tail = tail();

        assertFalse("grid size unknown: no round", tail.startRound(-1));
        assertTrue("the first load painted 120 cards", tail.startRound(120));
        assertEquals(1, tail.rounds());

        // The round's section list and shelves added 40 cards: another round.
        assertTrue(tail.startRound(160));
        // That one added only duplicates and a handful of new cards: the feed ends here.
        assertFalse(tail.startRound(160 + ShelfTail.MIN_ROUND_CARDS - 1));
        assertFalse(tail.startRound(160 + ShelfTail.MIN_ROUND_CARDS - 1));
        assertEquals(2, tail.rounds());
    }

    @Test
    public void aRoundStartsWithACleanEmptyRunAndRoundsAreCapped() {
        ShelfTail<Shelf> tail = tail();
        for (int i = 0; i < ShelfTail.MAX_EMPTY_PAGES; i++) {
            tail.offer(new Shelf("s" + i, 1));
        }
        int grid = 50;
        Shelf shelf;
        while ((shelf = tail.next(grid)) != null) {
            land(tail, shelf, grid, 0);
        }
        assertTrue(tail.isStopped());
        assertTrue(tail.consumeEndNotice());

        assertTrue(tail.startRound(grid));
        assertFalse(tail.isStopped());
        assertTrue("a later end is reported again", tail.consumeEndNotice());

        for (int i = 1; i < ShelfTail.MAX_ROUNDS; i++) {
            grid += ShelfTail.MIN_ROUND_CARDS;
            assertTrue(tail.startRound(grid));
        }
        assertFalse("capped", tail.startRound(grid + 100));
        assertEquals(ShelfTail.MAX_ROUNDS, tail.rounds());
    }

    @Test
    public void aShrunkGridNeitherBlamesAShelfNorEndsTheFeed() {
        ShelfTail<Shelf> tail = tail();
        Shelf a = new Shelf("a", 3);
        Shelf b = new Shelf("b", 3);
        tail.offer(a);
        tail.offer(b);

        assertSame(a, tail.next(250));
        land(tail, a, 250, 5);
        // Back from another tab within the TTL: FeedCache repainted its first 120 cards.
        assertSame(b, tail.next(120));
        assertEquals("a shrink is not an empty page", 0, tail.emptyPages());
        land(tail, b, 120, 5);
        assertSame("a keeps its turn", a, tail.next(125));

        ShelfTail<Shelf> spent = tail();
        assertTrue(spent.startRound(250));
        assertTrue("a repainted grid is judged from zero", spent.startRound(120));
    }

    @Test
    public void unknownGridSizeNeverBlamesAShelf() {
        ShelfTail<Shelf> tail = tail();
        Shelf a = new Shelf("a", 3);
        Shelf b = new Shelf("b", 3);
        tail.offer(a);
        tail.offer(b);

        assertSame(a, tail.next(-1));
        land(tail, a, 0, 0);
        assertSame(b, tail.next(-1));
        assertEquals(0, tail.emptyPages());
        land(tail, b, 0, 0);
        assertSame(a, tail.next(10));
        assertEquals(0, tail.emptyPages());
    }
}

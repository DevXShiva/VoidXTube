package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * NEWTUBE(still-lift): only the stream this open prepared can report "first frame rendered" to the
 * loading still; a late event of the previous stream on the reused surface never counts.
 */
public class OpenFirstFrameTest {
    private final OpenFirstFrame mFirstFrame = new OpenFirstFrame();

    @Test
    public void thisOpensFirstFrameCounts() {
        mFirstFrame.onPrepare(7);
        mFirstFrame.onFence(7);
        assertEquals(0, mFirstFrame.renderedAtMs(7));

        mFirstFrame.onRenderedFirstFrame(7, 1_450);
        assertEquals(1_450, mFirstFrame.renderedAtMs(7));

        // A later re-render (seek, surface refresh, live window slide) keeps the first one.
        mFirstFrame.onRenderedFirstFrame(7, 3_000);
        assertEquals(1_450, mFirstFrame.renderedAtMs(7));
    }

    @Test
    public void aQueuedEventOfThePreviousStreamDoesNotCount() {
        mFirstFrame.onPrepare(7);
        // Delivered after the prepare, under the new generation, but ahead of the fence: the old
        // stream's renderer posted it before the playback thread handled the stop.
        mFirstFrame.onRenderedFirstFrame(7, 1_010);
        assertEquals(0, mFirstFrame.renderedAtMs(7));

        mFirstFrame.onFence(7);
        mFirstFrame.onRenderedFirstFrame(7, 1_300);
        assertEquals(1_300, mFirstFrame.renderedAtMs(7));
    }

    @Test
    public void aNewerOpenOrResetInvalidatesIt() {
        mFirstFrame.onPrepare(7);
        mFirstFrame.onFence(7);
        mFirstFrame.onRenderedFirstFrame(7, 1_450);

        // resetPlayerState / the next open's source build bumped the generation.
        assertEquals(0, mFirstFrame.renderedAtMs(8));
        // An event delivered in that window (before the next prepare) is not recorded either.
        mFirstFrame.onRenderedFirstFrame(8, 1_500);
        assertEquals(0, mFirstFrame.renderedAtMs(8));

        mFirstFrame.onPrepare(9);
        mFirstFrame.onFence(7); // the older open's fence, arriving late
        mFirstFrame.onRenderedFirstFrame(9, 2_100);
        assertEquals(0, mFirstFrame.renderedAtMs(9));
        mFirstFrame.onFence(9);
        mFirstFrame.onRenderedFirstFrame(9, 2_300);
        assertEquals(2_300, mFirstFrame.renderedAtMs(9));
    }

    @Test
    public void nothingBeforeTheFirstPrepare() {
        mFirstFrame.onFence(0);
        mFirstFrame.onRenderedFirstFrame(0, 500);
        assertEquals(0, mFirstFrame.renderedAtMs(0));
    }
}

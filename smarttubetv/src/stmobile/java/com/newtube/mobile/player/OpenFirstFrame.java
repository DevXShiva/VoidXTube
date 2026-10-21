package com.newtube.mobile.player;

/**
 * NEWTUBE(still-lift): has the stream THIS open prepared rendered its first frame, and when.
 *
 * <p>The watch page lifts its loading still at READY when it has; see
 * {@code MobilePlaybackActivity.canLiftStillAtReady}. The danger is the reused persistent surface:
 * the previous stream's renderer can still post a first-frame event (after a seek, a surface
 * refresh, a live window slide) that reaches the main thread after this open's reset, and the
 * generation current at delivery cannot tell whose it was. So each open sends a fence through the
 * player right after {@code prepare()}: a PlayerMessage the playback thread handles after this
 * open's stop and prepare, and posts to the main thread. Renderer events travel the same way
 * (posted by the playback thread to the main looper, in order), so a first-frame event delivered
 * after the fence was rendered after the old stream's renderers were disabled: it is this
 * stream's. Anything delivered before it does not count, and an open whose fence never arrives
 * simply keeps the texture-frame path.
 *
 * <p>The time kept is the {@code elapsedRealtime} media3 takes on the playback thread when it
 * releases the frame ({@code AnalyticsListener.onRenderedFirstFrame}'s renderTimeMs).
 *
 * <p>Main thread only (prepare, the fence, player events and the activity all run there). Plain JVM
 * for tests.
 */
final class OpenFirstFrame {
    private static final int NONE = Integer.MIN_VALUE;

    private int mGeneration = NONE;
    private boolean mFenced;
    private long mRenderedAtMs;

    /** At {@code prepare()}, under the generation this open now runs in; the fence goes out next. */
    void onPrepare(int generation) {
        mGeneration = generation;
        mFenced = false;
        mRenderedAtMs = 0;
    }

    /** The fence sent after that prepare came back through the playback thread. */
    void onFence(int generation) {
        if (generation == mGeneration) {
            mFenced = true;
        }
    }

    /**
     * @param renderTimeMs when the renderer released the frame (playback thread's elapsedRealtime);
     *                     only the first counting event of an open is kept
     */
    void onRenderedFirstFrame(int currentGeneration, long renderTimeMs) {
        if (mFenced && mRenderedAtMs == 0 && currentGeneration == mGeneration && renderTimeMs > 0) {
            mRenderedAtMs = renderTimeMs;
        }
    }

    /** When this open's first frame was released to the surface; 0 when not (yet), or stale. */
    long renderedAtMs(int currentGeneration) {
        return currentGeneration == mGeneration ? mRenderedAtMs : 0;
    }
}

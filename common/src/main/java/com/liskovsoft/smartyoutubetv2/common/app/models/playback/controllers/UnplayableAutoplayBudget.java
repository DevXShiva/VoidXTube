package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

/**
 * NEWTUBE(autoplay-budget): how many unplayable videos in a row autoplay skips past.
 *
 * <p>Upstream moves to the next video 5 s after any unplayable answer - right for a lone 18+ or
 * removed video. When the related videos are unplayable too (made-for-kids videos signed out,
 * issue #5) it became a chain: a new video every ~7.5 s, each costing a full /player walk, about
 * 90 /player calls a minute until the app was closed (Pixel 9 over LTE, 2026-09-28, twice). Now
 * one unplayable video still skips once; the next unplayable one that autoplay itself reached
 * stops there, with its reason on screen. A video that plays, or any video the user opens,
 * starts over. The app's own reload of the same video, and the open an advance leads to - even
 * when it lands later, after the suggestions or a playlist item were resolved - are not new starts.
 * A video the user picks in the meantime is, and an advance that has not landed within
 * {@link #ADVANCE_LANDS_WITHIN_MS} is forgotten.
 */
final class UnplayableAutoplayBudget {
    /** Unplayable videos in a row that autoplay skips past; the next one ends the chain. */
    static final int MAX_SKIPS = 1;
    /** How long a pending advance may take to open its video (metadata wait, playlist lookup). */
    static final long ADVANCE_LANDS_WITHIN_MS = 20_000;

    private int mSkips;
    /** 0 = no advance pending; else the time until which the next open is that advance. */
    private long mAdvancePendingUntilMs;

    /** An unplayable answer for the current video: true = advance to the next one. */
    boolean onUnplayable() {
        if (mSkips >= MAX_SKIPS) {
            return false;
        }
        mSkips++;
        return true;
    }

    /** A playable answer: the chain is broken. */
    void onPlayable() {
        mSkips = 0;
        mAdvancePendingUntilMs = 0;
    }

    /** Autoplay is moving past an unplayable video; the next open (soon) is that move. */
    void onAdvance(long nowMs) {
        mAdvancePendingUntilMs = nowMs + ADVANCE_LANDS_WITHIN_MS;
    }

    /** The user picked a video in the player: whatever opens next is theirs, not the advance. */
    void onUserPick() {
        mAdvancePendingUntilMs = 0;
    }

    /**
     * Any video open. The app's own reload of the same video keeps the streak, and so does the
     * open a pending advance leads to; anything else (the user's pick, normal end-of-video
     * autoplay, an advance that never landed) starts over.
     */
    void onOpen(boolean ownReload, long nowMs) {
        if (ownReload) {
            return;
        }
        boolean advance = mAdvancePendingUntilMs != 0 && nowMs <= mAdvancePendingUntilMs;
        mAdvancePendingUntilMs = 0;
        if (!advance) {
            mSkips = 0;
        }
    }

    int skips() {
        return mSkips;
    }
}

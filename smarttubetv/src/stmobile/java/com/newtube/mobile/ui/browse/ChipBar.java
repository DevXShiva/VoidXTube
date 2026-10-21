package com.newtube.mobile.ui.browse;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * VOIDXDEV(feed-strip): how Home's floating chip row behaves while the feed scrolls. At the top the
 * chips show; scrolling down slides them away; scrolling back up (still down the feed) brings only
 * a small glass arrow, and a tap on it brings the chips. The row keeps its layout height while
 * hidden (INVISIBLE, never GONE) so the feed never jumps under it.
 */
final class ChipBar {
    private static final int SHOWN = 0;
    private static final int HIDDEN = 1;
    private static final int ARROW = 2;
    private static final long DURATION_MS = 220;

    private final View mRow;
    private final View mArrow;
    private final int mSlopPx;
    private boolean mActive;
    private int mState = SHOWN;
    private int mTravel; // signed scroll distance since the last change of direction

    ChipBar(View row, View arrow) {
        mRow = row;
        mArrow = arrow;
        mSlopPx = Math.round(24 * row.getResources().getDisplayMetrics().density);
        mArrow.setOnClickListener(v -> show());
    }

    /** On for Home and its topics (not over the You panel); off hides both, without animation. */
    void setActive(boolean active) {
        if (active == mActive) {
            return;
        }
        mActive = active;
        mRow.animate().cancel();
        mArrow.animate().cancel();
        mState = SHOWN;
        mTravel = 0;
        mRow.setAlpha(1f);
        mRow.setTranslationY(0f);
        mRow.setVisibility(active ? View.VISIBLE : View.GONE);
        mArrow.setVisibility(View.GONE);
    }

    boolean isActive() {
        return mActive;
    }

    /** From the feed's scroll listener; {@code atTop} = the feed cannot scroll further up. */
    void onScrolled(int dy, boolean atTop) {
        if (!mActive) {
            return;
        }
        if (atTop) {
            show();
            return;
        }
        if (dy == 0) {
            return;
        }
        mTravel = Integer.signum(dy) == Integer.signum(mTravel) ? mTravel + dy : dy;
        if (mTravel > mSlopPx && mState != HIDDEN) {
            hide();
        } else if (mTravel < -mSlopPx && mState == HIDDEN) {
            showArrow();
        }
    }

    /** A new section or a scroll to the top: the chips again. */
    void show() {
        if (!mActive || mState == SHOWN) {
            return;
        }
        mState = SHOWN;
        fadeOut(mArrow, View.GONE, 0);
        mRow.setVisibility(View.VISIBLE);
        mRow.animate().cancel();
        mRow.animate().alpha(1f).translationY(0f).setDuration(DURATION_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hide() {
        boolean fromArrow = mState == ARROW;
        mState = HIDDEN;
        if (fromArrow) {
            fadeOut(mArrow, View.GONE, 0);
        } else {
            fadeOut(mRow, View.INVISIBLE, -mRow.getHeight() / 2f);
        }
    }

    private void showArrow() {
        mState = ARROW;
        mArrow.animate().cancel();
        mArrow.setVisibility(View.VISIBLE);
        mArrow.setAlpha(0f);
        mArrow.setScaleX(0.6f);
        mArrow.setScaleY(0.6f);
        mArrow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(DURATION_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private static void fadeOut(View view, int endVisibility, float translationY) {
        view.animate().cancel();
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        view.animate().alpha(0f).translationY(translationY).setDuration(DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> view.setVisibility(endVisibility)).start();
    }
}

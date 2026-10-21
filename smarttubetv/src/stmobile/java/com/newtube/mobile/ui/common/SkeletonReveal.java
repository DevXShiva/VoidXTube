package com.newtube.mobile.ui.common;

import android.view.View;
import android.view.ViewTreeObserver;

import androidx.recyclerview.widget.RecyclerView;

/**
 * NEWTUBE(motion): hands a list over from its loading skeleton to its first cards. The skeleton
 * fades out only once the cards are laid out under it, and those first cards skip the item
 * animator's own fade-in: faded together, both were half transparent at once and the screen dipped
 * to the background for ~150 ms (cold start, Pixel 9 and emulator).
 */
public final class SkeletonReveal {
    /** A list that never lays out its cards (an error lands meanwhile) still loses the skeleton. */
    private static final long WAIT_MS = 400;

    private SkeletonReveal() {
    }

    /**
     * Fade {@code skeleton} out over {@code grid}'s first cards, then run {@code onGone} (which
     * hides it). {@code stillWanted} is asked again once the cards are in: false = a new load put
     * the skeleton back meanwhile, leave it.
     */
    public static void fadeOverCards(View skeleton, RecyclerView grid, Condition stillWanted, Runnable onGone) {
        if (grid.getChildCount() > 0 && !grid.hasPendingAdapterUpdates()) {
            fade(skeleton, onGone);
            return;
        }
        RecyclerView.ItemAnimator animator = grid.getItemAnimator();
        grid.setItemAnimator(null); // decided at layout: the first cards appear whole
        boolean[] done = {false};
        Runnable reveal = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            if (grid.getItemAnimator() == null) {
                grid.setItemAnimator(animator);
            }
            if (stillWanted.holds()) {
                fade(skeleton, onGone);
            }
        };
        grid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (done[0]) {
                    removeSelf();
                    return true;
                }
                if (grid.getChildCount() == 0 || grid.hasPendingAdapterUpdates()) {
                    return true; // not yet
                }
                removeSelf();
                // This frame draws the cards under the still-opaque skeleton; the fade starts next.
                grid.post(reveal);
                return true;
            }

            private void removeSelf() {
                ViewTreeObserver observer = grid.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
            }
        });
        grid.postDelayed(reveal, WAIT_MS);
    }

    private static void fade(View skeleton, Runnable onGone) {
        skeleton.animate().cancel();
        skeleton.animate().alpha(0f).setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.STANDARD)
                .withEndAction(onGone).start();
    }

    public interface Condition {
        boolean holds();
    }
}

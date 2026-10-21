package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MagneticDrag;

/**
 * NEWTUBE(motion): swipe the docked mini card sideways to close it, like YouTube's. The card
 * fades with the distance; let go past the point where it lets go (or flick it) and it flies off
 * that side, keeping the finger's speed, and the host closes the session; otherwise it springs
 * back. Taps are untouched: the swipe only claims a touch once it is clearly sideways, and then
 * cancels the card's pending click so the release does not also expand it.
 *
 * <p>NEWTUBE(haptics): it swipes like a notification on the Pixel (MagneticDrag): stuck at half
 * the finger's distance with a faint grain, then a click at 72 dp - 40% of the card, the old
 * commit point - where it catches up with the finger; back under 56 dp it sticks again, with the
 * same click. The spring back has SystemUI's small bounce.</p>
 *
 * <p>Shared by Home's card (MobileBrowseActivity) and the Search/Channel one
 * (MobileMiniPlayerController); each host does its own closing in {@link Callback}.</p>
 */
public final class MiniCardSwipe implements View.OnTouchListener {
    public interface Callback {
        /** False while the card must not move (closing, entering, no session). */
        boolean canSwipe();

        /** Committed: fly the card to {@code toTranslationX} over {@code durationMs}, then close. */
        void onSwipedAway(float toTranslationX, long durationMs);
    }

    private static final float FLING_VELOCITY_DP = 450f;
    /** SystemUI's snap-back spring for a notification that did not go. */
    private static final float SNAP_BACK_STIFFNESS = 550f;
    private static final float SNAP_BACK_DAMPING = 0.6f;
    private static final long FLY_MIN_MS = 110;
    private static final long FLY_MAX_MS = 240;

    private final View mCard;
    private final Callback mCallback;
    private final int mTouchSlop;
    private final float mFlingVelocity;
    private final MagneticDrag mMagnet;
    @Nullable
    private SpringAnimation mSnapBack;
    private float mDownRawX;
    private float mDownRawY;
    private boolean mDragging;
    /** The touch stopped being a swipe (a second finger): consume it to the end. */
    private boolean mIgnoring;
    @Nullable
    private VelocityTracker mVelocity;

    /** Returns the swipe, so the host can {@link #stop} it before moving the card itself. */
    public static MiniCardSwipe attach(View card, Callback callback, View... touchTargets) {
        MiniCardSwipe swipe = new MiniCardSwipe(card, callback);
        for (View target : touchTargets) {
            if (target != null) {
                target.setOnTouchListener(swipe);
            }
        }
        return swipe;
    }

    /**
     * NEWTUBE(haptics): the host is about to move the card itself (close, fold, show, enter): stop
     * the swipe's springs, which a card.animate().cancel() does not reach, and ignore the rest of a
     * touch still on it. A spring back still running kept restoring the card's alpha and position
     * under the host's own fade.
     */
    public void stop() {
        if (mDragging) {
            mDragging = false;
            mIgnoring = true;
            recycleVelocity();
        }
        mMagnet.finish();
        cancelSnapBack();
    }

    private MiniCardSwipe(View card, Callback callback) {
        mCard = card;
        mCallback = callback;
        mTouchSlop = ViewConfiguration.get(card.getContext()).getScaledTouchSlop();
        mFlingVelocity = FLING_VELOCITY_DP * card.getResources().getDisplayMetrics().density;
        mMagnet = new MagneticDrag(card, position -> {
            mCard.setTranslationX(position);
            mCard.setAlpha(alphaFor(position));
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mDragging = false;
                mIgnoring = false;
                recycleVelocity();
                mVelocity = VelocityTracker.obtain();
                trackVelocity(event);
                return false; // the view still sees the press (ripple, click)
            case MotionEvent.ACTION_MOVE: {
                if (mIgnoring) {
                    return true;
                }
                trackVelocity(event);
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (!mDragging) {
                    if (Math.abs(dx) <= mTouchSlop || Math.abs(dx) <= Math.abs(dy) * 1.5f
                            || !mCallback.canSwipe()) {
                        return false;
                    }
                    mDragging = true;
                    // Drop the press and its pending click: this touch is a swipe now.
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    view.onTouchEvent(cancel);
                    cancel.recycle();
                    ViewParent parent = mCard.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    mCard.animate().cancel();
                    cancelSnapBack();
                    mMagnet.start();
                }
                mMagnet.move(dx);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mIgnoring) {
                    mIgnoring = false;
                    return true;
                }
                if (!mDragging) {
                    recycleVelocity();
                    return false;
                }
                trackVelocity(event);
                float velocityX = 0f;
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    velocityX = mVelocity.getXVelocity();
                }
                recycleVelocity();
                mDragging = false;
                float dx = event.getRawX() - mDownRawX;
                mMagnet.move(dx); // where the finger lifted, which a last MOVE may not have said
                mMagnet.finish();
                release(mMagnet.isDetached(), dx, velocityX);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger: not a swipe any more. The card goes back; the rest of the touch
                // is ignored (no index-0 jump when the first finger lifts).
                if (mDragging) {
                    mDragging = false;
                    mIgnoring = true;
                    recycleVelocity();
                    mMagnet.finish();
                    springBack(0f);
                }
                return mIgnoring;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                mIgnoring = false;
                if (mDragging) {
                    mDragging = false;
                    mMagnet.finish();
                    springBack(0f);
                    return true;
                }
                return false;
            default:
                return mDragging;
        }
    }

    /**
     * Decided on the finger ({@code dx}), animated from the card: right after the click the card
     * still trails the finger (its gap springs shut), and a fast swipe let go within a frame of it
     * clicked but read the card's 0 and sprang back.
     */
    private void release(boolean detached, float dx, float velocityX) {
        boolean flung = Math.abs(velocityX) > mFlingVelocity && Math.signum(velocityX) == Math.signum(dx);
        boolean flungBack = Math.abs(velocityX) > mFlingVelocity && Math.signum(velocityX) == -Math.signum(dx);
        if (dx == 0f || flungBack || (!detached && !flung) || !mCallback.canSwipe()) {
            springBack(velocityX);
            return;
        }
        if (!detached) {
            Haptics.threshold(mCard, true); // flung before it let go: the click it skipped
        }
        // Off that side of the screen: at least the card's width plus its distance to the edge.
        float screenWidth = mCard.getResources().getDisplayMetrics().widthPixels;
        float target = Math.signum(dx) * screenWidth;
        float remaining = Math.abs(target - mCard.getTranslationX());
        float speed = Math.max(Math.abs(velocityX), mFlingVelocity * 2f);
        long durationMs = Math.round(1000f * remaining / speed);
        durationMs = Math.max(FLY_MIN_MS, Math.min(FLY_MAX_MS, durationMs));
        mCallback.onSwipedAway(target, durationMs);
    }

    private void springBack(float velocityX) {
        mCard.animate().cancel();
        if (mSnapBack == null) {
            mSnapBack = new SpringAnimation(mCard, DynamicAnimation.TRANSLATION_X, 0f);
            mSnapBack.getSpring().setStiffness(SNAP_BACK_STIFFNESS).setDampingRatio(SNAP_BACK_DAMPING);
            mSnapBack.addUpdateListener((animation, value, velocity) -> mCard.setAlpha(alphaFor(value)));
        }
        mSnapBack.cancel();
        mSnapBack.setStartVelocity(velocityX);
        mSnapBack.animateToFinalPosition(0f);
    }

    private void cancelSnapBack() {
        if (mSnapBack != null) {
            mSnapBack.cancel();
        }
    }

    private float alphaFor(float dx) {
        float fraction = Math.min(1f, Math.abs(dx) / (Math.max(1, mCard.getWidth()) * 1.2f));
        return 1f - 0.85f * fraction;
    }

    private void trackVelocity(MotionEvent event) {
        if (mVelocity == null) {
            return;
        }
        // Raw coordinates: the card moves under the finger, so view-local ones would lag.
        MotionEvent raw = MotionEvent.obtain(event);
        raw.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(raw);
        raw.recycle();
    }

    private void recycleVelocity() {
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }
}

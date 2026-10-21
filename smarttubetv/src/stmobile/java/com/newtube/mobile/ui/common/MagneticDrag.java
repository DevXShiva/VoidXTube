package com.newtube.mobile.ui.common;

import android.view.View;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * NEWTUBE(haptics): a drag that sticks before it lets go - the Pixel's notification swipe
 * (SystemUI's MagneticNotificationRowManagerImpl, Android 16 QPR2; the owner's Pixel 9 on
 * Android 17 clicked at exactly 72 dp of finger travel). Until the finger has travelled
 * {@link #DETACH_DP} the object follows at half the distance (or its own share), with a faint
 * grain; there it springs onto the finger with one click and follows 1:1 - past that point,
 * letting go acts. Coming back under {@link #ATTACH_DP} sticks it again, with the same click, so
 * the finger always knows which side of the line it is on.
 *
 * <p>One axis, signed, in pixels of finger travel from where the drag began. The caller maps
 * {@link Target#onMagneticPosition} onto its own motion and decides what a release does
 * ({@link #isDetached}, then {@link #finish} and animate on from {@link #position}).</p>
 */
public final class MagneticDrag {
    public interface Target {
        /** Where the dragged object sits now, in the same units as the finger's travel. */
        void onMagneticPosition(float position);
    }

    public static final float DETACH_DP = 72f;
    public static final float ATTACH_DP = 56f;
    /** How far the object follows while it is still stuck: the Pixel's notification rows. */
    public static final float PIXEL_PULL = 0.5f;
    // SystemUI's springs: a quick, barely-overshooting catch-up onto the finger, and back.
    private static final float DETACH_STIFFNESS = 800f;
    private static final float DETACH_DAMPING = 0.95f;
    private static final float ATTACH_STIFFNESS = 850f;
    private static final float ATTACH_DAMPING = 0.95f;

    private final View mView;
    private final Target mTarget;
    private final float mPull;
    private final float mDetachPx;
    private final float mAttachPx;
    /**
     * Closes {@link #mGap}. Only the gap springs, never the object itself: an object that chases a
     * moving finger on a spring trails it for as long as it moves (~65 ms behind at this stiffness -
     * the owner felt it as lag moving up and down), while goal + a closing gap moves with the finger
     * from the first frame.
     */
    private final SpringAnimation mGapSpring;
    private boolean mDetached;
    private float mFinger;
    private float mPosition;
    /** How far the object sits from where it belongs ({@link #goal}); springs to 0 after a click. */
    private float mGap;

    public MagneticDrag(View hapticView, Target target) {
        this(hapticView, PIXEL_PULL, target);
    }

    /** {@code pull}: the share of the finger's travel the object follows while it is still stuck. */
    public MagneticDrag(View hapticView, float pull, Target target) {
        mView = hapticView;
        mPull = pull;
        mTarget = target;
        float density = hapticView.getResources().getDisplayMetrics().density;
        mDetachPx = DETACH_DP * density;
        mAttachPx = ATTACH_DP * density;
        mGapSpring = new SpringAnimation(new FloatValueHolder());
        mGapSpring.setSpring(new SpringForce(0f));
        mGapSpring.addUpdateListener((animation, value, velocity) -> {
            mGap = value;
            place(goal() + mGap);
        });
        mGapSpring.addEndListener((animation, canceled, value, velocity) -> {
            if (!canceled) {
                mGap = 0f;
                place(goal());
            }
        });
    }

    /** A new drag: stuck, at rest. */
    public void start() {
        mGapSpring.cancel();
        mDetached = false;
        mFinger = 0f;
        mPosition = 0f;
        mGap = 0f;
    }

    /** The finger moved to {@code finger} px from where the drag began. */
    public void move(float finger) {
        mFinger = finger;
        float distance = Math.abs(finger);
        if (!mDetached && distance >= mDetachPx) {
            mDetached = true;
            closeGap(DETACH_STIFFNESS, DETACH_DAMPING);
            Haptics.threshold(mView, true);
        } else if (mDetached && distance <= mAttachPx) {
            mDetached = false;
            closeGap(ATTACH_STIFFNESS, ATTACH_DAMPING);
            Haptics.threshold(mView, false);
        }
        place(goal() + mGap);
        if (!mDetached) {
            Haptics.tension(mView, distance / mDetachPx);
        }
    }

    /** Past the line: letting go now acts. */
    public boolean isDetached() {
        return mDetached;
    }

    /** Where the object sits (the last value handed to the target). */
    public float position() {
        return mPosition;
    }

    /** The drag ended: stop any catch-up where it is, so the caller can animate on from there. */
    public void finish() {
        mGapSpring.cancel();
    }

    /** Where the object belongs for the finger's travel: on it once let go, a share of it before. */
    private float goal() {
        return mDetached ? mFinger : mFinger * mPull;
    }

    /** The object's place just changed (a click): keep it where it is and spring the gap shut. */
    private void closeGap(float stiffness, float damping) {
        mGapSpring.cancel();
        mGap = mPosition - goal();
        mGapSpring.getSpring().setStiffness(stiffness).setDampingRatio(damping).setFinalPosition(0f);
        mGapSpring.setStartValue(mGap);
        mGapSpring.setStartVelocity(0f);
        mGapSpring.start();
    }

    private void place(float position) {
        mPosition = position;
        mTarget.onMagneticPosition(position);
    }
}

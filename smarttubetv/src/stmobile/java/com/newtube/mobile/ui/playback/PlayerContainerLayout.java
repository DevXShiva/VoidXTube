package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Root container for {@link MobilePlaybackActivity} that recognizes the player's one-finger swipes
 * (swipe down to minimize, and NEWTUBE(gestures): up to fullscreen, down out of it, brightness,
 * volume, sideways to seek) while leaving what each one does to the Activity.
 *
 * <p>The {@code slidableactivity} (Slidr) module was evaluated first but doesn't integrate cleanly
 * here: it hijacks the decor view and would fight the
 * {@link com.github.vkay94.dtpv3.DoubleTapPlayerViewImpl} which consumes all touch events. The mobile
 * activities now share one task and the player window is translucent, so this small, self-contained
 * drag can reveal the already-rendered Activity underneath without delegating the gesture or its
 * geometry to a window transition.</p>
 *
 * <p>A drag is only intercepted once it is clearly along one axis - past 2x touch slop and 1.5x
 * its sideways travel - and the listener has claimed it for that direction, so single taps (toggle
 * controls), double taps (seek), press-and-hold and the seek bar all still reach the player view
 * untouched. Once a drag has left its slop without being claimed, it stays the children's for the
 * rest of the touch: a diagonal drag never turns into a different swipe half-way. Deltas use raw
 * screen coordinates so the math stays correct even while the Activity moves this view's
 * children during the drag.</p>
 */
public class PlayerContainerLayout extends FrameLayout {

    /** Which way a drag left its slop. */
    public static final int UP = 1;
    public static final int DOWN = 2;
    public static final int LEFT = 3;
    public static final int RIGHT = 4;
    /** {@link SwipeListener#onSwipeStart}: not a swipe - the drag stays the children's. */
    public static final int SWIPE_NONE = 0;

    public interface SwipeListener {
        /**
         * A drag that began in the swipe region has just left its slop {@code direction}-wards;
         * the finger is now ({@code dx}, {@code dy}) raw px from where it landed.
         *
         * @return the swipe it becomes (any non-zero value, handed back on each call below), or
         *         {@link #SWIPE_NONE} to leave the whole touch to the views under the finger
         */
        int onSwipeStart(int direction, float downRawX, float downRawY, float dx, float dy);

        /** Every move of a claimed swipe; {@code dx}/{@code dy} are raw px from where the finger landed. */
        void onSwipeMove(int swipe, float dx, float dy);

        /** The finger lifted; velocities in px/s. */
        void onSwipeReleased(int swipe, float dx, float dy, float xVelocity, float yVelocity);

        /**
         * NEWTUBE(motion): the swipe was taken away (ACTION_CANCEL: a parent or the system claimed
         * the gesture). Not a release - it used to be one, and could minimize the player.
         */
        void onSwipeCancelled(int swipe);
    }

    private SwipeListener mListener;
    private final int mTouchSlop;
    private float mDownRawX;
    private float mDownRawY;
    /** The finger the touch follows: a second one landing or lifting never moves the swipe. */
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private int mSwipe = SWIPE_NONE;
    /** The touch began where a swipe may start and has not been decided yet. */
    private boolean mUndecided;
    @Nullable
    private View mDragStartBoundView;
    private final int[] mTmpLocation = new int[2];
    private final int[] mRootLocation = new int[2];
    @Nullable
    private VelocityTracker mVelocityTracker;

    public PlayerContainerLayout(Context context) {
        this(context, null);
    }

    public PlayerContainerLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PlayerContainerLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setSwipeListener(SwipeListener listener) {
        mListener = listener;
    }

    /** Raw screen Y of the current gesture's first touch - where the drag grabbed the video. */
    public float getDownRawY() {
        return mDownRawY;
    }

    /**
     * Restrict where a swipe may begin. When set, a swipe only starts if the initial touch-down
     * landed inside {@code view}'s on-screen bounds (the watch page uses the 16:9 video area). This
     * keeps the drags off the scrollable content column below, so the related list scrolls
     * normally. When {@code null}, a swipe may begin anywhere.
     */
    public void setDragStartBoundView(@Nullable View view) {
        mDragStartBoundView = view;
    }

    /**
     * The top band where the system's own swipe-down begins: status-bar reveal in immersive
     * fullscreen, then the notification shade. Sticky-immersive delivers that edge swipe to the
     * app TOO, so without this exclusion "peek at the notifications" while fullscreen started the
     * dismiss drag and minimized the player. Band = the hidden bars' height (ignoring visibility -
     * the visible height is 0 while immersive) or the mandatory top gesture inset, whichever is
     * larger; raw screen coordinates, so it holds regardless of window insets/padding.
     */
    private boolean isInTopSystemGestureBand(MotionEvent ev) {
        int band = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        if (insets != null) {
            band = Math.max(
                    insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()
                            | WindowInsetsCompat.Type.displayCutout()).top,
                    insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).top);
        }
        if (band <= 0) {
            band = Math.round(24 * getResources().getDisplayMetrics().density);
        }
        return rawInRootY(ev.getRawY()) <= band;
    }

    /**
     * NEWTUBE(gestures): the same at the bottom - the home gesture and, in immersive fullscreen,
     * the swipe that brings the navigation bar back, which reaches the app too. Before the swipes
     * up (and the volume and brightness ones) existed, nothing here listened to an upward drag.
     */
    private boolean isInBottomSystemGestureBand(MotionEvent ev) {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        if (insets == null) {
            return false;
        }
        int band = Math.max(
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom);
        return band > 0 && rawInRootY(ev.getRawY()) >= getRootView().getHeight() - band;
    }

    /**
     * NEWTUBE(gestures): whether raw x {@code rawX} is in a side band where the system's back
     * gesture starts (gesture navigation; 0 wide with buttons). A sideways swipe from there is
     * Back's, not a seek.
     */
    public boolean isInSideSystemGestureBand(float rawX) {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        if (insets == null) {
            return false;
        }
        androidx.core.graphics.Insets gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures());
        getRootView().getLocationOnScreen(mRootLocation);
        float x = rawX - mRootLocation[0];
        return x < gestures.left || x >= getRootView().getWidth() - gestures.right;
    }

    /**
     * Raw (screen) y in the window's own terms: the insets and the root's height are the window's,
     * and in split screen the window does not start at the top of the screen.
     */
    private float rawInRootY(float rawY) {
        getRootView().getLocationOnScreen(mRootLocation);
        return rawY - mRootLocation[1];
    }

    /** Raw x/y of pointer {@code index}: getRawX(int) is API 29; all pointers share one offset. */
    private static float rawX(MotionEvent ev, int index) {
        return ev.getX(index) + ev.getRawX() - ev.getX();
    }

    private static float rawY(MotionEvent ev, int index) {
        return ev.getY(index) + ev.getRawY() - ev.getY();
    }

    private boolean isInDragRegion(MotionEvent ev) {
        if (mDragStartBoundView == null) {
            return true;
        }
        if (mDragStartBoundView.getVisibility() != VISIBLE
                || mDragStartBoundView.getWidth() == 0 || mDragStartBoundView.getHeight() == 0) {
            return false;
        }
        mDragStartBoundView.getLocationOnScreen(mTmpLocation);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= mTmpLocation[0] && x <= mTmpLocation[0] + mDragStartBoundView.getWidth()
                && y >= mTmpLocation[1] && y <= mTmpLocation[1] + mDragStartBoundView.getHeight();
    }

    /**
     * Which way a drag of ({@code dx}, {@code dy}) px from its start has gone, or 0 while it is
     * still within {@code slop} or not yet clearly along one axis.
     */
    static int directionOf(float dx, float dy, float slop) {
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);
        if (ay > slop && ay > ax * 1.5f) {
            return dy > 0 ? DOWN : UP;
        }
        if (ax > slop && ax > ay * 1.5f) {
            return dx > 0 ? RIGHT : LEFT;
        }
        return 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mListener == null) {
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownRawX = ev.getRawX();
                mDownRawY = ev.getRawY();
                mActivePointerId = ev.getPointerId(0);
                mSwipe = SWIPE_NONE;
                mUndecided = isInDragRegion(ev) && !isInTopSystemGestureBand(ev)
                        && !isInBottomSystemGestureBand(ev);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger means pinch-zoom (PinchZoomLayout); a swipe must not start off
                // one of the pinching fingers drifting.
                mUndecided = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSwipe == SWIPE_NONE && mUndecided) {
                    int index = ev.findPointerIndex(mActivePointerId);
                    if (index < 0) {
                        break;
                    }
                    float dx = rawX(ev, index) - mDownRawX;
                    float dy = rawY(ev, index) - mDownRawY;
                    int direction = directionOf(dx, dy, mTouchSlop * 2);
                    if (direction != 0) {
                        mUndecided = false;
                        mSwipe = mListener.onSwipeStart(direction, mDownRawX, mDownRawY, dx, dy);
                        if (mSwipe != SWIPE_NONE) {
                            startVelocityTracking(ev);
                            return true;
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mUndecided = false;
                mSwipe = SWIPE_NONE;
                break;
        }

        return mSwipe != SWIPE_NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mSwipe == SWIPE_NONE || mListener == null) {
            return super.onTouchEvent(ev);
        }

        if (mVelocityTracker == null) {
            startVelocityTracking(ev);
        } else {
            mVelocityTracker.addMovement(ev);
        }

        int swipe = mSwipe;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_UP) {
            if (ev.getPointerId(ev.getActionIndex()) != mActivePointerId) {
                return true; // another finger: the swipe never follows it
            }
            action = MotionEvent.ACTION_UP; // the swipe's own finger lifted: that ends it
        }
        int index = ev.findPointerIndex(mActivePointerId);
        if (index < 0 && action != MotionEvent.ACTION_CANCEL) {
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                mListener.onSwipeMove(swipe, rawX(ev, index) - mDownRawX, rawY(ev, index) - mDownRawY);
                return true;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocityTracker();
                mSwipe = SWIPE_NONE;
                mListener.onSwipeCancelled(swipe);
                return true;
            case MotionEvent.ACTION_UP:
                float xVelocity = 0f;
                float yVelocity = 0f;
                if (mVelocityTracker != null) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    xVelocity = mVelocityTracker.getXVelocity(mActivePointerId);
                    yVelocity = mVelocityTracker.getYVelocity(mActivePointerId);
                }
                recycleVelocityTracker();
                mSwipe = SWIPE_NONE;
                mListener.onSwipeReleased(swipe, rawX(ev, index) - mDownRawX, rawY(ev, index) - mDownRawY,
                        xVelocity, yVelocity);
                return true;
        }

        return true;
    }

    private void startVelocityTracking(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }
}

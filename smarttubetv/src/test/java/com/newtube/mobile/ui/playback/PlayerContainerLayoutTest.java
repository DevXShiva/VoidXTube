package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEWTUBE(gestures): the player's swipe arbiter - a drag becomes a swipe only once it is clearly
 * along one axis past twice the touch slop and the listener claims it; until then (and for good,
 * once declined) the views under the finger keep the touch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerContainerLayoutTest {
    private static final int CLAIMED = 7;

    private PlayerContainerLayout mContainer;
    private final List<String> mChildSaw = new ArrayList<>();
    private final List<String> mSwipes = new ArrayList<>();
    /** What the listener answers to each direction (0 = decline). */
    private final int[] mClaims = new int[5];
    private float mSlop;
    private long mDownTime;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
        mContainer = new PlayerContainerLayout(context);
        View child = new View(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                mChildSaw.add(name(event.getActionMasked()));
                return true;
            }
        };
        mContainer.addView(child, new PlayerContainerLayout.LayoutParams(1000, 1000));
        mContainer.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        mContainer.layout(0, 0, 1000, 1000);
        mContainer.setSwipeListener(new PlayerContainerLayout.SwipeListener() {
            @Override
            public int onSwipeStart(int direction, float downRawX, float downRawY, float dx, float dy) {
                mSwipes.add("start " + direction + " at " + Math.round(downRawX) + "," + Math.round(downRawY));
                return mClaims[direction];
            }

            @Override
            public void onSwipeMove(int swipe, float dx, float dy) {
                mSwipes.add("move " + swipe + " " + Math.round(dx) + "," + Math.round(dy));
            }

            @Override
            public void onSwipeReleased(int swipe, float dx, float dy, float xVelocity, float yVelocity) {
                mSwipes.add("release " + swipe + " " + Math.round(dx) + "," + Math.round(dy));
            }

            @Override
            public void onSwipeCancelled(int swipe) {
                mSwipes.add("cancel " + swipe);
            }
        });
    }

    @Test
    public void directionNeedsTheSlopAndOneClearAxis() {
        assertEquals(0, PlayerContainerLayout.directionOf(0, 10, 16));
        assertEquals(PlayerContainerLayout.DOWN, PlayerContainerLayout.directionOf(0, 17, 16));
        assertEquals(PlayerContainerLayout.UP, PlayerContainerLayout.directionOf(5, -30, 16));
        assertEquals(PlayerContainerLayout.RIGHT, PlayerContainerLayout.directionOf(30, 10, 16));
        assertEquals(PlayerContainerLayout.LEFT, PlayerContainerLayout.directionOf(-30, -10, 16));
        assertEquals(0, PlayerContainerLayout.directionOf(30, 25, 16)); // diagonal: not yet anything
    }

    @Test
    public void aClaimedSwipeTakesTheTouchFromTheChild() {
        mClaims[PlayerContainerLayout.DOWN] = CLAIMED;
        dispatch(MotionEvent.ACTION_DOWN, 500, 300);
        dispatch(MotionEvent.ACTION_MOVE, 500, 300 + mSlop + 4);
        dispatch(MotionEvent.ACTION_MOVE, 500, 400);
        dispatch(MotionEvent.ACTION_UP, 502, 450);

        assertEquals(Arrays.asList("down", "cancel"), mChildSaw); // the move that decided it reaches the child as its cancel
        assertEquals(Arrays.asList("start 2 at 500,300", "move 7 0,100", "release 7 2,150"), mSwipes);
    }

    @Test
    public void aDeclinedSwipeStaysTheChildsEvenIfItTurns() {
        mClaims[PlayerContainerLayout.DOWN] = CLAIMED; // a vertical swipe would be taken...
        dispatch(MotionEvent.ACTION_DOWN, 500, 300);
        dispatch(MotionEvent.ACTION_MOVE, 500 + mSlop + 4, 300); // ...but this one went sideways first
        dispatch(MotionEvent.ACTION_MOVE, 520, 500);
        dispatch(MotionEvent.ACTION_UP, 520, 500);

        assertEquals(Arrays.asList("down", "move", "move", "up"), mChildSaw);
        assertEquals(Arrays.asList("start 4 at 500,300"), mSwipes);
    }

    @Test
    public void aSecondFingerIsAPinchNotASwipe() {
        mClaims[PlayerContainerLayout.DOWN] = CLAIMED;
        dispatch(MotionEvent.ACTION_DOWN, 500, 300);
        MotionEvent pointer = twoFingers(MotionEvent.ACTION_POINTER_DOWN, 500, 300, 700, 300);
        mContainer.dispatchTouchEvent(pointer);
        pointer.recycle();
        dispatch(MotionEvent.ACTION_MOVE, 500, 450);

        assertTrue(mSwipes.isEmpty());
    }

    @Test
    public void aTakenSwipeIsCancelledNotReleased() {
        mClaims[PlayerContainerLayout.LEFT] = CLAIMED;
        dispatch(MotionEvent.ACTION_DOWN, 500, 300);
        dispatch(MotionEvent.ACTION_MOVE, 500 - mSlop - 4, 300);
        dispatch(MotionEvent.ACTION_CANCEL, 400, 300);

        assertEquals("cancel 7", mSwipes.get(mSwipes.size() - 1));
    }

    @Test
    public void theSwipeEndsWithItsOwnFingerNotASecondOne() {
        mClaims[PlayerContainerLayout.RIGHT] = CLAIMED;
        dispatch(MotionEvent.ACTION_DOWN, 300, 300);
        dispatch(MotionEvent.ACTION_MOVE, 300 + mSlop + 4, 300);
        dispatch(MotionEvent.ACTION_MOVE, 400, 300);
        // A second finger lands far away and moves: the swipe keeps following the first.
        dispatchTwo(MotionEvent.ACTION_POINTER_DOWN, 1, 400, 300, 900, 600);
        dispatchTwo(MotionEvent.ACTION_MOVE, -1, 420, 300, 950, 650);
        assertEquals("move 7 120,0", mSwipes.get(mSwipes.size() - 1));
        // The first finger lifts: that is the release, where it lifted.
        dispatchTwo(MotionEvent.ACTION_POINTER_UP, 0, 430, 300, 950, 650);
        assertEquals("release 7 130,0", mSwipes.get(mSwipes.size() - 1));
        int calls = mSwipes.size();
        // The second finger's moves and lift drive nothing.
        dispatchOne(MotionEvent.ACTION_MOVE, 1, 100, 650);
        dispatchOne(MotionEvent.ACTION_UP, 1, 100, 650);
        assertEquals(calls, mSwipes.size());
    }

    @Test
    public void outsideTheBoundViewNothingStarts() {
        View box = new View(RuntimeEnvironment.getApplication());
        mContainer.addView(box, new PlayerContainerLayout.LayoutParams(1000, 200));
        mContainer.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        mContainer.layout(0, 0, 1000, 1000);
        mContainer.setDragStartBoundView(box); // the video box: the top 200 px
        mClaims[PlayerContainerLayout.UP] = CLAIMED;

        dispatch(MotionEvent.ACTION_DOWN, 500, 600);
        dispatch(MotionEvent.ACTION_MOVE, 500, 400);
        dispatch(MotionEvent.ACTION_UP, 500, 400);
        assertTrue(mSwipes.isEmpty());

        dispatch(MotionEvent.ACTION_DOWN, 500, 150);
        dispatch(MotionEvent.ACTION_MOVE, 500, 50);
        assertEquals(Arrays.asList("start 1 at 500,150"), mSwipes);
    }

    private void dispatch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            mDownTime = now;
        }
        MotionEvent event = MotionEvent.obtain(mDownTime, now, action, x, y, 0);
        try {
            mContainer.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** Fingers 0 and 1; {@code actionIndex} is the one going down or up (-1 for a move). */
    private void dispatchTwo(int action, int actionIndex, float x0, float y0, float x1, float y1) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        float[][] at = {{x0, y0}, {x1, y1}};
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = at[i][0];
            coords[i].y = at[i][1];
        }
        int fullAction = actionIndex < 0 ? action : action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        MotionEvent event = MotionEvent.obtain(mDownTime, SystemClock.uptimeMillis(), fullAction, 2,
                properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
        try {
            mContainer.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** One finger with id {@code id}. */
    private void dispatchOne(int action, int id, float x, float y) {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = id;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = y;
        MotionEvent event = MotionEvent.obtain(mDownTime, SystemClock.uptimeMillis(), action, 1,
                properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
        try {
            mContainer.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private MotionEvent twoFingers(int action, float x0, float y0, float x1, float y1) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        float[][] at = {{x0, y0}, {x1, y1}};
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = at[i][0];
            coords[i].y = at[i][1];
        }
        return MotionEvent.obtain(mDownTime, SystemClock.uptimeMillis(),
                action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords,
                0, 0, 1f, 1f, 0, 0, 0, 0);
    }

    private static String name(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "down";
            case MotionEvent.ACTION_MOVE:
                return "move";
            case MotionEvent.ACTION_UP:
                return "up";
            case MotionEvent.ACTION_CANCEL:
                return "cancel";
            default:
                return "other";
        }
    }
}

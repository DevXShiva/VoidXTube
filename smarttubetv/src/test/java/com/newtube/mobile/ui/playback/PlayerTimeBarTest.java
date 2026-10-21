package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.media3.ui.TimeBar;
import org.robolectric.RuntimeEnvironment;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

/**
 * NEWTUBE(seek bar): the seek bar's touch rules, YouTube's - a drag moves the dot as far as the
 * finger moves, from wherever it lands (no jump, a tap seeks nothing), after a 6 dp slop; near a
 * screen edge the dot runs just fast enough to reach that end 16 dp short of the edge; a drag that
 * went away and comes back onto where playback was arms "Release to cancel" without sticking there;
 * the hidden bar leaves touches to the video. Density 1 here, so dp = px: a 1000 px track over a
 * 100 s video is 100 ms per px, and the bar is its own window from x = 0 to 1000.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerTimeBarTest {
    private PlayerTimeBar mBar;
    private final List<String> mEvents = new ArrayList<>();
    private final List<Boolean> mCancelArmed = new ArrayList<>();
    private long mDownTime;

    @Before
    public void setUp() {
        mBar = new PlayerTimeBar(RuntimeEnvironment.getApplication());
        mBar.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(18, View.MeasureSpec.EXACTLY));
        mBar.layout(0, 0, 1000, 18);
        mBar.setDuration(100_000);
        mBar.setPosition(50_000); // the dot at x = 500
        mBar.addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                mEvents.add("start " + position);
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
                mEvents.add("move " + position);
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                mEvents.add((canceled ? "cancel " : "stop ") + position);
            }
        });
        mBar.setCancelListener(mCancelArmed::add);
    }

    @Test
    public void aDragMovesTheDotAsFarAsTheFingerFromWhereItLands() {
        assertTrue(touch(MotionEvent.ACTION_DOWN, 300)); // 200 px left of the dot at 500
        assertTrue(mEvents.isEmpty()); // no jump to the finger
        touch(MotionEvent.ACTION_MOVE, 306); // the 6 dp slop: not a drag yet
        assertTrue(mEvents.isEmpty());
        touch(MotionEvent.ACTION_MOVE, 406); // 100 px on from the slop
        touch(MotionEvent.ACTION_UP, 406);

        assertEquals(Arrays.asList("start 50000", "move 60000", "stop 60000"), mEvents);
        assertTrue(mCancelArmed.isEmpty());
    }

    @Test
    public void aTapSeeksNothing() {
        assertTrue(touch(MotionEvent.ACTION_DOWN, 800));
        touch(MotionEvent.ACTION_MOVE, 805);
        assertTrue(touch(MotionEvent.ACTION_UP, 805));
        assertTrue(mEvents.isEmpty());
    }

    @Test
    public void aSmallDragSeeksTheLittleItMoved() {
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_MOVE, 310);
        touch(MotionEvent.ACTION_UP, 310);
        assertEquals("stop 50400", last()); // 4 px past the slop; never away, so no cancel either
    }

    @Test
    public void comingBackOntoPlaybackArmsReleaseToCancelWithoutSticking() {
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_MOVE, 400); // away: 59.4 s
        touch(MotionEvent.ACTION_MOVE, 320);
        assertTrue(mCancelArmed.isEmpty()); // 1.4 s off: not back yet
        touch(MotionEvent.ACTION_MOVE, 309);
        assertEquals("move 50300", last()); // within 4 dp: armed, and the time still follows the finger
        assertEquals(Arrays.asList(true), mCancelArmed);

        touch(MotionEvent.ACTION_UP, 309);
        assertEquals("cancel 50000", last());
        assertEquals(Arrays.asList(true, false), mCancelArmed);
    }

    @Test
    public void leavingTheCancelAgainSeeksNormally() {
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_MOVE, 400);
        touch(MotionEvent.ACTION_MOVE, 308);
        touch(MotionEvent.ACTION_MOVE, 312); // 6 px off: inside the 8 dp release margin, still armed
        assertEquals(Arrays.asList(true), mCancelArmed);

        touch(MotionEvent.ACTION_MOVE, 330);
        touch(MotionEvent.ACTION_UP, 330);
        assertEquals(Arrays.asList(true, false), mCancelArmed);
        assertEquals("stop 52400", last());
    }

    @Test
    public void fromADotNearTheStartTheFingerReachesItShortOfTheEdge() {
        // The owner's case: a minute into a 20-minute video the dot sits 70 px from the edge, and a
        // fingertip lets go before its middle gets to the edge.
        mBar.setPosition(7_000); // the dot at x = 70
        touch(MotionEvent.ACTION_DOWN, 70);
        touch(MotionEvent.ACTION_MOVE, 60); // the drag starts at 64: 70 px of track, 48 px of room to 16
        touch(MotionEvent.ACTION_MOVE, 40);
        assertEquals("move 3500", last()); // the times in between stay reachable
        touch(MotionEvent.ACTION_MOVE, 16);
        assertEquals("move 0", last());
        touch(MotionEvent.ACTION_UP, 12);
        assertEquals("stop 0", last());
    }

    @Test
    public void nearTheStartASmallDragStillMovesALittle() {
        mBar.setPosition(7_000);
        touch(MotionEvent.ACTION_DOWN, 70);
        touch(MotionEvent.ACTION_MOVE, 77); // 1 px past the slop, rightwards: ~1:1 there
        assertEquals("start 7000", mEvents.get(0));
        assertEquals(7_100.0, lastPosition(), 10.0);
    }

    @Test
    public void aDragBegunInsideTheReachGoesStraightToThatEnd() {
        mBar.setPosition(1_500); // the dot at x = 15: the finger on it is already within 16 dp of the edge
        touch(MotionEvent.ACTION_DOWN, 15);
        touch(MotionEvent.ACTION_MOVE, 8);
        touch(MotionEvent.ACTION_UP, 8);
        assertEquals("stop 0", last());
    }

    @Test
    public void theStartNeverCancelsFromADotJustAfterIt() {
        mBar.setPosition(300); // the dot at x = 3: the start is within its 4 dp cancel margin
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_MOVE, 400); // away
        touch(MotionEvent.ACTION_MOVE, 290); // on the start
        assertEquals("move 0", last());
        touch(MotionEvent.ACTION_MOVE, 304); // 1 px off it, 2 px from the dot: no re-arm at the line
        touch(MotionEvent.ACTION_MOVE, 290);
        touch(MotionEvent.ACTION_UP, 290);
        assertTrue(mCancelArmed.isEmpty());
        assertEquals("stop 0", last());
    }

    @Test
    public void aFingerRestingOnTheBarLeavesAnArrowKeyScrubAlone() {
        touch(MotionEvent.ACTION_DOWN, 300);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); // 55 s
        touch(MotionEvent.ACTION_MOVE, 400); // would have been a drag
        touch(MotionEvent.ACTION_UP, 400);
        assertEquals(Arrays.asList("start 55000", "move 55000"), mEvents);
        key(KeyEvent.KEYCODE_ENTER);
        assertEquals("stop 55000", last());
    }

    @Test
    public void aSecondFingerNeitherTakesOverNorJumpsTheDrag() {
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_MOVE, 406); // 60 s
        pointers(MotionEvent.ACTION_POINTER_DOWN, 1, 406, 950);
        pointers(MotionEvent.ACTION_MOVE, -1, 406, 960); // only the second finger moved
        assertEquals("move 60000", last());
        pointers(MotionEvent.ACTION_POINTER_UP, 0, 406, 960); // the dragging finger lifts
        assertEquals("stop 60000", last());
        assertFalse(mBar.isHeld());

        int count = mEvents.size();
        pointers1(MotionEvent.ACTION_MOVE, 1000); // the other finger, now alone
        pointers1(MotionEvent.ACTION_UP, 1000);
        assertEquals(count, mEvents.size());
    }

    @Test
    public void theBarIsHeldFromTheFirstTouch() {
        assertFalse(mBar.isHeld());
        touch(MotionEvent.ACTION_DOWN, 300); // not a drag yet: the controls must stay all the same
        assertTrue(mBar.isHeld());
        touch(MotionEvent.ACTION_MOVE, 400);
        assertTrue(mBar.isHeld());
        touch(MotionEvent.ACTION_UP, 400);
        assertFalse(mBar.isHeld());
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_UP, 300);
        assertFalse(mBar.isHeld());
    }

    @Test
    public void aDragReachesTheEndShortOfTheOtherEdge() {
        mBar.setPosition(93_000); // the dot at x = 930
        touch(MotionEvent.ACTION_DOWN, 930);
        touch(MotionEvent.ACTION_MOVE, 940); // starts at 936: 70 px of track, 48 px of room to 984
        touch(MotionEvent.ACTION_MOVE, 984);
        touch(MotionEvent.ACTION_UP, 990);
        assertEquals("stop 100000", last());
    }

    @Test
    public void withRoomPastTheEndsTheDotKeepsPaceWithTheFinger() {
        // Fullscreen: the track sits 30 px in from the window's edges, more than the 16 dp reach.
        mBar.setPadding(30, 0, 30, 0); // a 940 px track from x = 30; the dot at 30 + 470 = 500
        touch(MotionEvent.ACTION_DOWN, 500);
        touch(MotionEvent.ACTION_MOVE, 400); // the drag starts at 494
        assertEquals("move 40000", last()); // 94 px of 940: 1:1
        touch(MotionEvent.ACTION_UP, 20); // past the track's end
        assertEquals("stop 0", last());
    }

    @Test
    public void aTakenTouchCancels() {
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_MOVE, 400);
        touch(MotionEvent.ACTION_CANCEL, 400);
        assertEquals("cancel 50000", last());

        mEvents.clear();
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_CANCEL, 300); // taken before it was a drag
        assertTrue(mEvents.isEmpty());
    }

    @Test
    public void theHiddenBarLeavesTouchesToTheVideo() {
        mBar.setShown(false, false);
        assertFalse(touch(MotionEvent.ACTION_DOWN, 800));
        assertTrue(mEvents.isEmpty());
    }

    @Test
    public void hidingTheControlsMidDragCancelsIt() {
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_MOVE, 400);
        mBar.setShown(false, false);
        assertEquals("cancel 50000", last());
        touch(MotionEvent.ACTION_UP, 400);
        assertEquals("cancel 50000", last());
    }

    @Test
    public void letGoIsWhereTheFingerLifts() {
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_MOVE, 350);
        touch(MotionEvent.ACTION_UP, 406);
        assertEquals("stop 60000", last());
    }

    @Test
    public void theTouchBandReachesPastTheThinView() {
        // The 2 px track lies on the 18 px view's bottom edge, its middle at y = 17: the band runs
        // 20 above it, 16 below it (over the page, in portrait) and 12 past its ends.
        assertTrue(mBar.isInTouchBand(500, 17 - 20));
        assertFalse(mBar.isInTouchBand(500, 17 - 21));
        assertTrue(mBar.isInTouchBand(500, 17 + 16));
        assertFalse(mBar.isInTouchBand(500, 17 + 17));
        assertTrue(mBar.isInTouchBand(-12, 17));
        assertFalse(mBar.isInTouchBand(-13, 17));
        assertTrue(mBar.isInTouchBand(1012, 17));
        assertFalse(mBar.isInTouchBand(1013, 17));
    }

    @Test
    public void theBandFollowsACenteredTrack() {
        mBar.setTrackAtBottom(false); // fullscreen: the track in the middle, y = 9
        assertTrue(mBar.isInTouchBand(500, 9 - 20));
        assertFalse(mBar.isInTouchBand(500, 9 - 21));
        assertTrue(mBar.isInTouchBand(500, 9 + 16));
        assertFalse(mBar.isInTouchBand(500, 9 + 17));
    }

    @Test
    public void theHiddenBarHasNoBand() {
        mBar.setShown(false, false);
        assertFalse(mBar.isInTouchBand(500, 17));
    }

    @Test
    public void aRoutedBarTakesTouchesOnlyFromItsRouter() {
        mBar.setTouchRouted(true);
        assertFalse(touch(MotionEvent.ACTION_DOWN, 800)); // on the view itself: left to the views under it
        assertTrue(mEvents.isEmpty());

        assertTrue(routed(MotionEvent.ACTION_DOWN, 300, 30)); // under the view, in the band
        routed(MotionEvent.ACTION_MOVE, 406, 30);
        routed(MotionEvent.ACTION_UP, 406, 30);
        assertEquals("start 50000", mEvents.get(0));
        assertEquals("stop 60000", last());
    }

    @Test
    public void arrowKeysScrubAndEnterLands() {
        assertTrue(key(KeyEvent.KEYCODE_DPAD_RIGHT)); // a twentieth of the video: 5 s
        assertEquals(Arrays.asList("start 55000", "move 55000"), mEvents);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("move 60000", last());
        assertTrue(key(KeyEvent.KEYCODE_ENTER));
        assertEquals("stop 60000", last());
    }

    @Test
    public void anArrowKeyScrubLandsASecondAfterTheLastKey() {
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999));
        assertEquals("move 45000", last());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertEquals("stop 45000", last());
    }

    @Test
    public void theHiddenBarIsNoControl() {
        mBar.setShown(false, false);
        assertFalse(mBar.isFocusable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, mBar.getImportantForAccessibility());
        assertFalse(key(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertFalse(mBar.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
        assertTrue(mEvents.isEmpty());

        mBar.setShown(true, false);
        assertTrue(mBar.isFocusable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, mBar.getImportantForAccessibility());
    }

    @Test
    public void chapterIndexCountsTheStartsPassed() {
        long[] starts = {10_000, 20_000, 35_000};
        assertEquals(0, PlayerTimeBar.chapterAt(starts, 0));
        assertEquals(0, PlayerTimeBar.chapterAt(starts, 9_999));
        assertEquals(1, PlayerTimeBar.chapterAt(starts, 10_000));
        assertEquals(2, PlayerTimeBar.chapterAt(starts, 34_999));
        assertEquals(3, PlayerTimeBar.chapterAt(starts, 90_000));
        assertEquals(0, PlayerTimeBar.chapterAt(new long[0], 5_000));
    }

    @Test
    public void chapterStartsAreSortedAndTheImplicitZeroDropped() {
        mBar.setChapterStarts(new long[] {35_000, 0, 10_000, 10_000});
        assertEquals(0, mBar.chapterAt(5_000));
        assertEquals(1, mBar.chapterAt(20_000));
        assertEquals(2, mBar.chapterAt(40_000));
    }

    @Test
    public void theClockReadsLikeYouTubes() {
        StringBuilder builder = new StringBuilder();
        Formatter formatter = new Formatter(builder, Locale.US);
        assertEquals("0:00", PlayerTimeBar.formatTime(builder, formatter, 0));
        assertEquals("0:05", PlayerTimeBar.formatTime(builder, formatter, 5_000));
        assertEquals("2:18", PlayerTimeBar.formatTime(builder, formatter, 138_000));
        assertEquals("59:59", PlayerTimeBar.formatTime(builder, formatter, 3_599_000));
        assertEquals("4:26:52", PlayerTimeBar.formatTime(builder, formatter, 16_012_000));
        assertEquals("0:00", PlayerTimeBar.formatTime(builder, formatter, -1_000));
    }

    // NEWTUBE(gestures): a sideways swipe on the video scrubs by the finger's travel.

    @Test
    public void aSwipeSeeksByTheFingersTravel() {
        assertTrue(mBar.startSwipeScrub(300));
        mBar.moveSwipeScrub(400); // 100 dp: 10 s + 10 s
        mBar.stopSwipeScrub(false);
        assertEquals(Arrays.asList("start 50000", "move 70000", "stop 70000"), mEvents);
    }

    @Test
    public void aSwipeIsFineWhenShortAndFastWhenLong() {
        assertEquals(1_010, PlayerTimeBar.swipeOffsetMs(10f));
        assertEquals(10_932, PlayerTimeBar.swipeOffsetMs(72f)); // about a centimetre: a double tap
        assertEquals(-181_250, PlayerTimeBar.swipeOffsetMs(-250f));
        assertEquals(0, PlayerTimeBar.swipeOffsetMs(0f));
    }

    @Test
    public void aSwipeStopsAtTheEnds() {
        mBar.startSwipeScrub(300);
        mBar.moveSwipeScrub(900);
        assertEquals("move 100000", last());
        mBar.moveSwipeScrub(-300);
        assertEquals("move 0", last());
    }

    @Test
    public void aSwipeBackOntoPlaybackCancelsLikeTheBar() {
        mBar.startSwipeScrub(300);
        mBar.moveSwipeScrub(400);
        mBar.moveSwipeScrub(301);
        assertEquals(Arrays.asList(true), mCancelArmed);
        mBar.stopSwipeScrub(false);
        assertEquals("cancel 50000", last());
    }

    @Test
    public void aTakenSwipeSeeksNothing() {
        mBar.startSwipeScrub(300);
        mBar.moveSwipeScrub(450);
        mBar.stopSwipeScrub(true);
        assertEquals("cancel 50000", last());
        mBar.moveSwipeScrub(600); // over: later moves drive nothing
        mBar.stopSwipeScrub(false);
        assertEquals("cancel 50000", last());
    }

    @Test
    public void aNewVideoEndsTheScrubSeekingNothing() {
        mBar.startSwipeScrub(300);
        mBar.moveSwipeScrub(450);
        mBar.cancelScrub();
        assertEquals("cancel 50000", last());
        mBar.moveSwipeScrub(600);
        mBar.stopSwipeScrub(false);
        assertEquals("cancel 50000", last());

        touch(MotionEvent.ACTION_DOWN, 300); // the bar's own drag too
        touch(MotionEvent.ACTION_MOVE, 400);
        mBar.cancelScrub();
        assertEquals("cancel 50000", last());
        touch(MotionEvent.ACTION_UP, 450);
        assertEquals("cancel 50000", last());
    }

    @Test
    public void noSwipeScrubWithoutAVideoLength() {
        mBar.setDuration(0);
        assertFalse(mBar.startSwipeScrub(300));
        assertTrue(mEvents.isEmpty());
    }

    @Test
    public void noSwipeScrubWhileAFingerIsOnTheBar() {
        touch(MotionEvent.ACTION_DOWN, 300);
        assertFalse(mBar.startSwipeScrub(300));
        touch(MotionEvent.ACTION_UP, 300);
        assertTrue(mEvents.isEmpty());
    }

    private boolean touch(int action, float x) {
        MotionEvent event = event(action, x, 9);
        try {
            return mBar.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private boolean key(int keyCode) {
        return mBar.onKeyDown(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
    }

    private boolean routed(int action, float x, float y) {
        MotionEvent event = event(action, x, y);
        try {
            return mBar.onRoutedTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private MotionEvent event(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            mDownTime = now;
        }
        return MotionEvent.obtain(mDownTime, now, action, x, y, 0);
    }

    /** Two fingers, ids 0 and 1, at x0 and x1; {@code actionIndex} is the finger going down or up. */
    private void pointers(int action, int actionIndex, float x0, float x1) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        float[] xs = {x0, x1};
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = 9;
        }
        int fullAction = actionIndex < 0 ? action
                : action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        MotionEvent event = MotionEvent.obtain(mDownTime, SystemClock.uptimeMillis(), fullAction, 2,
                properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
        try {
            mBar.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** Finger id 1 alone (after finger 0 lifted). */
    private void pointers1(int action, float x) {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = 1;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = 9;
        MotionEvent event = MotionEvent.obtain(mDownTime, SystemClock.uptimeMillis(), action, 1,
                properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
        try {
            mBar.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private String last() {
        return mEvents.get(mEvents.size() - 1);
    }

    private double lastPosition() {
        String event = last();
        return Double.parseDouble(event.substring(event.indexOf(' ') + 1));
    }
}

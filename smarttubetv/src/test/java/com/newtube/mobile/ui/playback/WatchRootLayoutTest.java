package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

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
 * NEWTUBE(seek bar): the watch column hands a claimed touch - the seek bar's band - to its router
 * whole, before the child under the finger sees any of it; the rest reaches the children as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class WatchRootLayoutTest {
    private final List<String> mChildSaw = new ArrayList<>();
    private final List<String> mRouted = new ArrayList<>();
    private long mDownTime;

    @Test
    public void aClaimedTouchGoesWholeToTheRouter() {
        Context context = RuntimeEnvironment.getApplication();
        WatchRootLayout root = new WatchRootLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        View child = new View(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                mChildSaw.add(name(event));
                return true;
            }
        };
        root.addView(child, new LinearLayout.LayoutParams(100, 100));
        root.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 100, 100);
        root.setTouchRouter(new WatchRootLayout.TouchRouter() {
            @Override
            public boolean claimDown(MotionEvent down) {
                return down.getY() >= 80; // the band: the bottom 20 px
            }

            @Override
            public void route(MotionEvent event) {
                mRouted.add(name(event) + " " + Math.round(event.getY()));
            }
        });

        dispatch(root, MotionEvent.ACTION_DOWN, 90);
        dispatch(root, MotionEvent.ACTION_MOVE, 60); // leaving the band keeps the touch routed
        dispatch(root, MotionEvent.ACTION_UP, 60);
        assertEquals(Arrays.asList("down 90", "move 60", "up 60"), mRouted);
        assertTrue(mChildSaw.isEmpty());

        dispatch(root, MotionEvent.ACTION_DOWN, 20);
        dispatch(root, MotionEvent.ACTION_UP, 20);
        assertEquals(Arrays.asList("down", "up"), mChildSaw);
        assertEquals(3, mRouted.size());
    }

    private void dispatch(View root, int action, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            mDownTime = now;
        }
        MotionEvent event = MotionEvent.obtain(mDownTime, now, action, 50, y, 0);
        root.dispatchTouchEvent(event);
        event.recycle();
    }

    private static String name(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return "down";
            case MotionEvent.ACTION_MOVE:
                return "move";
            case MotionEvent.ACTION_UP:
                return "up";
            default:
                return "other";
        }
    }
}

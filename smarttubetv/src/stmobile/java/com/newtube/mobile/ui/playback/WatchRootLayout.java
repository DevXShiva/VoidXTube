package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * NEWTUBE(seek bar): the watch page's column - the video box, then the page under it - drawing the
 * video box LAST. The seek bar's dot sits on the video's bottom edge like YouTube's, so half of it
 * hangs over the top of the page; drawn first, the box had that half painted over by the page's
 * opaque background. This and the video box leave their children unclipped (layout XML).
 *
 * <p>The two children never overlap, so touches go to one or the other - except the seek bar's
 * touch band, which straddles the video's edge: a {@link TouchRouter} claims those touches here,
 * before either child hit-tests them.</p>
 */
public class WatchRootLayout extends LinearLayout {

    /**
     * Claims a whole touch before any child sees it (the seek bar's band, half of it over the page
     * that the video box never gets a touch from). Coordinates are this layout's.
     */
    public interface TouchRouter {
        boolean claimDown(MotionEvent down);

        void route(MotionEvent event);
    }

    @Nullable
    private TouchRouter mTouchRouter;
    private boolean mRouting;

    public WatchRootLayout(Context context) {
        this(context, null);
    }

    public WatchRootLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setChildrenDrawingOrderEnabled(true);
    }

    public void setTouchRouter(@Nullable TouchRouter router) {
        mTouchRouter = router;
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        // 1, 2, ..., n-1, then 0: the first child (the video box) on top of the rest.
        return childCount < 2 ? drawingPosition : (drawingPosition + 1) % childCount;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mRouting = mTouchRouter != null && mTouchRouter.claimDown(ev);
        }
        // Once claimed, the rest of the touch comes straight to onTouchEvent.
        return mRouting || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mRouting && mTouchRouter != null) {
            mTouchRouter.route(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mRouting = false;
            }
            return true;
        }
        return super.onTouchEvent(ev);
    }
}

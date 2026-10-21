package com.newtube.mobile.ui.update;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * A NestedScrollView that wraps its content up to {@link #setMaxHeight} and scrolls past it - the
 * update sheet's release notes, so a long list never pushes the buttons off the screen.
 */
public class MaxHeightScrollView extends NestedScrollView {
    private int mMaxHeight = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMaxHeight(int maxHeightPx) {
        if (mMaxHeight != maxHeightPx) {
            mMaxHeight = maxHeightPx;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxHeight == Integer.MAX_VALUE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int mode = View.MeasureSpec.getMode(heightMeasureSpec);
        int size = View.MeasureSpec.getSize(heightMeasureSpec);
        int max = mode == View.MeasureSpec.UNSPECIFIED ? mMaxHeight : Math.min(size, mMaxHeight);

        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
    }
}

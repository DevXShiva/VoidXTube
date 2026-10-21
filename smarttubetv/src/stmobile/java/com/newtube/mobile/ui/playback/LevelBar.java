package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * NEWTUBE(gestures): the slim bar in the brightness/volume pill - a white fill on a faint white
 * track, both with round ends, as thin as the seek bar's dragged track family. No thumb: the
 * finger is on the video, not here.
 */
public class LevelBar extends View {
    private static final int TRACK_COLOR = 0x4DFFFFFF;
    private static final int FILL_COLOR = 0xFFFFFFFF;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mLevel;

    public LevelBar(Context context) {
        this(context, null);
    }

    public LevelBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 0..1. */
    public void setLevel(float level) {
        float clamped = Math.max(0f, Math.min(1f, level));
        if (clamped != mLevel) {
            mLevel = clamped;
            invalidate();
        }
    }

    public float getLevel() {
        return mLevel;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop();
        float bottom = getHeight() - getPaddingBottom();
        float radius = (bottom - top) / 2f;
        if (right <= left || bottom <= top) {
            return;
        }
        mPaint.setColor(TRACK_COLOR);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, mPaint);
        if (mLevel > 0f) {
            // Never thinner than its own round ends, so a low level still reads as a dot.
            float end = Math.max(left + 2f * radius, left + (right - left) * mLevel);
            mPaint.setColor(FILL_COLOR);
            canvas.drawRoundRect(left, top, end, bottom, radius, radius, mPaint);
        }
    }
}

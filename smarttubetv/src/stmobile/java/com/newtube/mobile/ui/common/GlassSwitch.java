package com.newtube.mobile.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * VOIDXDEV(glass switch): an iOS 26 style toggle - a flat 52x28dp capsule (accent on, grey off) and a
 * white pill knob, wider than tall. While it slides across, the knob swells into a clear glass
 * lens and settles back to white, like Liquid Glass. Drawn here, not with the switch's drawables:
 * SwitchCompat makes its track twice the knob's width, which stretched a pill knob's track to 76dp.
 */
public class GlassSwitch extends SwitchMaterial {
    private static final float TRACK_W = 52f;
    private static final float TRACK_H = 28f;
    private static final float KNOB_W = 32f;
    private static final float KNOB_H = 24f;
    private static final long SLIDE_MS = 300;

    private final float mDp;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final int mOn;
    private final int mOff;
    /** 0 = off, 1 = on. */
    private float mPosition;
    /** 0 = a white knob at rest, 1 = the clear glass lens mid-slide. */
    private float mLens;
    @Nullable
    private ValueAnimator mSlide;

    public GlassSwitch(Context context) {
        this(context, null);
    }

    public GlassSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mOn = ContextCompat.getColor(context, R.color.voidx_switch_on);
        mOff = ContextCompat.getColor(context, R.color.voidx_switch_off);
        setThumbDrawable(new ColorDrawable(Color.TRANSPARENT));
        setTrackDrawable(new ColorDrawable(Color.TRANSPARENT));
        setBackground(null);
        mPosition = isChecked() ? 1f : 0f;
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(TRACK_W * mDp) + getPaddingLeft() + getPaddingRight(),
                Math.round(32 * mDp) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (mDp == 0f) {
            return; // the super constructor sets the state before this class is ready
        }
        if (mSlide != null) {
            mSlide.cancel();
        }
        float target = checked ? 1f : 0f;
        if (!changed || !isAttachedToWindow() || !isLaidOut()) {
            mPosition = target; // a bound or recycled row jumps, a tapped one slides
            mLens = 0f;
            invalidate();
            return;
        }
        float from = mPosition;
        mSlide = ValueAnimator.ofFloat(0f, 1f).setDuration(SLIDE_MS);
        mSlide.setInterpolator(Motion.EMPHASIZED);
        mSlide.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            mPosition = from + (target - from) * t;
            mLens = (float) Math.sin(Math.PI * t); // swells mid-slide, settles at both ends
            invalidate();
        });
        mSlide.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = TRACK_W * mDp;
        float h = TRACK_H * mDp;
        float left = getPaddingLeft();
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - h) / 2f;

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(blend(mOff, mOn, mPosition));
        mRect.set(left, top, left + w, top + h);
        canvas.drawRoundRect(mRect, h / 2f, h / 2f, mPaint);

        float inset = (h - KNOB_H * mDp) / 2f;
        float knobW = KNOB_W * mDp * (1f + 0.22f * mLens);
        float knobH = KNOB_H * mDp * (1f + 0.25f * mLens);
        float travel = w - 2 * inset - KNOB_W * mDp;
        float cx = left + inset + KNOB_W * mDp / 2f + travel * mPosition;
        float cy = top + h / 2f;
        mRect.set(cx - knobW / 2f, cy - knobH / 2f, cx + knobW / 2f, cy + knobH / 2f);
        float r = knobH / 2f;

        // a soft shade under the white knob, gone while it is glass
        mPaint.setColor(Color.argb(Math.round(40 * (1f - mLens)), 0, 0, 0));
        mRect.offset(0, mDp);
        canvas.drawRoundRect(mRect, r, r, mPaint);
        mRect.offset(0, -mDp);

        mPaint.setColor(Color.argb(Math.round(255 - 185 * mLens), 255, 255, 255));
        canvas.drawRoundRect(mRect, r, r, mPaint);
        if (mLens > 0f) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(1.2f * mDp);
            mPaint.setColor(Color.argb(Math.round(230 * mLens), 255, 255, 255));
            canvas.drawRoundRect(mRect, r, r, mPaint);
        }
    }

    private static int blend(int from, int to, float t) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }
}

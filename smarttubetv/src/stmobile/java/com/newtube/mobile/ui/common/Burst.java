package com.newtube.mobile.ui.common;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * VOIDXDEV(burst): the splash a like or a subscribe makes - a ring that grows out of the button and
 * fades, and a spray of confetti dots flying off its edge. Drawn on the window's overlay, so a
 * clipping parent (the action row's scroll view) cannot cut it, and gone when it ends.
 */
public final class Burst extends Drawable {
    private static final long DURATION_MS = 620;
    private static final int DOTS = 12;
    private static final int[] COLORS = {0xFFFF453A, 0xFFFF9F0A, 0xFFFF375F, 0xFFFFD60A, 0xFF0A84FF, 0xFFBF5AF2};

    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mAnchor;
    private final RectF mRect = new RectF();
    private final float mDp;
    private final int mRingColor;
    private float mT;

    private Burst(RectF anchor, float dp, int ringColor) {
        mAnchor = anchor;
        mDp = dp;
        mRingColor = ringColor;
        mRing.setStyle(Paint.Style.STROKE);
    }

    /** Plays the splash around {@code anchor}; {@code ringColor} is the ring's (dots are confetti). */
    public static void play(@Nullable View anchor, int ringColor) {
        if (anchor == null || !anchor.isShown() || anchor.getWidth() == 0) {
            return;
        }
        View root = anchor.getRootView();
        if (!(root instanceof ViewGroup)) {
            return;
        }
        int[] a = new int[2];
        int[] r = new int[2];
        anchor.getLocationInWindow(a);
        root.getLocationInWindow(r);
        float left = a[0] - r[0];
        float top = a[1] - r[1];
        RectF rect = new RectF(left, top, left + anchor.getWidth(), top + anchor.getHeight());
        Burst burst = new Burst(rect, anchor.getResources().getDisplayMetrics().density, ringColor);
        burst.setBounds(0, 0, root.getWidth(), root.getHeight());
        ViewGroup group = (ViewGroup) root;
        group.getOverlay().add(burst);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS);
        animator.setInterpolator(Motion.STANDARD_DECELERATE);
        animator.addUpdateListener(va -> {
            burst.mT = (float) va.getAnimatedValue();
            burst.invalidateSelf();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                group.getOverlay().remove(burst);
            }
        });
        animator.start();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        float t = mT;
        float cx = mAnchor.centerX();
        float cy = mAnchor.centerY();
        float halfW = mAnchor.width() / 2f;
        float halfH = mAnchor.height() / 2f;

        // Ring: grows 18dp past the button over the first 60%, thinning and fading as it goes.
        float ringT = Math.min(1f, t / 0.6f);
        if (ringT < 1f) {
            float grow = 18 * mDp * ringT;
            mRing.setColor(mRingColor);
            mRing.setAlpha((int) (255 * (1f - ringT)));
            mRing.setStrokeWidth(Math.max(0.5f, 4 * mDp * (1f - ringT)));
            mRect.set(cx - halfW - grow, cy - halfH - grow, cx + halfW + grow, cy + halfH + grow);
            float corner = Math.min(halfW, halfH) + grow;
            canvas.drawRoundRect(mRect, corner, corner, mRing);
        }

        // Dots: start at the button's edge, fly 26dp out and shrink away; every other one is smaller
        // and offset half a step, so the spray doesn't look like a clock face.
        float fly = 26 * mDp * t;
        for (int i = 0; i < DOTS; i++) {
            double angle = Math.PI * 2 * i / DOTS + (i % 2 == 0 ? 0 : Math.PI / DOTS);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float reach = (i % 2 == 0 ? 1f : 0.7f) * fly;
            float x = cx + cos * (halfW + 2 * mDp + reach);
            float y = cy + sin * (halfH + 2 * mDp + reach);
            float radius = (i % 2 == 0 ? 3.2f : 2.2f) * mDp * (1f - t);
            if (radius <= 0) {
                continue;
            }
            mDot.setColor(COLORS[i % COLORS.length]);
            mDot.setAlpha((int) (255 * Math.min(1f, (1f - t) * 1.6f)));
            canvas.drawCircle(x, y, radius, mDot);
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

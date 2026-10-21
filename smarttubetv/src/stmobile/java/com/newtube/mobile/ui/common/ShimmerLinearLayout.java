package com.newtube.mobile.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.Build;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(motion): the loading skeletons' container. A soft highlight sweeps diagonally across its
 * blocks, left to right, then rests - the placeholder reads as "loading" without the whole shape
 * blinking (the alpha pulse it replaces). The highlight is drawn SRC_ATOP over this view's own
 * layer, so it lights only the blocks, never the gaps between them.
 *
 * <p>The sweep runs on one clock in WINDOW coordinates: every skeleton on screen (the feed's
 * cards, a column of comment rows, each its own view) shows the same band in the same place, as
 * one sheet of light. It redraws only while it is drawn, its window is visible and a sweep is
 * under way (the rest between sweeps is one delayed redraw), and it holds still when the system's
 * animations are off.</p>
 */
public class ShimmerLinearLayout extends LinearLayout {
    private static final long SWEEP_MS = 1100;
    private static final long REST_MS = 350;
    private static final long PERIOD_MS = SWEEP_MS + REST_MS;
    /** Band width as a share of the window width. */
    private static final float BAND = 0.55f;
    /** The band leans right going down: x = c + SLOPE * y. */
    private static final float SLOPE = 0.35f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final int[] mLocation = new int[2];
    private final Runnable mNextSweep = this::invalidate;
    private LinearGradient mShader;
    private int mShaderWidth;
    private int mShaderColor;

    public ShimmerLinearLayout(Context context) {
        this(context, null);
    }

    public ShimmerLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        // SRC_ATOP needs this view's pixels in a layer of their own.
        setLayerType(LAYER_TYPE_HARDWARE, null);
        mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        removeCallbacks(mNextSweep);
        View root = getRootView();
        int windowWidth = root.getWidth();
        int windowHeight = root.getHeight();
        if (windowWidth <= 0 || getWidth() <= 0 || getHeight() <= 0
                || getWindowVisibility() != VISIBLE || !animationsEnabled()) {
            return; // static blocks; onWindowVisibilityChanged restarts the sweep
        }
        long t = AnimationUtils.currentAnimationTimeMillis() % PERIOD_MS;
        if (t >= SWEEP_MS) {
            postDelayed(mNextSweep, PERIOD_MS - t);
            return;
        }
        ensureShader(windowWidth);
        float progress = Motion.STANDARD.getInterpolation(t / (float) SWEEP_MS);
        float band = windowWidth * BAND;
        // Start clear of the window's top-left corner, end clear of its bottom-right one.
        float from = -band - SLOPE * windowHeight;
        float c = from + (windowWidth - from) * progress;
        getLocationInWindow(mLocation);
        mMatrix.setTranslate(c - mLocation[0], -mLocation[1]);
        mShader.setLocalMatrix(mMatrix);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mNextSweep);
        super.onDetachedFromWindow();
    }

    private boolean animationsEnabled() {
        if (Build.VERSION.SDK_INT >= 26) {
            return ValueAnimator.areAnimatorsEnabled();
        }
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    private void ensureShader(int windowWidth) {
        // Resolved every frame: the player switches theme in place, without new views.
        int highlight = ContextCompat.getColor(getContext(), R.color.mobile_color_skeleton_highlight);
        if (mShader != null && mShaderWidth == windowWidth && mShaderColor == highlight) {
            return;
        }
        mShaderWidth = windowWidth;
        mShaderColor = highlight;
        float band = windowWidth * BAND;
        int clear = highlight & 0x00FFFFFF;
        mShader = new LinearGradient(0f, 0f, band, -band * SLOPE,
                new int[] {clear, highlight, clear}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        mPaint.setShader(mShader);
    }
}

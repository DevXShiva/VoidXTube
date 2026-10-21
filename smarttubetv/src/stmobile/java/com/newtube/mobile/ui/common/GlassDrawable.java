package com.newtube.mobile.ui.common;

import android.content.res.ColorStateList;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * VOIDXDEV(glass): {@link GlassView}'s frost as a background drawable, for controls whose visibility,
 * alpha and size the code already drives (the player's buttons) - it follows them with no wrapper
 * view. A disc (a pill, on a wide view) inset into the bounds: the {@link GlassSource} under it
 * (the video) saturated and blurred, a dark tint, grain and a light rim, with a ripple on press.
 * No lens: on a 40dp disc it would only magnify. Below API 31 there is nothing to sample, so the
 * tint alone, darker. With Liquid glass off ({@link Glass#isLiquid}) no grain or rim, as GlassView.
 */
public final class GlassDrawable extends Drawable {
    private static final float BLUR_DP = 12f;
    private static final float SATURATION = 1.5f;
    private static final int TINT = 0x47000000;
    private static final int FALLBACK_TINT = 0x8C000000;
    private static final int NOISE_ALPHA = 10;

    private final View mHost;
    private final GlassSource mSource;
    private final int mInset;
    private final float mDensity;
    private final boolean mLiquid;
    private final Paint mTint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNoise = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mShape = new RectF();
    private final int[] mLoc = new int[2];
    private final int[] mSourceLoc = new int[2];
    private int mRecordedX = Integer.MIN_VALUE;
    private int mRecordedY = Integer.MIN_VALUE;
    @Nullable
    private final RenderNode mNode;

    /** Makes {@code host}'s background the glass, inset {@code insetPx} from its edges, with a ripple. */
    public static void apply(@NonNull View host, @Nullable GlassSource source, int insetPx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(Float.MAX_VALUE / 2); // a disc or a pill, whatever the bounds
        mask.setColor(0xFFFFFFFF);
        int start = host.getPaddingStart();
        int top = host.getPaddingTop();
        int end = host.getPaddingEnd();
        int bottom = host.getPaddingBottom();
        host.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                new GlassDrawable(host, source, insetPx), new InsetDrawable(mask, insetPx)));
        host.setPaddingRelative(start, top, end, bottom); // the inset is not padding: the icon stays its size
    }

    private GlassDrawable(View host, @Nullable GlassSource source, int insetPx) {
        mHost = host;
        mSource = source;
        mInset = insetPx;
        mDensity = host.getResources().getDisplayMetrics().density;
        mLiquid = Glass.isLiquid(host.getContext());
        boolean live = GlassSource.HARDWARE && source != null;
        mNode = live ? new RenderNode("VoidTube glass button") : null;
        mTint.setColor(live ? TINT : FALLBACK_TINT);
        mNoise.setShader(new BitmapShader(GlassView.noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mNoise.setAlpha(NOISE_ALPHA);
        mRim.setStyle(Paint.Style.STROKE);
        mRim.setStrokeWidth(mDensity);
        if (live) {
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(SATURATION);
            float blur = BLUR_DP * mDensity;
            mNode.setRenderEffect(RenderEffect.createBlurEffect(blur, blur,
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturate)), Shader.TileMode.CLAMP));
            mNode.setClipToOutline(true);
            // re-sample when the button moves over the video (a rotation re-centres the transport)
            host.getViewTreeObserver().addOnPreDrawListener(() -> {
                if (host.isShown() && moved()) {
                    invalidateSelf();
                }
                return true;
            });
        }
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        mShape.set(bounds.left + mInset, bounds.top + mInset, bounds.right - mInset, bounds.bottom - mInset);
        // light from above, a faint bounce at the bottom (GlassView's rim without runtime shaders)
        mRim.setShader(new LinearGradient(0, mShape.top, 0, mShape.bottom,
                new int[] {0x66FFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x2EFFFFFF},
                new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        mRecordedX = Integer.MIN_VALUE;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mShape.isEmpty()) {
            return;
        }
        float r = Math.min(mShape.width(), mShape.height()) / 2f;
        if (mNode != null && canvas.isHardwareAccelerated() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            moved();
            mRecordedX = mLoc[0] - mSourceLoc[0];
            mRecordedY = mLoc[1] - mSourceLoc[1];
            int w = Math.round(mShape.width());
            int h = Math.round(mShape.height());
            mNode.setPosition(0, 0, w, h);
            Outline outline = new Outline();
            outline.setRoundRect(0, 0, w, h, r);
            mNode.setOutline(outline);
            Canvas recording = mNode.beginRecording();
            recording.translate(-mRecordedX, -mRecordedY);
            recording.drawRenderNode(mSource.node);
            mNode.endRecording();
            int save = canvas.save();
            canvas.translate(mShape.left, mShape.top);
            canvas.drawRenderNode(mNode);
            canvas.restoreToCount(save);
        }
        canvas.drawRoundRect(mShape, r, r, mTint);
        if (mLiquid) {
            canvas.drawRoundRect(mShape, r, r, mNoise);
            canvas.drawRoundRect(mShape, r, r, mRim);
        }
    }

    /** Refreshes where the glass sits on the source; true when that differs from the last recording. */
    private boolean moved() {
        mHost.getLocationInWindow(mLoc);
        mSource.getLocationInWindow(mSourceLoc);
        mLoc[0] += Math.round(mShape.left);
        mLoc[1] += Math.round(mShape.top);
        return mLoc[0] - mSourceLoc[0] != mRecordedX || mLoc[1] - mSourceLoc[1] != mRecordedY;
    }

    @Override
    public void setAlpha(int alpha) {
        // the host's own alpha fades it (the controls' show / hide)
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

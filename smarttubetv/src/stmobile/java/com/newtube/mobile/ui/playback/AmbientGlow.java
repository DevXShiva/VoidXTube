package com.newtube.mobile.ui.playback;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * VOIDXDEV(ambient): YouTube's ambient mode. Every couple of seconds a tiny frame is read off the
 * playing video, box-blurred, saturated, and painted as the watch page's background behind the
 * title - a soft glow that spreads down from the player and fades into the page colour. New frames
 * cross-fade in so the glow drifts with the scene instead of jumping.
 *
 * <p>In the vertical fullscreen the same frames also fill the black bands above and below the
 * video ({@link #setFill}): the frame blown up to the whole screen, dimmed, behind the picture.
 * Both follow the Ambient mode setting (on unless turned off, {@link #isOn}).</p>
 */
public final class AmbientGlow implements Runnable {
    private static final int FRAME_W = 48;
    private static final int FRAME_H = 27;
    private static final int BLUR_RADIUS = 3;
    private static final long INTERVAL_MS = 2000;
    private static final long FADE_MS = 1200;
    private static final int GLOW_ALPHA = 210; // ~0.8: YouTube's glow reads strong right under the video

    private static final String PREFS = "voidx_player";
    private static final String KEY_ON = "ambient_mode";

    private final View mHost;
    private final TextureView mVideo;
    private final GlowDrawable mDrawable;
    private final FillDrawable mFill = new FillDrawable();
    @Nullable
    private View mFillHost;
    private boolean mRunning;

    public static boolean isOn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true);
    }

    public static void setOn(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
    }

    AmbientGlow(@NonNull View host, @NonNull TextureView video, int pageColor) {
        mHost = host;
        mVideo = video;
        mDrawable = new GlowDrawable(pageColor, host.getResources().getDisplayMetrics().density);
        host.setBackground(mDrawable);
    }

    void start() {
        if (!mRunning) {
            mRunning = true;
            mHost.post(this);
        }
    }

    void stop() {
        mRunning = false;
        mHost.removeCallbacks(this);
    }

    /** {@code area} (the video box) gets the ambient fill as its background; null gives it back black. */
    void setFill(@Nullable View area) {
        if (area == mFillHost) {
            return;
        }
        if (mFillHost != null) {
            mFillHost.setBackgroundColor(Color.BLACK);
        }
        mFillHost = area;
        if (area != null) {
            area.setBackground(mFill);
            if (mRunning) {
                mHost.removeCallbacks(this);
                mHost.post(this); // a frame now, not in up to two seconds
            }
        }
    }

    @Override
    public void run() {
        if (!mRunning) {
            return;
        }
        boolean fill = mFillHost != null && mFillHost.isShown();
        if ((mHost.isShown() || fill) && mVideo.isAvailable()) {
            Bitmap frame = mVideo.getBitmap(FRAME_W, FRAME_H);
            if (frame != null) {
                blur(frame);
                mDrawable.push(frame);
                mFill.push(frame);
            }
        }
        mHost.postDelayed(this, INTERVAL_MS);
    }

    /** Separable box blur, two passes: at 48x27 this costs nothing and reads as a wide gaussian. */
    private static void blur(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            boxPass(px, tmp, w, h, 1, w);  // horizontal
            boxPass(tmp, px, h, w, w, 1);  // vertical
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** Blurs `lines` lines of `len` pixels; `step` walks a line, `stride` jumps between lines. */
    private static void boxPass(int[] src, int[] dst, int len, int lines, int step, int stride) {
        for (int line = 0; line < lines; line++) {
            int base = line * stride;
            for (int i = 0; i < len; i++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int k = Math.max(0, i - BLUR_RADIUS); k <= Math.min(len - 1, i + BLUR_RADIUS); k++) {
                    int c = src[base + k * step];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                dst[base + i * step] = 0xFF000000 | (r / n << 16) | (g / n << 8) | (b / n);
            }
        }
    }

    /** The fill: the frame stretched over the whole video box, dimmed, under the picture. */
    private static final class FillDrawable extends Drawable {
        private final Paint mFrame = new Paint(Paint.FILTER_BITMAP_FLAG);
        @Nullable private Bitmap mCurrent;
        @Nullable private Bitmap mPrevious;
        private float mMix = 1f;
        private ValueAnimator mFadeIn;

        FillDrawable() {
            ColorMatrix look = new ColorMatrix();
            look.setSaturation(1.5f);
            ColorMatrix dim = new ColorMatrix();
            dim.setScale(0.6f, 0.6f, 0.6f, 1f);
            look.postConcat(dim);
            mFrame.setColorFilter(new ColorMatrixColorFilter(look));
        }

        void push(Bitmap frame) {
            mPrevious = mCurrent;
            mCurrent = frame;
            if (mFadeIn != null) {
                mFadeIn.cancel();
            }
            mFadeIn = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
            mFadeIn.addUpdateListener(a -> {
                mMix = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            mFadeIn.start();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (mCurrent == null) {
                return;
            }
            if (mPrevious != null && mMix < 1f) {
                mFrame.setAlpha(Math.round(255 * (1f - mMix)));
                canvas.drawBitmap(mPrevious, null, getBounds(), mFrame);
            }
            mFrame.setAlpha(Math.round(255 * mMix));
            canvas.drawBitmap(mCurrent, null, getBounds(), mFrame);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /** The glow: the frame scaled to the page's width, faded out into the page colour below. */
    private static final class GlowDrawable extends Drawable {
        private final Paint mFrame = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint mFade = new Paint();
        private final Rect mFrameRect = new Rect();
        private final int mPageColor;
        private final float mDensity;
        @Nullable private Bitmap mCurrent;
        @Nullable private Bitmap mPrevious;
        private float mMix = 1f;
        private ValueAnimator mFadeIn;

        GlowDrawable(int pageColor, float density) {
            mPageColor = pageColor;
            mDensity = density;
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(1.6f);
            mFrame.setColorFilter(new ColorMatrixColorFilter(saturate));
        }

        void push(Bitmap frame) {
            mPrevious = mCurrent;
            mCurrent = frame;
            if (mFadeIn != null) {
                mFadeIn.cancel();
            }
            mFadeIn = ValueAnimator.ofFloat(0f, 1f).setDuration(mPrevious == null ? FADE_MS / 2 : FADE_MS);
            mFadeIn.addUpdateListener(a -> {
                mMix = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            mFadeIn.start();
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            // The glow reaches ~420dp down (the design's 620px at 390 wide is ~1.6 widths, capped).
            int height = Math.min(Math.round(bounds.width() * 1.1f), Math.round(420 * mDensity));
            mFrameRect.set(bounds.left - Math.round(30 * mDensity), bounds.top,
                    bounds.right + Math.round(30 * mDensity), bounds.top + height);
            int page = mPageColor & 0x00FFFFFF;
            mFade.setShader(new LinearGradient(0, mFrameRect.top, 0, mFrameRect.bottom,
                    new int[] {page, page | 0x59000000, mPageColor},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawColor(mPageColor);
            if (mCurrent == null) {
                return;
            }
            canvas.save();
            canvas.clipRect(getBounds());
            if (mPrevious != null && mMix < 1f) {
                mFrame.setAlpha(Math.round(GLOW_ALPHA * (1f - mMix)));
                canvas.drawBitmap(mPrevious, null, mFrameRect, mFrame);
            }
            mFrame.setAlpha(Math.round(GLOW_ALPHA * mMix));
            canvas.drawBitmap(mCurrent, null, mFrameRect, mFrame);
            canvas.drawRect(mFrameRect, mFade);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}

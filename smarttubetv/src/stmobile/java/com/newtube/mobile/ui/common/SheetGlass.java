package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.lang.ref.WeakReference;

/**
 * VOIDXDEV(glass): frosted sheets. A sheet lives in its own window, so it cannot sample the page live
 * the way {@link GlassView} does; instead the page's window is copied once as the sheet opens
 * (PixelCopy, video frame included), blurred small, and painted only inside the sheet - lined up
 * with the screen, so it stays put under the sheet while it is dragged. The page itself stays sharp.
 * Below API 26 the sheet keeps its plain see-through surface.
 */
public final class SheetGlass {
    /** The copy is this many times smaller than the page; scaling it back up adds to the blur. */
    private static final int SCALE = 8;
    private static final int BLUR_RADIUS = 3;
    private static final float CORNER_DP = 28f;

    private static WeakReference<Activity> sBackdrop = new WeakReference<>(null);

    private SheetGlass() {
    }

    /** Remembers the last resumed screen that is not a {@code sheetActivity}: what such a sheet frosts. */
    public static void track(Application app, Class<? extends Activity> sheetActivity) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (!sheetActivity.isInstance(activity)) {
                    sBackdrop = new WeakReference<>(activity);
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    /** The screen under a sheet that has its own activity, if still around. */
    @Nullable
    public static Activity backdrop() {
        return sBackdrop.get();
    }

    /** A bottom sheet dialog that just showed: frosts its content with its activity's page. */
    static void frost(@NonNull Dialog dialog) {
        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        Activity activity = activityOf(dialog.getContext());
        if (frame instanceof ViewGroup && ((ViewGroup) frame).getChildCount() > 0 && activity != null) {
            // The frame pads its content for the system bars (the sides in landscape, the top once
            // expanded to full height): the glass covers the whole frame, padding included, so the
            // frame's plain tint never shows beside it.
            ViewGroup sheet = (ViewGroup) frame;
            sheet.setClipToPadding(false);
            sheet.setClipChildren(false);
            frost(sheet.getChildAt(0), activity.getWindow(), sheet, CORNER_DP * sheet.getResources().getDisplayMetrics().density);
        }
    }

    /** Paints {@code target}'s background with {@code page}, blurred, behind the sheet's tint. */
    public static void frost(@NonNull View target, @Nullable Window page) {
        frost(target, page, CORNER_DP * target.getResources().getDisplayMetrics().density);
    }

    /** As above, rounded to {@code cornerPx} (a popup menu's corners, not a sheet's). */
    public static void frost(@NonNull View target, @Nullable Window page, float cornerPx) {
        frost(target, page, target, cornerPx);
    }

    /** As above, filling {@code cover} - {@code target} itself or its direct parent. */
    private static void frost(@NonNull View target, @Nullable Window page, @NonNull View cover, float cornerPx) {
        View decor = page != null ? page.peekDecorView() : null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || decor == null
                || decor.getWidth() == 0 || decor.getHeight() == 0) {
            return;
        }
        Bitmap copy = Bitmap.createBitmap(Math.max(1, decor.getWidth() / SCALE),
                Math.max(1, decor.getHeight() / SCALE), Bitmap.Config.ARGB_8888);
        int[] origin = new int[2];
        decor.getLocationOnScreen(origin);
        int pageW = decor.getWidth();
        int pageH = decor.getHeight();
        try {
            PixelCopy.request(page, copy, result -> {
                if (result == PixelCopy.SUCCESS && target.isAttachedToWindow()) {
                    blur(copy, BLUR_RADIUS);
                    target.setBackground(new Backdrop(target, cover, copy, origin, pageW, pageH, cornerPx));
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {
            // The page's window has no surface (already gone): keep the plain surface.
        }
    }

    @Nullable
    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Three box passes each way approximate a gaussian; on a 1/8 copy that is a wide, soft blur. */
    static void blur(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        bitmap.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(a, b, w, h, radius, true);
            boxPass(b, a, w, h, radius, false);
        }
        bitmap.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void boxPass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * w : line;
            for (int i = 0; i < len; i++) {
                int sa = 0;
                int sr = 0;
                int sg = 0;
                int sb = 0;
                int n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int c = src[start + k * step];
                    sa += c >>> 24;
                    sr += (c >> 16) & 0xFF;
                    sg += (c >> 8) & 0xFF;
                    sb += c & 0xFF;
                    n++;
                }
                dst[start + i * step] = (sa / n) << 24 | (sr / n) << 16 | (sg / n) << 8 | (sb / n);
            }
        }
    }

    /** The blurred page under a sheet, screen-aligned, saturated a little like {@link GlassView}, then tinted. */
    private static final class Backdrop extends Drawable {
        private final View mTarget;
        private final View mCover;
        private final int[] mOrigin;
        private final float mScaleX;
        private final float mScaleY;
        private final Paint mPage = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint mTint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final BitmapShader mShader;
        private final Matrix mMatrix = new Matrix();
        private final RectF mRect = new RectF();
        private final float mCorner;
        private final int[] mLoc = new int[2];
        private int mDrawnX = Integer.MIN_VALUE;
        private int mDrawnY = Integer.MIN_VALUE;

        Backdrop(View target, View cover, Bitmap page, int[] origin, int pageW, int pageH, float corner) {
            mTarget = target;
            mCover = cover;
            mOrigin = origin;
            mScaleX = pageW / (float) page.getWidth();
            mScaleY = pageH / (float) page.getHeight();
            mShader = new BitmapShader(page, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mPage.setShader(mShader);
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(1.4f);
            mPage.setColorFilter(new ColorMatrixColorFilter(saturate));
            mTint.setColor(ContextCompat.getColor(target.getContext(), R.color.voidx_sheet_glass));
            mCorner = corner;
            // A drag moves the sheet without redrawing it: redraw so the page stays put underneath.
            target.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (target.getBackground() != Backdrop.this) {
                        target.getViewTreeObserver().removeOnPreDrawListener(this);
                        return true;
                    }
                    target.getLocationOnScreen(mLoc);
                    if (mLoc[0] != mDrawnX || mLoc[1] != mDrawnY) {
                        invalidateSelf();
                    }
                    return true;
                }
            });
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            mTarget.getLocationOnScreen(mLoc);
            mDrawnX = mLoc[0];
            mDrawnY = mLoc[1];
            mMatrix.setScale(mScaleX, mScaleY);
            mMatrix.postTranslate(mOrigin[0] - mLoc[0], mOrigin[1] - mLoc[1]);
            mShader.setLocalMatrix(mMatrix);
            if (mCover == mTarget) {
                mRect.set(getBounds());
            } else { // the parent's box, in the target's coordinates
                mRect.set(-mTarget.getLeft(), -mTarget.getTop(),
                        mCover.getWidth() - mTarget.getLeft(), mCover.getHeight() - mTarget.getTop());
            }
            canvas.drawRoundRect(mRect, mCorner, mCorner, mPage);
            canvas.drawRoundRect(mRect, mCorner, mCorner, mTint);
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
}

package com.newtube.mobile.ui.playback;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * NEWTUBE(theme): the portrait player's window backdrop in the light theme. Under enforced
 * edge-to-edge the decor background is what shows through the transparent system bars: black
 * behind the status bar, so the band above the video stays the video's colour (as it is in the
 * dark theme, where the whole backdrop is plain black), and the page colour everywhere else, so
 * the navigation-bar band under the white watch page is white too, not a black strip.
 *
 * <p>The minimize morph fades the decor background with {@link #setAlpha}, like the plain colour
 * it replaces.</p>
 */
final class WatchBackdropDrawable extends Drawable {
    private final View mDecor;
    private final Paint mBand = new Paint();
    private final Paint mPage = new Paint();
    private final int mBandColor;
    private final int mPageColor;
    private int mAlpha = 255;

    WatchBackdropDrawable(@NonNull View decor, int bandColor, int pageColor) {
        mDecor = decor;
        mBandColor = bandColor;
        mPageColor = pageColor;
        applyAlpha();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int band = Math.min(bounds.height(), statusBand());
        if (band > 0) {
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + band, mBand);
        }
        canvas.drawRect(bounds.left, bounds.top + band, bounds.right, bounds.bottom, mPage);
    }

    private int statusBand() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mDecor);
        if (insets == null) {
            return 0;
        }
        Insets top = insets.getInsets(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
        return top.top;
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            applyAlpha();
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    private void applyAlpha() {
        mBand.setColor(scale(mBandColor, mAlpha));
        mPage.setColor(scale(mPageColor, mAlpha));
    }

    private static int scale(int color, int alpha) {
        int a = (color >>> 24) * alpha / 255;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mBand.setColorFilter(colorFilter);
        mPage.setColorFilter(colorFilter);
        invalidateSelf();
    }

    /**
     * NEWTUBE(motion): always translucent. DecorView takes the window's pixel format from its
     * background's opacity when the background is set, so OPAQUE here made the player's window
     * opaque: the open and minimize morphs then drew over a white page instead of Home (no screen
     * below composited), and the shrinking video left trails on the uncleared surface.
     */
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

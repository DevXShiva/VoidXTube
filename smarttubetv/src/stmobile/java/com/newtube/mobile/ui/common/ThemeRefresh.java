package com.newtube.mobile.ui.common;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.BaseProgressIndicator;

import java.util.List;

/**
 * NEWTUBE(theme): re-colours views that must survive a theme change in place (the player's watch
 * page: recreating the player would restart the video).
 *
 * <p>A view keeps no record of the resources its XML named, so the new colours come from a
 * second inflation of the same layout under the new theme: {@link #copyColors} pairs the live
 * views with the fresh ones by position and copies what a theme decides - backgrounds and
 * foregrounds, text/hint/link colours, image tints and XML glyphs, progress and button tints.
 * Nothing else is touched (text, visibility, alpha, padding, listeners). Whatever code sets after
 * inflation - a filled thumb, the Subscribe pill's state - is the caller's to set again.</p>
 */
public final class ThemeRefresh {
    private ThemeRefresh() {
    }

    /**
     * Copies the theme-dependent colours of {@code fresh} (the same layout, inflated under the new
     * theme) onto {@code live}, view by view. A subtree whose shape differs from the fresh one
     * (views added or removed in code) is left alone and counted. RecyclerViews are not walked:
     * their rows are recycled views, so they are collected into {@code lists} for
     * {@link #rebuildRows}.
     *
     * @return how many subtrees were skipped
     */
    public static int copyColors(@NonNull View live, @NonNull View fresh, @NonNull List<RecyclerView> lists) {
        if (live.getClass() != fresh.getClass()) {
            return 1;
        }
        copyViewColors(live, fresh);
        if (live instanceof RecyclerView) {
            lists.add((RecyclerView) live);
            return 0;
        }
        if (!(live instanceof ViewGroup)) {
            return 0;
        }
        ViewGroup liveGroup = (ViewGroup) live;
        ViewGroup freshGroup = (ViewGroup) fresh;
        if (liveGroup.getChildCount() != freshGroup.getChildCount()) {
            return 1;
        }
        int skipped = 0;
        for (int i = 0; i < liveGroup.getChildCount(); i++) {
            skipped += copyColors(liveGroup.getChildAt(i), freshGroup.getChildAt(i), lists);
        }
        return skipped;
    }

    /**
     * Throws away a list's rows (and its pooled ones) so they are created again from the current
     * theme, keeping the adapter, its data and the scroll position.
     */
    public static void rebuildRows(@NonNull RecyclerView list) {
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter == null) {
            return;
        }
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        Parcelable scroll = manager != null ? manager.onSaveInstanceState() : null;
        list.swapAdapter(adapter, true); // detaches every row into the pool...
        list.getRecycledViewPool().clear(); // ...which is dropped, so new rows are inflated
        if (manager != null && scroll != null) {
            manager.onRestoreInstanceState(scroll);
        }
    }

    private static void copyViewColors(View live, View fresh) {
        // Material widgets own their background (a shape drawable re-applied on every layout);
        // theirs go through their own setters below.
        boolean ownsBackground = live instanceof MaterialButton || live instanceof MaterialCardView;
        if (!ownsBackground && (fresh.getBackground() != null || live.getBackground() != null)) {
            Drawable background = fresh.getBackground();
            fresh.setBackground(null);
            int start = ViewCompat.getPaddingStart(live);
            int top = live.getPaddingTop();
            int end = ViewCompat.getPaddingEnd(live);
            int bottom = live.getPaddingBottom();
            live.setBackground(background); // an inset background would also reset the padding
            ViewCompat.setPaddingRelative(live, start, top, end, bottom);
        }
        if (fresh.getBackgroundTintList() != null || live.getBackgroundTintList() != null) {
            live.setBackgroundTintList(fresh.getBackgroundTintList());
        }
        if (Build.VERSION.SDK_INT >= 23 && (fresh.getForeground() != null || live.getForeground() != null)) {
            Drawable foreground = fresh.getForeground();
            fresh.setForeground(null);
            live.setForeground(foreground);
        }

        if (live instanceof TextView) {
            TextView liveText = (TextView) live;
            TextView freshText = (TextView) fresh;
            liveText.setTextColor(freshText.getTextColors());
            if (freshText.getHintTextColors() != null) {
                liveText.setHintTextColor(freshText.getHintTextColors());
            }
            if (freshText.getLinkTextColors() != null) {
                liveText.setLinkTextColor(freshText.getLinkTextColors());
            }
            liveText.setHighlightColor(freshText.getHighlightColor());
            if (Build.VERSION.SDK_INT >= 23 && freshText.getCompoundDrawableTintList() != null) {
                liveText.setCompoundDrawableTintList(freshText.getCompoundDrawableTintList());
            }
        }

        if (live instanceof ImageView) {
            ImageView liveImage = (ImageView) live;
            ImageView freshImage = (ImageView) fresh;
            if (freshImage.getImageTintList() != null || liveImage.getImageTintList() != null) {
                liveImage.setImageTintList(freshImage.getImageTintList());
            }
            // An XML glyph (a vector whose colour is a theme colour) is swapped for the fresh one.
            // A picture loaded into the view (a bitmap, a crossfade) is not a glyph and stays.
            Drawable liveDrawable = liveImage.getDrawable();
            Drawable freshDrawable = freshImage.getDrawable();
            if (liveDrawable != null && freshDrawable != null && !(liveDrawable instanceof BitmapDrawable)
                    && liveDrawable.getClass() == freshDrawable.getClass()) {
                freshImage.setImageDrawable(null);
                liveImage.setImageDrawable(freshDrawable);
            }
        }

        if (live instanceof ProgressBar) {
            ProgressBar liveBar = (ProgressBar) live;
            ProgressBar freshBar = (ProgressBar) fresh;
            if (freshBar.getProgressTintList() != null) {
                liveBar.setProgressTintList(freshBar.getProgressTintList());
            }
            if (freshBar.getProgressBackgroundTintList() != null) {
                liveBar.setProgressBackgroundTintList(freshBar.getProgressBackgroundTintList());
            }
            if (freshBar.getSecondaryProgressTintList() != null) {
                liveBar.setSecondaryProgressTintList(freshBar.getSecondaryProgressTintList());
            }
            if (freshBar.getIndeterminateTintList() != null) {
                liveBar.setIndeterminateTintList(freshBar.getIndeterminateTintList());
            }
        }
        if (live instanceof BaseProgressIndicator) {
            BaseProgressIndicator<?> liveIndicator = (BaseProgressIndicator<?>) live;
            BaseProgressIndicator<?> freshIndicator = (BaseProgressIndicator<?>) fresh;
            liveIndicator.setIndicatorColor(freshIndicator.getIndicatorColor());
            liveIndicator.setTrackColor(freshIndicator.getTrackColor());
        }

        if (live instanceof CompoundButton && ((CompoundButton) fresh).getButtonTintList() != null) {
            ((CompoundButton) live).setButtonTintList(((CompoundButton) fresh).getButtonTintList());
        }

        if (live instanceof MaterialButton) {
            MaterialButton liveButton = (MaterialButton) live;
            MaterialButton freshButton = (MaterialButton) fresh;
            liveButton.setIconTint(freshButton.getIconTint());
            liveButton.setStrokeColor(freshButton.getStrokeColor());
            liveButton.setRippleColor(freshButton.getRippleColor());
        }

        if (live instanceof MaterialCardView) {
            MaterialCardView liveCard = (MaterialCardView) live;
            MaterialCardView freshCard = (MaterialCardView) fresh;
            liveCard.setCardBackgroundColor(freshCard.getCardBackgroundColor());
            liveCard.setStrokeColor(freshCard.getStrokeColorStateList());
            liveCard.setRippleColor(freshCard.getRippleColor());
        }
    }
}

package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(you-subscreen): Browse's top bar. On a bottom-nav tab it is the brand; a section opened
 * from a You row (Sports, Playlists, ...) is a sub-screen of You, so it gets a back arrow and the
 * section's name - like YouTube's You sub-pages - instead of a brand that gave no hint where the
 * user was or that back returns to You. VOIDXDEV(glass): both live in the floating brand pill.
 */
final class BrowseTopBar {
    private final TextView mTitle;
    private final View mBack;
    private final View mMark;

    BrowseTopBar(Activity activity, Runnable onBack) {
        mTitle = activity.findViewById(R.id.mobile_title_bar);
        mBack = activity.findViewById(R.id.mobile_title_back);
        mMark = activity.findViewById(R.id.mobile_brand_mark);
        // VOIDXDEV(app icon): the brand and the launch splash follow the picked launcher icon.
        ((ImageView) mMark).setImageResource(com.newtube.mobile.ui.common.AppIcon.mark(activity));
        com.newtube.mobile.ui.common.AppIcon.syncSplash(activity);
        mBack.setOnClickListener(v -> onBack.run());
        // VOIDXDEV(quotes): on a tab the brand pill shows a quote; on a sub-screen it goes back.
        QuoteCard quote = new QuoteCard(activity);
        activity.findViewById(R.id.mobile_brand_glass).setOnClickListener(v -> {
            if (mBack.getVisibility() == View.VISIBLE) {
                onBack.run();
            } else {
                com.newtube.mobile.ui.common.Motion.nudge(v);
                quote.show();
            }
        });
    }

    /**
     * A tab shows the brand; a sub-screen shows the back arrow and {@code title} (the app name if
     * the section came without one - the arrow is what matters).
     */
    void show(boolean subScreen, CharSequence title) {
        mBack.setVisibility(subScreen ? View.VISIBLE : View.GONE);
        mMark.setVisibility(subScreen ? View.GONE : View.VISIBLE);
        mTitle.setText(subScreen && title != null && title.length() > 0
                ? title : mTitle.getContext().getString(R.string.app_name));
    }
}

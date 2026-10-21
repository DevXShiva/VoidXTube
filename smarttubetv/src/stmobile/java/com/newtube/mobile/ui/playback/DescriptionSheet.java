package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VOIDXDEV(description): YouTube's Description panel - the title, a Likes / Views / date row and the
 * description, selectable so any of it can be copied. Opened from the watch page's title; a plain
 * {@link BottomSheetDialog} on the frosted sheet glass, shown by the player (showPlayerSheet).
 */
final class DescriptionSheet {
    /** "1.8B views" -> "1.8B" over "Views"; other shapes (other languages) show whole. */
    private static final Pattern VIEWS = Pattern.compile("^(.*\\d\\S*)\\s+views?$", Pattern.CASE_INSENSITIVE);
    /** "Oct 25, 2009" -> "Oct 25" over "2009", as YouTube stacks it. */
    private static final Pattern DATE = Pattern.compile("^(.+?),?\\s+(\\d{4})$");

    private DescriptionSheet() {
    }

    /**
     * Empty parts are left out. With {@code video} (the portrait page) the sheet fills the screen
     * under it exactly, however short the text; without it (fullscreen) it is a plain sheet.
     */
    static BottomSheetDialog create(@NonNull Activity activity, CharSequence title, @Nullable CharSequence likes,
                                    @Nullable String views, @Nullable String date, CharSequence description,
                                    @Nullable View video) {
        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_description, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDimAmount(0f); // the playing video above stays as it is, never dimmed
        }
        if (video != null) {
            fillUnder(dialog, content, video);
        }

        content.findViewById(R.id.description_sheet_close).setOnClickListener(v -> dialog.dismiss());
        ((TextView) content.findViewById(R.id.description_sheet_title)).setText(title);
        TextView text = content.findViewById(R.id.description_sheet_text);
        text.setText(description);
        text.setVisibility(TextUtils.isEmpty(description) ? View.GONE : View.VISIBLE);

        LinearLayout stats = content.findViewById(R.id.description_sheet_stats);
        if (!TextUtils.isEmpty(likes)) {
            addStat(stats, likes, activity.getString(R.string.mobile_description_likes));
        }
        if (!TextUtils.isEmpty(views)) {
            Matcher m = VIEWS.matcher(views.trim());
            addStat(stats, m.matches() ? m.group(1) : views, m.matches() ? activity.getString(R.string.mobile_description_views) : null);
        }
        if (!TextUtils.isEmpty(date)) {
            Matcher m = DATE.matcher(date.trim());
            addStat(stats, m.matches() ? m.group(1) : date, m.matches() ? m.group(2) : null);
        }
        stats.setVisibility(stats.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        return dialog;
    }

    /**
     * Sizes the sheet to reach exactly the video's bottom edge, measured on screen before its first
     * frame: the dialog's frame need not end where the page does (a navigation bar inset).
     */
    static void fillUnder(BottomSheetDialog dialog, View content, View video) {
        int minPx = Math.round(240 * content.getResources().getDisplayMetrics().density);
        content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            private final int[] mAt = new int[2];

            @Override
            public boolean onPreDraw() {
                View sheet = (View) content.getParent();
                View frame = sheet != null ? (View) sheet.getParent() : null;
                if (frame == null || frame.getHeight() == 0) {
                    return true;
                }
                frame.getLocationOnScreen(mAt);
                int frameBottom = mAt[1] + frame.getHeight();
                video.getLocationOnScreen(mAt);
                int height = frameBottom - (mAt[1] + video.getHeight());
                int contentHeight = height - sheet.getPaddingTop() - sheet.getPaddingBottom();
                if (height < minPx || contentHeight == content.getMinimumHeight()) {
                    return true;
                }
                content.setMinimumHeight(contentHeight);
                dialog.getBehavior().setMaxHeight(height);
                return false; // laid out again at that height before anything is drawn
            }
        });
    }

    /** One column: the number big and bold, what it counts small beneath. */
    private static void addStat(LinearLayout row, CharSequence value, @Nullable CharSequence label) {
        LinearLayout column = new LinearLayout(row.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(stat(row, value, 18, R.color.mobile_color_on_surface, true));
        if (!TextUtils.isEmpty(label)) {
            column.addView(stat(row, label, 13, R.color.mobile_color_on_surface_secondary, false));
        }
        row.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private static TextView stat(View row, CharSequence text, int sp, int color, boolean bold) {
        TextView view = new TextView(row.getContext());
        view.setText(text);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(ContextCompat.getColor(row.getContext(), color));
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }
}

package com.newtube.mobile.ui.search;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.function.IntConsumer;

/**
 * VOIDXDEV(search filters, #11): YouTube's own "Search filters" dialog - Type, Duration, Upload date
 * and Prioritize dropdowns, then the Features chips - with YouTube's current choices. It replaces
 * the TV presenter's radio list, which still offered filters YouTube has dropped (last hour, sort by
 * upload date or rating).
 */
final class SearchFiltersDialog {
    // {label, flag}; each row's first entry (flag 0) is its "no filter".
    private static final int[][][] ROWS = {
            {{R.string.mobile_search_filter_type},
                    {R.string.mobile_search_filter_all, 0},
                    {R.string.mobile_search_filter_videos, SearchOptions.TYPE_VIDEO},
                    {R.string.mobile_search_filter_shorts, SearchOptions.TYPE_SHORTS},
                    {R.string.mobile_search_filter_channels, SearchOptions.TYPE_CHANNEL},
                    {R.string.mobile_search_filter_playlists, SearchOptions.TYPE_PLAYLIST},
                    {R.string.mobile_search_filter_movies, SearchOptions.TYPE_MOVIE}},
            {{R.string.mobile_search_filter_duration},
                    {R.string.mobile_search_filter_any, 0},
                    {R.string.mobile_search_filter_under_3, SearchOptions.DURATION_UNDER_3},
                    {R.string.mobile_search_filter_3_20, SearchOptions.DURATION_BETWEEN_3_20},
                    {R.string.mobile_search_filter_over_20, SearchOptions.DURATION_OVER_20}},
            {{R.string.mobile_search_filter_upload_date},
                    {R.string.mobile_search_filter_anytime, 0},
                    {R.string.mobile_search_filter_today, SearchOptions.UPLOAD_DATE_TODAY},
                    {R.string.mobile_search_filter_this_week, SearchOptions.UPLOAD_DATE_THIS_WEEK},
                    {R.string.mobile_search_filter_this_month, SearchOptions.UPLOAD_DATE_THIS_MONTH},
                    {R.string.mobile_search_filter_this_year, SearchOptions.UPLOAD_DATE_THIS_YEAR}},
            {{R.string.mobile_search_filter_prioritize},
                    {R.string.mobile_search_filter_relevance, 0},
                    {R.string.mobile_search_filter_popularity, SearchOptions.SORT_BY_VIEW_COUNT}},
    };

    private static final int[][] FEATURES = {
            {R.string.mobile_search_filter_live, SearchOptions.FEATURE_LIVE},
            {R.string.mobile_search_filter_4k, SearchOptions.FEATURE_4K},
            {R.string.mobile_search_filter_hd, SearchOptions.FEATURE_HD},
            {R.string.mobile_search_filter_subtitles, SearchOptions.FEATURE_SUBTITLES},
            {R.string.mobile_search_filter_creative_commons, SearchOptions.FEATURE_CREATIVE_COMMONS},
            {R.string.mobile_search_filter_360, SearchOptions.FEATURE_360},
            {R.string.mobile_search_filter_vr180, SearchOptions.FEATURE_VR180},
            {R.string.mobile_search_filter_3d, SearchOptions.FEATURE_3D},
            {R.string.mobile_search_filter_hdr, SearchOptions.FEATURE_HDR},
            {R.string.mobile_search_filter_location, SearchOptions.FEATURE_LOCATION},
            {R.string.mobile_search_filter_purchased, SearchOptions.FEATURE_PURCHASED},
    };

    private SearchFiltersDialog() {
    }

    /** Show the dialog for {@code options}; Apply hands the new options to {@code onApply}. */
    static void show(Activity activity, int options, IntConsumer onApply) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View content = inflater.inflate(R.layout.mobile_search_filters, null);
        ViewGroup rows = content.findViewById(R.id.mobile_search_filters_rows);
        int[] chosen = new int[ROWS.length]; // each row's chosen flag

        for (int r = 0; r < ROWS.length; r++) {
            int[][] row = ROWS[r];
            int index = r;
            View rowView = inflater.inflate(R.layout.mobile_search_filter_row, rows, false);
            ((TextView) rowView.findViewById(R.id.mobile_search_filter_label)).setText(row[0][0]);
            TextView value = rowView.findViewById(R.id.mobile_search_filter_value);
            for (int i = row.length - 1; i >= 1; i--) {
                if (i == 1 || (options & row[i][1]) != 0) {
                    chosen[r] = row[i][1];
                    value.setText(row[i][0]);
                    break;
                }
            }
            rowView.setOnClickListener(v -> showChoices(activity, value, row, flag -> chosen[index] = flag));
            rows.addView(rowView, r);
        }

        ChipGroup features = content.findViewById(R.id.mobile_search_filters_features);
        for (int[] feature : FEATURES) {
            Chip chip = (Chip) inflater.inflate(R.layout.mobile_search_filter_chip, features, false);
            chip.setText(feature[0]);
            chip.setTag(feature[1]);
            chip.setChecked((options & feature[1]) != 0);
            features.addView(chip);
        }

        Haptics.watch(new MaterialAlertDialogBuilder(activity, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_search_filters)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mobile_search_filter_apply, (dialog, which) -> {
                    int result = 0;
                    for (int flag : chosen) {
                        result |= flag;
                    }
                    for (int i = 0; i < features.getChildCount(); i++) {
                        Chip chip = (Chip) features.getChildAt(i);
                        if (chip.isChecked()) {
                            result |= (int) chip.getTag();
                        }
                    }
                    onApply.accept(result);
                })
                .show());
    }

    private static void showChoices(Activity activity, TextView value, int[][] row, IntConsumer onPick) {
        String[] labels = new String[row.length - 1];
        for (int i = 1; i < row.length; i++) {
            labels[i - 1] = activity.getString(row[i][0]);
        }
        ListPopupWindow popup = new ListPopupWindow(activity);
        popup.setAdapter(new ArrayAdapter<>(activity, R.layout.mobile_search_filter_choice, labels));
        popup.setAnchorView(value);
        popup.setModal(true);
        popup.setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.bg_comments_menu));
        popup.setOnItemClickListener((parent, view, position, id) -> {
            value.setText(row[position + 1][0]);
            onPick.accept(row[position + 1][1]);
            popup.dismiss();
        });
        popup.show();
    }
}

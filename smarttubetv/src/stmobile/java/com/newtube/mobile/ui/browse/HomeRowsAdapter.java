package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * VOIDXDEV(home-rows, 2.9.8, issue #4): Home as SmartTube shows it - one titled carousel per shelf
 * (Settings > General > Home layout > Rows). It mirrors the feed list the grid adapter holds
 * ({@link #submit}), grouping consecutive cards by the shelf they came from, so loading, paging and
 * filtering stay the feed's own; only the drawing differs.
 */
final class HomeRowsAdapter extends RecyclerView.Adapter<HomeRowsAdapter.RowHolder> {
    private static final int CARD_WIDTH_DP = 280;

    private static final class Row {
        final String title;
        final List<Video> videos = new ArrayList<>();

        Row(String title) {
            this.title = title;
        }
    }

    private final VideoCardAdapter.OnVideoClickListener mClick;
    private final VideoCardAdapter.OnVideoLongClickListener mLongClick;
    private final RecyclerView.RecycledViewPool mCardPool = new RecyclerView.RecycledViewPool();
    private List<Row> mRows = new ArrayList<>();

    HomeRowsAdapter(VideoCardAdapter.OnVideoClickListener click, VideoCardAdapter.OnVideoLongClickListener longClick) {
        mClick = click;
        mLongClick = longClick;
        setHasStableIds(true);
    }

    void submit(List<Video> videos) {
        List<Row> rows = new ArrayList<>();
        Row row = null;
        for (Video video : videos) {
            String title = video.getGroupTitle();
            if (row == null || !TextUtils.equals(row.title, title)) {
                row = new Row(title);
                rows.add(row);
            }
            row.videos.add(video);
        }
        mRows = rows;
        notifyDataSetChanged();
    }

    /** Cards in rows [0, row]: the feed position the grid's paging logic expects. */
    int cardsThrough(int row) {
        int cards = 0;
        for (int i = 0; i <= row && i < mRows.size(); i++) {
            cards += mRows.get(i).videos.size();
        }
        return cards - 1;
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @Override
    public long getItemId(int position) {
        // Stable per shelf title and place, so a new page keeps each row's sideways scroll.
        Row row = mRows.get(position);
        return (31L * position) + (row.title != null ? row.title.hashCode() : 0);
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new RowHolder(parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        Row row = mRows.get(position);
        holder.title.setText(row.title);
        holder.title.setVisibility(TextUtils.isEmpty(row.title) ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.cards.submitList(new ArrayList<>(row.videos));
    }

    final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final VideoCardAdapter cards;

        RowHolder(Context context) {
            super(new LinearLayout(context));
            float density = context.getResources().getDisplayMetrics().density;
            LinearLayout root = (LinearLayout) itemView;
            root.setOrientation(LinearLayout.VERTICAL);
            root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            root.setPadding(0, Math.round(8 * density), 0, Math.round(4 * density));

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            int side = Math.round(16 * density);
            title.setPadding(side, 0, side, Math.round(4 * density));
            root.addView(title);

            int cardWidth = Math.round(CARD_WIDTH_DP * density);
            cards = new VideoCardAdapter(mClick, mLongClick) {
                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    RecyclerView.ViewHolder card = super.onCreateViewHolder(parent, viewType);
                    ViewGroup.LayoutParams lp = card.itemView.getLayoutParams();
                    lp.width = cardWidth;
                    card.itemView.setLayoutParams(lp);
                    return card;
                }
            };
            RecyclerView strip = new RecyclerView(context);
            strip.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
            strip.setRecycledViewPool(mCardPool);
            strip.setNestedScrollingEnabled(false);
            strip.setClipToPadding(false);
            strip.setPadding(Math.round(4 * density), 0, Math.round(4 * density), 0);
            strip.setAdapter(cards);
            root.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
}

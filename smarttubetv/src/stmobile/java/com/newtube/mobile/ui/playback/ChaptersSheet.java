package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(chapters, issue #13): the Chapters list, opened from the current-chapter button above
 * the seek bar. Chapters arrive as the {@code VideoGroup#isChapters()} suggestions group
 * ({@code Video.isChapter} items: {@code title}, {@code startTimeMs}, the start time preformatted
 * in {@code badge}, a frame in {@code cardImageUrl}). A tap hands the chapter to the caller, which
 * seeks, and closes the sheet.
 *
 * <p>A plain {@link BottomSheetDialog}, built here and shown by the player (showPlayerSheet keeps
 * it expanded and the fullscreen system bars hidden). The list lives in the player's memory and
 * never needs to survive recreation: the player handles rotation itself.</p>
 */
final class ChaptersSheet {

    interface Listener {
        void onChapterClicked(Video chapter);
    }

    private ChaptersSheet() {
    }

    /**
     * The sheet for {@code chapters}, the one at {@code currentIndex} highlighted and scrolled into
     * view. {@code maxHeightPx} caps the sheet (0 = no cap).
     */
    static BottomSheetDialog create(@NonNull Activity activity, @NonNull List<Video> chapters, int currentIndex,
                                    int maxHeightPx, @NonNull Listener listener) {
        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_chapters, null);
        dialog.setContentView(content);

        if (maxHeightPx > 0) {
            dialog.getBehavior().setMaxHeight(maxHeightPx);
        }

        RecyclerView list = content.findViewById(R.id.chapters_sheet_list);
        LinearLayoutManager layoutManager = new LinearLayoutManager(activity);
        list.setLayoutManager(layoutManager);
        list.setAdapter(new ChapterAdapter(new ArrayList<>(chapters), currentIndex, chapter -> {
            listener.onChapterClicked(chapter);
            dialog.dismiss();
        }));
        if (currentIndex > 0) {
            // The playing chapter second from the top: the one before it stays in view for context.
            layoutManager.scrollToPositionWithOffset(currentIndex - 1, 0);
        }

        return dialog;
    }

    private static class ChapterAdapter extends RecyclerView.Adapter<ChapterHolder> {
        private final List<Video> mChapters;
        private final int mCurrentIndex;
        private final Listener mListener;

        ChapterAdapter(List<Video> chapters, int currentIndex, Listener listener) {
            mChapters = chapters;
            mCurrentIndex = currentIndex;
            mListener = listener;
        }

        @NonNull
        @Override
        public ChapterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_chapter, parent, false);
            return new ChapterHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ChapterHolder holder, int position) {
            holder.bind(mChapters.get(position), position == mCurrentIndex, mListener);
        }

        @Override
        public void onViewRecycled(@NonNull ChapterHolder holder) {
            holder.unbind();
        }

        @Override
        public int getItemCount() {
            return mChapters.size();
        }
    }

    private static class ChapterHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumbnail;
        private final TextView mTitle;
        private final TextView mTime;

        ChapterHolder(@NonNull View itemView) {
            super(itemView);
            mThumbnail = itemView.findViewById(R.id.chapter_thumbnail);
            mTitle = itemView.findViewById(R.id.chapter_title);
            mTime = itemView.findViewById(R.id.chapter_time);
        }

        void bind(Video chapter, boolean isCurrent, Listener listener) {
            Context context = itemView.getContext();
            mTitle.setText(chapter.title);
            mTime.setText(chapter.badge);
            // The playing chapter: a filled row, as YouTube marks it (no accent color, no bold).
            itemView.setBackgroundColor(isCurrent
                    ? ContextCompat.getColor(context, R.color.mobile_color_pill) : 0);
            itemView.setOnClickListener(v -> listener.onChapterClicked(chapter));

            if (chapter.cardImageUrl != null) {
                // Ahead of the queue: the watch page binds every related thumbnail at once, and
                // with Glide's two source threads the sheet's frames waited seconds behind them.
                Glide.with(context)
                        .load(chapter.cardImageUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .priority(Priority.IMMEDIATE)
                        .centerCrop()
                        .into(mThumbnail);
            } else {
                unbind();
            }
        }

        void unbind() {
            Glide.with(itemView.getContext().getApplicationContext()).clear(mThumbnail);
            mThumbnail.setImageDrawable(null);
        }
    }
}

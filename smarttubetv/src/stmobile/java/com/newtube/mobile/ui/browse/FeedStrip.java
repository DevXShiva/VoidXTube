package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.newtube.mobile.ui.common.GlassDrawable;
import com.newtube.mobile.ui.common.GlassSource;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * VOIDXDEV(feed-strip, 2.9.8): the horizontal strip at the top of a browse feed, YouTube-style. Home
 * shows topic chips (issue #4: All / Music / Gaming / ...), Subscriptions shows the subscribed
 * channels' avatars (issue #2: All first, a tap filters the feed to that channel). The avatars are
 * the grid's first row (a ConcatAdapter header, full span) and scroll away with the feed; the chips
 * float over the feed instead ({@link #items()} in the activity's chip row), each a liquid-glass pill
 * frosting the page under it - inside the page they would have to sample themselves.
 */
final class FeedStrip extends RecyclerView.Adapter<FeedStrip.Holder> {
    static final int MODE_NONE = 0;
    static final int MODE_CHIPS = 1;
    static final int MODE_AVATARS = 2;

    static final class Item {
        final long id;
        final CharSequence label;
        @Nullable final String imageUrl;
        @Nullable final Object data;

        Item(long id, CharSequence label, @Nullable String imageUrl, @Nullable Object data) {
            this.id = id;
            this.label = label;
            this.imageUrl = imageUrl;
            this.data = data;
        }
    }

    interface Listener {
        void onPicked(Item item);
    }

    private final Listener mListener;
    @Nullable private final GlassSource mGlass;
    private final ItemAdapter mItems = new ItemAdapter();
    private int mMode = MODE_NONE;
    private long mSelected;

    FeedStrip(Listener listener, @Nullable GlassSource glass) {
        mListener = listener;
        mGlass = glass;
    }

    /** The items themselves, for the floating chip row. */
    RecyclerView.Adapter<?> items() {
        return mItems;
    }

    int mode() {
        return mMode;
    }

    /** Shows the strip (or hides it with MODE_NONE / no items); the selected item gets the mark. */
    void show(int mode, List<Item> items, long selected) {
        boolean wasShown = getItemCount() > 0;
        boolean sameList = mode == mMode && sameItems(items);
        int oldMode = mMode;
        mMode = items == null || items.isEmpty() ? MODE_NONE : mode;
        if (oldMode != mMode) {
            mItems.mList = new ArrayList<>(); // another kind of cell: rebuild, not rebind
            mItems.notifyDataSetChanged();
        }
        mSelected = selected;
        if (!sameList) {
            mItems.mList = items != null ? new ArrayList<>(items) : new ArrayList<>();
        }
        mItems.notifyDataSetChanged();
        boolean shown = getItemCount() > 0;
        if (wasShown && !shown) {
            notifyItemRemoved(0);
        } else if (!wasShown && shown) {
            notifyItemInserted(0);
        }
    }

    void select(long selected) {
        mSelected = selected;
        mItems.notifyDataSetChanged();
    }

    long selected() {
        return mSelected;
    }

    private boolean sameItems(List<Item> items) {
        if (items == null || items.size() != mItems.mList.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id != mItems.mList.get(i).id) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getItemCount() {
        return mMode == MODE_AVATARS ? 1 : 0; // chips float (items()), only avatars sit in the grid
    }

    @Override
    public int getItemViewType(int position) {
        return R.id.mobile_feed_strip; // its own type: never confused with a card in the ConcatAdapter
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        RecyclerView row = new RecyclerView(parent.getContext());
        row.setId(R.id.mobile_feed_strip);
        row.setLayoutManager(new LinearLayoutManager(parent.getContext(), RecyclerView.HORIZONTAL, false));
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(parent.getContext(), 12);
        row.setPadding(pad, dp(parent.getContext(), 4), pad, dp(parent.getContext(), 8));
        row.setClipToPadding(false);
        row.setItemAnimator(null);
        // The feed scrolls vertically; a sideways drag on the strip is the strip's.
        row.setNestedScrollingEnabled(false);
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        RecyclerView row = (RecyclerView) holder.itemView;
        if (row.getAdapter() != mItems) {
            row.setAdapter(mItems);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(@NonNull View itemView) {
            super(itemView);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private final class ItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Item> mList = new ArrayList<>();

        @Override
        public int getItemCount() {
            return mList.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mMode;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == MODE_AVATARS) {
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setLayoutParams(new RecyclerView.LayoutParams(dp(context, 72), ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setPadding(0, dp(context, 4), 0, 0);
                ImageView avatar = new ImageView(context);
                avatar.setId(android.R.id.icon);
                avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(avatar, new LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)));
                TextView name = new TextView(context);
                name.setId(android.R.id.text1);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER_HORIZONTAL);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nameLp.topMargin = dp(context, 6);
                nameLp.leftMargin = nameLp.rightMargin = dp(context, 2);
                cell.addView(name, nameLp);
                view = cell;
            } else {
                TextView chip = new TextView(context);
                chip.setId(android.R.id.text1);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                chip.setGravity(Gravity.CENTER);
                chip.setMinHeight(dp(context, 34));
                chip.setPadding(dp(context, 14), 0, dp(context, 14), 0);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36));
                lp.rightMargin = dp(context, 8);
                chip.setLayoutParams(lp);
                chip.setElevation(dp(context, 4));
                // VOIDXDEV(glass): frosted, saturated feed under each pill (liquid: grain + rim too);
                // made once per cell - apply() hooks a pre-draw listener.
                GlassDrawable.apply(chip, mGlass, 0);
                chip.setTag(R.id.mobile_feed_strip, chip.getBackground());
                view = chip;
            }
            view.setClickable(true);
            view.setFocusable(true);
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = mList.get(position);
            Context context = holder.itemView.getContext();
            boolean selected = item.id == mSelected;
            TextView label = holder.itemView.findViewById(android.R.id.text1);
            label.setText(item.label);
            if (mMode == MODE_AVATARS) {
                // YouTube: picking one channel fades the others; "All" keeps every avatar lit.
                boolean dim = mSelected != mList.get(0).id && !selected;
                holder.itemView.setAlpha(dim ? 0.45f : 1f);
                label.setTextColor(ContextCompat.getColor(context, selected
                        ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary));
                ImageView avatar = holder.itemView.findViewById(android.R.id.icon);
                if (item.imageUrl != null) {
                    avatar.setBackground(null);
                    avatar.setImageDrawable(null);
                    Glide.with(context).load(item.imageUrl).circleCrop()
                            .placeholder(R.drawable.ic_watch_channel_placeholder)
                            .error(R.drawable.ic_watch_channel_placeholder).into(avatar);
                } else {
                    // "All": a plain disc with the grid glyph.
                    Glide.with(context).clear(avatar);
                    GradientDrawable disc = new GradientDrawable();
                    disc.setShape(GradientDrawable.OVAL);
                    disc.setColor(ContextCompat.getColor(context, R.color.mobile_color_pill));
                    avatar.setBackground(disc);
                    avatar.setScaleType(ImageView.ScaleType.CENTER);
                    avatar.setImageResource(R.drawable.ic_nav_subscriptions_outline);
                    avatar.setColorFilter(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
                }
                if (item.imageUrl != null) {
                    avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    avatar.clearColorFilter();
                }
            } else {
                // The picked chip is a solid light pill on the glass row, the rest are glass.
                if (selected) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(dp(context, 18));
                    bg.setColor(0xF2FFFFFF);
                    label.setBackground(bg);
                } else {
                    label.setBackground((android.graphics.drawable.Drawable) label.getTag(R.id.mobile_feed_strip));
                }
                label.setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
            }
            holder.itemView.setOnClickListener(v -> {
                if (item.id != mSelected) {
                    mListener.onPicked(item);
                }
            });
        }
    }
}

package com.newtube.mobile.ui.playback;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.GlassDrawable;
import com.newtube.mobile.ui.common.GlassSource;
import com.newtube.mobile.ui.common.ShortsSeen;

import java.util.List;
import java.util.Locale;

/**
 * VOIDXDEV(shorts): the TikTok/YouTube-style vertical player, layered on the ordinary one. A video
 * from the Shorts tab ({@link Video#belongsToShorts()}) switches the watch screen into shorts mode:
 * the video fills the screen (the host resizes it), this overlay shows the channel row, title and
 * actions like YouTube's Shorts, and a vertical drag pages to the next / previous short. Tap
 * pauses, double tap likes (with a heart), hold is the player's own 2x. Looping and preloading
 * the next one come from the shared playback controllers.
 *
 * <p>Paging follows the finger 1:1 with the neighbour's portrait thumbnail (oar2, preloaded into
 * Glide's memory cache a moment after each short opens) sliding in behind it, as YouTube's pager
 * does; the thumbnail then covers the screen until the new short is bound, and the host's loading
 * still (same image, same cache key - {@link #portrait}) holds it until the first frame. With no
 * neighbour queued yet the drag only rubber-bands.</p>
 */
final class ShortsOverlay {
    interface Host {
        void onShortsModeChanged(boolean on);

        void playNextShort();

        void playPreviousShort();

        void openShortsComments();

        void openShortsMore();

        void seekShortTo(long positionMs);

        void exitShorts();
    }

    /** The watch page's views the overlay mirrors and forwards to. */
    static final class Watch {
        View like;
        View dislike;
        View share;
        View subscribe;
        TextView likeCount;
        TextView commentsCount;
        TextView meta;
        TextView subscribeLabel;
        ImageView avatar;
    }

    private static final long OUT_MS = 220;
    private static final long IN_MS = 240;
    private static final float FLING_PX_PER_S = 1200f;
    /** No neighbour to page to: the drag gives a quarter of the finger's travel. */
    private static final float RUBBER_BAND = 0.25f;
    /**
     * VOIDXDEV(shorts): at once - a swipe inside the old 1.2 s found no neighbour warmed. The
     * thumbnails are small next to the short's first media chunks.
     */
    private static final long NEIGHBOUR_PRELOAD_DELAY_MS = 0;
    /** The peek stays over a paged-to short until it is bound, or this long at most. */
    private static final long PEEK_COVER_MAX_MS = 2500;
    private static final int PORTRAIT_W = 720;
    private static final int PORTRAIT_H = 1280;

    private final View mOverlay;
    private final View mVideo;
    private final TextView mChannel;
    private final TextView mTitle;
    private final TextView mMeta;
    private final TextView mLikes;
    private final TextView mComments;
    private final TextView mSubscribe;
    private final TextView mSeekTime;
    private final ImageView mLikeIcon;
    private final ImageView mAvatar;
    private final ImageView mDisc;
    private final ImageView mFlash;
    private final ImageView mHeart;
    private final ImageView mPeek;
    private final SeekBar mProgress;
    private final Watch mWatch;
    private final Host mHost;
    private boolean mAvatarKnown;
    private boolean mInPip;
    private final Runnable mPreloadNeighbours = this::preloadNeighbours;
    private final Runnable mHidePeek = this::hidePeek;
    @Nullable
    private Video mCurrent;
    @Nullable
    private Video mPeekVideo;
    private int mPeekDir;
    private boolean mActive;
    private boolean mCommentsOpen;
    private boolean mScrubbing;
    private long mDurationMs;

    ShortsOverlay(@NonNull View root, @NonNull View video, @NonNull Host host, @NonNull Watch watch) {
        mOverlay = root.findViewById(R.id.mobile_shorts_overlay);
        mVideo = video;
        mHost = host;
        mWatch = watch;
        mChannel = root.findViewById(R.id.mobile_shorts_channel);
        mTitle = root.findViewById(R.id.mobile_shorts_title);
        mMeta = root.findViewById(R.id.mobile_shorts_meta);
        mLikes = root.findViewById(R.id.mobile_shorts_like_count);
        mComments = root.findViewById(R.id.mobile_shorts_comments_count);
        mSubscribe = root.findViewById(R.id.mobile_shorts_subscribe);
        mSeekTime = root.findViewById(R.id.mobile_shorts_seek_time);
        mLikeIcon = root.findViewById(R.id.mobile_shorts_like);
        mAvatar = root.findViewById(R.id.mobile_shorts_avatar);
        mDisc = root.findViewById(R.id.mobile_shorts_disc);
        mFlash = root.findViewById(R.id.mobile_shorts_flash);
        mHeart = root.findViewById(R.id.mobile_shorts_heart);
        mPeek = root.findViewById(R.id.mobile_shorts_peek);
        mProgress = root.findViewById(R.id.mobile_shorts_progress);
        ShortsSeen.load(root.getContext());

        forward(R.id.mobile_shorts_like, watch.like);
        forward(R.id.mobile_shorts_dislike, watch.dislike);
        forward(R.id.mobile_shorts_share, watch.share);
        forward(R.id.mobile_shorts_subscribe, watch.subscribe);
        mOverlay.findViewById(R.id.mobile_shorts_comments).setOnClickListener(v -> mHost.openShortsComments());
        mOverlay.findViewById(R.id.mobile_shorts_more).setOnClickListener(v -> mHost.openShortsMore());
        mOverlay.findViewById(R.id.mobile_shorts_back).setOnClickListener(v -> mHost.exitShorts());
        // VOIDXDEV(glass): as on the player, the buttons over the short (and the play / pause flash)
        // sit on frosted discs of it; the actions keep their 48dp discs, the labels their shadows.
        GlassSource glass = root.findViewById(R.id.mobile_player_glass);
        for (int id : new int[] {R.id.mobile_shorts_like, R.id.mobile_shorts_dislike, R.id.mobile_shorts_comments,
                R.id.mobile_shorts_share, R.id.mobile_shorts_more, R.id.mobile_shorts_flash}) {
            GlassDrawable.apply(mOverlay.findViewById(id), glass, 0);
        }
        GlassDrawable.apply(mOverlay.findViewById(R.id.mobile_shorts_back), glass,
                Math.round(4 * root.getResources().getDisplayMetrics().density)); // the player's minimize disc
        // Tap the title for the rest of it (and tap again to fold it).
        mTitle.setOnClickListener(v -> mTitle.setMaxLines(mTitle.getMaxLines() == 2 ? 8 : 2));

        round(mAvatar, true);
        round(mDisc, false);
        mProgress.getThumb().mutate().setAlpha(0);
        mProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    mSeekTime.setText(time(progress * mDurationMs / 1000) + " / " + time(mDurationMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                mScrubbing = true;
                bar.getThumb().setAlpha(255);
                mSeekTime.setVisibility(View.VISIBLE);
                showChrome(false); // YouTube clears the text while you scrub
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                mScrubbing = false;
                bar.getThumb().setAlpha(0);
                mSeekTime.setVisibility(View.GONE);
                showChrome(true);
                if (mDurationMs > 0) {
                    mHost.seekShortTo(bar.getProgress() * mDurationMs / 1000);
                }
            }
        });

        mirror(watch.likeCount, text -> mLikes.setText(isCount(text) ? text
                : mLikes.getContext().getString(R.string.mobile_watch_like)));
        mirror(watch.commentsCount, text -> mComments.setText(isCount(text) ? text
                : mComments.getContext().getString(R.string.mobile_comments_title)));
        mirror(watch.meta, mMeta::setText);
        mirror(watch.subscribeLabel, mSubscribe::setText);
    }

    /** The portrait still of a short - one request shape for the peek, its preload and the host's still. */
    static RequestBuilder<Drawable> portrait(@NonNull Context context, @NonNull String videoId) {
        return Glide.with(context.getApplicationContext())
                .load("https://i.ytimg.com/vi/" + videoId + "/oar2.jpg")
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565)
                .override(PORTRAIT_W, PORTRAIT_H)
                .centerCrop();
    }

    boolean isActive() {
        return mActive;
    }

    /** Shorts mode with the video filling the screen (not shrunk above the comments). */
    boolean isFullscreen() {
        return mActive && !mCommentsOpen;
    }

    boolean isCommentsOpen() {
        return mActive && mCommentsOpen;
    }

    /** The comments sheet takes the lower part of the screen; the short shrinks above it. */
    void setCommentsOpen(boolean open) {
        mCommentsOpen = open;
        mOverlay.setVisibility(mActive && !open && !mInPip ? View.VISIBLE : View.GONE);
    }

    /** VOIDXDEV(shorts-pip): the PiP window shows only the short; the buttons come back on expand. */
    void setInPip(boolean pip) {
        mInPip = pip;
        hidePeek();
        mOverlay.setVisibility(mActive && !mCommentsOpen && !pip ? View.VISIBLE : View.GONE);
    }

    /** Every setVideo: shorts mode follows the video that is playing now. */
    void bind(@Nullable Video item) {
        boolean on = item != null && item.belongsToShorts();
        if (on) {
            boolean fresh = mCurrent == null || !mCurrent.equals(item);
            mCurrent = item;
            ShortsSeen.add(item.videoId);
            mChannel.setText(item.getAuthor());
            mTitle.setText(item.getTitle());
            if (fresh) {
                mTitle.setMaxLines(2);
                mAvatar.setImageDrawable(null);
                mDisc.setImageDrawable(null);
                mAvatarKnown = false;
                showKnownAvatar(item);
                mOverlay.removeCallbacks(mPreloadNeighbours);
                mOverlay.postDelayed(mPreloadNeighbours, NEIGHBOUR_PRELOAD_DELAY_MS);
                if (mPeek.getVisibility() == View.VISIBLE && mPeekDir == 0) {
                    mOverlay.removeCallbacks(mHidePeek);
                    mOverlay.postDelayed(mHidePeek, 60); // the host's still has drawn by then
                }
            }
            // The watch page loads the channel avatar on its own schedule: copy it as it lands
            // (not at once for a new short - the watch page still shows the last one's then).
            if (!fresh) {
                copyAvatar();
            }
            mAvatar.postDelayed(this::copyAvatar, 400);
            mAvatar.postDelayed(this::copyAvatar, 1200);
            mAvatar.postDelayed(this::copyAvatar, 2500);
        }
        if (on != mActive) {
            mActive = on;
            mCommentsOpen = false;
            mOverlay.setVisibility(on && !mInPip ? View.VISIBLE : View.GONE);
            if (!on) {
                mCurrent = null;
                hidePeek();
                mVideo.setTranslationY(0f);
                mVideo.setAlpha(1f);
            }
            mHost.onShortsModeChanged(on);
        }
    }

    void setLiked(boolean liked) {
        mLikeIcon.setImageResource(liked ? R.drawable.ic_watch_thumb_up : R.drawable.ic_watch_thumb_up_outline);
    }

    void setSubscribed(boolean subscribed) {
        mSubscribe.setSelected(subscribed);
    }

    /** VOIDXDEV(burst): where a like or a subscribe made in Shorts splashes. */
    View likeIcon() {
        return mLikeIcon;
    }

    View subscribeButton() {
        return mSubscribe;
    }

    void setProgress(long positionMs, long durationMs) {
        if (mActive && !mScrubbing) {
            mDurationMs = durationMs;
            mProgress.setProgress(durationMs > 0 ? (int) (positionMs * 1000 / durationMs) : 0);
        }
    }

    /** The tap's answer: a play or pause glyph that pops up in the middle and fades. */
    void flashPlayState(boolean playing) {
        mFlash.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play); // paused shows play
        mFlash.animate().cancel();
        mFlash.setAlpha(1f);
        mFlash.setScaleX(0.8f);
        mFlash.setScaleY(0.8f);
        mFlash.animate().scaleX(1f).scaleY(1f).setDuration(150).withEndAction(() ->
                mFlash.animate().alpha(0f).setStartDelay(450).setDuration(200).start()).start();
    }

    /** Double tap: a big heart springs in and floats off. */
    void popHeart() {
        mHeart.animate().cancel();
        mHeart.setAlpha(1f);
        mHeart.setScaleX(0.4f);
        mHeart.setScaleY(0.4f);
        mHeart.setRotation((float) (Math.random() * 24 - 12));
        mHeart.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(2.5f)).setDuration(260)
                .withEndAction(() -> mHeart.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setStartDelay(250)
                        .setInterpolator(new DecelerateInterpolator()).setDuration(400).start()).start();
    }

    /** The short follows the finger; the neighbour's thumbnail comes in behind it. */
    void drag(float dy) {
        int dir = dy < 0 ? 1 : dy > 0 ? -1 : 0; // finger up: the next short comes up from below
        if (dir != mPeekDir) {
            showPeek(dir);
        }
        mVideo.animate().cancel();
        mVideo.setTranslationY(mPeekVideo != null ? dy : dy * RUBBER_BAND);
    }

    void release(float dy, float yVelocity) {
        float height = Math.max(1, mVideo.getHeight());
        boolean far = Math.abs(dy) > height * 0.18f;
        boolean fling = Math.abs(yVelocity) > FLING_PX_PER_S && Math.signum(yVelocity) == Math.signum(dy);
        if (dy == 0 || mPeekVideo == null || !(far || fling)) {
            mVideo.animate().translationY(0f).setDuration(IN_MS).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(this::hidePeek).start();
            return;
        }
        boolean next = dy < 0;
        float left = 1f - Math.min(1f, Math.abs(mVideo.getTranslationY()) / height);
        mVideo.animate().translationY(next ? -height : height).setDuration(Math.max(90, (long) (OUT_MS * left)))
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    mProgress.setProgress(0);
                    // The thumbnail now fills the screen: it stays there, over the old short's last
                    // frame, until the new one is bound (bind hides it).
                    mPeekDir = 0;
                    mPeek.setTranslationY(0f);
                    mVideo.setTranslationY(0f);
                    mOverlay.removeCallbacks(mHidePeek);
                    mOverlay.postDelayed(mHidePeek, PEEK_COVER_MAX_MS);
                    if (next) {
                        mHost.playNextShort();
                    } else {
                        mHost.playPreviousShort();
                    }
                }).start();
    }

    private void showChrome(boolean show) {
        for (int id : new int[] {R.id.mobile_shorts_info, R.id.mobile_shorts_actions}) {
            mOverlay.findViewById(id).animate().alpha(show ? 1f : 0f).setDuration(150).start();
        }
    }

    private void showPeek(int dir) {
        mPeekDir = dir;
        mPeekVideo = dir != 0 ? neighbour(dir) : null;
        if (mPeekVideo == null || mPeekVideo.videoId == null) {
            mPeekVideo = null;
            mPeek.setVisibility(View.GONE);
            return;
        }
        mOverlay.removeCallbacks(mHidePeek);
        float height = mVideo.getHeight();
        mPeek.setTranslationY(dir > 0 ? height : -height);
        mPeek.setVisibility(View.VISIBLE);
        portrait(mPeek.getContext(), mPeekVideo.videoId).into(mPeek);
    }

    private void hidePeek() {
        mOverlay.removeCallbacks(mHidePeek);
        mPeekDir = 0;
        mPeekVideo = null;
        mPeek.setVisibility(View.GONE);
    }

    /** The short {@code step} places from this one in the queue the Shorts tab built, or null. */
    @Nullable
    private Video neighbour(int step) {
        List<Video> queue = Playlist.instance().getAll();
        int at = mCurrent != null ? queue.indexOf(mCurrent) : -1;
        int to = at + step;
        return at >= 0 && to >= 0 && to < queue.size() ? queue.get(to) : null;
    }

    /** Warm Glide's memory cache with the shorts a swipe can reach, so the peek is there at once. */
    private void preloadNeighbours() {
        if (!mActive) {
            return;
        }
        for (int step : new int[] {1, -1, 2, 3}) {
            Video video = neighbour(step);
            if (video != null && video.videoId != null) {
                portrait(mOverlay.getContext(), video.videoId).preload();
            }
        }
    }

    /** VOIDXDEV(card avatars): the channel's avatar at once, from ChannelAvatars, not the watch page. */
    private void showKnownAvatar(Video item) {
        android.content.Context context = mAvatar.getContext();
        String url = com.newtube.mobile.ui.common.ChannelAvatars.get(context, item.channelId, item.getAuthor());
        if (url != null) {
            loadAvatar(url);
        } else {
            com.newtube.mobile.ui.common.ChannelAvatars.fetch(context, item.channelId, item.getAuthor(),
                    () -> item.equals(mCurrent), found -> {
                if (item.equals(mCurrent)) {
                    loadAvatar(found);
                }
            }, true);
        }
    }

    private void loadAvatar(String url) {
        mAvatarKnown = true;
        com.bumptech.glide.Glide.with(mAvatar).load(url).circleCrop().into(mAvatar);
        com.bumptech.glide.Glide.with(mDisc).load(url).centerCrop().into(mDisc);
    }

    private void copyAvatar() {
        if (mAvatarKnown) {
            return; // already the channel's own picture
        }
        Drawable avatar = mWatch.avatar != null ? mWatch.avatar.getDrawable() : null;
        if (avatar != null && avatar.getConstantState() != null) {
            mAvatar.setImageDrawable(avatar.getConstantState().newDrawable().mutate());
            mDisc.setImageDrawable(avatar.getConstantState().newDrawable().mutate());
        }
    }

    private static void round(View view, boolean oval) {
        float radius = 8 * view.getResources().getDisplayMetrics().density;
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                if (oval) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                } else {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            }
        });
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** A count has digits; the placeholder and "N/A" (counts hidden by the channel) don't. */
    private static boolean isCount(@Nullable CharSequence text) {
        return text != null && text.toString().matches(".*\\d.*");
    }

    private interface OnText {
        void set(CharSequence text);
    }

    private static void mirror(@Nullable TextView source, OnText target) {
        if (source == null) {
            return;
        }
        target.set(source.getText());
        source.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                target.set(s);
            }
        });
    }

    private void forward(int id, @Nullable View target) {
        View button = mOverlay.findViewById(id);
        if (target == null) {
            button.setVisibility(View.GONE);
        } else {
            button.setOnClickListener(v -> target.performClick());
        }
    }
}

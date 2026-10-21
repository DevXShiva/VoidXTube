package com.newtube.mobile.ui.channel;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.FilteredPageTopUp;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.ShortsFilter;
import com.newtube.mobile.ui.common.SkeletonReveal;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Touch Channel page (Wave 4a; tabbed 2026-07-09).
 *
 * <p>The touch replacement for the Leanback {@code ChannelFragment}/{@code ChannelActivity}.
 * {@link ChannelView} delivers MULTIPLE {@link VideoGroup}s (one per channel section:
 * Videos, Live, Playlists, Shorts, ...). YouTube-style but pure Material: each section is a
 * {@link TabLayout} tab, and the selected section renders below as the standard full-width
 * card feed (the same {@link VideoCardAdapter} as Home/Search) — no nested carousels, no
 * interleaved mega-list.</p>
 *
 * <p>Drives the unchanged {@link ChannelPresenter} via the standard MVP seam. Routing is
 * fully natural: tapping a plain video plays it ({@code MobilePlaybackActivity}); tapping a
 * playlist/sub-channel item opens {@code MobileChannelUploadsActivity}/this screen again
 * (via {@code VideoActionPresenter.apply()}); long-press/⋮ shows the context menu through
 * the Wave-3 {@code MobileAppDialogActivity}.</p>
 */
public class MobileChannelActivity extends MobileActivity
        implements ChannelView, MiniPlayerBridge.MiniHost {
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;

    /** A channel section, keyed by its {@link VideoGroup#getId()} so continuations/replacements
     *  for the same row merge into it instead of creating a duplicate tab. */
    private static final class Section {
        final int id;
        String title;
        final List<Video> videos = new ArrayList<>();
        /** NEWTUBE(shorts): pages this tab fetches itself when its dropped Shorts left it short. */
        final FilteredPageTopUp topUp = new FilteredPageTopUp();
        /**
         * NEWTUBE(shorts): the group this tab pages - its last video (a Short, maybe) is the anchor
         * for the next page, so a tab whose cards were all Shorts can still continue.
         */
        VideoGroup group;

        Section(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private ChannelPresenter mPresenter;

    private RecyclerView mGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private TabLayout mTabs;
    private ProgressBar mProgressBar;
    private TextView mTitleView;
    /** The header holds a real channel name (from the card, or from the loaded videos). */
    private boolean mTitleFromCard;
    private ImageButton mBackButton;
    /** VOIDXDEV(channel sort, issue #5): ChannelPresenter's sorted page arrives as this group id. */
    private static final int SORTED_GROUP_ID = 144;
    /** The tab the sort was asked from: the sorted videos replace its contents. */
    private int mSortTargetId = -1;
    private MobileMiniPlayerController mMiniPlayer;
    private boolean mAnimateMiniFromPlayer;
    private SwipeRefreshLayout mSwipe;
    /** First page failed / came back empty (see {@link #showLoadFailure}). */
    private PageLoadState mLoadState;
    /** "Couldn't load more" row under the section whose next page failed. */
    private LoadMoreFailureAdapter mLoadMoreFooter;
    /** Section whose next page failed; its scroll-end paging is suspended until Try again. */
    private int mLoadMoreFailedSectionId = -1;
    /** Section the last next-page request was made for (a failure is reported without it). */
    private int mPagingSectionId = -1;
    /** Pull-to-refresh: re-select this tab when a section with this title comes back. */
    private String mRestoreSectionTitle;

    /** Sections in delivery order; iteration order == tab order. */
    private final Map<Integer, Section> mSections = new LinkedHashMap<>();
    /** NEWTUBE(shorts): sections arrived but the Shorts filter left nothing of them (#63). */
    private boolean mShortsEmptied;
    /** Group id of the section shown in the grid (its tab is selected). */
    private int mActiveSectionId = -1;
    private int mLastPaginationTriggerCount = -1;
    /** Guards against the tab-selected listener reacting to programmatic tab sync. */
    private boolean mSuppressTabCallback;
    /** NEWTUBE(shorts): onDestroy has begun - the presenter's teardown must not page on. */
    private boolean mTornDown;
    /** The neighbouring tab's cards, slid in beside the grid during a sideways swipe. */
    private RecyclerView mPeek;
    private VideoCardAdapter mPeekAdapter;
    /** Tab index {@link #mPeek} shows, -1 = none yet this swipe. */
    private int mPeekTab = -1;
    /** A swipe just selected a tab: {@link #mPeek} covers the grid until its cards are drawn. */
    private boolean mPeekSwapPending;
    private ValueAnimator mSwipeAnim;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_channel);

        registerBackHandler(this::handleBack);

        bindViews();
        mMiniPlayer = new MobileMiniPlayerController(this);
        mAnimateMiniFromPlayer = MiniPlayerBridge.completePendingNavigation();
        if (mAnimateMiniFromPlayer) {
            // The live card supplies the spatial transition from the watch page; suppress the
            // channel window's generic alpha so the two motions do not stack.
            overridePendingTransition(0, 0);
        }
        setupGrid();
        setupTabs();
        setupSwipeRefresh();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.mobile_channel_sort).setOnClickListener(v -> {
            if (mPresenter != null && mActiveSectionId != -1) {
                mSortTargetId = mActiveSectionId;
                mPresenter.onSearchSettingsClicked(sortChannelId()); // the Latest / Popular / Oldest picker
            }
        });

        mPresenter = ChannelPresenter.instance(this);

        applyChannelTitle();
        loadHeader();

        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    /** VOIDXDEV(channel header, issue #10): the channel's UC id the header shows, once asked for. */
    @Nullable
    private String mHeaderChannelId;

    /** Fill the header once the channel's UC id is known (a link-opened one: from its videos). */
    private void loadHeader() {
        String id = sortChannelId();
        if (id == null || !id.startsWith("UC") || id.equals(mHeaderChannelId)) {
            return;
        }
        mHeaderChannelId = id;
        findViewById(R.id.mobile_channel_header).setVisibility(View.GONE);
        ChannelHeader.fetch(id, info -> {
            if (!isDestroyed() && id.equals(mHeaderChannelId)) {
                bindHeader(id, info);
            }
        });
    }

    private void bindHeader(String channelId, ChannelHeader.Info info) {
        View header = findViewById(R.id.mobile_channel_header);
        ((TextView) findViewById(R.id.mobile_channel_name)).setText(info.title);
        setTextOrGone(R.id.mobile_channel_handle, info.handle);
        setTextOrGone(R.id.mobile_channel_stats, info.stats);
        TextView description = setTextOrGone(R.id.mobile_channel_description, info.description);
        description.setMaxLines(2);
        description.setOnClickListener(v -> description.setMaxLines(
                description.getMaxLines() == 2 ? Integer.MAX_VALUE : 2)); // tap: the rest, in place

        com.bumptech.glide.RequestManager glide = com.bumptech.glide.Glide.with(this);
        com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions fade =
                com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(250);
        android.widget.ImageView avatar = findViewById(R.id.mobile_channel_avatar);
        avatar.setImageDrawable(com.newtube.mobile.ui.common.ChannelAvatars.placeholder(info.title));
        if (info.avatarUrl != null) {
            com.newtube.mobile.ui.common.ChannelAvatars.put(this, channelId, info.title, info.avatarUrl);
            glide.load(info.avatarUrl).circleCrop().placeholder(avatar.getDrawable()).transition(fade).into(avatar);
        }
        findViewById(R.id.mobile_channel_banner_frame).setVisibility(info.bannerUrl != null ? View.VISIBLE : View.GONE);
        if (info.bannerUrl != null) {
            glide.load(info.bannerUrl).transition(fade).into((android.widget.ImageView) findViewById(R.id.mobile_channel_banner));
        }
        // The wash: the banner (or avatar) decoded tiny and stretched - the upscale is the blur.
        String wash = info.bannerUrl != null ? info.bannerUrl : info.avatarUrl;
        android.widget.ImageView washView = findViewById(R.id.mobile_channel_header_wash);
        if (wash != null) {
            glide.load(wash).override(24).transition(fade).into(washView);
        } else {
            glide.clear(washView);
        }
        if (!mTitleFromCard) {
            mTitleFromCard = true;
            mTitleView.setText(info.title);
        }
        if (header.getVisibility() != View.VISIBLE) {
            header.setVisibility(View.VISIBLE);
            header.setAlpha(0f);
            header.animate().alpha(1f).setDuration(220).start();
        }
    }

    private TextView setTextOrGone(int id, @Nullable String text) {
        TextView view = findViewById(id);
        view.setText(text);
        view.setVisibility(text != null ? View.VISIBLE : View.GONE);
        return view;
    }

    /**
     * Prefer the channel/author name (e.g. "Ibai") over the source item's own title (which, when
     * a channel is opened from a video, is that video's title). Mirrors the TV ChannelFragment
     * header (Helpers.firstNonNull(author, title)). NEWTUBE: a channel opened from a link has no
     * card; keep the default header until {@link #update} names it from the loaded videos.
     */
    private void applyChannelTitle() {
        Video channel = mPresenter != null ? mPresenter.getChannel() : null;
        String name = channel != null ? Helpers.firstNonNull(channel.getAuthor(), channel.getTitle()) : null;
        mTitleFromCard = name != null;
        mTitleView.setText(name != null ? name : getString(R.string.header_channels));
    }

    /** The channel's UC id: the presenter's, or (opened from an @handle link) its videos'. */
    @Nullable
    private String sortChannelId() {
        String id = mPresenter.getChannelId();
        if (id != null && id.startsWith("UC")) {
            return id;
        }
        for (Section section : mSections.values()) {
            for (Video video : section.videos) {
                if (video.channelId != null && video.channelId.startsWith("UC")) {
                    return video.channelId;
                }
            }
        }
        return id;
    }

    /** NEWTUBE: name a link-opened channel once its first videos arrive. */
    private void maybeNameFromVideos(@Nullable List<Video> videos) {
        if (mTitleFromCard || videos == null) {
            return;
        }
        List<String> authors = new ArrayList<>();
        for (Video video : videos) {
            if (video != null) {
                authors.add(video.getAuthor());
            }
        }
        String name = ChannelTitle.fromAuthors(authors);
        if (name != null) {
            mTitleFromCard = true;
            mTitleView.setText(name);
        }
    }

    private void bindViews() {
        mGrid = findViewById(R.id.mobile_channel_grid);
        // NEWTUBE(mini-inset): the last row can scroll clear of the docked mini-player card.
        com.newtube.mobile.ui.playback.MiniPlayerListInset.attach(findViewById(R.id.mobile_mini_player), mGrid);
        mTabs = findViewById(R.id.mobile_channel_tabs);
        mPeek = findViewById(R.id.mobile_channel_peek);
        mProgressBar = findViewById(R.id.mobile_channel_progress);
        mTitleView = findViewById(R.id.mobile_channel_title);
        mBackButton = findViewById(R.id.mobile_channel_back);
        mSwipe = findViewById(R.id.mobile_channel_swipe);
        mLoadState = new PageLoadState(findViewById(R.id.mobile_page_load_state), this::retryFirstPage);
    }

    private void setupGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);
        mLoadMoreFooter = new LoadMoreFailureAdapter(this::retryLoadMore);

        // Channel rows (rare here) span the whole grid width in multi-column layouts, and so does
        // the "Couldn't load more" row after the cards.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position >= mAdapter.getItemCount() || mAdapter.isFullSpan(position)
                        ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mGrid.setHasFixedSize(true);
        mGrid.setItemViewCacheSize(8);
        mGrid.setLayoutManager(mLayoutManager);
        // Cards first, so grid positions below the footer are card positions.
        mGrid.setAdapter(new ConcatAdapter(mAdapter, mLoadMoreFooter));
        // Next cards' thumbnails decoded before they scroll in (no grey card + fade on a fling).
        com.newtube.mobile.ui.common.FeedThumbnailPreloader.attach(mGrid, mAdapter);
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // dy == 0: a layout pass changed the visible range, not the user.
                maybeTriggerPagination(dy != 0);
            }
        });
    }

    private void setupTabs() {
        mTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (mSuppressTabCallback || !(tab.getTag() instanceof Integer)) {
                    return;
                }
                mActiveSectionId = (Integer) tab.getTag();
                mRestoreSectionTitle = null; // the user picked a tab; a refresh must not undo it
                showActiveSection(true);
                Section picked = mSections.get(mActiveSectionId);
                if (picked != null) {
                    picked.topUp.onUserAction(); // NEWTUBE(shorts)
                    picked.topUp.onShown();
                    topUpActiveIfShort();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                mGrid.scrollToPosition(0);
            }
        });
        setupTabSwipe();
    }

    /**
     * Sideways swipe on the feed moves to the neighbouring tab, like YouTube's channel page: the
     * grid follows the finger with the next tab's cards ({@link #mPeek}) beside it and the tab
     * indicator in step. Past a quarter of the width (or on a fling) it settles on the next tab,
     * otherwise it springs back. A claimed swipe cancels the card's press, so it never plays.
     */
    private void setupTabSwipe() {
        mPeekAdapter = new VideoCardAdapter(video -> { });
        GridLayoutManager peekLayout = new GridLayoutManager(this, computeSpanCount());
        peekLayout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mPeekAdapter.isFullSpan(position) ? peekLayout.getSpanCount() : 1;
            }
        });
        mPeek.setLayoutManager(peekLayout);
        mPeek.setAdapter(mPeekAdapter);

        ViewConfiguration config = ViewConfiguration.get(this);
        int slop = config.getScaledTouchSlop();
        int minFling = config.getScaledMinimumFlingVelocity() * 8;
        mGrid.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float mDownX;
            private float mDownY;
            private boolean mSwiping;
            private VelocityTracker mVelocity;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mDownX = e.getX();
                    mDownY = e.getY();
                    mSwiping = false;
                    if (mVelocity != null) {
                        mVelocity.recycle();
                    }
                    mVelocity = VelocityTracker.obtain();
                }
                if (mVelocity != null) {
                    mVelocity.addMovement(e);
                }
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE && mTabs.getTabCount() > 1
                        && rv.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                    float dx = Math.abs(e.getX() - mDownX);
                    if (dx > slop && dx > 2 * Math.abs(e.getY() - mDownY)) {
                        mSwiping = true;
                        if (mSwipeAnim != null) {
                            mSwipeAnim.end(); // a settle still running: finish it first
                        }
                        mDownX = e.getX(); // start the grid from the finger, no jump of one slop
                        rv.getParent().requestDisallowInterceptTouchEvent(true); // no pull-to-refresh
                    }
                }
                return mSwiping;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                mVelocity.addMovement(e);
                float dx = e.getX() - mDownX;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        setSwipeOffset(dx);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mVelocity.computeCurrentVelocity(1000);
                        float vx = e.getActionMasked() == MotionEvent.ACTION_UP ? mVelocity.getXVelocity() : 0;
                        int target = mTabs.getSelectedTabPosition() + (dx < 0 ? 1 : -1);
                        boolean fling = Math.abs(vx) > minFling && (vx < 0) == (dx < 0);
                        boolean commit = dx != 0 && target >= 0 && target < mTabs.getTabCount()
                                && (fling || Math.abs(dx) > rv.getWidth() / 4f);
                        settleSwipe(dx, commit ? Math.copySign(rv.getWidth(), dx) : 0, commit ? target : -1, vx);
                        mSwiping = false;
                        break;
                }
            }
        });
    }

    /** Grid at {@code dx}, the neighbouring tab's cards beside it, the tab indicator in step. */
    private void setSwipeOffset(float dx) {
        int width = mGrid.getWidth();
        int current = mTabs.getSelectedTabPosition();
        int target = current + (dx < 0 ? 1 : -1);
        if (dx == 0 || width == 0 || target < 0 || target >= mTabs.getTabCount()) {
            mGrid.setTranslationX(dx / 4); // rubber band at the ends
            mPeek.setVisibility(View.GONE);
            mTabs.setScrollPosition(current, 0, false);
            return;
        }

        if (mPeekTab != target) {
            TabLayout.Tab tab = mTabs.getTabAt(target);
            Section section = tab != null && tab.getTag() instanceof Integer ? mSections.get((Integer) tab.getTag()) : null;
            mPeek.animate().cancel(); // a hand-over fade from the last swipe
            mPeek.setAlpha(1f);
            mPeekAdapter.submitList(null); // null first: the next list lands at once, no async diff
            mPeekAdapter.submitList(section != null ? new ArrayList<>(section.videos) : new ArrayList<>());
            mPeek.scrollToPosition(0);
            mPeekTab = target;
        }
        mPeek.setVisibility(View.VISIBLE);
        mGrid.setTranslationX(dx);
        mPeek.setTranslationX(dx < 0 ? dx + width : dx - width);
        float fraction = Math.abs(dx) / width;
        mTabs.setScrollPosition(dx < 0 ? current : target, dx < 0 ? fraction : 1 - fraction, false);
    }

    /**
     * Glide from {@code from} to {@code to} at the finger's speed. On a new tab, mPeek (already
     * showing it, in place) covers the grid until the grid has drawn the same cards under it.
     */
    private void settleSwipe(float from, float to, int targetTab, float velocity) {
        float distance = Math.abs(to - from);
        long duration = (long) Math.min(Motion.ENTER_MS, Math.max(120, distance * 1000 / Math.max(Math.abs(velocity), 2000)));
        mSwipeAnim = ValueAnimator.ofFloat(from, to);
        mSwipeAnim.setDuration(duration);
        mSwipeAnim.setInterpolator(Motion.STANDARD_DECELERATE);
        mSwipeAnim.addUpdateListener(animation -> setSwipeOffset((float) animation.getAnimatedValue()));
        mSwipeAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSwipeAnim = null;
                mPeekTab = -1;
                mGrid.setTranslationX(0);
                TabLayout.Tab tab = targetTab >= 0 ? mTabs.getTabAt(targetTab) : null;
                if (tab != null) {
                    mPeekSwapPending = true;
                    tab.select(); // -> showActiveSection, which hands over from mPeek
                } else {
                    mPeek.setVisibility(View.GONE);
                }
            }
        });
        mSwipeAnim.start();
    }

    private void onVideoClicked(Video video) {
        if (mPresenter != null) {
            mPresenter.onVideoItemClicked(video);
        }
    }

    private boolean onVideoLongClicked(Video video) {
        if (mPresenter == null) {
            return false;
        }

        mPresenter.onVideoItemLongClicked(video);
        return true;
    }

    private void setupSwipeRefresh() {
        mSwipe.setColorSchemeColors(getResources().getColor(R.color.mobile_color_on_surface));
        mSwipe.setProgressBackgroundColorSchemeColor(getResources().getColor(R.color.mobile_color_surface));
        mSwipe.setOnRefreshListener(() -> {
            boolean hasContent = hasContent();
            Section active = mSections.get(mActiveSectionId);
            // The refresh rebuilds the tabs; land back on the one being read.
            mRestoreSectionTitle = hasContent && active != null ? active.title : null;

            if (mPresenter == null || !mPresenter.reload(hasContent)) {
                mSwipe.setRefreshing(false);
            }
        });
    }

    /** Failure state's Try again: the same first load, from a blank page. */
    private void retryFirstPage() {
        if (mPresenter != null) {
            mPresenter.reload(false);
        }
    }

    /** Footer's Try again: re-ask for the page that failed (its continuation key is unchanged). */
    private void retryLoadMore() {
        mLoadMoreFailedSectionId = -1;
        syncLoadMoreFooter();

        Section active = mSections.get(mActiveSectionId);
        Video anchor = active != null ? anchorOf(active) : null;
        if (anchor == null || mPresenter == null) {
            return;
        }

        mPagingSectionId = active.id;
        mLastPaginationTriggerCount = mAdapter.getItemCount();
        mPresenter.onScrollEnd(anchor);
    }

    /** The footer belongs to the section whose page failed; other tabs page normally. */
    private void syncLoadMoreFooter() {
        mLoadMoreFooter.setFailed(mLoadMoreFailedSectionId != -1 && mLoadMoreFailedSectionId == mActiveSectionId);
    }

    private boolean hasContent() {
        for (Section section : mSections.values()) {
            if (!section.videos.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private void hideLoadState() {
        mLoadState.hide();
        mGrid.setVisibility(View.VISIBLE);
    }

    private void maybeTriggerPagination(boolean userScroll) {
        Section active = mSections.get(mActiveSectionId);
        Video anchor = active != null ? anchorOf(active) : null;
        if (active == null || active.videos.isEmpty() || anchor == null || mPresenter == null) {
            return;
        }

        // Its next page failed: wait for Try again instead of re-firing on every scroll frame.
        if (mLoadMoreFailedSectionId == active.id) {
            return;
        }

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible == RecyclerView.NO_POSITION || itemCount == 0) {
            return;
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            if (userScroll) {
                active.topUp.onUserAction(); // NEWTUBE(shorts): the user asked for more
            } else if (!active.topUp.takeAutomatic(hasMore(anchor))) {
                return; // a layout pass, not the user: only within the top-up budget
            }
            mLastPaginationTriggerCount = itemCount;
            mPagingSectionId = active.id;
            // Continues the ACTIVE section; the continuation arrives as an ACTION_APPEND
            // VideoGroup with the same id and merges back into it.
            mPresenter.onScrollEnd(anchor);
        }
    }

    /** The video the section's next page continues from: the last of its group, Shorts included. */
    private static Video anchorOf(Section section) {
        List<Video> raw = section.group != null ? section.group.getVideos() : null;
        if (raw != null && !raw.isEmpty()) {
            return raw.get(raw.size() - 1);
        }
        return section.videos.isEmpty() ? null : section.videos.get(section.videos.size() - 1);
    }

    private static boolean hasMore(Video anchor) {
        return anchor != null && anchor.getGroup() != null && anchor.getGroup().getNextPageKey() != null;
    }

    /**
     * NEWTUBE(shorts): with its Shorts dropped the tab on screen may be too short to scroll, and
     * then nothing would ever ask for its next page: fetch it now, within the tab's
     * {@link FilteredPageTopUp} budget - only right after one of its pages landed (not after a
     * cancel or a failure) or when its tab is picked. Returns whether a page was asked for.
     */
    private boolean topUpActiveIfShort() {
        Section active = mSections.get(mActiveSectionId);
        Video anchor = active != null ? anchorOf(active) : null;
        if (active == null || anchor == null || mPresenter == null || mTornDown || isFinishing()
                || isDestroyed() || mLoadMoreFailedSectionId == active.id) {
            return false;
        }
        if (!active.topUp.take(active.videos.size(), hasMore(anchor))) {
            return false;
        }
        mPagingSectionId = active.id;
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("channel-topup section=" + active.id
                + " page=" + active.topUp.pages() + " items=" + active.videos.size());
        mPresenter.onScrollEnd(anchor);
        return true;
    }

    private int computeSpanCount() {
        return com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this);
    }

    /** Sync the TabLayout with the current sections (delivery order), keeping the selection. */
    private void rebuildTabs() {
        mSuppressTabCallback = true;

        // Add missing tabs / fix titles in place; section order only ever grows by append.
        int index = 0;
        for (Section section : mSections.values()) {
            String title = section.title != null && !section.title.isEmpty() ? section.title
                    : getString(ChannelTabShortcuts.isShortcutRow(section.videos) // NEWTUBE(#63)
                            ? R.string.mobile_channel_tab_more : R.string.mobile_channel_tab_videos);

            TabLayout.Tab tab = index < mTabs.getTabCount() ? mTabs.getTabAt(index) : null;
            if (tab == null) {
                tab = mTabs.newTab();
                mTabs.addTab(tab, false);
            }
            if (!(tab.getTag() instanceof Integer) || (Integer) tab.getTag() != section.id) {
                tab.setTag(section.id);
            }
            if (!title.contentEquals(tab.getText() != null ? tab.getText() : "")) {
                tab.setText(title);
            }

            if (section.id == mActiveSectionId && !tab.isSelected()) {
                tab.select();
            }
            index++;
        }
        while (mTabs.getTabCount() > mSections.size()) {
            mTabs.removeTabAt(mTabs.getTabCount() - 1);
        }

        // Tab bar only earns its space with 2+ sections; a single section shows as a plain feed.
        mTabs.setVisibility(mSections.size() > 1 ? View.VISIBLE : View.GONE);

        mSuppressTabCallback = false;
    }

    /** Push the active section's videos into the grid. */
    private void showActiveSection(boolean scrollToTop) {
        Section active = mSections.get(mActiveSectionId);
        mLastPaginationTriggerCount = -1;
        syncLoadMoreFooter();
        mAdapter.submitList(active != null ? new ArrayList<>(active.videos) : new ArrayList<>(), () -> {
            if (mPeekSwapPending) {
                mPeekSwapPending = false;
                SkeletonReveal.fadeOverCards(mPeek, mGrid, () -> true, () -> {
                    mPeek.setVisibility(View.GONE);
                    mPeek.setAlpha(1f);
                });
            }
        });
        if (scrollToTop) {
            mGrid.scrollToPosition(0);
        }
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();

        // REORDER_TO_FRONT may reuse an existing channel Activity, bypassing onCreate entirely.
        // Complete a watch-page channel handoff here as well so that path still becomes mini mode.
        if (MiniPlayerBridge.completePendingNavigation()) {
            mAnimateMiniFromPlayer = true;
            overridePendingTransition(0, 0);
        }

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }
        // Last-resumed host wins: a video opened from this channel minimizes back onto it.
        MiniPlayerBridge.registerMiniHost(this);
        if (mMiniPlayer != null) {
            mMiniPlayer.sync(mAnimateMiniFromPlayer);
            mAnimateMiniFromPlayer = false;
        }
    }

    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        return mMiniPlayer != null && mMiniPlayer.prepareForHandoff(onDrawn);
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return ChannelView.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        // Overlay card (mobile_mini_player_overlay.xml) sits flush at the content bottom.
        return 0;
    }

    @Override
    protected void onPause() {
        // NEWTUBE(motion): a docked card stays up frozen until the player covers it.
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostPause();
        }
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostStop();
        }
    }

    @Override
    protected void onDestroy() {
        MiniPlayerBridge.unregisterMiniHost(this);
        mTornDown = true;

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    private void handleBack() {
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }

        finish();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mLayoutManager != null) {
            int spans = com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(newConfig);
            mLayoutManager.setSpanCount(spans);
            ((GridLayoutManager) mPeek.getLayoutManager()).setSpanCount(spans);
        }
    }

    // ---------------------------------------------------------------------------------
    // ChannelView
    // ---------------------------------------------------------------------------------

    @Override
    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            int id = group.getId();
            if (id == SORTED_GROUP_ID && group.getAction() != VideoGroup.ACTION_REPLACE
                    && mSections.containsKey(mSortTargetId)) {
                id = mSortTargetId; // VOIDXDEV(channel sort): the sorted list's next pages
            }
            Section section = mSections.get(id);
            boolean isNewSection = section == null;
            List<Video> shown = ShortsFilter.withoutShorts(group.getVideos()); // NEWTUBE(shorts)
            if (id == SORTED_GROUP_ID && group.getAction() == VideoGroup.ACTION_REPLACE) {
                Section target = mSections.get(mSortTargetId);
                if (target != null && shown != null) {
                    target.videos.clear();
                    target.videos.addAll(shown);
                    target.group = group; // further pages continue the sorted list
                    mActiveSectionId = target.id;
                    rebuildTabs();
                    showActiveSection(true);
                }
                return;
            }
            boolean content = group.getAction() != VideoGroup.ACTION_REMOVE && group.getAction() != VideoGroup.ACTION_SYNC;
            int shortsDropped = (group.getVideos() != null ? group.getVideos().size() : 0)
                    - (shown != null ? shown.size() : 0);
            mShortsEmptied |= content && shortsDropped > 0;
            // ...and no Shorts tab: the channel's Shorts section (emptied here, or already by the
            // service under a stored "Hide shorts from a channel") never becomes a tab. Any other
            // section keeps its tab while it has more to load, even if this page was all Shorts
            // (its last Short is the next page's anchor); one with nothing now and nothing more to
            // load, or nothing to continue from, is left out.
            if (isNewSection && content && (shown == null || shown.isEmpty())
                    && (ShortsFilter.isShortsSection(group.getTitle(), getString(R.string.header_shorts), group.getVideos())
                        || group.getNextPageKey() == null || group.isEmpty())) {
                return;
            }

            if (content) {
                maybeNameFromVideos(group.getVideos());
                mGrid.post(this::loadHeader); // a link-opened channel's id comes with its videos
            }

            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    section = new Section(id, group.getTitle());
                    section.videos.addAll(shown);
                    mSections.put(id, section);
                    break;
                case VideoGroup.ACTION_REMOVE:
                    if (section != null) {
                        section.videos.removeAll(group.getVideos());
                    }
                    break;
                case VideoGroup.ACTION_SYNC:
                    if (section != null) {
                        syncVideos(section, group.getVideos());
                    }
                    // NEWTUBE(#45): a SYNC item was mutated in place (Original titles, DeArrow),
                    // so submitList() sees the same objects and never rebinds - rebind it here.
                    mAdapter.refreshItems(group.getVideos());
                    break;
                case VideoGroup.ACTION_PREPEND:
                    if (section == null) {
                        section = new Section(id, group.getTitle());
                        mSections.put(id, section);
                    }
                    section.videos.addAll(0, shown);
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    if (section == null) {
                        section = new Section(id, group.getTitle());
                        mSections.put(id, section);
                    } else if ((section.title == null || section.title.isEmpty()) && group.getTitle() != null) {
                        section.title = group.getTitle();
                    }
                    appendNew(section, shown);
                    break;
            }

            if (content && section != null) {
                section.group = group; // NEWTUBE(shorts): the next page's anchor
                section.topUp.onPageLanded(shortsDropped);
            }

            // A SYNC/REMOVE for an unknown section (e.g. the just-watched video's position
            // sync arriving on resume before any section loaded) must not touch the map —
            // guard the empty case or iterator().next() throws.
            if (mSections.isEmpty()) {
                return;
            }

            // Pull-to-refresh rebuilt the sections: the tab that was being read wins again.
            if (isNewSection && mRestoreSectionTitle != null && mSections.containsKey(id)
                    && mRestoreSectionTitle.equals(mSections.get(id).title)) {
                mActiveSectionId = id;
                mRestoreSectionTitle = null;
            }

            // First section to arrive becomes the visible one.
            if (mActiveSectionId == -1 || !mSections.containsKey(mActiveSectionId)) {
                mActiveSectionId = mSections.keySet().iterator().next();
            }

            if (hasContent()) {
                hideLoadState();
            }

            rebuildTabs();

            if (id == mActiveSectionId) {
                showActiveSection(false);
            }
        });
    }

    private void appendNew(Section section, List<Video> videos) {
        for (Video video : videos) {
            if (!section.videos.contains(video)) {
                section.videos.add(video);
            }
        }
    }

    private void syncVideos(Section section, List<Video> videos) {
        for (Video video : videos) {
            int idx = section.videos.indexOf(video);
            if (idx >= 0) {
                section.videos.set(idx, video);
            }
        }
    }

    @Override
    public void setPosition(int index) {
        runOnUiThread(() -> {
            if (index >= 0 && index < mTabs.getTabCount()) {
                TabLayout.Tab tab = mTabs.getTabAt(index);
                if (tab != null) {
                    tab.select(); // routes through the listener -> shows that section
                }
            }
        });
    }

    @Override
    public void clear() {
        runOnUiThread(() -> {
            mSections.clear();
            mShortsEmptied = false;
            mActiveSectionId = -1;
            mLastPaginationTriggerCount = -1;
            mLoadMoreFailedSectionId = -1;
            mPagingSectionId = -1;
            if (!mSwipe.isRefreshing()) {
                mRestoreSectionTitle = null; // only a refresh's own clear carries the tab over
                applyChannelTitle(); // NEWTUBE: another channel opened into this page
                mHeaderChannelId = null;
                ((com.google.android.material.appbar.AppBarLayout) findViewById(R.id.mobile_channel_appbar))
                        .setExpanded(true, false);
                loadHeader();
            }
            mSuppressTabCallback = true;
            mTabs.removeAllTabs();
            mTabs.setVisibility(View.GONE);
            mSuppressTabCallback = false;
            syncLoadMoreFooter();
            mAdapter.submitList(new ArrayList<>());
            hideLoadState();
        });
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> {
            if (!show && topUpActiveIfShort()) {
                return; // NEWTUBE(shorts): another page is on its way - still loading
            }
            // Pull-to-refresh draws its own spinner; don't stack the centered one over it.
            mProgressBar.setVisibility(show && !mSwipe.isRefreshing() ? View.VISIBLE : View.GONE);
            if (!show) {
                mSwipe.setRefreshing(false);
                showEmptyIfShortsLeftNothing();
            }
        });
    }

    /**
     * NEWTUBE(shorts): the page loaded, but the Shorts filter left no card in any tab (a channel of
     * only Shorts, or a Shorts list reached some other way) - the empty state, never a blank page
     * (#63). ShortsFilter drops the tiles that lead to a Shorts list; this is the safety net.
     */
    private void showEmptyIfShortsLeftNothing() {
        if (mShortsEmptied && !hasContent() && !mLoadState.isShowing()) {
            mGrid.setVisibility(View.GONE);
            mLoadState.show(LoadFailure.EMPTY);
        }
    }

    /**
     * NEWTUBE(page-load-errors): the first load put nothing on screen. Rows still on screen (a
     * pull-to-refresh that failed) stay - stale rows beat a full-page error, as on Home - with a
     * snackbar saying the refresh failed. Otherwise the grid gives way to the Home-style empty
     * state with Try again.
     */
    @Override
    public void showLoadFailure(int state) {
        runOnUiThread(() -> {
            mSwipe.setRefreshing(false);
            mProgressBar.setVisibility(View.GONE);
            mRestoreSectionTitle = null;

            if (hasContent()) {
                if (state != LoadFailure.EMPTY) {
                    Snackbar.make(findViewById(android.R.id.content),
                            state == LoadFailure.NO_CONNECTION
                                    ? R.string.mobile_empty_no_connection : R.string.mobile_refresh_error,
                            Snackbar.LENGTH_SHORT).show();
                }
                return;
            }

            mGrid.setVisibility(View.GONE);
            mLoadState.show(state);
        });
    }

    /** NEWTUBE(page-load-errors): a next page failed - footer row with Try again, rows stay. */
    @Override
    public void showLoadMoreFailure() {
        runOnUiThread(() -> {
            mLoadMoreFailedSectionId = mPagingSectionId != -1 ? mPagingSectionId : mActiveSectionId;
            syncLoadMoreFooter();
        });
    }
}

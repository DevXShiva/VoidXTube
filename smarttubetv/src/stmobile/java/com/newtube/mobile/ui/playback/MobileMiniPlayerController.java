package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.FrameGate;
import com.newtube.mobile.ui.common.Motion;

/**
 * Renders an active {@link MiniPlayerBridge} session inside a screen's floating mini card.
 *
 * <p>The playback Activity still owns both ExoPlayer and its session-long SurfaceTexture. This
 * controller only adopts that texture into a temporary TextureView while its host Activity is in
 * front, then returns it before the host pauses. Keeping this small host adapter outside the
 * channel Activity makes the surface ownership rules identical to Home's proven mini-player.</p>
 */
public final class MobileMiniPlayerController {
    private static final long TICK_MS = 500;
    private static final long ENTRY_DURATION_MS = 280;
    /** NEWTUBE(motion): the longest the expanding player waits for this card to fold. */
    private static final long FOLD_WAIT_MS = 50;

    private final Activity mActivity;
    private final View mBar;
    /** NEWTUBE(haptics): stopped before this controller moves the card itself (MiniCardSwipe#stop). */
    private MiniCardSwipe mSwipe;
    private final FrameLayout mFrame;
    private final ImageView mFreeze;
    private final ImageButton mPlayPause;
    private final ImageButton mClose;
    private final ProgressBar mProgress;
    private final Runnable mTick = this::onTick;
    /**
     * SCREEN-ON (#50): the card's screen-on hold follows the player's own events, not the 500 ms
     * tick. The tick stops when the host pauses, but the card stays up (frozen, audio playing) while
     * a translucent window such as the card menu sits on top and the host never reaches onStop - a
     * pause or end there must still let the screen go, and playing there must still hold it.
     */
    private final Player.Listener mScreenOnListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                setKeepScreenOn(isActivelyPlaying(player));
            }
        }
    };
    private Player mScreenOnPlayer;

    private TextureView mTexture;
    private boolean mEntryAnimating;
    private boolean mTextureHasFrame;
    /** NEWTUBE(motion): pre-drawn for a minimize; the buttons fade in once the host is in front. */
    private boolean mButtonsFadePending;
    private final MiniPlayerBridge.CardFold mCardFold = this::foldForPlayer;

    public MobileMiniPlayerController(Activity activity) {
        mActivity = activity;
        mBar = activity.findViewById(R.id.mobile_mini_player);
        mFrame = activity.findViewById(R.id.mobile_mini_player_frame);
        mFreeze = activity.findViewById(R.id.mobile_mini_freeze);
        mPlayPause = activity.findViewById(R.id.mobile_mini_play_pause);
        mClose = activity.findViewById(R.id.mobile_mini_close);
        mProgress = activity.findViewById(R.id.mobile_mini_progress);

        View.OnClickListener expand = v -> {
            if (mClosing || !MiniPlayerBridge.isActive()) {
                return; // a closed card leaving
            }
            publishBounds();
            MiniPlayerBridge.expand(mActivity);
        };
        mBar.setOnClickListener(expand);
        mFrame.setOnClickListener(expand);

        mPlayPause.setOnClickListener(v -> {
            ExoPlayer player = MiniPlayerBridge.getPlayer();
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
                updatePlayPause(player);
            }
        });

        mClose.setOnClickListener(v -> close());
        MiniPip.wireAudioButton(activity.findViewById(R.id.mobile_mini_audio));
        // NEWTUBE(motion): or swipe the card away sideways.
        mSwipe = MiniCardSwipe.attach(mBar, new MiniCardSwipe.Callback() {
            @Override
            public boolean canSwipe() {
                return !mClosing && !mEntryAnimating && MiniPlayerBridge.isActive();
            }

            @Override
            public void onSwipedAway(float toTranslationX, long durationMs) {
                close(toTranslationX, durationMs);
            }
        }, mFrame, mBar);
    }

    /**
     * NEWTUBE(motion): X - the sound stops at once and the card, frozen on its last frame, shrinks
     * and fades away instead of vanishing in one frame. The session closes once the card is gone
     * (see MobileBrowseActivity#closeMiniPlayer for why not first).
     */
    private boolean mClosing;
    private float mVolumeBeforeClose = 1f;

    private void close() {
        close(null, 0);
    }

    /** {@code flyToX}: swiped away - the card keeps going off that side instead of shrinking. */
    private void close(@androidx.annotation.Nullable Float flyToX, long flyMs) {
        if (mClosing) {
            return;
        }
        mClosing = true;
        if (MiniPlayerBridge.isActive()) {
            mVolumeBeforeClose = MiniPlayerBridge.getVolume();
            MiniPlayerBridge.setVolume(0f); // released with the session right after
        }
        MiniPlayerBridge.setClosing(this::abortClose);
        MiniPlayerBridge.clearPendingCardFold(mCardFold);
        detachTexture();
        mSwipe.stop();
        mBar.animate().cancel();
        android.view.ViewPropertyAnimator exit;
        if (flyToX != null) {
            exit = mBar.animate().translationX(flyToX).alpha(0f).setDuration(flyMs)
                    .setInterpolator(new android.view.animation.LinearInterpolator());
        } else {
            resetTransform();
            exit = mBar.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setDuration(Motion.EXIT_MS).setInterpolator(Motion.EMPHASIZED_ACCELERATE);
        }
        exit.withLayer().withEndAction(() -> {
                    if (mClosing) {
                        finishClose();
                    } else {
                        fold(); // aborted: the player took the session back; only the card goes
                    }
                }).start();
    }

    /** The end of close(); also run at once by anything that would re-show the card. */
    private void finishClose() {
        if (!mClosing) {
            return;
        }
        mClosing = false;
        MiniPlayerBridge.setClosing(null);
        fold();
        MiniPlayerBridge.close();
    }

    /** The session was handed a new video while the card left: it lives on; the card still goes. */
    private void abortClose() {
        if (!mClosing) {
            return;
        }
        mClosing = false;
        MiniPlayerBridge.setVolume(mVolumeBeforeClose);
    }

    /**
     * NEWTUBE(motion): the host is pausing. Give the texture back (the player may be taking it) but
     * keep a docked card up, frozen on its last frame, until the player covers it - see
     * MiniPlayerBridge.setPendingCardFold. Hiding it here was a blink before every expand.
     */
    public void onHostPause() {
        finishClose();
        detachTexture();
        if (mBar.getVisibility() == View.VISIBLE && MiniPlayerBridge.isActive()) {
            MiniPlayerBridge.setPendingCardFold(mCardFold);
        } else {
            hide();
        }
    }

    /** The host is covered: a card still waiting to be folded can go now. */
    public void onHostStop() {
        hide();
    }

    /** Show the live mini session. Optionally animate it down from the watch-page video box. */
    public void sync(boolean animateFromPlayer) {
        finishClose(); // a card closing when this runs is closed, not brought back
        MiniPlayerBridge.clearPendingCardFold(mCardFold);
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            hide();
            return;
        }
        mSwipe.stop();
        mBar.animate().cancel();
        mBar.setAlpha(1f);
        mBar.setScaleX(1f);
        mBar.setScaleY(1f);
        mBar.setTranslationX(0f); // a card swiped away (MiniCardSwipe)

        mEntryAnimating = animateFromPlayer;
        mTextureHasFrame = false;

        Bitmap entryStill = MiniPlayerBridge.takeMiniEntryStill();
        if (entryStill != null) {
            mFreeze.setImageBitmap(entryStill);
            mFreeze.setVisibility(View.VISIBLE);
        }

        MiniPlayerBridge.fitToVideo(mFrame); // NEWTUBE(issue #9): letterbox, never stretch
        attachTexture();
        mBar.setVisibility(View.VISIBLE);
        updatePlayPause(player);

        Utils.removeCallbacks(mTick);
        Utils.postDelayed(mTick, TICK_MS);

        if (mButtonsFadePending && !animateFromPlayer) {
            mButtonsFadePending = false;
            fadeInControls();
        }

        mBar.post(() -> {
            publishBounds();
            if (animateFromPlayer && mBar.getVisibility() == View.VISIBLE) {
                animateFromWatchPage();
            }
        });
    }

    /**
     * The translucent playback Activity is still on top when this runs. Make the endpoint card
     * visible now so this host can draw it underneath before Android reorders it to the front.
     * Mirrors Home's proven pre-render gate (see MobileBrowseActivity#prepareMiniPlayerForHandoff).
     */
    public boolean prepareForHandoff(Runnable onDrawn) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) {
            return false;
        }
        sync(false);
        if (mBar.getVisibility() != View.VISIBLE) {
            return false;
        }
        // NEWTUBE(motion): the landing video is a bare picture; the buttons fade in once this host
        // is in front (its onResume sync) rather than appearing with the card in one frame.
        setControlsAlpha(0f);
        mButtonsFadePending = true;

        // A pair of frame callbacks only proves that time passed; it does not prove this paused
        // window submitted a buffer. Gate the reorder on an actual draw containing the card, then
        // wait one compositor frame before removing the playback window above it. The fallback
        // avoids stranding the player at the endpoint if an OEM suppresses draws on paused windows.
        final ViewTreeObserver observer = mBar.getViewTreeObserver();
        class DrawGate implements ViewTreeObserver.OnDrawListener, Runnable {
            private boolean mDelivered;

            @Override
            public void onDraw() {
                mBar.post(this);
            }

            @Override
            public void run() {
                if (mDelivered) {
                    return;
                }
                mDelivered = true;
                if (observer.isAlive()) {
                    observer.removeOnDrawListener(this);
                }
                mBar.postOnAnimation(onDrawn);
            }
        }
        DrawGate gate = new DrawGate();
        observer.addOnDrawListener(gate);
        mBar.postDelayed(gate, 100);
        mBar.invalidate();
        return true;
    }

    /**
     * Freeze the last mini frame, release this screen's TextureView, and hide the card. The player
     * Activity can then reclaim the same session texture during Back/expand without a black beat.
     */
    public void hide() {
        finishClose();
        MiniPlayerBridge.clearPendingCardFold(mCardFold);
        detachTexture();
        fold();
    }

    /** Freeze the last frame and give the session texture back; the card itself stays as it is. */
    private void detachTexture() {
        Utils.removeCallbacks(mTick);
        mTextureHasFrame = false;
        publishBounds();

        if (mTexture != null && mTexture.getParent() != null) {
            if (mTexture.isAvailable() && MiniPlayerBridge.isActive()) {
                Bitmap still = mTexture.getBitmap();
                if (still != null) {
                    mFreeze.setImageBitmap(still);
                    mFreeze.setVisibility(View.VISIBLE);
                    MiniPlayerBridge.setHandoffStill(still);
                }
            }
            // No throwaway-texture swap here: TextureView#setSurfaceTexture releases the
            // texture the view currently holds, which would destroy the session texture
            // (see MobilePlaybackActivity#detachVideoTexture).
            mFrame.removeView(mTexture);
            mTexture = null;
        }
    }

    private void foldForPlayer(@androidx.annotation.Nullable Runnable onFolded) {
        fold();
        if (onFolded != null) {
            FrameGate.afterNextFrame(mBar.getRootView(), FOLD_WAIT_MS, onFolded);
        }
    }

    private void fold() {
        mSwipe.stop();
        mBar.animate().cancel();
        mEntryAnimating = false;
        mButtonsFadePending = false;
        resetTransform();
        mBar.setAlpha(1f);
        mBar.setVisibility(View.GONE);
        unwatchScreenOn();
        setKeepScreenOn(false); // no card, no video on this screen
    }

    private void fadeInControls() {
        for (View view : new View[] {mPlayPause, mClose, mProgress}) {
            view.animate().alpha(1f).setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.STANDARD).start();
        }
    }

    private void attachTexture() {
        if (mTexture == null) {
            final TextureView texture = new TextureView(mActivity);
            texture.setOpaque(false); // NEWTUBE(texture-opaque): see MobilePlaybackActivity
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture created, int width, int height) {
                    SurfaceTexture session = MiniPlayerBridge.getSessionTexture();
                    if (session != null && created != session) {
                        texture.setSurfaceTexture(session);
                        created.release();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    // The playback Activity owns the session texture. Release only a temporary
                    // TextureView-created surface that was never replaced by that session.
                    return texture != MiniPlayerBridge.getSessionTexture();
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                    mTextureHasFrame = true;
                    hideFreezeWhenReady();
                }
            });
            mTexture = texture;
        }

        if (mTexture.getParent() == null) {
            mFrame.addView(mTexture, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** Start as the full-width 16:9 watch video, then land on the channel's floating card. */
    private void animateFromWatchPage() {
        int width = mBar.getWidth();
        int height = mBar.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        mSwipe.stop();

        int[] cardLocation = new int[2];
        int[] contentLocation = new int[2];
        mBar.getLocationOnScreen(cardLocation);
        View content = mActivity.findViewById(android.R.id.content);
        content.getLocationOnScreen(contentLocation);

        float sourceWidth = mActivity.getResources().getDisplayMetrics().widthPixels;
        float sourceHeight = sourceWidth * 9f / 16f;

        mBar.setPivotX(0f);
        mBar.setPivotY(0f);
        mBar.setScaleX(sourceWidth / width);
        mBar.setScaleY(sourceHeight / height);
        mBar.setTranslationX(contentLocation[0] - cardLocation[0]);
        mBar.setTranslationY(contentLocation[1] - cardLocation[1]);

        setControlsAlpha(0f);
        mBar.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(ENTRY_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    resetTransform();
                    mEntryAnimating = false;
                    hideFreezeWhenReady();
                    mPlayPause.animate().alpha(1f).setDuration(90).start();
                    mClose.animate().alpha(1f).setDuration(90).start();
                    mProgress.animate().alpha(1f).setDuration(90).start();
                    publishBounds();
                })
                .start();
    }

    private void hideFreezeWhenReady() {
        if (!mEntryAnimating && mTextureHasFrame && mFreeze.getVisibility() == View.VISIBLE) {
            mFreeze.setVisibility(View.GONE);
        }
    }

    private void resetTransform() {
        mBar.setPivotX(mBar.getWidth() / 2f);
        mBar.setPivotY(mBar.getHeight() / 2f);
        mBar.setScaleX(1f);
        mBar.setScaleY(1f);
        mBar.setTranslationX(0f);
        mBar.setTranslationY(0f);
        setControlsAlpha(1f);
    }

    private void setControlsAlpha(float alpha) {
        mPlayPause.animate().cancel();
        mClose.animate().cancel();
        mProgress.animate().cancel();
        mPlayPause.setAlpha(alpha);
        mClose.setAlpha(alpha);
        mProgress.setAlpha(alpha);
    }

    private void publishBounds() {
        if (mBar.getVisibility() != View.VISIBLE || mBar.getWidth() <= 0 || mBar.getHeight() <= 0) {
            return;
        }
        int[] location = new int[2];
        mBar.getLocationOnScreen(location);
        MiniPlayerBridge.setMiniBounds(new Rect(location[0], location[1],
                location[0] + mBar.getWidth(), location[1] + mBar.getHeight()));
    }

    private void onTick() {
        if (mBar.getVisibility() != View.VISIBLE) {
            return;
        }
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            hide();
            return;
        }
        long duration = player.getDuration();
        if (duration > 0) {
            mProgress.setProgress((int) (player.getCurrentPosition() * 1000 / duration));
        }
        updatePlayPause(player);
        MiniPlayerBridge.fitToVideo(mFrame); // an autoplayed next video may have another shape
        Utils.postDelayed(mTick, TICK_MS);
    }

    private void updatePlayPause(ExoPlayer player) {
        MiniPip.syncAudioButton(mActivity.findViewById(R.id.mobile_mini_audio));
        boolean playing = player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE;
        mPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        // SCREEN-ON (#50): the docked card is a visible video, so this host window holds the screen
        // while it plays (the stopped player Activity no longer does), and lets go when it pauses.
        watchScreenOn(player);
        setKeepScreenOn(isActivelyPlaying(player));
    }

    private static boolean isActivelyPlaying(Player player) {
        return player.getPlayWhenReady()
                && (player.getPlaybackState() == Player.STATE_READY
                    || player.getPlaybackState() == Player.STATE_BUFFERING);
    }

    /** Listen while the card is up (see mScreenOnListener); fold() stops it. */
    private void watchScreenOn(Player player) {
        if (mScreenOnPlayer == player) {
            return;
        }
        unwatchScreenOn();
        player.addListener(mScreenOnListener);
        mScreenOnPlayer = player;
    }

    private void unwatchScreenOn() {
        if (mScreenOnPlayer != null) {
            mScreenOnPlayer.removeListener(mScreenOnListener);
            mScreenOnPlayer = null;
        }
    }

    private void setKeepScreenOn(boolean on) {
        android.view.Window window = mActivity.getWindow();
        if (window == null) {
            return;
        }
        if (on) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}

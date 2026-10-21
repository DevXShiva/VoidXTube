package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.material.card.MaterialCardView;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Arrays;

/**
 * VOIDXDEV(mini-pip): leaving the app (Home, Recents) while a screen's mini card plays floats that
 * card in a PiP window, like leaving the full player does. The player Activity sits stopped behind
 * the card's screen and cannot enter PiP itself, so the HOST does: its window becomes the video
 * (the card blown up to fill it, its buttons hidden) until the window expands back or closes.
 * The play/pause action is the player's own broadcast, which its receiver already handles.
 *
 * <p>Host wiring: {@link #update} whenever the card shows, hides or changes play state;
 * {@link #onUserLeaveHint}, {@link #onModeChanged}, {@link #onResume} from the same callbacks;
 * {@link #isPinned} keeps onPause from detaching the card; {@link #onStop} from onStop.</p>
 */
public final class MiniPip {
    private final Activity mHost;
    private final View mCard;
    private final View[] mChrome;
    private final Runnable mOnClosed;
    private boolean mEntering;
    private boolean mPinned;
    private boolean mDismissPending;
    private boolean mAudioExit;
    private BroadcastReceiver mAudioReceiver;
    private String mShown;
    // The card's own geometry, put back when the window expands.
    private ViewGroup.MarginLayoutParams mSavedLp;
    private float mSavedZ;
    private float mSavedRadius;
    private int mSavedStroke;

    /** {@code onClosed}: the PiP window was closed - stop the video with it. */
    public MiniPip(Activity host, View card, Runnable onClosed, View... chrome) {
        mHost = host;
        mCard = card;
        mOnClosed = onClosed;
        mChrome = chrome;
    }

    private static boolean supported(Activity host) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Helpers.isPictureInPictureSupported(host);
    }

    /** A mini session is showing and playing: what Home floats. */
    private boolean canFloat() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        return player != null && player.getPlayWhenReady() && mCard.getVisibility() == View.VISIBLE;
    }

    /** Re-arm (or disarm) the window's auto-enter and refresh the PiP window's action. */
    public void update() {
        if (!supported(mHost)) {
            return;
        }
        boolean playing = canFloat();
        Rational aspect = aspect();
        String shown = playing + "|" + aspect + "|" + mPinned;
        if (shown.equals(mShown)) {
            return;
        }
        mShown = shown;
        try {
            mHost.setPictureInPictureParams(params(playing && !mPinned, aspect));
        } catch (Exception e) {
            NetPath.log("mini-pip params-failed " + e.getClass().getSimpleName());
        }
    }

    /** Before Android 12 Home arrives here; from 12 on the auto-enter flag does it (update). */
    public void onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !supported(mHost) || mPinned || !canFloat()) {
            return;
        }
        setChromeVisible(false); // the shrink animation captures the live window: no buttons in it
        try {
            mEntering = mHost.enterPictureInPictureMode(params(false, aspect()));
        } catch (Exception e) {
            mEntering = false;
        }
        NetPath.log("mini-pip enter-request accepted=" + (mEntering ? "y" : "n"));
        if (!mEntering) {
            setChromeVisible(true);
        }
    }

    /**
     * Android 15+: the PiP transition is starting (auto-enter from Home included). Hide the card's
     * buttons now so the shrink shows only video, not the chrome popping out at its end.
     */
    public void onTransitioningToPip(boolean transitioning) {
        if (transitioning) {
            setChromeVisible(false);
        } else if (!isPinned()) { // cancelled; an entry that landed keeps them hidden
            setChromeVisible(true);
        }
    }

    /** True from the entry until the window expands or closes: the card must stay attached. */
    public boolean isPinned() {
        return mPinned || mEntering || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && mHost.isInPictureInPictureMode());
    }

    public void onModeChanged(boolean inPip) {
        mEntering = false;
        if (inPip == mPinned) {
            return;
        }
        mPinned = inPip;
        NetPath.log("mini-pip mode inPip=" + (inPip ? "y" : "n"));
        if (inPip) {
            mAudioExit = false;
            watchAudio(true);
            fillWindow();
        } else {
            watchAudio(false);
            restoreCard();
            // Closed, the screen stops - before this callback (Android 16) or right after it;
            // expanded, it resumes (onResume clears the pending close).
            if (mHost instanceof LifecycleOwner && !((LifecycleOwner) mHost).getLifecycle()
                    .getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                close();
            } else {
                mDismissPending = true;
            }
        }
        mShown = null;
        update();
    }

    public void onResume() {
        mDismissPending = false;
    }

    /** Stopped right after leaving PiP without resuming: the window was closed. */
    public void onStop() {
        if (mDismissPending) {
            close();
        }
    }

    private void close() {
        mDismissPending = false;
        if (mAudioExit) { // headphones: the window went, the sound plays on
            mAudioExit = false;
            NetPath.log("mini-pip closed to audio");
            return;
        }
        NetPath.log("mini-pip closed");
        mOnClosed.run();
    }

    private void fillWindow() {
        mSavedLp = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) mCard.getLayoutParams());
        mSavedZ = mCard.getTranslationZ();
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mCard.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.setMargins(0, 0, 0, 0);
        mCard.setLayoutParams(lp);
        mCard.setTranslationX(0f);
        mCard.setTranslationY(0f);
        mCard.setTranslationZ(1000f); // over the screen's other floating layers
        mCard.animate().cancel();
        mCard.setScaleX(1f);
        mCard.setScaleY(1f);
        if (mCard instanceof MaterialCardView) { // square, borderless: the PiP window rounds itself
            MaterialCardView card = (MaterialCardView) mCard;
            mSavedRadius = card.getRadius();
            mSavedStroke = card.getStrokeWidth();
            card.setRadius(0f);
            card.setStrokeWidth(0);
        }
        setChromeVisible(false);
    }

    private void setChromeVisible(boolean visible) {
        for (View view : mChrome) {
            if (view != null) {
                view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    private void restoreCard() {
        if (mSavedLp != null) {
            int fullW = mCard.getWidth();
            int fullH = mCard.getHeight();
            if (mCard instanceof MaterialCardView) {
                ((MaterialCardView) mCard).setRadius(mSavedRadius);
                ((MaterialCardView) mCard).setStrokeWidth(mSavedStroke);
            }
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mCard.getLayoutParams();
            lp.width = mSavedLp.width;
            lp.height = mSavedLp.height;
            lp.setMargins(mSavedLp.leftMargin, mSavedLp.topMargin, mSavedLp.rightMargin, mSavedLp.bottomMargin);
            mCard.setLayoutParams(lp);
            mCard.setTranslationZ(mSavedZ);
            mSavedLp = null;
            shrinkBack(fullW, fullH);
        }
        setChromeVisible(true);
    }

    /**
     * The expanded window shows the card filling the screen; settle it into its corner instead of
     * snapping there: after the relayout, start at full size and ease down.
     */
    private void shrinkBack(int fullW, int fullH) {
        if (fullW <= 0 || fullH <= 0) {
            return;
        }
        mCard.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mCard.getViewTreeObserver().removeOnPreDrawListener(this);
                int w = mCard.getWidth();
                int h = mCard.getHeight();
                if (w <= 0 || h <= 0 || mPinned) {
                    return true;
                }
                mCard.setPivotX(0f);
                mCard.setPivotY(0f);
                mCard.setScaleX((float) fullW / w);
                mCard.setScaleY((float) fullH / h);
                mCard.setTranslationX(-mCard.getLeft());
                mCard.setTranslationY(-mCard.getTop());
                mCard.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                        .setDuration(320).setInterpolator(new FastOutSlowInInterpolator())
                        .withEndAction(() -> {
                            mCard.setPivotX(w / 2f);
                            mCard.setPivotY(h / 2f);
                        })
                        .start();
                return true;
            }
        });
    }

    private static Rational aspect() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        VideoSize size = player != null ? player.getVideoSize() : VideoSize.UNKNOWN;
        if (size.width <= 0 || size.height <= 0) {
            return new Rational(16, 9);
        }
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        if (ratio < 0.5f) {
            return new Rational(1, 2);
        }
        if (ratio > 2.3f) {
            return new Rational(23, 10);
        }
        return new Rational(Math.round(ratio * 1000), 1000);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private PictureInPictureParams params(boolean autoEnter, Rational aspect) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .setActions(Arrays.asList(audioOnly(), playPause()));
        // The window grows out of the video inside the card (the frame is fitted to the video's
        // shape): a hint whose aspect matches the window's gives the clean shrink, not a crossfade.
        View video = mCard.findViewById(R.id.mobile_mini_player_frame);
        Rect hint = new Rect();
        if (!mPinned && (video != null ? video : mCard).getGlobalVisibleRect(hint) && !hint.isEmpty()) {
            builder.setSourceRectHint(hint);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true); // video: resize in place, no crossfade
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
        }
        return builder.build();
    }

    /** The card's headphones: toggles the player's audio-only; lit while it is on. */
    public static void wireAudioButton(View button) {
        if (button == null) {
            return;
        }
        syncAudioButton(button);
        button.setOnClickListener(v -> {
            boolean on = !MobilePlaybackActivity.sAudioOnly;
            MobilePlaybackActivity.sAudioOnly = on; // the receiver confirms it a moment later
            syncAudioButton(v);
            v.getContext().sendBroadcast(new Intent(MobilePlaybackActivity.ACTION_PIP_AUDIO)
                    .setPackage(v.getContext().getPackageName())
                    .putExtra(MobilePlaybackActivity.EXTRA_AUDIO_TOGGLE, true));
        });
    }

    public static void syncAudioButton(View button) {
        if (button != null) {
            button.setAlpha(MobilePlaybackActivity.sAudioOnly ? 1f : 0.55f);
            button.setSelected(MobilePlaybackActivity.sAudioOnly);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private RemoteAction playPause() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        boolean playing = player != null && player.getPlayWhenReady();
        String label = mHost.getString(playing ? R.string.mobile_player_pause : R.string.mobile_player_play);
        PendingIntent toggle = PendingIntent.getBroadcast(mHost, 0,
                new Intent(MobilePlaybackActivity.ACTION_PIP_TOGGLE).setPackage(mHost.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(mHost,
                playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play), label, label, toggle);
    }

    /** Headphones: the player turns audio-only (its own receiver), this window goes behind Home. */
    @RequiresApi(Build.VERSION_CODES.O)
    private RemoteAction audioOnly() {
        String label = mHost.getString(R.string.mobile_player_audio_only);
        PendingIntent intent = PendingIntent.getBroadcast(mHost, 1,
                new Intent(MobilePlaybackActivity.ACTION_PIP_AUDIO).setPackage(mHost.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(mHost, R.drawable.ic_player_headphones), label, label, intent);
    }

    private void watchAudio(boolean on) {
        if (on && mAudioReceiver == null) {
            mAudioReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mAudioExit = true;
                    mHost.moveTaskToBack(true);
                }
            };
            ContextCompat.registerReceiver(mHost, mAudioReceiver,
                    new IntentFilter(MobilePlaybackActivity.ACTION_PIP_AUDIO), ContextCompat.RECEIVER_NOT_EXPORTED);
        } else if (!on && mAudioReceiver != null) {
            mHost.unregisterReceiver(mAudioReceiver);
            mAudioReceiver = null;
        }
    }
}

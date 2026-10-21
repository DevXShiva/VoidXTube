package com.newtube.mobile.ui.playback;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.Motion;

import java.util.function.BooleanSupplier;

/**
 * VOIDXDEV(up-next): the ten-second card over an ended video. It counts down "Up next in N" and then
 * opens the next video, unless the user taps Play now (at once), Replay (this video from the start)
 * or Cancel (stay on the ended video). It also gives up quietly when the video plays again under it
 * (a seek back) - the player's own controls won that race.
 */
final class NextCountdown {
    private static final int SECONDS = 10;

    private final View mCard;
    private final TextView mLabel;
    private final TextView mTitle;
    private final View mBar;
    private final BooleanSupplier mPlaying;
    private final Runnable mTick = this::tick;
    private int mLeft;
    @Nullable private Runnable mPlayNext;

    NextCountdown(View card, BooleanSupplier playing, Runnable onReplay, Runnable onCancel) {
        mCard = card;
        mPlaying = playing;
        mLabel = card.findViewById(R.id.mobile_next_label);
        mTitle = card.findViewById(R.id.mobile_next_title);
        mBar = card.findViewById(R.id.mobile_next_bar);
        card.setClipToOutline(true);
        TextView play = card.findViewById(R.id.mobile_next_play);
        TextView replay = card.findViewById(R.id.mobile_next_repeat);
        icon(play, R.drawable.ic_player_play, 0xFF000000);
        icon(replay, R.drawable.ic_player_replay, 0xFFFFFFFF);
        play.setOnClickListener(v -> {
            Haptics.click(v);
            fire();
        });
        replay.setOnClickListener(v -> {
            Haptics.click(v);
            hide();
            onReplay.run();
        });
        card.findViewById(R.id.mobile_next_cancel).setOnClickListener(v -> {
            Haptics.click(v);
            hide();
            onCancel.run();
        });
    }

    void show(@Nullable String nextTitle, Runnable playNext) {
        mPlayNext = playNext;
        mLeft = SECONDS;
        mTitle.setText(nextTitle);
        mTitle.setVisibility(TextUtils.isEmpty(nextTitle) ? View.GONE : View.VISIBLE);
        updateLabel();

        mCard.animate().cancel();
        mCard.setVisibility(View.VISIBLE);
        mCard.setAlpha(0f);
        mCard.setScaleX(0.92f);
        mCard.setScaleY(0.92f);
        mCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();

        mBar.animate().cancel();
        mBar.setPivotX(0f);
        mBar.setScaleX(1f);
        mBar.animate().scaleX(0f).setDuration(SECONDS * 1000L).setInterpolator(new LinearInterpolator()).start();

        mCard.removeCallbacks(mTick);
        mCard.postDelayed(mTick, 1000);
    }

    void hide() {
        if (mPlayNext == null) {
            return;
        }
        mPlayNext = null;
        mCard.removeCallbacks(mTick);
        mBar.animate().cancel();
        mCard.animate().cancel();
        mCard.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> mCard.setVisibility(View.GONE)).start();
    }

    private void tick() {
        if (mPlaying.getAsBoolean()) {
            hide(); // the video plays again (a seek back): no advance
            return;
        }
        if (--mLeft <= 0) {
            fire();
        } else {
            updateLabel();
            mCard.postDelayed(mTick, 1000);
        }
    }

    private void fire() {
        Runnable playNext = mPlayNext;
        hide();
        if (playNext != null) {
            playNext.run();
        }
    }

    private void updateLabel() {
        mLabel.setText(mCard.getContext().getString(R.string.mobile_next_in, mLeft));
    }

    private static void icon(TextView view, int iconRes, int color) {
        Drawable icon = ContextCompat.getDrawable(view.getContext(), iconRes);
        if (icon == null) {
            return;
        }
        icon = icon.mutate();
        int size = Math.round(18 * view.getResources().getDisplayMetrics().density);
        icon.setBounds(0, 0, size, size);
        icon.setTintList(ColorStateList.valueOf(color));
        view.setCompoundDrawablesRelative(icon, null, null, null);
    }
}

package com.newtube.mobile.ui.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

/**
 * VOIDXDEV(sleep-timer): the player sheet's YouTube-style sleep timer - pause after N minutes, or at
 * the end of the current video. Session-only (never persisted) and process-wide, so it outlives a
 * recreated player screen. The countdown runs on the main looper: the player's wake lock keeps the
 * CPU up while audio plays, and once nothing plays there is nothing left to pause.
 */
final class SleepTimer {
    static final int OFF = 0;
    static final int END_OF_VIDEO = -1;
    /** The sheet's choices, in minutes. */
    static final int[] PRESETS = {OFF, 10, 15, 20, 30, 45, 60, 90, END_OF_VIDEO};

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable EXPIRE = SleepTimer::expire;

    private static long sDeadlineMs; // elapsedRealtime; 0 = no countdown
    private static boolean sEndOfVideo;
    private static int sPicked = OFF;
    @Nullable private static Runnable sPause; // the live player's pause

    private SleepTimer() {
    }

    static void attach(Runnable pause) {
        sPause = pause;
    }

    static void detach(Runnable pause) {
        if (sPause == pause) {
            sPause = null;
        }
    }

    /** Minutes, {@link #OFF} or {@link #END_OF_VIDEO}; replaces any running timer. */
    static void set(int minutes) {
        HANDLER.removeCallbacks(EXPIRE);
        sDeadlineMs = 0;
        sEndOfVideo = minutes == END_OF_VIDEO;
        sPicked = minutes;
        if (minutes > 0) {
            long delayMs = minutes * 60_000L;
            sDeadlineMs = SystemClock.elapsedRealtime() + delayMs;
            HANDLER.postDelayed(EXPIRE, delayMs);
        }
    }

    static boolean isEndOfVideo() {
        return sEndOfVideo;
    }

    /** What the sheet marks as selected: the running preset, {@link #END_OF_VIDEO} or {@link #OFF}. */
    static int picked() {
        return sEndOfVideo || sDeadlineMs > 0 ? sPicked : OFF;
    }

    /** Whole minutes left (rounded up), 0 when no countdown runs. */
    static int minutesLeft() {
        if (sDeadlineMs <= 0) {
            return 0;
        }
        long left = sDeadlineMs - SystemClock.elapsedRealtime();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** The player paused at the end of the video for {@link #END_OF_VIDEO}: the timer is done. */
    static void onPausedAtEnd() {
        if (sEndOfVideo) {
            expire();
        }
    }

    private static void expire() {
        HANDLER.removeCallbacks(EXPIRE);
        sDeadlineMs = 0;
        sEndOfVideo = false;
        if (sPause != null) {
            sPause.run();
        }
    }
}

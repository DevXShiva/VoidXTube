package com.liskovsoft.smartyoutubetv2.common.misc;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NEWTUBE(token-warmup): is a video open in flight - tapped or opened, its first frame not shown
 * yet and no fatal error - and deferred work that waits for it to settle.
 *
 * <p>NetPath's open milestones drive it ({@link NetPath#logTap} and {@link NetPath#logOpen} start an
 * open, {@link NetPath#logFirstFrame} and {@link NetPath#logError} settle it), so every open path
 * counts: a share-link cold start (its tap is logged before the player's window draws), a card tap,
 * autoplay. The work held here is the BotGuard WebView's construction (the token warm-up, and the
 * WEB subtitle-enrichment request that would otherwise build it on its own): on a slow phone it
 * lands on the main thread exactly when the open's answer does (Mi 8 share-link cold starts:
 * answer -> first media request 280 ms beside it, 107 without; netbench r11 analysis, 3.4a).
 *
 * <p>Nothing waits forever: each task has its own cap, and an open that never settles (backed out
 * before its first frame, an unplayable video) stops holding anything after
 * {@link #STALE_OPEN_MS}. Tasks run on the main thread, in the order they were held.
 */
public final class OpenSettle {
    /** An open older than this that never settled holds nothing. */
    static final long STALE_OPEN_MS = 20_000;

    private static final Object sLock = new Object();
    private static Handler sMainHandler;
    /** elapsedRealtime at which the open in flight started; 0 = none. Guarded by sLock. */
    private static long sOpenStartedAtMs;
    private static final List<Runnable> sHeld = new ArrayList<>();

    private OpenSettle() {
    }

    /** A tap or an open started (NetPath). */
    static void onOpenStarted() {
        synchronized (sLock) {
            sOpenStartedAtMs = SystemClock.elapsedRealtime();
        }
    }

    /** The open in flight showed its first frame or failed ({@code how}, for the log). */
    static void onOpenSettled(String how) {
        List<Runnable> released;
        long startedAtMs;
        synchronized (sLock) {
            startedAtMs = sOpenStartedAtMs;
            if (startedAtMs == 0) {
                return;
            }
            sOpenStartedAtMs = 0;
            released = new ArrayList<>(sHeld);
            sHeld.clear();
        }
        if (!released.isEmpty()) {
            NetPath.log(NetPath.context() + " open-settled how=" + how + " released=" + released.size()
                    + " openMs=" + (SystemClock.elapsedRealtime() - startedAtMs));
        }
        for (Runnable task : released) {
            main().post(task);
        }
    }

    /** Whether an open is in flight now (started, not settled, not stale). */
    public static boolean isOpenInFlight() {
        synchronized (sLock) {
            return inFlight(SystemClock.elapsedRealtime());
        }
    }

    /**
     * Runs {@code task} on the main thread once no open is in flight: right away (posted) when none
     * is, else when the open settles, and after {@code maxHoldMs} at the latest. Runs it once.
     */
    public static void runWhenSettled(long maxHoldMs, Runnable task) {
        AtomicBoolean ran = new AtomicBoolean();
        Runnable once = () -> {
            if (ran.compareAndSet(false, true)) {
                task.run();
            }
        };
        synchronized (sLock) {
            if (inFlight(SystemClock.elapsedRealtime())) {
                sHeld.add(once);
                main().postDelayed(once, maxHoldMs);
                return;
            }
        }
        main().post(once);
    }

    private static boolean inFlight(long nowMs) {
        return sOpenStartedAtMs != 0 && nowMs - sOpenStartedAtMs < STALE_OPEN_MS;
    }

    private static Handler main() {
        synchronized (sLock) {
            if (sMainHandler == null) {
                sMainHandler = new Handler(Looper.getMainLooper());
            }
            return sMainHandler;
        }
    }

    /** Test hook: forget the open in flight and anything held. */
    static void resetForTest() {
        synchronized (sLock) {
            sOpenStartedAtMs = 0;
            sHeld.clear();
            sMainHandler = null;
        }
    }
}

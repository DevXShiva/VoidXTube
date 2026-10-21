package com.newtube.mobile.player;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

/**
 * NEWTUBE(switch): opt-in open-path experiments for the watch page, readable outside this package.
 * Every switch here is inert in release builds (the debug.arc.* properties are only read by debug
 * and benchmark builds), so an experiment can be A/B-timed on a benchmark build before it ships.
 */
public final class SwitchExperiments {
    private static final long MAX_TOUCH_PREFETCH_MS = 500;

    private SwitchExperiments() {
    }

    /**
     * Still-finger time after which a related row resolves its /player before the tap lands
     * ({@code adb shell setprop debug.arc.touch_prefetch_ms 60}); 0 = off (the default, and always
     * in release). OFF by default because every rest-then-scroll gesture costs one /player that is
     * never played - YouTube's bot heuristics count /player volume. Measure the waste on the Pixel
     * ({@code touch-prefetch fire} vs {@code touch-prefetch used} lines) before enabling it.
     */
    public static long touchPrefetchStillMs() {
        if (!(BuildConfig.DEBUG || BuildConfig.BENCHMARK)) {
            return 0;
        }
        int value = DebugMediaShaper.propInt("debug.arc.touch_prefetch_ms", 0);
        return value <= 0 ? 0 : Math.min(value, MAX_TOUCH_PREFETCH_MS);
    }

    /** NEWTUBE(still-lift): when the loading still of a new selection lifts. */
    public enum StillLift {
        /**
         * At this open's first rendered frame, once the texture has it (v21, the default): the
         * picture shows while the player finishes reaching READY (Pixel 67-74 ms, Mi 8 20-48 ms
         * sooner; netbench r11 analysis, 4.3). The per-open marker (OpenFirstFrame) still rules out
         * a stale frame of the previous video on the reused surface.
         */
        FRAME,
        /** At READY once this open's first frame is on the texture (v17-v20). */
        READY,
        /** At the first texture frame after READY (before v17). */
        TEXTURE
    }

    /**
     * The static switch: what release builds use, and debug and benchmark builds without the
     * property. Rollback: READY.
     */
    private static final StillLift STILL_LIFT_DEFAULT = StillLift.FRAME;

    private static volatile StillLift sStillLift;

    /**
     * NEWTUBE(still-lift): {@link #STILL_LIFT_DEFAULT}, or in debug and benchmark builds
     * {@code adb shell setprop debug.arc.still_lift frame|ready|texture} (one apk, an A/B; read once
     * per process, like the other switches: set it, then force-stop).
     */
    public static StillLift stillLift() {
        if (!(BuildConfig.DEBUG || BuildConfig.BENCHMARK)) {
            return STILL_LIFT_DEFAULT;
        }
        StillLift mode = sStillLift;
        if (mode == null) {
            mode = parseStillLift(DebugMediaShaper.prop("debug.arc.still_lift"));
            sStillLift = mode;
        }
        return mode;
    }

    static StillLift parseStillLift(String value) {
        if ("texture".equals(value)) {
            return StillLift.TEXTURE;
        }
        if ("ready".equals(value)) {
            return StillLift.READY;
        }
        if ("frame".equals(value)) {
            return StillLift.FRAME;
        }
        return STILL_LIFT_DEFAULT;
    }

    /**
     * NEWTUBE(still-lift): the loading still of a new selection may lift at READY once this open's
     * first frame is on the texture, instead of waiting for the NEXT texture frame after READY. That
     * wait hid an already decoded picture for 128 / 148 ms (median / p90, READY to picture-visible,
     * n=67 opens, Pixel 9 LTE, 2026-09-29). True for FRAME too: READY is its second chance.
     */
    public static boolean stillLiftAtReady() {
        return stillLift() != StillLift.TEXTURE;
    }

    /** NEWTUBE(still-lift): the still may lift at this open's first rendered frame (FRAME). */
    public static boolean stillLiftAtFrame() {
        return stillLift() == StillLift.FRAME;
    }
}

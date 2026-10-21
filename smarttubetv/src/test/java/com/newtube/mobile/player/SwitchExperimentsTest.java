package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowSystemProperties;
import org.robolectric.util.ReflectionHelpers;

/** The touch-prefetch experiment is off unless explicitly set, and bounded when it is. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SwitchExperimentsTest {
    @After
    public void tearDown() {
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "");
        stillLift("");
    }

    /**
     * NEWTUBE(still-lift): v21 lifts at this open's first frame by default (READY is its second
     * chance); "ready" is the v20 rollback, "texture" the one before.
     */
    @Test
    public void stillLiftsAtTheFirstFrameUnlessRolledBack() {
        stillLift("");
        assertEquals(SwitchExperiments.StillLift.FRAME, SwitchExperiments.stillLift());
        assertEquals(true, SwitchExperiments.stillLiftAtFrame());
        assertEquals(true, SwitchExperiments.stillLiftAtReady());
        stillLift("none");
        assertEquals(SwitchExperiments.StillLift.FRAME, SwitchExperiments.stillLift());
        stillLift("ready");
        assertEquals(false, SwitchExperiments.stillLiftAtFrame());
        assertEquals(true, SwitchExperiments.stillLiftAtReady());
        stillLift("texture");
        assertEquals(false, SwitchExperiments.stillLiftAtFrame());
        assertEquals(false, SwitchExperiments.stillLiftAtReady());
    }

    /** Read once per process (the scripts set the property, then force-stop the app). */
    @Test
    public void theModeIsReadOncePerProcess() {
        stillLift("ready");
        assertEquals(SwitchExperiments.StillLift.READY, SwitchExperiments.stillLift());
        ShadowSystemProperties.override("debug.arc.still_lift", "texture");
        assertEquals(SwitchExperiments.StillLift.READY, SwitchExperiments.stillLift());
    }

    private static void stillLift(String value) {
        ShadowSystemProperties.override("debug.arc.still_lift", value);
        ReflectionHelpers.setStaticField(SwitchExperiments.class, "sStillLift", null);
    }

    @Test
    public void offByDefault() {
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "");
        assertEquals(0, SwitchExperiments.touchPrefetchStillMs());
    }

    @Test
    public void explicitValueIsUsedAndClamped() {
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "60");
        assertEquals(60, SwitchExperiments.touchPrefetchStillMs());
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "9000");
        assertEquals(500, SwitchExperiments.touchPrefetchStillMs());
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "-5");
        assertEquals(0, SwitchExperiments.touchPrefetchStillMs());
        ShadowSystemProperties.override("debug.arc.touch_prefetch_ms", "junk");
        assertEquals(0, SwitchExperiments.touchPrefetchStillMs());
    }
}

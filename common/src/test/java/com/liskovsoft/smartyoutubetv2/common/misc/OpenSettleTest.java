package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(token-warmup): work held for the open in flight runs at its first frame or error, at its
 * cap at the latest, right away when no open is in flight, and once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class OpenSettleTest {
    private final List<String> ran = new ArrayList<>();

    @Before
    public void setUp() {
        OpenSettle.resetForTest();
    }

    @After
    public void tearDown() {
        OpenSettle.resetForTest();
    }

    @Test
    public void noOpenInFlightRunsRightAway() {
        assertFalse(OpenSettle.isOpenInFlight());
        OpenSettle.runWhenSettled(4_000, () -> ran.add("warmup"));
        idle();
        assertEquals(1, ran.size());
    }

    /** A share-link cold start: the tap, then the warm-up is held until the open's first frame. */
    @Test
    public void heldUntilTheOpensFirstFrame() {
        NetPath.logTap("v1");
        assertTrue(OpenSettle.isOpenInFlight());
        OpenSettle.runWhenSettled(4_000, () -> ran.add("warmup"));
        OpenSettle.runWhenSettled(4_000, () -> ran.add("enrichment"));
        idle();
        assertTrue(ran.isEmpty());
        NetPath.logFirstFrame("v1");
        assertFalse(OpenSettle.isOpenInFlight());
        idle();
        assertEquals("[warmup, enrichment]", ran.toString());
        // The cap passing later does not run them again.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        idle();
        assertEquals(2, ran.size());
    }

    @Test
    public void aFailedOpenReleasesToo() {
        NetPath.logOpen("v1", "title");
        OpenSettle.runWhenSettled(4_000, () -> ran.add("warmup"));
        NetPath.logError("v1", new RuntimeException("source error"));
        idle();
        assertEquals(1, ran.size());
    }

    /** An open that never settles (backed out, unplayable) holds its work for the cap only. */
    @Test
    public void theCapBoundsTheHold() {
        NetPath.logTap("v1");
        OpenSettle.runWhenSettled(4_000, () -> ran.add("warmup"));
        shadowOf().idleFor(Duration.ofMillis(3_999));
        assertTrue(ran.isEmpty());
        shadowOf().idleFor(Duration.ofMillis(2));
        assertEquals(1, ran.size());
        // ...and a later settle does not run it again.
        NetPath.logFirstFrame("v1");
        idle();
        assertEquals(1, ran.size());
    }

    /** An open older than STALE_OPEN_MS that never settled holds nothing new. */
    @Test
    public void aStaleOpenHoldsNothing() {
        NetPath.logTap("v1");
        ShadowSystemClock.advanceBy(Duration.ofMillis(OpenSettle.STALE_OPEN_MS));
        assertFalse(OpenSettle.isOpenInFlight());
        OpenSettle.runWhenSettled(4_000, () -> ran.add("warmup"));
        shadowOf().idle();
        assertEquals(1, ran.size());
    }

    private static void idle() {
        shadowOf().idle();
    }

    private static ShadowLooper shadowOf() {
        return org.robolectric.Shadows.shadowOf(Looper.getMainLooper());
    }
}
